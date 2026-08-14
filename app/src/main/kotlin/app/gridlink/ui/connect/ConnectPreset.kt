package app.gridlink.ui.connect

import app.gridlink.R
import app.gridlink.core.data.account.ConnectionSecurity
import app.gridlink.core.data.account.MailProtocol
import app.gridlink.core.data.net.ImapEndpoints

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

/** A known mail provider's IMAP/SMTP settings, applied by the quick-setup rows. */
internal data class MailProvider(
    val name: String,
    val imapHost: String,
    val imapPort: String,
    val imapSecurity: ConnectionSecurity,
    val smtpHost: String,
    val smtpPort: String,
    val smtpSecurity: ConnectionSecurity,
    /** When true the row starts the OAuth sign-in flow instead of filling host/port. */
    val oauth: Boolean = false,
    /** Page where the user creates an app-specific password (their normal one is refused). */
    val appPasswordUrl: String? = null,
    /**
     * The provider's logo, drawn on its row in the setup list. `null` falls back to a lettermark.
     *
     * The bundled ones are each provider's own mark, in `res/drawable-nodpi` at 256px so a 30dp
     * chip is sharp on any display. They are other companies' trademarks, used to identify the
     * service the row sets up and nothing else. To add one, drop in
     * `ic_provider_<lowercase name, no dots or spaces>` and point this field at it; the row already
     * sizes and centres whatever it is handed.
     */
    val logoRes: Int? = null,
)

