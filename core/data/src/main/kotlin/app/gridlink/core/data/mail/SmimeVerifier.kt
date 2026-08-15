package app.gridlink.core.data.mail

import app.gridlink.core.imap.SmimeEnvelope
import app.gridlink.core.imap.SmimeKind
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.Time
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.util.Selector
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** What an S/MIME signature turned out to be worth. */
enum class SmimeStatus {
    /** Verified, the certificate chains to a CA this device trusts, and it names the sender. */
    VALID,

    /**
     * Verified and it names the sender, but the certificate chains to nobody this device trusts —
     * self-signed, or issued by a private CA. Says who signed it, vouches for nothing.
     */
    UNTRUSTED,

    /**
     * 🔴 The signature is good and belongs to somebody else. This is NOT a valid signature with a
     * footnote: a certificate for another address proves that person signed something, not that
     * this message is from the address in From, which is the only question a reader is asking.
     */
    MISMATCH,

    /** The signature does not verify: the message was altered after signing, or it never matched. */
    INVALID,

    /** Signed with something this app cannot check, or not a signature at all. Claims nothing. */
    UNSUPPORTED,
}

/**
 * The outcome of checking a message's S/MIME signature. [signerEmail] and [issuer] are worth
 * carrying even for [SmimeStatus.MISMATCH] and [SmimeStatus.UNTRUSTED], because the useful thing to
 * tell a reader is *who* signed it when the answer is not the sender.
 */
data class SmimeVerdict(
    val status: SmimeStatus,
    val signerEmail: String? = null,
    val signerName: String? = null,
    val issuer: String? = null,
    /** The certificate was inside its validity window when it signed, but is outside it now. */
    val expired: Boolean = false,
)

/**
 * S/MIME signature verification. **Verify only**: nothing here imports, stores or uses a private
 * key, so this file cannot sign, cannot decrypt, and adds nothing to the phone worth stealing.
 *
 * 🔴 The design rule for everything below: **the only reportable success is a signature that ties
 * this message to the address it claims to come from.** Every other outcome, however
 * cryptographically sound, is a different status. A verifier that says "valid signature" about a
 * certificate belonging to someone else has told the reader the opposite of the truth, which is
 * worse than not checking, because the reader believed it.
 *
 * 🔴 BouncyCastle parses; the PLATFORM computes. Nothing here registers a security provider or
 * names one, so the digests and the RSA/EC verification run on whatever the device already ships
 * (Conscrypt on Android, the JDK in tests) and only the CMS/ASN.1 reading is BouncyCastle's.
 *
 * That is a deliberate trade with three payoffs and one cost. `Security.addProvider` would either
 * be ignored or shadow the platform's own stripped-down "BC" for the whole process, changing which
 * implementation unrelated code gets; the platform's primitives are the maintained, often
 * hardware-backed ones; and R8 can then strip BouncyCastle's entire JCE provider tree, worth a
 * measured 885 KB of release APK (6.86 MB with the provider kept, 5.97 MB without). The cost is that a signature using an
 * algorithm the device does not implement reports [SmimeStatus.UNSUPPORTED] rather than being
 * verified by a bundled fallback. That is the honest answer anyway, and it is rare: SHA-2 with RSA
 * or ECDSA is what S/MIME in the wild is signed with, and every supported Android has both.
 */
object SmimeVerifier {

