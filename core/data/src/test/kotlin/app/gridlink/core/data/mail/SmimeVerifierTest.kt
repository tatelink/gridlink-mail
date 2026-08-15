package app.gridlink.core.data.mail

import app.gridlink.core.imap.MimeParser
import app.gridlink.core.imap.SmimeKind
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date

/**
 * What an S/MIME signature is allowed to be reported as.
 *
 * 🔴 The case these tests exist for is [SmimeStatus.MISMATCH]: a cryptographically perfect signature
 * made with somebody else's certificate. Every library will happily confirm that signature, and a
 * client that repeats the confirmation as "verified" has helped the forger. The rest of the file
 * pins the other honest answers around it.
 *
 * Certificates here are made on the spot and self-signed, so nothing in this file can reach
 * [SmimeStatus.VALID] — that needs a real CA in the device's trust store, which is exactly the
 * distinction [SmimeStatus.UNTRUSTED] exists to draw.
 */
class SmimeVerifierTest {

    private val bc = BouncyCastleProvider()

    @Test
    fun `a message signed by its sender names the sender`() {
        val raw = signedMessage(signerAddress = "dara@example.com", from = "dara@example.com")
        val envelope = MimeParser.detectSmime(raw)
        assertNotNull(envelope)
        assertEquals(SmimeKind.DETACHED, envelope!!.kind)
        val verdict = SmimeVerifier.verify(envelope, "dara@example.com")
        // UNTRUSTED, not VALID: self-signed. Saying who signed is a fact; saying it is trustworthy
        // would be an opinion this device has no basis for.
        assertEquals(SmimeStatus.UNTRUSTED, verdict.status)
        assertEquals("dara@example.com", verdict.signerEmail)
        assertEquals("Dara Sender", verdict.signerName)
    }

    @Test
    fun `a perfect signature by the wrong person is not a valid signature`() {
        // The forgery: real key, real certificate, real signature over this exact message, issued
        // to somebody who is not the sender. The crypto is beyond reproach and the message is a lie.
        val raw = signedMessage(signerAddress = "attacker@evil.example", from = "bank@example.com")
        val envelope = MimeParser.detectSmime(raw)!!
        val verdict = SmimeVerifier.verify(envelope, "bank@example.com")
        assertEquals(SmimeStatus.MISMATCH, verdict.status)
        // Who it really was is the useful half of that answer.
        assertEquals("attacker@evil.example", verdict.signerEmail)
    }

    @Test
    fun `a message altered after signing does not verify`() {
        val raw = signedMessage(signerAddress = "dara@example.com", from = "dara@example.com")
        val tampered = raw.replace("Pay the usual account.", "Pay the NEW account.")
        val envelope = MimeParser.detectSmime(tampered)!!
        assertEquals(SmimeStatus.INVALID, SmimeVerifier.verify(envelope, "dara@example.com").status)
    }

    @Test
    fun `one changed byte in the signed headers does not verify`() {
        // The signed entity includes its own headers, which is the point of signing the entity
        // rather than the text: a subject swapped in transit has to break the signature too.
        val raw = signedMessage(signerAddress = "dara@example.com", from = "dara@example.com")
        val tampered = raw.replace("Subject: Invoice", "Subject: Urgent")
        val envelope = MimeParser.detectSmime(tampered)!!
        assertEquals(SmimeStatus.INVALID, SmimeVerifier.verify(envelope, "dara@example.com").status)
    }

    @Test
    fun `a message with no From to compare against claims nothing`() {
        // Nothing to match means nothing can be confirmed, and "signed by someone" is not a claim
        // worth making about a message whose sender is unknown.
        val raw = signedMessage(signerAddress = "dara@example.com", from = "dara@example.com")
        val envelope = MimeParser.detectSmime(raw)!!
        assertEquals(SmimeStatus.MISMATCH, SmimeVerifier.verify(envelope, null).status)
    }

    @Test
    fun `a whole message wrapped in its signature is read and verified`() {
        val raw = opaqueSignedMessage(signerAddress = "dara@example.com")
        val envelope = MimeParser.detectSmime(raw)!!
        assertEquals(SmimeKind.OPAQUE, envelope.kind)
        val verdict = SmimeVerifier.verify(envelope, "dara@example.com")
        assertEquals(SmimeStatus.UNTRUSTED, verdict.status)
        assertEquals("dara@example.com", verdict.signerEmail)
    }

