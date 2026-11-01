package app.gridlink.core.data.account

/**
 * Every account's OpenPGP signing key dropped, and nothing else touched.
 *
 * A key id is a fact about ONE provider's keyring: OpenKeychain's `0x1A2B…` names no key inside
 * PGPony. Carrying it across a provider switch would print, under "Your Key ID", sixteen hex digits
 * the new app has never heard of — and the first signature attempt would fail on a key the screen
 * says is configured (#151).
 *
 * ⭐ [StoredAccount.pgpEnabled] and [StoredAccount.pgpEncryptByDefault] are deliberately LEFT ALONE.
 * With the key cleared the account screen already reads "No key selected" next to its Choose key
 * button, which is the true state; turning PGP off as well would silently undo a setting the user
 * made, and re-enabling it is not something an app should decide on their behalf. It is what K-9
 * does with `NO_OPENPGP_KEY`.
 *
 * Lives outside [AccountStore] because nothing in this module can build one (it needs a `Context`
 * and the Keystore, and there is no Robolectric here) — so this is the part a JVM test can EXECUTE.
 */
fun withoutPgpSignKeys(accounts: List<StoredAccount>): List<StoredAccount> =
    accounts.map { it.copy(pgpSignKeyId = 0L) }
