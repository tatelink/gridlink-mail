package app.gridlink.ui.connect

import app.gridlink.core.data.account.ConnectionSecurity
import app.gridlink.core.data.account.MailProtocol

/**
 * What the add-account form decides on its own, kept out of the composable: which quick-setup
 * provider is selected, the server values that selection fills in, and where the Connect button
 * sends the result.
 *
 * It lives here because the answer used to be spread over four independent pieces of screen state
 * that could disagree with each other (#105): tapping "Outlook" armed a flag nothing could disarm,
 * so the server fields vanished with no way back, and the flag survived a switch to JMAP and sent
 * that form to the Microsoft sign-in.
 */

/** A known mail provider's IMAP/SMTP settings, applied by the quick-setup chips. */
internal data class MailProvider(
    val name: String,
    val imapHost: String,
    val imapPort: String,
    val imapSecurity: ConnectionSecurity,
    val smtpHost: String,
    val smtpPort: String,
    val smtpSecurity: ConnectionSecurity,
    /** When true the chip starts the OAuth sign-in flow instead of filling host/port. */
    val oauth: Boolean = false,
    /** Page where the user creates an app-specific password (their normal one is refused). */
    val appPasswordUrl: String? = null,
)

internal val MAIL_PROVIDERS = listOf(
    MailProvider("Gmail", "imap.gmail.com", "993", ConnectionSecurity.TLS, "smtp.gmail.com", "465", ConnectionSecurity.TLS, appPasswordUrl = "https://myaccount.google.com/apppasswords"),
    // Outlook authenticates over IMAP/SMTP with OAuth (XOAUTH2) — Microsoft has disabled
    // password IMAP — so this chip launches the OAuth flow rather than filling host/port.
    MailProvider("Outlook", "outlook.office365.com", "993", ConnectionSecurity.TLS, "smtp.office365.com", "587", ConnectionSecurity.STARTTLS, oauth = true),
    MailProvider("Yahoo", "imap.mail.yahoo.com", "993", ConnectionSecurity.TLS, "smtp.mail.yahoo.com", "465", ConnectionSecurity.TLS),
    MailProvider("iCloud", "imap.mail.me.com", "993", ConnectionSecurity.TLS, "smtp.mail.me.com", "587", ConnectionSecurity.STARTTLS),
    MailProvider("Fastmail", "imap.fastmail.com", "993", ConnectionSecurity.TLS, "smtp.fastmail.com", "465", ConnectionSecurity.TLS),
    // Yandex and Mail.ru are plain IMAP behind an app-specific password (#105). Values taken from
    // the providers' own documentation, not by analogy with each other:
    //   yandex.com/support/mail/mail-clients/others.html  → imap/smtp.yandex.com, 993/465, SSL
    //   help.mail.ru/mail/login/mailer/ (§ "Указать данные серверов") → imap/smtp.mail.ru, 993/465, SSL/TLS
    MailProvider("Yandex", "imap.yandex.com", "993", ConnectionSecurity.TLS, "smtp.yandex.com", "465", ConnectionSecurity.TLS, appPasswordUrl = "https://id.yandex.com/security/app-passwords"),
    MailProvider("Mail.ru", "imap.mail.ru", "993", ConnectionSecurity.TLS, "smtp.mail.ru", "465", ConnectionSecurity.TLS, appPasswordUrl = "https://account.mail.ru/user/2-step-auth/passwords/"),
    MailProvider("Proton Bridge", "127.0.0.1", "1143", ConnectionSecurity.STARTTLS, "127.0.0.1", "1025", ConnectionSecurity.STARTTLS),
)

/**
 * The quick-setup state of the form: the selected provider (by name — chips are brand labels, never
 * translated) plus the values that selection put in the server fields. [selected] `null` means no
 * chip is armed and the user is on their own, which is the state a fresh screen starts in and the
 * one a second tap on the armed chip returns to.
 */
internal data class PresetForm(
    val selected: String? = null,
    /** Sign-in goes through the browser (Microsoft device flow) instead of host/port + password. */
    val oauth: Boolean = false,
    val imapHost: String = "",
    val imapPort: String = "993",
    val imapSecurity: ConnectionSecurity = ConnectionSecurity.TLS,
    val smtpHost: String = "",
    val smtpPort: String = "465",
    val smtpSecurity: ConnectionSecurity = ConnectionSecurity.TLS,
    val appPasswordUrl: String? = null,
) {
    /**
     * Whether the manual server block is on screen. It is hidden under OAuth on purpose — host,
     * port and security mean nothing to XOAUTH2 and the user cannot know them — but hiding it was
     * only ever safe once the chip could be disarmed to bring it back.
     */
    val serverFieldsVisible: Boolean get() = !oauth

    companion object {
        /** No provider selected: empty hosts, the form's default ports, no app-password link. */
        val NONE = PresetForm()
    }
}

