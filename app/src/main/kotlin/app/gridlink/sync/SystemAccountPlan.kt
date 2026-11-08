package app.gridlink.sync

import java.util.Locale

/**
 * One Gridlink mail account as the system-account planner sees it.
 *
 * [wanted] is false for an account that publishes nothing (calendars and contacts both off). It
 * still has to be listed, because whether an old 2.1.3 account can be adopted depends on how many
 * mail accounts shared its login, wanted or not.
 */
internal data class MirrorSource(
    val id: String,
    val label: String,
    val username: String,
    val host: String,
    val wanted: Boolean,
)

/** A registered Android account: its name, and the Gridlink account id stored on it, if any. */
internal data class RegisteredAccount(val name: String, val gridlinkId: String?)

/**
 * What [SystemAccountPlanner.plan] decided.
 *
 * Every map is Android account name to Gridlink account id. [adopt] are 2.1.3 accounts that keep
 * their name and rows and only need the id written onto them.
 */
internal data class SystemAccountPlan(
    val keep: Map<String, String>,
    val adopt: Map<String, String>,
    val create: Map<String, String>,
    val remove: List<String>,
)

/**
 * Decides which Android accounts should exist, one per Gridlink account that publishes anything.
 *
 * ## Why identity is the stored id, not the name
 * 2.1.3 named each Android account after the login and found its mail accounts by matching that
 * name against usernames. Two Gridlink accounts with the same login (one over JMAP and one over
 * IMAP + DAV, or one address on two servers) then collapsed into a single Android account that
 * both published into, so the system Contacts and Calendar apps showed one account holding both
 * sets. Each Android account now carries its Gridlink account id in AccountManager user data, and
 * that id is the only thing a sync pass trusts. The name is for people.
 *
 * ## The name
 * The account's label (its own name, else its address). When two accounts would read the same,
 * the later one gets its server in brackets, and failing that a number. A name is chosen once and
 * then kept: renaming an Android account makes both providers drop and reload every row, which is
 * a lot of churn for a cosmetic change.
 *
 * ## 🔴 Migrating a 2.1.3 account
 * An account with no id on it is a 2.1.3 leftover named after a login. It is adopted, keeping its
 * name and every row in it (including edits made in the system apps that have not gone up yet),
 * only when exactly one Gridlink account has that login and that account publishes. Anything else
 * means its rows may belong to several mail accounts, so it is removed, which deletes its rows,
 * and each mail account is registered fresh and synced from the server.
 */
internal object SystemAccountPlanner {

    fun plan(sources: List<MirrorSource>, registered: List<RegisteredAccount>): SystemAccountPlan {
        val wanted = sources.filter { it.wanted }
        val wantedIds = wanted.map { it.id }.toSet()
        val keep = LinkedHashMap<String, String>()
        val adopt = LinkedHashMap<String, String>()
        val remove = ArrayList<String>()
        val claimed = HashSet<String>()

        for (account in registered) {
            val id = account.gridlinkId ?: continue
            if (id in wantedIds && claimed.add(id)) keep[account.name] = id else remove += account.name
        }
        for (account in registered.filter { it.gridlinkId == null }) {
            val sameLogin = sources.filter { it.username == account.name }
            val owner = sameLogin.singleOrNull()?.takeIf { it.wanted && it.id !in claimed }
            if (owner != null) {
                claimed += owner.id
                adopt[account.name] = owner.id
            } else {
                remove += account.name
            }
        }

        // 🔴 A name being removed in this same pass is not free yet. The providers delete a removed
        // account's rows later, when they hear about it, and an account re-added under the same
        // name before then would inherit the old rows, which nothing would ever sweep.
        val taken = (keep.keys + adopt.keys + remove).mapTo(HashSet()) { it.key() }
        val create = LinkedHashMap<String, String>()
        for (source in wanted.filter { it.id !in claimed }) {
            val name = uniqueName(source, taken)
            taken += name.key()
            create[name] = source.id
        }
        return SystemAccountPlan(keep, adopt, create, remove)
    }

    private fun uniqueName(source: MirrorSource, taken: Set<String>): String {
        val base = source.label.ifBlank { source.username }.ifBlank { source.id }
        if (base.key() !in taken) return base
        val withHost = if (source.host.isNotBlank()) "$base (${source.host})" else null
        if (withHost != null && withHost.key() !in taken) return withHost
        return generateSequence(2) { it + 1 }.map { "$base ($it)" }.first { it.key() !in taken }
    }

    /** Names are compared ignoring case, so two accounts never read the same in a list. */
    private fun String.key(): String = trim().lowercase(Locale.ROOT)
}
