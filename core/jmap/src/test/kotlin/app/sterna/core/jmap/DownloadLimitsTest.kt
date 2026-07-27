package app.sterna.core.jmap

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision itself: given the size a message announces for a part, do we agree to pull it
 * into memory? No network, no server — that is the point, the refusal has to be decidable from
 * the message alone, before any round-trip.
 */
class DownloadLimitsTest {

    @Test fun anOrdinaryPartIsAccepted() {
        assertTrue(DownloadLimits.allows(180 * 1024, DownloadLimits.INLINE_IMAGE_MAX_BYTES))
        assertTrue(DownloadLimits.allows(4L * 1024 * 1024, DownloadLimits.ATTACHMENT_MAX_BYTES))
    }

    @Test fun exactlyTheLimitIsStillAccepted() {
        assertTrue(
            DownloadLimits.allows(DownloadLimits.INLINE_IMAGE_MAX_BYTES, DownloadLimits.INLINE_IMAGE_MAX_BYTES),
        )
        assertTrue(
            DownloadLimits.allows(DownloadLimits.ATTACHMENT_MAX_BYTES, DownloadLimits.ATTACHMENT_MAX_BYTES),
        )
    }

    @Test fun onePastTheLimitIsRefused() {
        assertFalse(
            DownloadLimits.allows(DownloadLimits.INLINE_IMAGE_MAX_BYTES + 1, DownloadLimits.INLINE_IMAGE_MAX_BYTES),
        )
        assertFalse(
            DownloadLimits.allows(DownloadLimits.ATTACHMENT_MAX_BYTES + 1, DownloadLimits.ATTACHMENT_MAX_BYTES),
        )
    }

    @Test fun anImageIsHeldToATighterCeilingThanAnAttachment() {
        // Nobody asked for the image; the user tapped the attachment.
        val twentyMb = 20L * 1024 * 1024
        assertFalse(DownloadLimits.allows(twentyMb, DownloadLimits.INLINE_IMAGE_MAX_BYTES))
        assertTrue(DownloadLimits.allows(twentyMb, DownloadLimits.ATTACHMENT_MAX_BYTES))
    }

    @Test fun anUnstatedSizeIsNotATicket() {
        // A server that announces nothing passes this gate — the read is capped anyway.
        assertTrue(DownloadLimits.allows(0, DownloadLimits.INLINE_IMAGE_MAX_BYTES))
        assertTrue(DownloadLimits.allows(-1, DownloadLimits.INLINE_IMAGE_MAX_BYTES))
    }

    @Test fun aMessageCountsItsOversizedInlineImages() {
        val small = EmailBodyPart(blobId = "b1", cid = "small@x", type = "image/png", size = 40_000)
        val huge = EmailBodyPart(
            blobId = "b2",
            cid = "huge@x",
            type = "image/png",
            size = DownloadLimits.INLINE_IMAGE_MAX_BYTES + 1,
        )
        val attachment = EmailBodyPart(
            blobId = "b3",
            name = "doc.pdf",
            type = "application/pdf",
            size = DownloadLimits.INLINE_IMAGE_MAX_BYTES + 1,
        )
        val email = Email(id = "m1", attachments = listOf(small, huge, attachment))
        // Only inline (cid:) images count — the attachment has its own, larger ceiling.
        assertEquals(1, email.oversizedInlineImageCount())
        assertEquals(0, Email(id = "m2", attachments = listOf(small)).oversizedInlineImageCount())
    }
}
