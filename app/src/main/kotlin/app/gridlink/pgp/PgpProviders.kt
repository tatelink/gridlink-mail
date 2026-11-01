package app.gridlink.pgp

import android.content.Context
import org.openintents.openpgp.util.OpenPgpProviderUtil

/**
 * Which OpenPGP provider Gridlink talks to.
 *
 * The decision lives here, in pure functions over package names, and NOT in the engine: the engine
 * needs a [Context] and a live binder, so nothing about it can be executed by a JVM unit test (there
 * is no Robolectric in this repo). [eligible] and [preferredDefault] can, and are.
 *
 * Before this file the engine carried a two-entry literal list of OpenKeychain package names, so a
 * device whose only OpenPGP provider was something else (PGPony, `com.pgpony.android`) had every PGP
 * feature of the app silently dead. Discovery now goes through the api's own service query.
 */
object PgpProviders {

    /** OpenKeychain, the provider Gridlink shipped against and the one already-set-up users have. */
    const val OPENKEYCHAIN = "org.sufficientlysecure.keychain"

    /** OpenKeychain's debug build; a separate package, and a legitimate provider on a dev device. */
    const val OPENKEYCHAIN_DEBUG = "org.sufficientlysecure.keychain.debug"

    /**
     * The providers of [packages] Gridlink may bind, deduplicated and ordered by package name.
     *
     * The allow-list check is the api's own ([OpenPgpProviderUtil.isProviderAllowed]): it rejects
     * APG, which answers the same service intent with an incompatible implementation. The ordering
     * is only there so that the answer does not depend on the order the package manager happened to
     * return; which one is PICKED is [preferredDefault]'s business, not the sort's.
     */
    fun eligible(packages: List<String>): List<String> =
        packages.filter { OpenPgpProviderUtil.isProviderAllowed(it) }.distinct().sorted()

    /**
     * The provider to use when the user has expressed no preference, or `null` when there is none.
     *
     * ⛔ OpenKeychain FIRST, not the first in the sort. Every existing Gridlink user with PGP set up
     * uses OpenKeychain, and `com.pgpony.android` sorts before `org.sufficientlysecure.keychain`: a
     * bare alphabetical pick would move those users onto another provider on update, without a word,
     * and their keys are not there. The `.debug` variant is a fallback for the same reason (a dev
     * device typically has that one and nothing else), and only then does the sort decide.
     *
     * Order-independent by construction: the caller may hand the list in any order.
     */
    fun preferredDefault(installed: List<String>): String? = when {
        installed.contains(OPENKEYCHAIN) -> OPENKEYCHAIN
        installed.contains(OPENKEYCHAIN_DEBUG) -> OPENKEYCHAIN_DEBUG
        else -> installed.minOrNull()
    }

    /**
     * The provider to bind, given what is [installed] and what the user [chosen]; `null` when there
     * is nothing to bind. `chosen == null` is every existing user: nobody has expressed a choice.
     *
     * ⛔ A choice is honoured ONLY while its package is still installed. Returning an uninstalled
     * package would bind nothing and leave PGP dead while another provider sits right there — the
     * very defect #151 reports, re-created by the setting meant to fix it. Uninstalling the chosen
     * app therefore falls back to [preferredDefault], silently and immediately.
     *
     * ⛔ The choice comes FIRST. Consulting [preferredDefault] before it makes the picker
     * decorative: the user picks PGPony, and the app keeps calling OpenKeychain because
     * OpenKeychain outranks everything in the default order.
     */
    fun resolve(installed: List<String>, chosen: String?): String? =
        chosen?.takeIf { installed.contains(it) } ?: preferredDefault(installed)

    /**
     * Whether picking [picked] must drop every account's OpenPGP signing key, given the provider
     * [inUse] at the moment of the tap — i.e. whether this is a real switch of keyring.
     *
     * ⛔ `inUse == null` is NOT a switch. It means the caller does not know what is in use — the
     * screen state was not resolved yet, or nothing resolves at all — and clearing a signing key
     * is irreversible: the user has to walk through the provider's key chooser again. On an
     * unknown, the answer is to destroy nothing. Erasing on `null` is precisely the defect this
     * function was extracted to make executable: a tap landing in the frame before the screen had
     * resolved anything wiped the keys of every account for a choice that changed nothing.
     *
     * Picking the provider that is already in use is likewise not a switch: the keys on record
     * came out of that very keyring and are still valid.
     */
    fun switchErasesKeys(inUse: String?, picked: String): Boolean =
        inUse != null && inUse != picked

    /**
     * The package to write down as the chosen provider, given what is [stored] and what [resolve]
     * answered ([resolved]); `null` when there is nothing to write.
     *
     * ⭐ It exists for the user who has exactly ONE OpenPGP app. No row is offered — there is
     * nothing to pick — so nothing would ever be stored, and the day they install a second app for
     * unrelated reasons [preferredDefault] would rank it first and Gridlink would change provider
     * without a word. Their signing key would then be an id the new provider never emitted, and
     * nothing would clear it: no choice was ever made, so no switch was ever detected. Writing the
     * provider down the first time it is resolved closes that.
     *
     * ⛔ A [stored] choice is NEVER rewritten, and this is the dangerous half. [resolve] falls back
     * to the default whenever the chosen package is momentarily absent — an app updating, a work
     * profile, external storage not mounted yet. Re-pinning at that moment would overwrite an
     * explicit choice with the default, permanently: reinstalling the app would not bring it back,
     * because the record of what was chosen is gone.
     *
     * ⛔ Never an erasure. Pinning writes down the provider ALREADY in use; it is not a switch of
     * keyring, so it must not reach [switchErasesKeys] or clear a single signing key.
     */
    fun providerToPin(stored: String?, resolved: String?): String? =
        if (stored == null) resolved else null

    /**
     * Whether a connection already bound to [boundTo] may serve a call aimed at [target].
     *
     * Only when it is the same package. The engine keeps its binding for the process lifetime, so
     * without this a provider switched in Settings changes nothing at all until the app is killed —
     * every call keeps reaching the old provider, which no longer holds the keys on screen.
     * `boundTo == null` is "nothing bound yet", never reusable.
     */
    fun bindingReusable(boundTo: String?, target: String): Boolean =
        boundTo != null && boundTo == target

    /** The eligible providers installed on this device. The only part of this file touching Android. */
    fun installed(context: Context): List<String> =
        eligible(OpenPgpProviderUtil.getOpenPgpProviderPackages(context))
}