internal val MAIL_PROVIDERS = listOf(
    MailProvider("Gmail", "imap.gmail.com", "993", ConnectionSecurity.TLS, "smtp.gmail.com", "465", ConnectionSecurity.TLS, appPasswordUrl = "https://myaccount.google.com/apppasswords", logoRes = R.drawable.ic_provider_gmail),
    // Outlook authenticates over IMAP/SMTP with OAuth (XOAUTH2) — Microsoft has disabled
    // password IMAP — so this row launches the OAuth flow rather than filling host/port.
    MailProvider("Outlook", "outlook.office365.com", "993", ConnectionSecurity.TLS, "smtp.office365.com", "587", ConnectionSecurity.STARTTLS, oauth = true, logoRes = R.drawable.ic_provider_outlook),
    MailProvider("Yahoo", "imap.mail.yahoo.com", "993", ConnectionSecurity.TLS, "smtp.mail.yahoo.com", "465", ConnectionSecurity.TLS, logoRes = R.drawable.ic_provider_yahoo),
    MailProvider("iCloud", "imap.mail.me.com", "993", ConnectionSecurity.TLS, "smtp.mail.me.com", "587", ConnectionSecurity.STARTTLS, logoRes = R.drawable.ic_provider_icloud),
    MailProvider("Fastmail", "imap.fastmail.com", "993", ConnectionSecurity.TLS, "smtp.fastmail.com", "465", ConnectionSecurity.TLS, logoRes = R.drawable.ic_provider_fastmail),
    MailProvider("Proton Bridge", "127.0.0.1", "1143", ConnectionSecurity.STARTTLS, "127.0.0.1", "1025", ConnectionSecurity.STARTTLS, logoRes = R.drawable.ic_provider_protonbridge),
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
 * Choosing a protocol disarms an OAuth preset. The Microsoft device flow is an IMAP-side path, so a
 * preset left armed while JMAP is selected describes nothing the JMAP form can do (#105: it hid the
 * API-token field and sent Connect to the browser while the screen said JMAP).
 *
 * Non-OAuth presets survive, so a round trip through the protocol chips does not wipe the host and
 * port a user just filled in.
 */
internal fun presetForProtocol(preset: PresetForm, protocol: MailProtocol): PresetForm =
    if (protocol == MailProtocol.JMAP && preset.oauth) PresetForm.NONE else preset

/**
 * The form after applying what the address' domain publishes over SRV (RFC 6186), leaving anything
 * the user has already decided exactly as it was.
 *
 * 🔴 DNS never overwrites a person. A quick-setup chip is an explicit choice and stops this dead; a
 * host already in a field was either typed or put there by a chip, and either way the user's copy
 * wins. What is left is the case this feature exists for: an empty field on a domain that knows the
 * answer, where the alternative is the user hunting through a support page for four values.
 *
 * The port and security travel with the host they belong to, because a port on its own describes
 * nothing. [ImapEndpoints.smtpImplicitTls] is the only judgement call in here, and it is made where
 * the port is known — see [app.gridlink.core.data.net.MailSrv.imapEndpoints].
 */
internal fun presetWithDiscovered(current: PresetForm, discovered: ImapEndpoints): PresetForm {
    if (current.selected != null) return current
    var form = current
    val imapHost = discovered.imapHost
    val imapPort = discovered.imapPort
    if (form.imapHost.isBlank() && imapHost != null && imapPort != null) {
        form = form.copy(
            imapHost = imapHost,
            imapPort = imapPort.toString(),
            // `_imaps._tcp` is the implicit-TLS name; the cleartext `_imap._tcp` is never looked up.
            imapSecurity = ConnectionSecurity.TLS,
        )
    }
    val smtpHost = discovered.smtpHost
    val smtpPort = discovered.smtpPort
    if (form.smtpHost.isBlank() && smtpHost != null && smtpPort != null) {
        form = form.copy(
            smtpHost = smtpHost,
            smtpPort = smtpPort.toString(),
            smtpSecurity = if (discovered.smtpImplicitTls) ConnectionSecurity.TLS else ConnectionSecurity.STARTTLS,
        )
    }
    return form
}

/**
 * One row in the setup list. The list replaced a protocol chip row plus a separate, IMAP-only
 * provider chip row: two questions, the second of which was invisible until the first was answered
 * a particular way. A row is the whole answer.
 *
 * [Jmap] and [Imap] are the manual routes and lead the list, because they are what this app is for;
 * the branded rows are shortcuts that fill an IMAP form the user could have typed themselves.
 */
internal sealed interface SetupChoice {
    /** JMAP, configured by hand (autodiscovered from the address, or a server typed under Advanced). */
    data object Jmap : SetupChoice

    /** IMAP + SMTP with no provider chosen: the four server fields, empty and the user's problem. */
    data object Imap : SetupChoice

    /** A known provider, which fills those fields (or, for Outlook, hides them and runs OAuth). */
    data class Known(val provider: MailProvider) : SetupChoice
}

/** The list's contents, in row order: the two manual routes, then every known provider. */
internal val SETUP_CHOICES: List<SetupChoice> =
    listOf(SetupChoice.Jmap, SetupChoice.Imap) + MAIL_PROVIDERS.map { SetupChoice.Known(it) }

/**
 * Which row reads as chosen, derived from the form rather than stored beside it. A second copy of
 * "what is selected" is exactly the disagreement #105 was about, so there isn't one: [protocol] and
 * [preset] remain the only state, and the list is a view of them.
 */
internal fun selectedChoice(preset: PresetForm, protocol: MailProtocol): SetupChoice = when {
    protocol == MailProtocol.JMAP -> SetupChoice.Jmap
    preset.selected == null -> SetupChoice.Imap
    else -> MAIL_PROVIDERS.firstOrNull { it.name == preset.selected }
        ?.let { SetupChoice.Known(it) }
        // A saved selection naming a provider this build no longer ships: the form still holds that
        // provider's hosts, so plain IMAP is the honest row rather than nothing being selected.
        ?: SetupChoice.Imap
}

/**
 * The protocol a row implies. Every branded provider here is IMAP, including Outlook, whose OAuth
 * is XOAUTH2 over IMAP and SMTP rather than a protocol of its own.
 */
internal fun protocolFor(choice: SetupChoice): MailProtocol =
    if (choice == SetupChoice.Jmap) MailProtocol.JMAP else MailProtocol.IMAP

/**
 * The form after a row is tapped. Unlike the chips it replaced, tapping the selected row does
 * nothing: a row is a choice among choices, so there is no "off" to toggle back to, and the escape
 * that [presetChipTapped] existed to provide is now just the IMAP row sitting in plain sight.
 */
internal fun choiceTapped(current: PresetForm, choice: SetupChoice): PresetForm = when (choice) {
    // Both manual routes clear the form: an armed provider's hosts have no business surviving into
    // a form the user just said they wanted to fill in themselves.
    SetupChoice.Jmap, SetupChoice.Imap -> PresetForm.NONE
    is SetupChoice.Known ->
        if (current.selected == choice.provider.name) current else choice.provider.asForm()
}

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
