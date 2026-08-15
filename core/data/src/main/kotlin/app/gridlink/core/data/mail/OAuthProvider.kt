package app.gridlink.core.data.mail

import app.gridlink.core.data.account.ConnectionSecurity
import app.gridlink.core.data.account.MailEndpoint
import app.gridlink.core.jmap.OAuthMetadata

/**
 * A built-in OAuth provider with fixed endpoints (Microsoft, …). Unlike the JMAP path,
 * these aren't discovered via RFC 8414 — the auth/token/device endpoints and the
 * IMAP/SMTP servers are well-known and hard-coded.
 */
data class OAuthProvider(
    val id: String,
    val clientId: String,
    val scope: String,
    val metadata: OAuthMetadata,
    val imap: MailEndpoint,
    val smtp: MailEndpoint,
) {
    /** True once a real OAuth client id has been registered and dropped in below. */
    val isConfigured: Boolean get() = clientId.isNotBlank() && !clientId.startsWith("TODO")

    companion object {
        /**
         * Outlook / Microsoft (personal + work/school) over IMAP+SMTP with XOAUTH2.
         *
         * [clientId] is our own Entra app registration, display name **GridLink Mail**. That name
         * is not decoration: Microsoft's device-code page prints the display name of whoever owns
         * the client id, so inheriting upstream's registration meant the sign-in screen said
         * "Sterna". It is a public identifier, not a secret, and it is safe in the repo.
         *
         * ⚠️ The capital L is deliberate here and nowhere else. The app itself standardised on
         * "Gridlink" (2026-08-14), but this line quotes what is actually typed into the Azure
         * console today, and the sign-in page reads it from there, not from us. Correcting the
         * spelling here without renaming the registration would leave a comment that quietly
         * disagrees with the screen the user is looking at. Rename it in Entra first, then this.
         * 🔴 Rename the DISPLAY NAME only. Touching [clientId] signs every Outlook account out.
         *
         * The registration is a **public** client (Authentication → "Allow public client flows"
         * = Yes), which is what makes the device-code grant legal for an app that holds no secret.
         * Its Exchange delegated permissions are deliberately NOT pre-registered: personal
         * Microsoft accounts consent dynamically on the v2.0 endpoint, so [scope] is what actually
         * gets approved. A work/school account, whose admin must consent in advance, would need
         * IMAP.AccessAsUser.All + SMTP.Send + offline_access added to the registration first.
         *
         * 🔴 Changing this id invalidates every stored Outlook refresh token: they are issued to a
         * client id, so existing accounts must sign in again.
         */
        val MICROSOFT = OAuthProvider(
            id = "microsoft",
            clientId = "3cf80644-1d5a-4dcf-ae39-0ff330ae5b00",
            scope = "https://outlook.office.com/IMAP.AccessAsUser.All " +
                "https://outlook.office.com/SMTP.Send offline_access openid email",
            metadata = OAuthMetadata(
                issuer = "https://login.microsoftonline.com/common/v2.0",
                tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
                deviceAuthorizationEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode",
            ),
            imap = MailEndpoint("outlook.office365.com", 993, ConnectionSecurity.TLS),
            smtp = MailEndpoint("smtp.office365.com", 587, ConnectionSecurity.STARTTLS),
        )

        /** The built-in provider whose IMAP host matches [host], or null for an unknown XOAUTH2 server. */
        fun forImapHost(host: String): OAuthProvider? = when {
            host.isBlank() -> null
            host.equals(MICROSOFT.imap.host, ignoreCase = true) ||
                host.endsWith("office365.com", ignoreCase = true) ||
                host.endsWith("outlook.com", ignoreCase = true) -> MICROSOFT
            else -> null
        }
    }
}