/** The values [provider] puts in the form, built fresh so no field survives from a previous pick. */
private fun MailProvider.asForm(): PresetForm = if (oauth) {
    // The host/port fields stay empty and hidden, and no app-password link is offered: Microsoft
    // refuses password IMAP, so such a link would send the user to a dead end.
    PresetForm(selected = name, oauth = true)
} else {
    PresetForm(
        selected = name,
        oauth = false,
        imapHost = imapHost,
        imapPort = imapPort,
        imapSecurity = imapSecurity,
        smtpHost = smtpHost,
        smtpPort = smtpPort,
        smtpSecurity = smtpSecurity,
        appPasswordUrl = appPasswordUrl,
    )
}

/**
 * What a tap on a quick-setup chip yields. Tapping the chip that is already selected clears the
 * selection, which is the whole fix for #105: the chips carry a visible selected state and the
 * OAuth one is no longer a one-way door.
 */
internal fun presetChipTapped(current: PresetForm, tapped: MailProvider): PresetForm =
    if (current.selected == tapped.name) PresetForm.NONE else tapped.asForm()

/**
 * Choosing a protocol disarms an OAuth preset. The Microsoft device flow is an IMAP-side path, so a
 * preset left armed while JMAP is selected describes nothing the JMAP form can do (#105: it hid the
 * API-token field and sent Connect to the browser while the screen said JMAP).
 *
 * Non-OAuth presets survive, so a round trip through the protocol chips does not wipe the host and
 * port a user just filled in.
 */
internal fun presetForProtocol(preset: PresetForm, protocol: MailProtocol): PresetForm =
    if (protocol == MailProtocol.JMAP && preset.oauth) PresetForm.NONE else preset

/** Where the Connect button sends the form. */
internal enum class ConnectRoute {
    /** Microsoft device flow (XOAUTH2 over IMAP/SMTP), reachable only from the Outlook chip. */
    OUTLOOK_OAUTH,

    /** JMAP with a server-issued API token (Bearer) typed in the secret field. */
    JMAP_TOKEN,

    /** JMAP password sign-in; the server is discovered from the address' domain. */
    JMAP_AUTODISCOVER,

    /** JMAP password sign-in against the server the user typed under Advanced. */
    JMAP_SERVER,

    /** IMAP + SMTP password sign-in against the host/port fields. */
    IMAP_PASSWORD,
}

/**
 * Which sign-in the Connect button runs, given the chip state and the chosen protocol.
 *
 * The OAuth branch is gated on the protocol as well as the flag, so even a preset that somehow
 * stayed armed cannot claim a JMAP form — the belt to [presetForProtocol]'s braces.
 */
internal fun connectRoute(
    preset: PresetForm,
    protocol: MailProtocol,
    useApiToken: Boolean,
    server: String,
): ConnectRoute = when {
    protocol == MailProtocol.IMAP && preset.oauth -> ConnectRoute.OUTLOOK_OAUTH
    protocol == MailProtocol.IMAP -> ConnectRoute.IMAP_PASSWORD
    useApiToken -> ConnectRoute.JMAP_TOKEN
    server.isBlank() -> ConnectRoute.JMAP_AUTODISCOVER
    else -> ConnectRoute.JMAP_SERVER
}

/**
 * Whether Connect has enough to run the [route] it would take. OAuth needs only the address (the
 * browser collects the rest); IMAP needs the four server values it will dial; the JMAP routes need
 * the secret typed in the password/token field.
 *
 * Ports come straight from the text fields, so a non-numeric one counts as missing.
 */
internal fun connectReady(
    route: ConnectRoute,
    username: String,
    password: String,
    imapHost: String,
    imapPort: String,
    smtpHost: String,
    smtpPort: String,
): Boolean = when (route) {
    ConnectRoute.OUTLOOK_OAUTH -> username.isNotBlank()
    ConnectRoute.IMAP_PASSWORD -> username.isNotBlank() && password.isNotBlank() &&
        imapHost.isNotBlank() && imapPort.toIntOrNull() != null &&
        smtpHost.isNotBlank() && smtpPort.toIntOrNull() != null
    else -> username.isNotBlank() && password.isNotBlank()
}
