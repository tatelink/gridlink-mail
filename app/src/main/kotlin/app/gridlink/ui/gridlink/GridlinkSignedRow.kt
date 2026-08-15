package app.gridlink.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * What a message's S/MIME signature turned out to be worth.
 *
 * 🔴 The distinction this whole row exists to draw is [MISMATCH]. A cryptographically perfect
 * signature made with a certificate belonging to somebody other than the sender is not a valid
 * signature with a footnote: it is the exact shape a forgery takes, and a client that reports it as
 * "signed and verified" has done the forger's work. It is drawn like the problem it is.
 *
 * The other honest distinction is [VALID] against [UNTRUSTED]. Naming who signed something is a
 * fact; saying that person is who they claim to be is a statement this device can only make when the
 * certificate chains to a CA it already trusts.
 */
enum class GridlinkSignedState {
    /** Verified, chains to a trusted CA, and the certificate names the sender. */
    VALID,

    /** Verified and it names the sender, but nothing on this device vouches for the certificate. */
    UNTRUSTED,

    /** 🔴 A good signature belonging to somebody who is not the sender. */
    MISMATCH,

    /** The signature does not verify: altered after signing, or it never matched. */
    INVALID,

    /** Signed with something this app cannot check. Claims nothing either way. */
    UNSUPPORTED,
}

/** A signature verdict ready to draw. [signer] is the address in the certificate, not in From. */
@Immutable
data class GridlinkSigned(
    val state: GridlinkSignedState,
    val signer: String? = null,
    /** Who issued the certificate, for a reader who wants to know whose word this rests on. */
    val issuer: String? = null,
    /** Valid when it signed, out of date now. Worth saying, not worth failing the signature over. */
    val expired: Boolean = false,
)

/**
 * The row, above everything else in the message.
 *
 * Placed first on purpose: whether a message is really from the person it names governs how every
 * line under it should be read, including an invitation card. It is also the only row here that is
 * allowed to look like an alarm, and only in the two states that are actually alarming.
 *
 * There is no action. Nothing a reader could tap would change what the certificate says, and a
 * button next to a bad signature would only invite someone to dismiss it and carry on.
 */
@Composable
fun GridlinkSignedRow(signed: GridlinkSigned, modifier: Modifier = Modifier) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.field)
    val bad = signed.state == GridlinkSignedState.MISMATCH || signed.state == GridlinkSignedState.INVALID
    val tint = when (signed.state) {
        GridlinkSignedState.VALID -> colors.positive
        GridlinkSignedState.MISMATCH, GridlinkSignedState.INVALID -> colors.destructive
        GridlinkSignedState.UNTRUSTED, GridlinkSignedState.UNSUPPORTED -> colors.textSecondary
    }
    val icon = when (signed.state) {
        GridlinkSignedState.VALID -> Icons.Filled.GppGood
        GridlinkSignedState.MISMATCH, GridlinkSignedState.INVALID -> Icons.Filled.GppBad
        GridlinkSignedState.UNTRUSTED, GridlinkSignedState.UNSUPPORTED -> Icons.Filled.GppMaybe
    }
    val headline = headlineFor(signed)
    val detail = detailFor(signed)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GridlinkSpacing.s20, vertical = GridlinkSpacing.s4)
            .background(colors.surfaceRaised, shape)
            .border(GridlinkDimens.hairline, if (bad) tint else colors.surfaceBorder, shape)
            .padding(GridlinkSpacing.s12)
            // One announcement, in the order a person would say it. Read as separate nodes, a
            // screen reader gives "shield" and then two sentences whose relationship it cannot
            // convey, and the sentence that matters is the one about who really signed.
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(headline, detail).joinToString(" ")
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = headline,
                style = GridlinkType.metadata,
                color = if (bad) tint else colors.textPrimary,
            )
            detail?.let {
                Text(text = it, style = GridlinkType.metadata, color = colors.textSecondary)
            }
        }
    }
}

/**
 * The one sentence a reader gets. Each names the state in plain words rather than in the vocabulary
 * of certificates: "signed by" and "is not the sender" are things a person can act on, where
 * "signature validation failed" is a thing only a developer can.
 */
private fun headlineFor(signed: GridlinkSigned): String {
    val who = signed.signer
    return when (signed.state) {
        GridlinkSignedState.VALID ->
            if (who != null) "Signed by $who." else "Signed by the sender."
        GridlinkSignedState.UNTRUSTED ->
            if (who != null) "Signed by $who." else "Signed, but by nobody this phone can name."
        // Named first and named plainly. The useful half of a mismatch is WHO it really was.
        GridlinkSignedState.MISMATCH ->
            if (who != null) "Signed by $who, who is not the sender." else "Signed by somebody else."
        GridlinkSignedState.INVALID -> "This message was changed after it was signed."
        GridlinkSignedState.UNSUPPORTED -> "This message carries a signature this app can't check."
    }
}

/** The second line: what that first line does and does not prove. Null when it adds nothing. */
private fun detailFor(signed: GridlinkSigned): String? {
    val expired = "The certificate has expired since it signed this."
        .takeIf { signed.expired }
    val body = when (signed.state) {
        // Even here the claim stays narrow: a trusted chain says the certificate belongs to that
        // address, not that its owner is honest or that the mail is safe.
        GridlinkSignedState.VALID -> signed.issuer?.let { "Certificate issued by $it." }
        GridlinkSignedState.UNTRUSTED ->
            "Nothing on this phone vouches for that certificate, so it proves nothing on its own."
        GridlinkSignedState.MISMATCH ->
            "A real signature from the wrong person is how a forgery looks. Treat it as unsigned."
        GridlinkSignedState.INVALID ->
            "What you're reading is not what was signed. Nothing here can be trusted."
        GridlinkSignedState.UNSUPPORTED -> null
    }
    return listOfNotNull(body, expired).takeIf { it.isNotEmpty() }?.joinToString(" ")
}
