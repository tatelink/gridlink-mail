package app.sterna.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeTextTest {
    @Test fun detectsAttachmentMentionsAcrossLanguages() {
        val positives = listOf(
            "Please see the attached file",          // en
            "Voir le document ci-joint",             // fr
            "Voici la pièce jointe",                 // fr
            "Details im Anhang",                     // de
            "Te envío el documento adjunto",         // es
            "Trovi il file in allegato",             // it
            "Segue o documento em anexo",            // pt
            "Zie de bijlage",                        // nl
            "Смотри вложение",                       // ru
            "W załączeniu przesyłam plik",           // pl
        )
        positives.forEach { assertTrue("should flag: $it", mentionsAttachment(it)) }
    }

    @Test fun caseInsensitive() {
        assertTrue(mentionsAttachment("SEE THE ATTACHED FILE"))
        assertTrue(mentionsAttachment("PIÈCE JOINTE ci-dessous"))
    }

    @Test fun ignoresUnrelatedText() {
        listOf(
            "Hello, let's meet for lunch tomorrow",
            "Thanks for the quick reply",
            "Bonjour, à demain",
            "Re: Project Phoenix review",
        ).forEach { assertFalse("should not flag: $it", mentionsAttachment(it)) }
    }
}
