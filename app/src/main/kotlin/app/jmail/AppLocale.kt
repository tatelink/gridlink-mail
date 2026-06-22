package app.jmail

import java.util.Locale

/**
 * Locale for all user-facing formatting (dates, times, numbers), kept distinct
 * from the device locale so formatted content matches the app's UI language
 * rather than the phone's. The UI is English today; when a language setting
 * lands, this is the single place to make it read that setting.
 */
val appLocale: Locale = Locale.ENGLISH