    /**
     * Check [envelope] against [fromAddress], the address the message claims to be from.
     *
     * Never throws: a message is arbitrary bytes off the network and every parse below can fail on
     * them. A failure is a verdict ([SmimeStatus.INVALID] when a signature was present and did not
     * hold, [SmimeStatus.UNSUPPORTED] when it could not be read at all), never an exception into a
     * reading pane.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun verify(envelope: SmimeEnvelope, fromAddress: String?): SmimeVerdict = try {
        val signed = when (envelope.kind) {
            // 🔴 CRLF, and ISO-8859-1 on the way to bytes. The signature covers the entity as it
            // travelled: line endings are part of what was hashed, and the raw source is a byte
            // container (one char per wire octet), so any other charset would re-encode every
            // 8-bit byte and nothing would ever verify. Same reasoning as the PGP path.
            SmimeKind.DETACHED -> CMSSignedData(
                CMSProcessableByteArray(
                    canonicalizeCrlf(envelope.signedEntityRaw.orEmpty())
                        .toByteArray(Charsets.ISO_8859_1),
                ),
                envelope.signature,
            )
            SmimeKind.OPAQUE -> CMSSignedData(envelope.signature)
        }
        verifySigner(signed, fromAddress)
    } catch (@Suppress("SwallowedException") t: Throwable) {
        // Swallowed on purpose, and the verdict IS the report: which way a hostile blob failed to
        // parse is of no use to the reader, and every path in here is reached with bytes a stranger
        // chose. Includes OutOfMemory territory: a message must not be able to take the app down by
        // being badly formed.
        SmimeVerdict(SmimeStatus.UNSUPPORTED)
    }

    @Suppress("ReturnCount")
    private fun verifySigner(signed: CMSSignedData, fromAddress: String?): SmimeVerdict {
        // One signer. A CMS blob may carry several, and "one of them verified" is not something a
        // one-line answer can honestly summarise, so the first is the one reported on.
        val signer: SignerInformation = signed.signerInfos.signers.firstOrNull()
            ?: return SmimeVerdict(SmimeStatus.UNSUPPORTED)

        // The cast is BouncyCastle's raw Selector showing through its Java generics; a SignerId
        // selects certificate holders and nothing else.
        @Suppress("UNCHECKED_CAST")
        val selector = signer.sid as Selector<X509CertificateHolder>
        val holder = signed.certificates.getMatches(selector).firstOrNull()
            ?: return SmimeVerdict(SmimeStatus.UNSUPPORTED)
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        val identity = SmimeVerdict(
            status = SmimeStatus.INVALID,
            signerEmail = emailOf(cert),
            signerName = commonNameOf(cert),
            issuer = issuerNameOf(cert),
        )

        val verified = runCatching {
            signer.verify(JcaSimpleSignerInfoVerifierBuilder().build(cert))
        }.getOrDefault(false)
        if (!verified) return identity

        // The address check comes BEFORE the trust check on purpose. A message signed by a
        // perfectly trustworthy certificate belonging to somebody else is the interesting attack,
        // and reporting it as "signed, but not by the sender" is more use than "signed, untrusted".
        val claimed = fromAddress?.trim()?.lowercase()
        if (claimed.isNullOrEmpty() || !identity.signerEmail.equals(claimed, ignoreCase = true)) {
            return identity.copy(status = SmimeStatus.MISMATCH)
        }

        // Inverted on purpose: checkValidity throws when it is out of date, so "did not throw"
        // is the only expression of "still valid" the API offers.
        val expired = runCatching { cert.checkValidity() }.isFailure
        val chain = chainFrom(cert, signed)
        val trusted = trusts(chain, signingTime(signer) ?: Date())
        return identity.copy(
            status = if (trusted) SmimeStatus.VALID else SmimeStatus.UNTRUSTED,
            expired = expired,
        )
    }

    /**
     * The signer's certificate followed by whatever intermediates the message carried, each one
     * issued by the next. Stops at the first certificate that issued itself: a root inside the
     * message is decoration, and the anchor has to come from the device or it is not an anchor.
     */
    private fun chainFrom(signer: X509Certificate, signed: CMSSignedData): List<X509Certificate> {
        val carried = signed.certificates.getMatches(null).mapNotNull { holder ->
            runCatching { JcaX509CertificateConverter().getCertificate(holder) }
                .getOrNull()
        }
        val chain = mutableListOf(signer)
        while (chain.size < MAX_CHAIN) {
            val last = chain.last()
            // Self-issued means the top: an issuer of itself has nobody above it to look for.
            val next = if (last.subjectX500Principal == last.issuerX500Principal) {
                null
            } else {
                carried.firstOrNull {
                    it.subjectX500Principal == last.issuerX500Principal && it !in chain
                }
            }
            chain += next ?: break
        }
        return chain
    }

