package app.gridlink.core.data.mail

import app.gridlink.core.data.account.AccountCredentials
import app.gridlink.core.data.account.AccountStore
import app.gridlink.core.jmap.OAuthClient

/**
 * Hands out a currently-valid OAuth access token for an account, refreshing it (and
 * persisting the rotated tokens) when the cached one is within a minute of expiry.
 * Shared by the JMAP Bearer path and the IMAP/SMTP XOAUTH2 path so both refresh the
 * same way.
 */
class OAuthTokenRefresher(
    private val oauthClient: OAuthClient,
    private val accountStore: AccountStore,
) {
    /** A fresh access token for [credentials], or null when it isn't an OAuth account. */
    suspend fun freshAccessToken(credentials: AccountCredentials): String? {
        val oauth = credentials.oauth ?: return null
        if (oauth.accessToken.isNotBlank() &&
            oauth.accessExpiresAtMillis - System.currentTimeMillis() > 60_000
        ) {
            return oauth.accessToken
        }
        val tokens = oauthClient.refresh(oauth.tokenEndpoint, oauth.refreshToken, oauth.clientId)
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
        accountStore.updateOAuthTokens(
            credentials.id,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken.orEmpty(),
            accessExpiresAtMillis = expiresAt,
        )
        return tokens.accessToken
    }
}
