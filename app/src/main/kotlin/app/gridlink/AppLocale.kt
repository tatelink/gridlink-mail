package app.gridlink

import java.util.Locale

/**
 * Locale for all user-facing formatting (dates, times, numbers). Tracks the
 * locale Android resolved for the app — which honours a per-app language
 * (Settings → app language / `cmd locale set-app-locales`) before the device
 * locale — so formatted dates match the UI's translated language. Computed on
 * each read so it follows a per-app language change after the process restarts.
 */
val appLocale: Locale get() = Locale.getDefault()