    /**
     * Whether [chain] reaches a certificate authority this device trusts, as of [at].
     *
     * 🔴 Revocation checking is OFF, and that is a deliberate trade rather than an oversight. The
     * only way to check it is to ask the CA, over the network, at the moment a message is opened,
     * which tells that CA when the user read which sender's mail. The app does not spend the
     * reader's privacy on a check it can only sometimes make; what is on offer here is "this chains
     * to a real CA", not "this key is still live today", and the wording on screen says so.
     *
     * [at] is the signing time when the message claims one, so a message signed two years ago by a
     * certificate that has since expired still verifies, which is how mail is expected to age. A
     * forged signing time buys an attacker nothing on its own: the chain must still be genuine.
     */
    private fun trusts(chain: List<X509Certificate>, at: Date): Boolean = runCatching {
        val anchors = systemAnchors()
        if (anchors.isEmpty()) return false
        val path = CertificateFactory.getInstance("X.509").generateCertPath(chain)
        val params = PKIXParameters(anchors).apply {
            isRevocationEnabled = false
            date = at
        }
        CertPathValidator.getInstance("PKIX").validate(path, params)
        true
    }.getOrDefault(false)

    /**
     * The device's own CA list. Not the platform's `checkServerTrusted`/`checkClientTrusted`, which
     * demand a TLS extended key usage an email certificate does not have and would reject every
     * S/MIME certificate ever issued.
     */
    private fun systemAnchors(): Set<TrustAnchor> = runCatching {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>()
            .flatMap { it.acceptedIssuers.toList() }
            .map { TrustAnchor(it, null) }
            .toSet()
    }.getOrDefault(emptySet())

    /** The `signingTime` signed attribute, when the message carries one. */
    private fun signingTime(signer: SignerInformation): Date? = runCatching {
        // Time.getInstance, not a cast: a signing time is UTCTime before 2050 and
        // GeneralizedTime after it, and both are legal here.
        val attribute = signer.signedAttributes?.get(CMSAttributes.signingTime)
        attribute?.attributeValues?.firstOrNull()?.let { Time.getInstance(it).date }
    }.getOrNull()

    /**
     * The address a certificate is for: the rfc822 subject alternative name, falling back to an
     * `emailAddress` in the subject itself for older certificates that only carried it there.
     */
    private fun emailOf(cert: X509Certificate): String? = runCatching {
        val alt = cert.subjectAlternativeNames?.firstOrNull { it.size >= 2 && it[0] == RFC822_NAME }
        (alt?.get(1) as? String)?.lowercase()
            ?: rdn(cert, BCStyle.EmailAddress)?.lowercase()
    }.getOrNull()

    /** The human name on the certificate, for saying who signed rather than which address did. */
    private fun commonNameOf(cert: X509Certificate): String? =
        runCatching { rdn(cert, BCStyle.CN) }.getOrNull()

    /** Who issued the signer's certificate, in the form worth showing: its common name. */
    private fun issuerNameOf(cert: X509Certificate): String? = runCatching {
        val name = X500Name(cert.issuerX500Principal.name)
        name.getRDNs(BCStyle.CN).firstOrNull()?.first?.value?.let { IETFUtils.valueToString(it) }
            ?: name.getRDNs(BCStyle.O).firstOrNull()?.first?.value?.let { IETFUtils.valueToString(it) }
    }.getOrNull()

    private fun rdn(cert: X509Certificate, oid: ASN1ObjectIdentifier): String? {
        val name = X500Name(cert.subjectX500Principal.name)
        return name.getRDNs(oid).firstOrNull()?.first?.value?.let { IETFUtils.valueToString(it) }
    }

    /** RFC 5322 lines end CRLF, and that is what was hashed whatever the fetch handed back. */
    private fun canonicalizeCrlf(entity: String): String =
        entity.replace("\r\n", "\n").replace("\n", "\r\n")

    /** The subject-alternative-name tag for an email address (RFC 5280 §4.2.1.6). */
    private const val RFC822_NAME = 1

    /** A certificate chain longer than this is a loop or an attack, not a trust path. */
    private const val MAX_CHAIN = 12
}
