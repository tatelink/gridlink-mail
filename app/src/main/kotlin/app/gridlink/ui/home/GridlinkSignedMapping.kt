package app.gridlink.ui.home

import app.gridlink.core.data.mail.SmimeStatus
import app.gridlink.core.data.mail.SmimeVerdict
import app.gridlink.ui.gridlink.GridlinkSigned
import app.gridlink.ui.gridlink.GridlinkSignedState

/**
 * A verifier's verdict as the reading pane's row.
 *
 * Here rather than in `ui/gridlink` for that package's standing rule: it draws screens and knows
 * nothing about repositories or the crypto layer, so the conversion happens on this side of the
 * line and the screen receives a finished value. Same arrangement as `gridlinkInviteOf`.
 *
 * 🔴 The mapping is deliberately lossy in one direction only: every status that is not a confirmed,
 * trusted, sender-matching signature maps to something that does not say "verified". There is no
 * state here that hedges — a reader gets an answer, and the answers this app cannot stand behind
 * are the ones drawn as problems.
 */
internal fun gridlinkSignedOf(verdict: SmimeVerdict): GridlinkSigned = GridlinkSigned(
    state = when (verdict.status) {
        SmimeStatus.VALID -> GridlinkSignedState.VALID
        SmimeStatus.UNTRUSTED -> GridlinkSignedState.UNTRUSTED
        SmimeStatus.MISMATCH -> GridlinkSignedState.MISMATCH
        SmimeStatus.INVALID -> GridlinkSignedState.INVALID
        SmimeStatus.UNSUPPORTED -> GridlinkSignedState.UNSUPPORTED
    },
    // The address out of the certificate, never the one in From. Where the two differ is exactly
    // the case worth showing, and showing From here would hide it.
    signer = verdict.signerEmail,
    // Only where it means something. Under an untrusted or mismatched signature, naming the issuer
    // lends it the authority of a CA the phone has not actually agreed with.
    issuer = verdict.issuer.takeIf { verdict.status == SmimeStatus.VALID },
    expired = verdict.expired,
)
