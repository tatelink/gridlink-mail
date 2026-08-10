package app.gridlink.ui.connect

import app.gridlink.core.data.account.ConnectionSecurity
import app.gridlink.core.data.account.MailProtocol
import app.gridlink.core.data.net.ImapEndpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quick setup used to be four pieces of screen state that could disagree: the Outlook chip armed a
 * flag with no way to disarm it, so the server fields vanished for good, and the flag survived a
 * switch to JMAP and sent that form to the Microsoft sign-in (#105). These cover the decision that
 * replaced them — chip taps, protocol changes, and where Connect ends up.
 */
class ConnectPresetTest {

    private fun provider(name: String) = MAIL_PROVIDERS.first { it.name == name }

    private val outlook = provider("Outlook")
    private val gmail = provider("Gmail")
    private val yandex = provider("Yandex")
    private val mailru = provider("Mail.ru")

    // --- The chip is a door that opens both ways -------------------------------------------------

    @Test fun tappingTheArmedChipAgainReleasesIt() {
        val armed = presetChipTapped(PresetForm.NONE, outlook)
        assertEquals("Outlook", armed.selected)
        assertTrue(armed.oauth)
        assertFalse("the server fields go away under OAuth", armed.serverFieldsVisible)

        val released = presetChipTapped(armed, outlook)
        assertNull(released.selected)
        assertFalse(released.oauth)
        assertTrue("and the second tap must bring them back", released.serverFieldsVisible)
        assertEquals(PresetForm.NONE, released)
    }

    @Test fun aPasswordPresetIsAlsoReleasedByASecondTap() {
        val armed = presetChipTapped(PresetForm.NONE, gmail)
        assertEquals("Gmail", armed.selected)
        assertEquals(PresetForm.NONE, presetChipTapped(armed, gmail))
    }

    @Test fun tappingAnotherChipSwitchesRatherThanReleases() {
        val armed = presetChipTapped(PresetForm.NONE, outlook)
        val switched = presetChipTapped(armed, yandex)
        assertEquals("Yandex", switched.selected)
        assertFalse(switched.oauth)
        assertEquals("imap.yandex.com", switched.imapHost)
    }

    // --- No preset inherits from the one before it -----------------------------------------------

    @Test fun eachPresetReplacesEveryFieldOfTheLast() {
        // Proton Bridge is the only entry on non-standard ports and STARTTLS on both sides, so any
        // field left over from it would be visible in the next pick.
        val bridge = presetChipTapped(PresetForm.NONE, provider("Proton Bridge"))
        val next = presetChipTapped(bridge, mailru)
        assertEquals("imap.mail.ru", next.imapHost)
        assertEquals("993", next.imapPort)
        assertEquals(ConnectionSecurity.TLS, next.imapSecurity)
        assertEquals("smtp.mail.ru", next.smtpHost)
        assertEquals("465", next.smtpPort)
        assertEquals(ConnectionSecurity.TLS, next.smtpSecurity)
    }

    @Test fun theOauthPresetCarriesNoServerValuesAndNoAppPasswordLink() {
        // Selecting Outlook after Gmail must not leave Gmail's hosts hidden behind the OAuth
        // screen, and Microsoft refuses password IMAP, so an app-password link would be a dead end.
        val armed = presetChipTapped(presetChipTapped(PresetForm.NONE, gmail), outlook)
        assertEquals("", armed.imapHost)
        assertEquals("", armed.smtpHost)
        assertNull(armed.appPasswordUrl)
    }

    @Test fun releasingAPresetClearsTheFieldsItFilled() {
        val released = presetChipTapped(presetChipTapped(PresetForm.NONE, yandex), yandex)
        assertEquals("", released.imapHost)
        assertEquals("", released.smtpHost)
        assertNull(released.appPasswordUrl)
    }

    // --- Switching protocol cannot leave OAuth armed ---------------------------------------------

    @Test fun choosingJmapDisarmsTheOauthPreset() {
        val armed = presetChipTapped(PresetForm.NONE, outlook)
        val afterJmap = presetForProtocol(armed, MailProtocol.JMAP)
        assertFalse(afterJmap.oauth)
        assertNull(afterJmap.selected)
    }

    @Test fun choosingJmapKeepsAPasswordPresetIntact() {
        val armed = presetChipTapped(PresetForm.NONE, yandex)
        assertEquals(armed, presetForProtocol(armed, MailProtocol.JMAP))
        assertEquals(armed, presetForProtocol(armed, MailProtocol.IMAP))
    }

    @Test fun stayingOnImapKeepsTheOauthPreset() {
        val armed = presetChipTapped(PresetForm.NONE, outlook)
        assertEquals(armed, presetForProtocol(armed, MailProtocol.IMAP))
    }

    // --- Where Connect goes ----------------------------------------------------------------------

    @Test fun outlookThenJmapDoesNotSignInToMicrosoft() {
        val armed = presetChipTapped(PresetForm.NONE, outlook)
        // Through the screen's own path: the protocol chip disarms it first.
        val afterJmap = presetForProtocol(armed, MailProtocol.JMAP)
        assertEquals(
            ConnectRoute.JMAP_AUTODISCOVER,
            connectRoute(afterJmap, MailProtocol.JMAP, useApiToken = false, server = ""),
        )
        // And the second guard: even a preset that stayed armed cannot claim a JMAP form.
        assertEquals(
            ConnectRoute.JMAP_AUTODISCOVER,
            connectRoute(armed, MailProtocol.JMAP, useApiToken = false, server = ""),
        )
        assertEquals(
            ConnectRoute.JMAP_TOKEN,
            connectRoute(armed, MailProtocol.JMAP, useApiToken = true, server = ""),
        )
    }

    @Test fun theOutlookChipStillReachesTheMicrosoftFlow() {
        val armed = presetChipTapped(PresetForm.NONE, outlook)
        assertEquals(
            ConnectRoute.OUTLOOK_OAUTH,
            connectRoute(armed, MailProtocol.IMAP, useApiToken = false, server = ""),
        )
    }

    @Test fun aReleasedOutlookChipGoesBackToTheManualImapPath() {
        val released = presetChipTapped(presetChipTapped(PresetForm.NONE, outlook), outlook)
        assertEquals(
            ConnectRoute.IMAP_PASSWORD,
            connectRoute(released, MailProtocol.IMAP, useApiToken = false, server = ""),
        )
    }

    @Test fun aTypedJmapServerOverridesAutodiscovery() {
        assertEquals(
            ConnectRoute.JMAP_SERVER,
            connectRoute(PresetForm.NONE, MailProtocol.JMAP, useApiToken = false, server = "jmap.example.org"),
        )
    }

    // --- What Connect needs before it will run ---------------------------------------------------

    @Test fun oauthNeedsOnlyTheAddress() {
        assertTrue(
            connectReady(
                ConnectRoute.OUTLOOK_OAUTH, "alex@outlook.com", password = "",
                imapHost = "", imapPort = "", smtpHost = "", smtpPort = "",
            ),
        )
        assertFalse(
            connectReady(
                ConnectRoute.OUTLOOK_OAUTH, "", password = "",
                imapHost = "", imapPort = "", smtpHost = "", smtpPort = "",
            ),
        )
    }

    @Test fun theImapPathNeedsTheFourServerValuesItWillDial() {
        val filled = presetChipTapped(PresetForm.NONE, mailru)
        assertTrue(
            connectReady(
                ConnectRoute.IMAP_PASSWORD, "alex@mail.ru", "app-password",
                filled.imapHost, filled.imapPort, filled.smtpHost, filled.smtpPort,
            ),
        )
        // A port the user cleared or mistyped counts as missing, not as a crash at connect time.
        assertFalse(
            connectReady(
                ConnectRoute.IMAP_PASSWORD, "alex@mail.ru", "app-password",
                filled.imapHost, "", filled.smtpHost, filled.smtpPort,
            ),
        )
    }

    @Test fun theJmapPathsNeedTheSecretButNoServerValues() {
        assertTrue(
            connectReady(
                ConnectRoute.JMAP_AUTODISCOVER, "alex@example.org", "secret",
                imapHost = "", imapPort = "", smtpHost = "", smtpPort = "",
            ),
        )
        assertFalse(
            connectReady(
                ConnectRoute.JMAP_TOKEN, "alex@example.org", "",
                imapHost = "", imapPort = "", smtpHost = "", smtpPort = "",
            ),
        )
    }

    // --- The table itself ------------------------------------------------------------------------

    @Test fun yandexAndMailRuAreAppPasswordImapPresets() {
        // Both are plain IMAP with an app-specific password: the chip must fill the servers AND
        // offer the page where that password is created, or the user hits a login failure they
        // cannot diagnose. Values are the providers' own documented ones.
        assertEquals("imap.yandex.com", yandex.imapHost)
        assertEquals("993", yandex.imapPort)
        assertEquals(ConnectionSecurity.TLS, yandex.imapSecurity)
        assertEquals("smtp.yandex.com", yandex.smtpHost)
        assertEquals("465", yandex.smtpPort)
        assertEquals(ConnectionSecurity.TLS, yandex.smtpSecurity)

        assertEquals("imap.mail.ru", mailru.imapHost)
        assertEquals("993", mailru.imapPort)
        assertEquals(ConnectionSecurity.TLS, mailru.imapSecurity)
        assertEquals("smtp.mail.ru", mailru.smtpHost)
        assertEquals("465", mailru.smtpPort)
        assertEquals(ConnectionSecurity.TLS, mailru.smtpSecurity)

        listOf(yandex, mailru).forEach {
            assertFalse("${it.name} is password IMAP, not OAuth", it.oauth)
            assertNotNull("${it.name} needs its app-password page", it.appPasswordUrl)
        }
    }

    @Test fun everyEntryIsUsableAndOnlyOneSignsInByOauth() {
        MAIL_PROVIDERS.forEach {
            assertTrue("${it.name} host", it.imapHost.isNotBlank() && it.smtpHost.isNotBlank())
            assertNotNull("${it.name} imap port", it.imapPort.toIntOrNull())
            assertNotNull("${it.name} smtp port", it.smtpPort.toIntOrNull())
        }
        assertEquals(listOf("Outlook"), MAIL_PROVIDERS.filter { it.oauth }.map { it.name })
        // Names double as the chips' identity in [PresetForm.selected]: a duplicate would make two
        // chips light up together and share one selection.
        assertEquals(MAIL_PROVIDERS.size, MAIL_PROVIDERS.map { it.name }.toSet().size)
    }

    // --- What the domain publishes, and what it is not allowed to touch --------------------------

    private fun endpoints(
        imapHost: String? = "imap.example.com",
        imapPort: Int? = 993,
        smtpHost: String? = "smtp.example.com",
        smtpPort: Int? = 587,
        smtpImplicitTls: Boolean = false,
    ) = ImapEndpoints(imapHost, imapPort, smtpHost, smtpPort, smtpImplicitTls)

    @Test fun publishedServersFillAnEmptyForm() {
        val filled = presetWithDiscovered(PresetForm.NONE, endpoints())
        assertEquals("imap.example.com", filled.imapHost)
        assertEquals("993", filled.imapPort)
        assertEquals(ConnectionSecurity.TLS, filled.imapSecurity)
        assertEquals("smtp.example.com", filled.smtpHost)
        assertEquals("587", filled.smtpPort)
        assertEquals(ConnectionSecurity.STARTTLS, filled.smtpSecurity)
        // And the form is then complete enough to connect with, which is the entire point.
        assertTrue(
            connectReady(
                ConnectRoute.IMAP_PASSWORD, "alex@example.com", "secret",
                filled.imapHost, filled.imapPort, filled.smtpHost, filled.smtpPort,
            ),
        )
    }

    @Test fun submissionOn465IsFilledInAsImplicitTls() {
        val filled = presetWithDiscovered(PresetForm.NONE, endpoints(smtpPort = 465, smtpImplicitTls = true))
        assertEquals("465", filled.smtpPort)
        assertEquals(ConnectionSecurity.TLS, filled.smtpSecurity)
    }

    @Test fun anArmedChipIsNeverOverruledByDns() {
        // 🔴 The chip is an explicit choice about which provider this is. A domain that publishes
        // something else does not get to move the user's account to another server.
        val armed = presetChipTapped(PresetForm.NONE, gmail)
        assertEquals(armed, presetWithDiscovered(armed, endpoints()))
    }

    @Test fun aHostTheUserTypedIsNeverOverwritten() {
        val typed = PresetForm.NONE.copy(imapHost = "mine.example.com", imapPort = "1993")
        val filled = presetWithDiscovered(typed, endpoints())
        assertEquals("mine.example.com", filled.imapHost)
        assertEquals("1993", filled.imapPort)
        // The half they had not got to yet is still worth filling in.
        assertEquals("smtp.example.com", filled.smtpHost)
    }

    @Test fun halfAnAnswerFillsHalfTheForm() {
        val filled = presetWithDiscovered(PresetForm.NONE, endpoints(smtpHost = null, smtpPort = null))
        assertEquals("imap.example.com", filled.imapHost)
        assertEquals("", filled.smtpHost)
        // Left incomplete on purpose: Connect stays disabled until the user supplies the rest,
        // rather than dialling a default that was never published.
        assertFalse(
            connectReady(
                ConnectRoute.IMAP_PASSWORD, "alex@example.com", "secret",
                filled.imapHost, filled.imapPort, filled.smtpHost, filled.smtpPort,
            ),
        )
    }

    @Test fun anAnswerWithNothingUsableLeavesTheFormAlone() {
        // The screen compares the result with what it had to decide whether to tell the user their
        // settings came from DNS, so "changed nothing" has to come back as the same form.
        val empty = endpoints(imapHost = null, imapPort = null, smtpHost = null, smtpPort = null)
        assertEquals(PresetForm.NONE, presetWithDiscovered(PresetForm.NONE, empty))
    }
}
