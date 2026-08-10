package app.gridlink.core.data.net

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Looks up the SRV records published under a service name. */
fun interface SrvResolver {
    /** The records for [name] (e.g. `_jmap._tcp.example.com`), or empty if there are none. */
    suspend fun lookup(name: String): List<SrvRecord>

    companion object {
        /** A resolver that finds nothing. The default, so no test or preview does DNS by accident. */
        val None: SrvResolver = SrvResolver { emptyList() }
    }
}

/**
 * SRV lookups through the system resolver.
 *
 * ## 🔴 Why this quietly does nothing below Android 10
 *
 * `android.net.DnsResolver` is API 29 and it is the only way to ask for a record type the platform
 * does not otherwise expose. `minSdk` is 26. The other two ways out were both refused:
 *
 * - **A DNS library** (dnsjava and friends) is a new dependency on a FOSS-only project, carried by
 *   every user, for one optional lookup.
 * - **DNS-over-HTTPS** ⛔ rejects itself. It would hand the user's mail domain to a third-party
 *   resolver at the exact moment they are setting up an account, which is the opposite of what this
 *   app is for. Do not "fix" the API 26-28 gap with it later.
 *
 * So API 26-28 gets no SRV, and that costs those users nothing they had: discovery there behaves
 * exactly as it did before this existed, falling through to the conventional hostname guesses.
 *
 * ## Privacy
 *
 * This adds no new disclosure. The query goes to whatever resolver the device is already configured
 * to use — the same one that resolves `mail.<domain>` a moment later — and asks it about a domain
 * the user just typed and is about to connect to anyway.
 */
class AndroidSrvResolver(
    /**
     * ⚠️ Discovery is on the critical path of a person waiting at a sign-in screen, and a domain
     * with no SRV record can leave the query unanswered rather than refused. Whatever has not come
     * back by now is abandoned and the guesses proceed.
     */
    private val timeoutMs: Long = 4_000L,
) : SrvResolver {

    override suspend fun lookup(name: String): List<SrvRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        return withTimeoutOrNull(timeoutMs) { Api29.query(name) } ?: emptyList()
    }

    /**
     * Held apart from [AndroidSrvResolver] so that nothing referencing [DnsResolver] is on a class
     * an API 26 device loads. The version check above is what keeps it that way; keep them together.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private object Api29 {

        /** Runs the callback on the caller's thread: the work is one buffer parse. */
        private val executor = Executor { it.run() }

        suspend fun query(name: String): List<SrvRecord> = suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            @Suppress("WrongConstant") // nsType is @IntDef'd to A/AAAA; SRV is a valid DNS type.
            DnsResolver.getInstance().rawQuery(
                null, // the default network
                name,
                DnsResolver.CLASS_IN,
                DnsSrvParser.TYPE_SRV,
                DnsResolver.FLAG_EMPTY,
                executor,
                signal,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        cont.resumeOnce(if (rcode == 0) DnsSrvParser.parse(answer) else emptyList())
                    }

                    // A domain with no mail SRV records is the ordinary case, not a fault worth
                    // reporting: it lands here as NXDOMAIN and the caller simply gets no candidates.
                    override fun onError(error: DnsResolver.DnsException) {
                        cont.resumeOnce(emptyList())
                    }
                },
            )
        }

        /**
         * 🔴 A continuation resumed twice throws. The callback contract says one of the two methods
         * is called once, but this is a system callback racing a timeout, and a crash on the sign-in
         * screen is too high a price for trusting that.
         */
        private fun CancellableContinuation<List<SrvRecord>>.resumeOnce(value: List<SrvRecord>) {
            if (isActive) resume(value)
        }
    }
}
