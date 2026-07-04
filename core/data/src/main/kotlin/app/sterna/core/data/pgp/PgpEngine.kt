package app.sterna.core.data.pgp

import android.app.PendingIntent
import android.content.Intent

/** Per-message crypto mode chosen in the composer. ENCRYPT always signs too. */
enum class PgpMode { OFF, SIGN, ENCRYPT }

/** Outcome of verifying an OpenPGP signature, mapped for UI badge semantics. */
enum class PgpSignatureState {
    /** No signature present. */
    NONE,

    /** Valid signature from a key the user has confirmed (green). */
    VALID_CONFIRMED,

    /** Valid signature from a known but unconfirmed key (yellow). */
    VALID_UNCONFIRMED,

    /** Signature present but the public key is not available (grey). */
    KEY_MISSING,

    /** Signature did not verify — content altered or corrupt (red). */
    INVALID,

    /** Valid signature but the key is revoked (red). */
    KEY_REVOKED,

    /** Valid signature but the key is expired (red). */
    KEY_EXPIRED,

    /** Valid signature from a cryptographically weak key (red). */
    INSECURE,

    /** Valid signature but the key's user id does not match the sender (yellow). */
    SENDER_MISMATCH,
}

/**
 * Result of a [PgpEngine] operation. OpenPGP providers routinely need the user
 * (unlock passphrase, choose a key): that surfaces as [UserInteractionRequired],
 * whose [PendingIntent] must be launched with StartIntentSenderForResult; the
 * activity-result data Intent is then passed back as `interactionResult` to retry
 * the same call.
 */
sealed interface PgpResult<out T> {
    data class Success<T>(val value: T) : PgpResult<T>

    data class UserInteractionRequired(val pendingIntent: PendingIntent) : PgpResult<Nothing>

    data class Error(val message: String) : PgpResult<Nothing>

    /** No OpenPGP provider installed (or the service cannot be bound). */
    object NotAvailable : PgpResult<Nothing>
}

/** Detached-signature output: the armored signature + the micalg for the PGP/MIME wrapper. */
class PgpSignature(
    val armor: ByteArray,
    /** RFC 3156 micalg parameter, e.g. "pgp-sha256". */
    val micalg: String,
)

/** Decrypt/verify output: the plaintext plus what the signature (if any) told us. */
class PgpDecrypted(
    val plaintext: ByteArray,
    val signature: PgpSignatureState,
    val signatureKeyId: Long,
    val signatureUserId: String?,
    val wasEncrypted: Boolean,
    /** Pending intent to show/import the signing key in the provider, when offered. */
    val signaturePendingIntent: PendingIntent? = null,
)

/**
 * Message-level OpenPGP operations. Implemented in the app layer (the reference
 * implementation binds the OpenKeychain service); kept as an interface here so
 * core modules stay free of any crypto/provider dependency. All methods accept
 * the activity-result Intent from a prior [PgpResult.UserInteractionRequired]
 * round-trip to retry the operation.
 */
interface PgpEngine {
    /** True when an OpenPGP provider is installed and its service can be bound. */
    suspend fun isAvailable(): Boolean

    /** Let the user pick (or confirm) their signing key; returns the key id. */
    suspend fun getSignKeyId(
        userIdHint: String? = null,
        interactionResult: Intent? = null,
    ): PgpResult<Long>

    /**
     * Resolve recipient addresses to public-key ids. Fails (Error) when any
     * address has no usable key — callers surface which ones via [findKeysEach].
     */
    suspend fun findKeys(
        emails: List<String>,
        interactionResult: Intent? = null,
    ): PgpResult<LongArray>

    /** Per-address key availability (true = at least one usable key). */
    suspend fun findKeysEach(emails: List<String>): Map<String, Boolean>

    /** Detached-sign [data]; returns the ASCII-armored signature + micalg (RFC 3156). */
    suspend fun detachedSign(
        data: ByteArray,
        signKeyId: Long,
        interactionResult: Intent? = null,
    ): PgpResult<PgpSignature>

    /**
     * Encrypt [data] to [recipientKeyIds] (callers include the sender's own key
     * for encrypt-to-self), signing with [signKeyId] when non-null. Returns
     * ASCII-armored ciphertext.
     */
    suspend fun signAndEncrypt(
        data: ByteArray,
        signKeyId: Long?,
        recipientKeyIds: LongArray,
        interactionResult: Intent? = null,
    ): PgpResult<ByteArray>

    /**
     * Decrypt and/or verify [data]. For multipart/signed pass the exact signed
     * bytes plus [detachedSignature]; for encrypted messages pass the armored
     * ciphertext. [senderAddress] lets the provider flag sender/uid mismatches.
     */
    suspend fun decryptVerify(
        data: ByteArray,
        senderAddress: String?,
        detachedSignature: ByteArray? = null,
        interactionResult: Intent? = null,
    ): PgpResult<PgpDecrypted>
}
