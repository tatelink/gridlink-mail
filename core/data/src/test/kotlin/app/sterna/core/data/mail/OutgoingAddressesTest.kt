package app.sterna.core.data.mail

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The source-side refusal behind the SMTP envelope/header CR/LF filter: a send whose
 * addresses carry a line break must fail rather than quietly reach an address the user
 * never saw (a `mailto:` `%0D%0A`, or a `From` parsed out of a hostile message and
 * answered from a notification).
 */
class OutgoingAddressesTest {

    @Test fun plainAddressesAreAccepted() {
        requireSingleLineAddresses(listOf("alice@example.com", "Bob <bob@example.com>", ""))
    }

    @Test fun crlfInARecipientIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            requireSingleLineAddresses(
                listOf("alice@example.com", "bob@example.com\r\nRCPT TO:<victim@evil.com>"),
            )
        }
    }

    @Test fun bareCrOrLfIsRejectedToo() {
        assertThrows(IllegalArgumentException::class.java) {
            requireSingleLineAddresses(listOf("bob@example.com\nBcc: victim@evil.com"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireSingleLineAddresses(listOf("bob@example.com\rX"))
        }
    }
}
