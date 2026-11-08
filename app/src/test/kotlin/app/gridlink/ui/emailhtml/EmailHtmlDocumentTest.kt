package app.gridlink.ui.emailhtml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailHtmlDocumentTest {
    private val newsletter =
        "<html><body style=\"margin:0\"><table role=\"presentation\" width=\"100%\"><tr><td>" +
            "<table role=\"presentation\" width=\"600\" style=\"max-width:600px\"><tr><td>Hi</td></tr>" +
            "</table></td></tr></table></body></html>"

    @Test
    fun `a 600px table is a designed layout`() {
        assertTrue(isDesignedLayout(newsletter))
        assertTrue(isDesignedLayout("<div style=\"width: 560px\">x</div>"))
    }

    @Test
    fun `simple html mail is not a designed layout`() {
        assertFalse(isDesignedLayout("<div dir=\"ltr\">Hi there</div>"))
        assertFalse(isDesignedLayout("<table width=\"100%\"><tr><td>x</td></tr></table>"))
        assertFalse(isDesignedLayout("<img width=\"120\" src=\"x\">"))
    }

    @Test
    fun `a designed newsletter loses only its side margin`() {
        val dark = EmailTheme("transparent", "#ffffff", "#4da3ff", true, paper = 0x101828)
        assertTrue(buildEmailHtmlDocument(newsletter, theme = dark).contains("margin: 16px 0 !important"))
        val simple = buildEmailHtmlDocument("<div>Hi</div>", theme = dark)
        assertTrue(simple.contains("margin: 16px !important"))
    }

    @Test
    fun `a dark newsletter is mapped onto the card colour`() {
        val dark = EmailTheme("transparent", "#ffffff", "#4da3ff", true, paper = 0x101828)
        val doc = buildEmailHtmlDocument(newsletter, theme = dark)
        assertTrue(doc.contains("url(#gl-paper)"))
        assertTrue(doc.contains("id=\"gl-paper-undo\""))
        val plainInvert = buildEmailHtmlDocument(newsletter, theme = dark.copy(paper = null))
        assertTrue(plainInvert.contains("invert(1) hue-rotate(180deg)"))
    }

    @Test
    fun `plain text uses the app face when given one`() {
        val light = EmailTheme("#ffffff", "#111111", "#0b5fff", false)
        val face = "data:font/ttf;base64,AA=="
        val doc = buildEmailHtmlDocument(null, textContent = "Hello", theme = light, plainFontDataUri = face)
        assertTrue(doc.contains("font-family: 'GridlinkText', sans-serif"))
        val rich = buildEmailHtmlDocument("<p>Hi</p>", theme = light, plainFontDataUri = face)
        assertFalse(rich.contains("GridlinkText"))
    }
}
