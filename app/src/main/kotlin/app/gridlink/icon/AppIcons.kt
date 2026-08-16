package app.gridlink.icon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.gridlink.core.data.settings.AppIcon

/**
 * Puts the chosen launcher icon on the home screen.
 *
 * Android will not let an app set its own icon. The only mechanism is a set of activity-aliases in
 * the manifest, one per icon, of which exactly one is enabled; the launcher reads the enabled one.
 * This object is the single owner of that state, and it exists so the invariant lives in one place
 * rather than at every call site that wants to change an icon.
 *
 * ## What "exactly one" is protecting against
 * Zero enabled aliases removes the app from the launcher entirely. For a mail client that is not a
 * cosmetic bug: nothing is broken, no error is shown, the icon is simply gone, and the honest
 * reading from the home screen is that the app uninstalled itself. So [apply] enables the wanted
 * alias FIRST and only then disables the others. If the process dies between the two, the result is
 * two icons, which is visible, obviously wrong, and fixed by opening the app. The other order can
 * leave zero.
 *
 * ## Why the components are not killed
 * [PackageManager.DONT_KILL_APP] on every call. Without it the system may kill the process while the
 * user is sitting in Settings watching the change, which reads as a crash on a screen where they
 * just tapped something cosmetic. The flag is honoured for aliases; the icon still updates.
 *
 * ## What the user will actually see
 * ⚠️ The launcher decides when to redraw, and some do not do it immediately. Pixel Launcher and One
 * UI pick the change up within a second or two; a few third-party launchers need their own cache to
 * expire, and a pinned home-screen shortcut can briefly show the old icon. Nothing here can force
 * it, so the settings screen says so rather than leaving the user tapping the option again.
 */
object AppIcons {

    /**
     * 🔴 Frozen component names, matching AndroidManifest.xml. A launcher records the alias name
     * when the app is pinned to a home screen, so renaming one orphans that shortcut on every device
     * that has it. Add, never rename.
     */
    private val AppIcon.component: String
        get() = when (this) {
            AppIcon.AUTO -> "app.gridlink.icon.Auto"
            AppIcon.LIGHT -> "app.gridlink.icon.Light"
            AppIcon.DARK -> "app.gridlink.icon.Dark"
            AppIcon.OLED -> "app.gridlink.icon.Oled"
        }

    /**
     * Makes [icon] the one enabled launcher alias.
     *
     * Safe to call when nothing has changed: setting a component to the state it is already in is a
     * no-op inside PackageManager, which is what makes the startup reconciliation in [reconcile]
     * free rather than something that has to check first.
     */
    fun apply(context: Context, icon: AppIcon) {
        val pm = context.packageManager
        val pkg = context.packageName

        fun set(target: AppIcon, enabled: Boolean) {
            pm.setComponentEnabledSetting(
                ComponentName(pkg, target.component),
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        }

        // 🔴 Enable first, disable second. See the class KDoc: the reverse order has a window in
        // which the app has no launcher icon at all.
        set(icon, enabled = true)
        AppIcon.entries.filter { it != icon }.forEach { set(it, enabled = false) }
    }

    /**
     * Re-asserts [stored] over whatever the system currently has enabled, at startup.
     *
     * The two CAN drift, and neither one is wrong when they do:
     *  - "Clear app data" wipes the DataStore back to AUTO while leaving the alias where it was, so
     *    the phone keeps showing an icon the app no longer believes it chose.
     *  - A reinstall resets components to their manifest `enabled` values while an app-data restore
     *    can bring the stored choice back.
     *
     * The stored value wins, because it is the one the user actually made. Cheap enough to do every
     * launch: it is a handful of binder calls, and each one is a no-op when the state already agrees.
     */
    fun reconcile(context: Context, stored: AppIcon) = apply(context, stored)
}
