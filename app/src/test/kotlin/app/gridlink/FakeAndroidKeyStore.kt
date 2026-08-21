package app.gridlink

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * An in-memory stand-in for the Android KeyStore, for JVM tests only. The real
 * `AccountStore.add` encrypts the password through `KeyStore.getInstance("AndroidKeyStore")` and
 * `KeyGenerator.getInstance("AES", "AndroidKeyStore")`; Robolectric ships neither (the keystore is
 * a system service, not a JCA provider), so without this no account can be added from a test and
 * every account-bound screen is out of reach. This registers a JCA provider under that name whose
 * keystore keeps AES keys in a map. Ciphers still come from the JDK, so what the app writes and
 * reads is real AES-GCM, only the key lives in memory instead of in hardware.
 *
 * Installed once per JVM by [TestGridlinkApplication]; safe to call again.
 */
object FakeAndroidKeyStore {
    private const val NAME = "AndroidKeyStore"
    private val keys = ConcurrentHashMap<String, SecretKey>()

    fun install() {
        if (Security.getProvider(NAME) != null) return
        Security.addProvider(FakeProvider())
    }

    private class FakeProvider : Provider(NAME, 1.0, "In-memory Android KeyStore for tests") {
        init {
            put("KeyStore.$NAME", FakeKeyStoreSpi::class.java.name)
            put("KeyGenerator.AES", FakeAesKeyGenerator::class.java.name)
        }
    }

    /** Must be public with a no-arg constructor: the JCA instantiates it by name. */
    class FakeKeyStoreSpi : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineGetEntry(alias: String, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? =
            keys[alias]?.let { KeyStore.SecretKeyEntry(it) }
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) {
            keys[alias] = key as SecretKey
        }
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>?) =
            throw UnsupportedOperationException("raw key entries")
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) =
            throw UnsupportedOperationException("certificate entries")
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)
        override fun engineSize(): Int = keys.size
        override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    /** Generates a random AES key and files it under the alias the `KeyGenParameterSpec` names. */
    class FakeAesKeyGenerator : KeyGeneratorSpi() {
        private var alias: String? = null
        private var keySizeBits = 256

        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
            val spec = params as KeyGenParameterSpec
            alias = spec.keystoreAlias
            if (spec.keySize > 0) keySizeBits = spec.keySize
        }
        override fun engineInit(keysize: Int, random: SecureRandom?) { keySizeBits = keysize }
        override fun engineGenerateKey(): SecretKey {
            val raw = ByteArray(keySizeBits / 8).also { SecureRandom().nextBytes(it) }
            val key = SecretKeySpec(raw, "AES")
            alias?.let { keys[it] = key }
            return key
        }
    }
}
