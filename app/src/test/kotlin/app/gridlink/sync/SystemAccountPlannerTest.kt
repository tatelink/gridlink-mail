package app.gridlink.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Android accounts exist, and what becomes of the login-named ones 2.1.3 left behind.
 *
 * 2.1.3 keyed the Android account on the login, so a JMAP account and an IMAP + DAV account with
 * the same address shared one, and the system Contacts and Calendar apps showed both accounts'
 * rows under it. These pin that every Gridlink account gets its own, readable, stable account.
 */
class SystemAccountPlannerTest {

    private val jmap = MirrorSource("jmap-1", "avery@gridlink.me", "avery@gridlink.me", "mail.gridlink.me", true)
    private val imap = MirrorSource("imap-1", "avery@gridlink.me", "avery@gridlink.me", "imap.gridlink.me", true)

    @Test
    fun `two accounts sharing a login get two accounts with readable, different names`() {
        val plan = SystemAccountPlanner.plan(listOf(jmap, imap), emptyList())

        assertEquals(
            mapOf("avery@gridlink.me" to "jmap-1", "avery@gridlink.me (imap.gridlink.me)" to "imap-1"),
            plan.create,
        )
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun `the account's own name is used when it has one`() {
        val named = jmap.copy(label = "Work")
        assertEquals(mapOf("Work" to "jmap-1"), SystemAccountPlanner.plan(listOf(named), emptyList()).create)
    }

    @Test
    fun `same name with no server to tell apart falls back to a number, compared ignoring case`() {
        val other = imap.copy(label = "AVERY@gridlink.me", host = "")
        val third = imap.copy(id = "imap-2", host = "imap.gridlink.me")
        val plan = SystemAccountPlanner.plan(listOf(jmap, other, imap, third), emptyList())
        assertEquals(
            listOf(
                "avery@gridlink.me",
                "AVERY@gridlink.me (2)",
                "avery@gridlink.me (imap.gridlink.me)",
                "avery@gridlink.me (3)",
            ),
            plan.create.keys.toList(),
        )
    }

    @Test
    fun `an account already tagged keeps its name even after the label changes`() {
        val renamed = jmap.copy(label = "Personal")
        val plan = SystemAccountPlanner.plan(
            listOf(renamed),
            listOf(RegisteredAccount("avery@gridlink.me", "jmap-1")),
        )
        assertEquals(mapOf("avery@gridlink.me" to "jmap-1"), plan.keep)
        assertTrue(plan.create.isEmpty() && plan.remove.isEmpty())
    }

    @Test
    fun `a 2_1_3 account with one owner is adopted, rows and all`() {
        val plan = SystemAccountPlanner.plan(listOf(jmap), listOf(RegisteredAccount("avery@gridlink.me", null)))
        assertEquals(mapOf("avery@gridlink.me" to "jmap-1"), plan.adopt)
        assertTrue(plan.create.isEmpty() && plan.remove.isEmpty())
    }

    @Test
    fun `a 2_1_3 account shared by two mail accounts is removed and both are registered fresh`() {
        val plan = SystemAccountPlanner.plan(
            listOf(jmap, imap),
            listOf(RegisteredAccount("avery@gridlink.me", null)),
        )
        assertEquals(listOf("avery@gridlink.me"), plan.remove)
        assertTrue(plan.adopt.isEmpty())
        // Neither takes the old name back in the same pass, or it would inherit the old rows
        // before the providers have swept them.
        assertEquals(
            mapOf(
                "avery@gridlink.me (mail.gridlink.me)" to "jmap-1",
                "avery@gridlink.me (imap.gridlink.me)" to "imap-1",
            ),
            plan.create,
        )
    }

    @Test
    fun `a shared login still blocks adoption when the other account publishes nothing`() {
        val quiet = imap.copy(wanted = false)
        val plan = SystemAccountPlanner.plan(
            listOf(jmap, quiet),
            listOf(RegisteredAccount("avery@gridlink.me", null)),
        )
        assertEquals(listOf("avery@gridlink.me"), plan.remove)
        assertEquals(mapOf("avery@gridlink.me (mail.gridlink.me)" to "jmap-1"), plan.create)
    }

    @Test
    fun `accounts no longer wanted, unknown, or duplicated are removed`() {
        val plan = SystemAccountPlanner.plan(
            listOf(jmap, imap.copy(wanted = false)),
            listOf(
                RegisteredAccount("avery@gridlink.me", "jmap-1"),
                RegisteredAccount("copy", "jmap-1"),
                RegisteredAccount("avery@gridlink.me (imap.gridlink.me)", "imap-1"),
                RegisteredAccount("gone@example.com", null),
                RegisteredAccount("deleted", "old-id"),
            ),
        )
        assertEquals(mapOf("avery@gridlink.me" to "jmap-1"), plan.keep)
        assertEquals(
            listOf("copy", "avery@gridlink.me (imap.gridlink.me)", "deleted", "gone@example.com"),
            plan.remove,
        )
        assertTrue(plan.create.isEmpty())
    }

    @Test
    fun `mirror switched off removes everything`() {
        val plan = SystemAccountPlanner.plan(
            listOf(jmap.copy(wanted = false)),
            listOf(RegisteredAccount("avery@gridlink.me", "jmap-1"), RegisteredAccount("avery@gridlink.me (2)", null)),
        )
        assertEquals(2, plan.remove.size)
        assertTrue(plan.keep.isEmpty() && plan.adopt.isEmpty() && plan.create.isEmpty())
    }
}
