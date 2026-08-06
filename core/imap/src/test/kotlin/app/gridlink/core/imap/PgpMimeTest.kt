package app.gridlink.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PgpMimeTest {

    private fun message(subject: String = "s") = OutgoingMessage(
        from = "a@b.c",
        to = listOf("d@e.f"),
        subject = subject,
        body = "hello body",
        html = null,
        messageId = "mid123@b.c",
        dateMillis = 1_700_000_000_000,
    )

    @Test
    fun signedRoundTrip_extractedBytesMatchSignedPayload() {
        // Sender side: build the inner entity, canonicalize for signing, wrap.
        val inner = OutgoingMime.buildBodyEntity(message())
        val payload = PgpMime.signablePayload(inner)
        val sig = "-----BEGIN PGP SIGNATURE-----\r\n\r\nsig\r\n-----END PGP SIGNATURE-----"
        val entity = PgpMime.wrapSigned(payload, sig.toByteArray(), "pgp-sha256", "BND_SIG")

        // Receiver side: prepend message headers, run detection.
        val raw = "From: a@b.c\r\nMIME-Version: 1.0\r\n$entity"
        val env = MimeParser.detectCrypto(raw)
        assertNotNull(env)
        assertEquals(CryptoKind.PGP_SIGNED, env!!.kind)
        // THE invariant: what the verifier extracts is byte-identical to what was signed.
        assertEquals(payload, env.signedEntityRaw)
        assertTrue(env.signatureArmor!!.contains("BEGIN PGP SIGNATURE"))
    }

    @Test
    fun signedRoundTrip_withAttachmentEntity() {
        val m = message().copy(
            attachments = listOf(
                OutgoingAttachment(name = "doc.pdf", type = "application/pdf", bytes = "PDF".toByteArray()),
            ),
        )
        val payload = PgpMime.signablePayload(OutgoingMime.buildBodyEntity(m))
        val sig = "-----BEGIN PGP SIGNATURE-----\r\n\r\nx\r\n-----END PGP SIGNATURE-----"
        val entity = PgpMime.wrapSigned(payload, sig.toByteArray(), "pgp-sha256", "BND_SIG2")
        val env = MimeParser.detectCrypto("From: a@b.c\r\n$entity")
        assertNotNull(env)
        assertEquals(payload, env!!.signedEntityRaw)
        // The inner entity (a multipart/mixed) still parses: body + attachment inside.
        val innerBody = MimeParser.parseBody(env.signedEntityRaw!!)
        assertEquals("hello body", innerBody.text?.trim())
        assertEquals(1, innerBody.attachments.count { it.name == "doc.pdf" })
    }

    @Test
    fun encryptedRoundTrip_armorSurvives() {
        val armor = "-----BEGIN PGP MESSAGE-----\r\n\r\ncipher\r\n-----END PGP MESSAGE-----"
        val entity = PgpMime.wrapEncrypted(armor.toByteArray(), "BND_ENC")
        val env = MimeParser.detectCrypto("From: a@b.c\r\nMIME-Version: 1.0\r\n$entity")
        assertNotNull(env)
        assertEquals(CryptoKind.PGP_ENCRYPTED, env!!.kind)
        assertEquals(armor, env.encryptedArmor!!.trimEnd())
    }

    @Test
    fun pgpEntityReplacesBodyInFullMessage() {
        val armor = "-----BEGIN PGP MESSAGE-----\r\n\r\nzz\r\n-----END PGP MESSAGE-----"
        val entity = PgpMime.wrapEncrypted(armor.toByteArray(), "BND3")
        val full = OutgoingMime.build(message().copy(pgpEntity = entity))
        // Headers present, normal body absent, entity spliced in.
        assertTrue(full.startsWith("From: a@b.c\r\n"))
        assertTrue(full.contains("Subject: s\r\n"))
        assertTrue(full.contains("multipart/encrypted"))
        assertTrue(!full.contains("text/plain; charset=utf-8"))
        // And the whole message still detects as encrypted.
        assertEquals(CryptoKind.PGP_ENCRYPTED, MimeParser.detectCrypto(full)?.kind)
    }

    @Test
    fun buildBodyEntityMatchesLegacySingleBodyBuild() {
        // build() with no pgpEntity must equal headers + buildBodyEntity (refactor guard).
        val m = message()
        val full = OutgoingMime.build(m)
        assertTrue(full.endsWith(OutgoingMime.buildBodyEntity(m)))
    }
}
