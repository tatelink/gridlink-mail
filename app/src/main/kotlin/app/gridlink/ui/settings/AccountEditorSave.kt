package app.gridlink.ui.settings

import app.gridlink.core.data.account.StoredAccount
import app.gridlink.core.data.account.StoredIdentity

/**
 * What a Save from the account editor decides, kept out of the composable so the button at the
 * bottom of the screen and the Save offered by the exit dialog (#34) share one answer instead of
 * two lookalike copies.
 */

/** The identity-side fields a Save writes back, derived from the editor's live rows. */
internal data class AccountSaveFields(
    /** The MANUAL list to persist: blank rows dropped, old-fold pollution healed. */
    val identities: List<StoredIdentity>,
    /** The default sender, or null when it no longer points at anything. */
    val defaultIdentityId: String?,
    /** Legacy account-level signature, mirrored from the first manual identity. */
    val signature: String,
)

/**
 * Persist ONLY the manual list (server identities re-merge live on every read), dropping rows the
 * user left without an address and healing any pollution from the old merge-on-save fold, so
 * storage stays clean whichever way the user saved.
 *
 * The default sender is kept only while it still points at a surviving manual row or at a server
 * identity; otherwise it is dropped, so it degrades to "the first one" rather than dangling.
 */
internal fun accountSaveFields(
    edited: List<StoredIdentity>,
    serverIdentities: List<StoredIdentity>,
    defaultIdentityId: String?,
): AccountSaveFields {
    val clean = StoredAccount.normalizeManualIdentities(
        edited.filter { it.email.isNotBlank() },
        serverIdentities,
    )
    val serverIds = serverIdentities.map { it.id }
    val keptDefault = defaultIdentityId?.takeIf { id ->
        clean.any { it.id == id } || id in serverIds
    }
    return AccountSaveFields(
        identities = clean,
        defaultIdentityId = keptDefault,
        signature = clean.firstOrNull()?.signature ?: "",
    )
}

/**
 * Whether the editor holds enough to write an account at all. Gates the bottom button and, since
 * #34, the dialog's Save too: leaving through the dialog must never write a half-filled account
 * the button itself would have refused.
 *
 * Ports come straight from the text fields, so a non-numeric one counts as missing.
 */
internal fun canSaveAccount(
    username: String,
    isImap: Boolean,
    server: String,
    imapHost: String,
    imapPort: String,
    smtpHost: String,
    smtpPort: String,
): Boolean = username.isNotBlank() && if (isImap) {
    imapHost.isNotBlank() && imapPort.toIntOrNull() != null &&
        smtpHost.isNotBlank() && smtpPort.toIntOrNull() != null
} else {
    server.isNotBlank()
}

/**
 * Carry an edit of the account's Display name into the identity for the account's own address,
 * as long as that identity's name is still the one the account had (blank, or the old Display
 * name): the editor seeds that identity from the Display name on first open, so without this the
 * two drift apart and the From header keeps the stale (usually blank) name. A name the user typed
 * into the identity itself differs from the old Display name and is left alone.
 */
internal fun mirrorAccountName(
    identities: List<StoredIdentity>,
    username: String,
    oldName: String,
    newName: String,
): List<StoredIdentity> {
    val own = username.trim()
    if (own.isEmpty()) return identities
    return identities.map { identity ->
        val isOwn = identity.email.trim().equals(own, ignoreCase = true)
        val untouched = identity.name.isBlank() || identity.name.trim() == oldName.trim()
        if (isOwn && untouched) identity.copy(name = newName) else identity
    }
}
