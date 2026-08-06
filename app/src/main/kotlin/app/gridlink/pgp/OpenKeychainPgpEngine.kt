package app.gridlink.pgp

import android.content.Context
import android.content.Intent
import app.gridlink.core.data.pgp.PgpDecrypted
import app.gridlink.core.data.pgp.PgpEngine
import app.gridlink.core.data.pgp.PgpResult
import app.gridlink.core.data.pgp.PgpSignature
import app.gridlink.core.data.pgp.PgpSignatureState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.OpenPgpDecryptionResult
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.OpenPgpSignatureResult
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.openpgp.util.OpenPgpServiceConnection

/**
 * [PgpEngine] backed by the OpenKeychain app over the openpgp-api bound service.
 * Binds lazily on first use and keeps the connection for the process lifetime.
 * All calls run on IO; OpenKeychain's own UI (passphrase, key chooser) surfaces
 * as [PgpResult.UserInteractionRequired] per the openpgp-api contract.
 */
class OpenKeychainPgpEngine(context: Context) : PgpEngine {

    private val appContext = context.applicationContext
    private val bindMutex = Mutex()
    private var connection: OpenPgpServiceConnection? = null

    private fun providerInstalled(): Boolean = PROVIDERS.any { pkg ->
        runCatching { appContext.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    private fun installedProvider(): String? = PROVIDERS.firstOrNull { pkg ->
        runCatching { appContext.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    /** Bind (once) and return the service, or null when unavailable. */
    private suspend fun service(): IOpenPgpService2? = bindMutex.withLock {
        connection?.takeIf { it.isBound }?.let { return it.service }
        val pkg = installedProvider() ?: return null
        val bound = withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val conn = OpenPgpServiceConnection(
                    appContext,
                    pkg,
                    object : OpenPgpServiceConnection.OnBound {
                        override fun onBound(service: IOpenPgpService2) {
                            if (cont.isActive) cont.resume(service)
                        }

                        override fun onError(e: Exception) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
                connection = conn
                conn.bindToService()
            }
        }
        if (bound == null) connection = null
        bound
    }

    override suspend fun isAvailable(): Boolean =
        providerInstalled() && withContext(Dispatchers.IO) { service() != null }

    /** Run [intent] through the provider, streaming [input] in and collecting output. */
    private suspend fun execute(
        intent: Intent,
        input: ByteArray?,
        collectOutput: Boolean,
    ): Pair<Intent, ByteArray?>? = withContext(Dispatchers.IO) {
        val service = service() ?: return@withContext null
        val api = OpenPgpApi(appContext, service)
        val out = if (collectOutput) ByteArrayOutputStream() else null
        // The provider is a foreign process reached over binder: it can die mid-call
        // (DeadObjectException) or hand back parcels this app's version can't unmarshal.
        // Treat any of that as "provider unavailable" rather than letting it throw into
        // the caller's coroutine (Codeberg #14).
        val result = try {
            api.executeApi(intent, input?.let { ByteArrayInputStream(it) }, out)
        } catch (t: Throwable) {
            return@withContext null
        }
        result to out?.toByteArray()
    }

    /** Map the provider's result envelope; [onSuccess] extracts the payload. */
    private fun <T> mapResult(
        response: Pair<Intent, ByteArray?>?,
        onSuccess: (Intent, ByteArray?) -> PgpResult<T>,
    ): PgpResult<T> {
        val (result, output) = response ?: return PgpResult.NotAvailable
        return when (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
            OpenPgpApi.RESULT_CODE_SUCCESS -> onSuccess(result, output)
            OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED -> {
                val pi = result.getParcelableExtra<android.app.PendingIntent>(OpenPgpApi.RESULT_INTENT)
                if (pi != null) PgpResult.UserInteractionRequired(pi)
                else PgpResult.Error("Provider requested interaction without an intent")
            }
            else -> {
                val error = result.getParcelableExtra<OpenPgpError>(OpenPgpApi.RESULT_ERROR)
                PgpResult.Error(error?.message ?: "OpenPGP provider error")
            }
        }
    }

    /** Base intent for [action], carrying over a prior interaction result if present. */
    private fun request(action: String, interactionResult: Intent?): Intent =
        (interactionResult?.let { Intent(it) } ?: Intent()).setAction(action)

    override suspend fun getSignKeyId(
        userIdHint: String?,
        interactionResult: Intent?,
    ): PgpResult<Long> {
        val intent = request(OpenPgpApi.ACTION_GET_SIGN_KEY_ID, interactionResult)
        if (userIdHint != null) intent.putExtra(OpenPgpApi.EXTRA_USER_ID, userIdHint)
        return mapResult(execute(intent, input = null, collectOutput = false)) { result, _ ->
            val keyId = result.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, 0L)
            if (keyId != 0L) PgpResult.Success(keyId) else PgpResult.Error("No signing key selected")
        }
    }

    override suspend fun findKeys(
        emails: List<String>,
        interactionResult: Intent?,
    ): PgpResult<LongArray> {
        val intent = request(OpenPgpApi.ACTION_GET_KEY_IDS, interactionResult)
        intent.putExtra(OpenPgpApi.EXTRA_USER_IDS, emails.toTypedArray())
        return mapResult(execute(intent, input = null, collectOutput = false)) { result, _ ->
            val ids = result.getLongArrayExtra(OpenPgpApi.RESULT_KEY_IDS)
            if (ids != null && ids.size >= emails.size) PgpResult.Success(ids)
            else PgpResult.Error("Missing public keys")
        }
    }

    override suspend fun findKeysEach(emails: List<String>): Map<String, Boolean> =
        emails.associateWith { email ->
            when (val r = findKeys(listOf(email))) {
                is PgpResult.Success -> r.value.isNotEmpty()
                else -> false
            }
        }

    override suspend fun detachedSign(
        data: ByteArray,
        signKeyId: Long,
        interactionResult: Intent?,
    ): PgpResult<PgpSignature> {
        val intent = request(OpenPgpApi.ACTION_DETACHED_SIGN, interactionResult)
        intent.putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, signKeyId)
        intent.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true)
        return mapResult(execute(intent, input = data, collectOutput = false)) { result, _ ->
            val sig = result.getByteArrayExtra(OpenPgpApi.RESULT_DETACHED_SIGNATURE)
            val micalg = result.getStringExtra(OpenPgpApi.RESULT_SIGNATURE_MICALG) ?: "pgp-sha256"
            if (sig != null) PgpResult.Success(PgpSignature(sig, micalg))
            else PgpResult.Error("Provider returned no signature")
        }
    }

    override suspend fun signAndEncrypt(
        data: ByteArray,
        signKeyId: Long?,
        recipientKeyIds: LongArray,
        interactionResult: Intent?,
    ): PgpResult<ByteArray> {
        val action =
            if (signKeyId != null) OpenPgpApi.ACTION_SIGN_AND_ENCRYPT else OpenPgpApi.ACTION_ENCRYPT
        val intent = request(action, interactionResult)
        intent.putExtra(OpenPgpApi.EXTRA_KEY_IDS, recipientKeyIds)
        if (signKeyId != null) intent.putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, signKeyId)
        intent.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true)
        return mapResult(execute(intent, input = data, collectOutput = true)) { _, output ->
            if (output != null && output.isNotEmpty()) PgpResult.Success(output)
            else PgpResult.Error("Provider returned no ciphertext")
        }
    }

    override suspend fun decryptVerify(
        data: ByteArray,
        senderAddress: String?,
        detachedSignature: ByteArray?,
        interactionResult: Intent?,
    ): PgpResult<PgpDecrypted> {
        val intent = request(OpenPgpApi.ACTION_DECRYPT_VERIFY, interactionResult)
        if (senderAddress != null) intent.putExtra(OpenPgpApi.EXTRA_SENDER_ADDRESS, senderAddress)
        if (detachedSignature != null) {
            intent.putExtra(OpenPgpApi.EXTRA_DETACHED_SIGNATURE, detachedSignature)
        }
        return mapResult(execute(intent, input = data, collectOutput = true)) { result, output ->
            val sig = result.getParcelableExtra<OpenPgpSignatureResult>(OpenPgpApi.RESULT_SIGNATURE)
            val dec = result.getParcelableExtra<OpenPgpDecryptionResult>(OpenPgpApi.RESULT_DECRYPTION)
            // For detached verification the plaintext IS the input we already have.
            val plaintext = if (detachedSignature != null) data else output ?: ByteArray(0)
            PgpResult.Success(
                PgpDecrypted(
                    plaintext = plaintext,
                    signature = sig.toState(),
                    signatureKeyId = sig?.keyId ?: 0L,
                    signatureUserId = sig?.primaryUserId,
                    wasEncrypted =
                        dec?.result == OpenPgpDecryptionResult.RESULT_ENCRYPTED,
                    signaturePendingIntent =
                        result.getParcelableExtra(OpenPgpApi.RESULT_INTENT),
                ),
            )
        }
    }

    private fun OpenPgpSignatureResult?.toState(): PgpSignatureState {
        this ?: return PgpSignatureState.NONE
        return when (result) {
            OpenPgpSignatureResult.RESULT_NO_SIGNATURE -> PgpSignatureState.NONE
            OpenPgpSignatureResult.RESULT_INVALID_SIGNATURE -> PgpSignatureState.INVALID
            OpenPgpSignatureResult.RESULT_KEY_MISSING -> PgpSignatureState.KEY_MISSING
            OpenPgpSignatureResult.RESULT_INVALID_KEY_REVOKED -> PgpSignatureState.KEY_REVOKED
            OpenPgpSignatureResult.RESULT_INVALID_KEY_EXPIRED -> PgpSignatureState.KEY_EXPIRED
            OpenPgpSignatureResult.RESULT_INVALID_KEY_INSECURE -> PgpSignatureState.INSECURE
            OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED,
            OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED,
            -> {
                val mismatch = senderStatusResult ==
                    OpenPgpSignatureResult.SenderStatusResult.USER_ID_MISSING
                when {
                    mismatch -> PgpSignatureState.SENDER_MISMATCH
                    result == OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED ->
                        PgpSignatureState.VALID_CONFIRMED
                    else -> PgpSignatureState.VALID_UNCONFIRMED
                }
            }
            else -> PgpSignatureState.INVALID
        }
    }

    private companion object {
        val PROVIDERS = listOf(
            "org.sufficientlysecure.keychain",
            "org.sufficientlysecure.keychain.debug",
        )
        const val BIND_TIMEOUT_MS = 5_000L
    }
}
