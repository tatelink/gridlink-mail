package app.gridlink.ui.gridlink

import android.content.Context
import android.content.Intent

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