    @Test
    fun `encrypted mail is not mistaken for a signature`() {
        // ⛔ enveloped-data is encryption. This app holds no private key and never will while
        // S/MIME here is verify-only, so the honest answer is to not recognise it at all rather
        // than to report a broken signature about a message that carries none.
        val raw = buildString {
            append("From: dara@example.com\r\n")
            append("Content-Type: application/pkcs7-mime; smime-type=enveloped-data; name=smime.p7m\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(Base64.getMimeEncoder().encodeToString(ByteArray(64) { it.toByte() }))
            append("\r\n")
        }
        assertNull(MimeParser.detectSmime(raw))
    }

    @Test
    fun `a signature blob that is not a signature claims nothing`() {
        val raw = buildString {
            append("From: dara@example.com\r\n")
            append("Content-Type: multipart/signed; protocol=\"application/pkcs7-signature\"; ")
            append("boundary=\"b1\"\r\n\r\n")
            append("--b1\r\nContent-Type: text/plain\r\n\r\nHello.\r\n")
            append("--b1\r\nContent-Type: application/pkcs7-signature\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(Base64.getMimeEncoder().encodeToString("not a signature at all".toByteArray()))
            append("\r\n--b1--\r\n")
        }
        val envelope = MimeParser.detectSmime(raw)!!
        assertEquals(SmimeStatus.UNSUPPORTED, SmimeVerifier.verify(envelope, "dara@example.com").status)
    }

    @Test
    fun `Outlook's own spelling of the signature part is still a signature`() {
        // application/x-pkcs7-signature predates the registration and is still emitted. Messages
        // written that way are real mail, so refusing to look at them would only lose signatures.
        val raw = signedMessage("dara@example.com", "dara@example.com")
            .replace("application/pkcs7-signature", "application/x-pkcs7-signature")
        val envelope = MimeParser.detectSmime(raw)
        assertNotNull(envelope)
        assertEquals(SmimeStatus.UNTRUSTED, SmimeVerifier.verify(envelope!!, "dara@example.com").status)
    }

    @Test
    fun `a message with no S-MIME in it is left alone`() {
        val raw = "From: dara@example.com\r\nSubject: Hi\r\nContent-Type: text/plain\r\n\r\nHello.\r\n"
        assertNull(MimeParser.detectSmime(raw))
    }

    // ---- test material -------------------------------------------------------------------

    /** A `multipart/signed` message whose first part is genuinely signed by [signerAddress]. */
    private fun signedMessage(signerAddress: String, from: String): String {
        val entity = buildString {
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Subject: Invoice\r\n\r\n")
            append("Pay the usual account.\r\n")
        }
        val der = sign(entity.toByteArray(Charsets.ISO_8859_1), signerAddress, encapsulate = false)
        return buildString {
            append("From: Dara Sender <$from>\r\n")
            append("Subject: Invoice\r\n")
            append("Content-Type: multipart/signed; protocol=\"application/pkcs7-signature\"; ")
            append("micalg=sha-256; boundary=\"b1\"\r\n\r\n")
            append("--b1\r\n")
            append(entity)
            // 🔴 A second CRLF, on purpose. RFC 2046 gives the break before a boundary to the
            // boundary, so a reader strips exactly one. The entity's own trailing CRLF was signed
            // and has to survive that strip, which is how real senders write it too.
            append("\r\n--b1\r\n")
            append("Content-Type: application/pkcs7-signature; name=smime.p7s\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(Base64.getMimeEncoder().encodeToString(der))
            append("\r\n--b1--\r\n")
        }
    }

    /** An `application/pkcs7-mime` message: the content lives inside the signature. */
    private fun opaqueSignedMessage(signerAddress: String): String {
        val content = "Content-Type: text/plain\r\n\r\nInside the blob.\r\n"
        val der = sign(content.toByteArray(Charsets.ISO_8859_1), signerAddress, encapsulate = true)
        return buildString {
            append("From: Dara Sender <$signerAddress>\r\n")
            append("Content-Type: application/pkcs7-mime; smime-type=signed-data; name=smime.p7m\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(Base64.getMimeEncoder().encodeToString(der))
            append("\r\n")
        }
    }

    /** A CMS SignedData over [content], detached unless [encapsulate]. */
    private fun sign(content: ByteArray, address: String, encapsulate: Boolean): ByteArray {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
        val cert = certificateFor(keys, address)
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(keys.private)
        val generator = CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(
                JcaSignerInfoGeneratorBuilder(
                    JcaDigestCalculatorProviderBuilder().setProvider(bc).build(),
                ).build(signer, cert),
            )
            addCertificates(JcaCertStore(listOf(cert)))
        }
        return generator.generate(CMSProcessableByteArray(content), encapsulate).encoded
    }

    /** A self-signed certificate carrying [address] as its rfc822 subject alternative name. */
    private fun certificateFor(keys: KeyPair, address: String): X509Certificate {
        val name = X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.CN, "Dara Sender")
            .addRDN(BCStyle.O, "Example")
            .build()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - DAY_MILLIS),
            Date(now + DAY_MILLIS),
            name,
            keys.public,
        ).addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.rfc822Name, address)),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(keys.private)
        return JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(signer))
    }

    private companion object {
        /** Small on purpose: these keys exist for one assertion each and are never stored. */
        const val KEY_BITS = 2048
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
