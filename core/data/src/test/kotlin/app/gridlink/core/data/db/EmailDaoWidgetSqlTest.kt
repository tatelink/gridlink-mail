package app.gridlink.core.data.db

import app.gridlink.core.data.mail.notSnoozedSql
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the widget query's hand-written snooze clause to the real one.
 *
 * This exists because Room forces the duplication: `@Query` takes a compile-time constant, so
 * [WIDGET_RECENT_SQL] cannot call [notSnoozedSql] and has to repeat it. Two definitions of
 * "snoozed" in a codebase drift; this test is what makes the drift a build failure rather than a
 * message the user snoozed away reappearing on their home screen.
 */
class EmailDaoWidgetSqlTest {

    @Test
    fun `widget query hides snoozed messages using the shared predicate`() {
        assertTrue(
            "The widget query no longer contains notSnoozedSql(\"emails\") verbatim. If the shared " +
                "predicate changed, copy the new text into WIDGET_RECENT_SQL — do not relax this test.",
            WIDGET_RECENT_SQL.contains(notSnoozedSql("emails")),
        )
    }

    /**
     * The correlation on `accountId` is the part that is easy to drop when copying, and dropping it
     * is silent: it only misbehaves for someone with two accounts on one server (issue #31), where
     * one account's snooze would hide a same-id message belonging to the other.
     */
    @Test
    fun `widget query correlates the snooze on the account as well as the message`() {
        assertTrue(WIDGET_RECENT_SQL.contains("snoozed.accountId = emails.accountId"))
    }

    /** Newest first, and bounded — a widget that read the whole mailbox would blow the update size. */
    @Test
    fun `widget query is newest-first and limited`() {
        assertTrue(WIDGET_RECENT_SQL.contains("ORDER BY sortKey DESC"))
        assertTrue(WIDGET_RECENT_SQL.contains("LIMIT :limit"))
    }
}
