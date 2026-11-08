package app.gridlink.ui.gridlink

import androidx.compose.runtime.Immutable
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact

/** One mailbox on a message header: the display name it carried (maybe blank) and the address. */
@Immutable
data class GridlinkAddress(val name: String, val email: String)

/**
 * What a reply needs from the message it answers and cannot get from the list row: who else was on
 * it, and the ids that thread the answer under it.
 *
 * A plain value for the package rule that keeps repositories out of `ui/gridlink`: the host fetches
 * the original on the account it belongs to and converts, this package only addresses the reply.
 *
 * [ownAddresses] are the addresses of the account the reply goes out from (its login and every
 * identity), lower case. Reply-all leaves them off, which is the part every client that gets
 * reply-all wrong gets wrong: it mails you a copy of your own answer.
 */
@Immutable
data class GridlinkReplyOriginal(
    val messageId: List<String>,
    val references: List<String>,
    val from: List<GridlinkAddress>,
    val to: List<GridlinkAddress>,
    val cc: List<GridlinkAddress>,
    val ownAddresses: Set<String>,
)

/** Who a reply or reply-all is addressed to: the TO line and the CC line. */
data class GridlinkReplyAddressing(val to: List<GridlinkAddress>, val cc: List<GridlinkAddress>)

/**
 * The In-Reply-To and References a reply to [original] carries (RFC 5322 section 3.6.4): the
 * original's Message-ID, and its References with that id appended, so every client files the answer
 * under the conversation instead of starting a new one.
 */
internal fun gridlinkReplyThreading(original: GridlinkReplyOriginal): Pair<List<String>, List<String>> {
    val ids = original.messageId.map { it.trim() }.filter { it.isNotEmpty() }
    val references = (original.references.map { it.trim() } + ids).filter { it.isNotEmpty() }.distinct()
    return ids to references
}

/**
 * Who a plain reply goes to: the sender, unless the sender is you (a reply from Sent), in which case
 * the people you wrote to, the way every mail client treats answering your own message.
 */
internal fun gridlinkReplyAddressing(original: GridlinkReplyOriginal): GridlinkReplyAddressing {
    val own = original.ownAddresses
    val fromOthers = original.from.filterNot { it.email.lowercase() in own }
    val to = when {
        fromOthers.isNotEmpty() -> fromOthers
        original.from.isEmpty() -> emptyList()
        else -> original.to.filterNot { it.email.lowercase() in own }.ifEmpty { original.from }
    }
    return GridlinkReplyAddressing(to = gridlinkDistinctAddresses(to), cc = emptyList())
}

/**
 * Who a reply-all goes to: everyone on the original except you.
 *
 * TO is the sender plus the original TO, CC stays CC, so the people who were copied are still only
 * copied. Your own addresses (every identity of the sending account) come off both lines, and
 * nobody appears twice. When that leaves TO empty but CC not (you were the only TO), CC moves up,
 * because a message with nobody in TO reads as a mistake. When it leaves nobody at all (a note to
 * yourself), it falls back to the plain reply's addressing rather than opening an empty composer.
 */
internal fun gridlinkReplyAllAddressing(original: GridlinkReplyOriginal): GridlinkReplyAddressing {
    val own = original.ownAddresses
    val reply = gridlinkReplyAddressing(original)
    val to = gridlinkDistinctAddresses(reply.to + original.to)
        .filterNot { it.email.lowercase() in own }
    val toKeys = to.map { it.email.lowercase() }.toSet()
    val cc = gridlinkDistinctAddresses(original.cc)
        .filterNot { it.email.lowercase() in own || it.email.lowercase() in toKeys }
    return when {
        to.isNotEmpty() -> GridlinkReplyAddressing(to, cc)
        cc.isNotEmpty() -> GridlinkReplyAddressing(cc, emptyList())
        else -> reply
    }
}

private fun gridlinkDistinctAddresses(addresses: List<GridlinkAddress>): List<GridlinkAddress> =
    addresses.filter { it.email.isNotBlank() }.distinctBy { it.email.trim().lowercase() }

/**
 * A header mailbox as a composer chip. The display name when the message carried one (so the chip
 * says "Avery Tate" and not the bare address), else the address, built the way a typed address is so
 * the two are indistinguishable once in the field.
 */
internal fun gridlinkAddressContact(address: GridlinkAddress): GridlinkContact {
    val email = address.email.trim()
    val name = address.name.trim()
    return GridlinkContact(
        id = "typed:${email.lowercase()}",
        given = "",
        family = name.ifEmpty { email },
        role = "",
        email = email,
    )
}

/**
 * [request] (a reply built from the list row by [gridlinkReplyTo]) addressed and threaded from the
 * real [original]. [all] picks reply-all's addressing over the plain reply's.
 *
 * The row-built recipient is kept only when the original has no usable sender at all, so a reply
 * never opens with nobody in TO just because a header was missing.
 */
internal fun gridlinkAddressedReply(
    request: GridlinkComposeRequest,
    original: GridlinkReplyOriginal,
    all: Boolean,
): GridlinkComposeRequest {
    val addressing = if (all) gridlinkReplyAllAddressing(original) else gridlinkReplyAddressing(original)
    val (inReplyTo, references) = gridlinkReplyThreading(original)
    val draft = request.draft
    return request.copy(
        draft = draft.copy(
            recipients = addressing.to.map(::gridlinkAddressContact).ifEmpty { draft.recipients },
            cc = addressing.cc.map(::gridlinkAddressContact),
            inReplyTo = inReplyTo,
            references = references,
            from = gridlinkReplyFrom(draft.from, original),
        ),
    )
}

/**
 * The From a reply starts on: the owning account ([gridlinkSentFromOwner] already chose it), as the
 * address the original was sent to when that is one of the account's own, so mail to an alias is
 * answered from the alias. A From that already names an address is left alone, and an address
 * that turns out not to be an identity degrades to the account's default when the send resolves it.
 */
internal fun gridlinkReplyFrom(from: GridlinkFrom?, original: GridlinkReplyOriginal): GridlinkFrom? {
    if (from == null || from.email != null) return from
    val addressed = (original.to + original.cc).firstOrNull { it.email.lowercase() in original.ownAddresses }
    return addressed?.let { from.copy(email = it.email) } ?: from
}
