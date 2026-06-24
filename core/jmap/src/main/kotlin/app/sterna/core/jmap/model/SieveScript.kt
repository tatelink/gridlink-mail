package app.sterna.core.jmap.model

import kotlinx.serialization.Serializable

/**
 * A JMAP SieveScript (RFC 9661 "JMAP for Sieve Scripts"): a server-side mail
 * filtering script. The script text lives in the blob referenced by [blobId];
 * at most one script per account is [isActive] (the one the server runs).
 */
@Serializable
data class SieveScript(
    val id: String,
    val name: String = "",
    val blobId: String = "",
    val isActive: Boolean = false,
)
