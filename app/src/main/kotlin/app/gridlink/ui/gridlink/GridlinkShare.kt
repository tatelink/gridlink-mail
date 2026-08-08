package app.gridlink.ui.gridlink

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Hand a plain-text summary to the system share sheet, reporting whether anything took it.
 *
 * The system sheet, not an in-app picker. Who someone shares a contact or an event with is not this
 * app's business, and the OS already knows the answer.
 *
 * 🔴 This exists as its own `: Boolean` function so that the cards calling it do not contain a raw
 * `startActivity` themselves. A composable holding one gets read as an opener by the hand-off rule
 * (`NavHostSourceRulesTest`), which then wants every call site of the CARD guarded, which is
 * meaningless: opening a card is not leaving the app. Keeping the launch here puts the guard where
 * it belongs, on the tap.
 *
 * Reporting rather than swallowing is what `leaveOnce` needs: a device with nothing that can share
 * throws, and a latch that had already recorded "we left" would then eat the next real tap.
 */
internal fun gridlinkShare(
    context: Context,
    subject: String,
    text: String,
    chooserTitle: String,
): Boolean = try {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, chooserTitle))
    true
} catch (e: Exception) {
    false
}

/**
 * Open the dialler with [number] typed in, reporting whether anything took it.
 *
 * ACTION_DIAL, not ACTION_CALL: the number is shown, not rung, so a mistap on a contact card costs
 * nothing and no call permission enters the manifest. Same `: Boolean` shape as [gridlinkShare],
 * for the same `leaveOnce` reason — a device with no dialler (a tablet) throws, and a latch that
 * had already recorded "we left" would eat the next real tap.
 */
internal fun gridlinkDial(
    context: Context,
    number: String,
): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
    true
} catch (e: Exception) {
    false
}
