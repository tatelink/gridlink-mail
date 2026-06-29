package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTextTest {
    @Test fun reflowJoinsSoftWrapsAndKeepsParagraphs() {
        // Thunderbird-style format=flowed: intra-paragraph lines end with a space (soft break),
        // paragraphs are separated by a blank line.
        val flowed = "First paragraph wrapped softly here \n" +
            "and continues after a soft break.\n" +
            "\n" +
            "Second paragraph after a blank line."
        val expected = "First paragraph wrapped softly here and continues after a soft break.\n" +
            "\n" +
            "Second paragraph after a blank line."
        assertEquals(expected, reflowFormatFlowed(flowed))
    }

    @Test fun reflowLeavesNonFlowedTextUntouched() {
        // No trailing spaces -> nothing to join; hard line breaks must be preserved.
        val plain = "Line one\nLine two\n\nNext block"
        assertEquals(plain, reflowFormatFlowed(plain))
    }

    @Test fun reflowUndoesSpaceStuffingAndKeepsSignatureBreak() {
        // A leading space is space-stuffing (removed). "-- " is a hard break despite its space.
        val input = " From the start\n-- \nSignature line"
        assertEquals("From the start\n-- \nSignature line", reflowFormatFlowed(input))
    }
}
