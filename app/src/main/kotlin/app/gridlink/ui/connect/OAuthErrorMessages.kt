package app.gridlink.ui.connect

import android.content.Context
import app.gridlink.R
import app.gridlink.core.jmap.DeviceTokenResult

/**
 * Map a device-flow failure to a specific, actionable message. Microsoft refusals carry an AADSTS
 * code / description in [DeviceTokenResult.Failed]; unverified-publisher and admin-consent refusals
 * point the user at asking their admin or using an app password instead of a dead-end "declined".
 * Shared by the first-run import sign-in and the account-settings sign-in.
 */
fun oauthFailureMessage(context: Context, failure: DeviceTokenResult.Failed): String {
    val aadsts = failure.aadstsCode
    val desc = failure.description
    return when {
        failure.error == "authorization_declined" || failure.error == "access_denied" ->
            context.getString(R.string.connect_oauth_declined)
        failure.error == "expired_token" ->
            context.getString(R.string.connect_oauth_expired)
        // Unverified-publisher / admin-consent / app-not-approved → the org must approve, or app password.
        aadsts == "AADSTS650051" || aadsts == "AADSTS90094" || aadsts == "AADSTS65001" ||
            desc.contains("admin", ignoreCase = true) || desc.contains("consent", ignoreCase = true) ||
            desc.contains("verified publisher", ignoreCase = true) ||
            desc.contains("not been approved", ignoreCase = true) ->
            context.getString(R.string.connect_oauth_admin_consent)
        aadsts != null -> context.getString(R.string.connect_oauth_error_code, aadsts)
        desc.isNotBlank() -> desc
        else -> context.getString(R.string.connect_oauth_denied)
    }
}
