package app.gridlink.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unsaved-changes guard must fire on a real edit and stay quiet on a close that changed nothing
 * (#70/#94): reopening a queued message with a Cc and an attachment, or tapping a chip to edit an
 * address, is not an edit.
 */
class ComposeDirtyTest {
    private fun dirty(
        onlyCopy: Boolean = false,
        to: String = "", initialTo: String = "",
        cc: String = "", initialCc: String = "",
        bcc: String = "", initialBcc: String = "",
        subject: String = "", initialSubject: String = "",
        body: String = "", initialBody: String = "",
        attachmentsTouched: Boolean = false,
    ) = ComposeDirty.isDirty(
        onlyCopy, to, initialTo, cc, initialCc, bcc, initialBcc,
        subject, initialSubject, body, initialBody, attachmentsTouched,
    )

    @Test fun anUntouchedReopenedMessageIsNotDirty() {
        // Reopened from the outbox with everything pre-filled — nothing changed since.
        assertFalse(
            dirty(
                to = "jordan@example.org, ", initialTo = "jordan@example.org, ",
                cc = "cc@example.org, ", initialCc = "cc@example.org, ",
                bcc = "bcc@example.org, ", initialBcc = "bcc@example.org, ",
                subject = "Report", initialSubject = "Report",
                body = "Body", initialBody = "Body",
            ),
        )
    }

    @Test fun reorderingRecipientChipsIsNotAnEdit() {
        // Tapping a chip to edit it reorders the field; the set of addresses is the same (#94).
        assertFalse(
            dirty(
                to = "b@example.org, a@example.org", initialTo = "a@example.org, b@example.org",
            ),
        )
    }

    @Test fun caseAndSpacingDifferencesInRecipientsAreNotAnEdit() {
        assertFalse(dirty(to = "  Alex@Example.org ", initialTo = "alex@example.org"))
    }

    @Test fun addingARecipientIsDirty() {
        assertTrue(dirty(to = "a@example.org, c@example.org", initialTo = "a@example.org"))
    }

    @Test fun editingSubjectOrBodyIsDirty() {
        assertTrue(dirty(subject = "New", initialSubject = "Old"))
        assertTrue(dirty(body = "New", initialBody = "Old"))
    }

    @Test fun touchingAttachmentsIsDirtyButMerelyHavingThemIsNot() {
        assertTrue(dirty(attachmentsTouched = true))
        assertFalse(dirty(attachmentsTouched = false))
    }

    @Test fun anUndoneSendIsAlwaysGuardedEvenUntouched() {
        assertTrue(dirty(onlyCopy = true))
    }
}
