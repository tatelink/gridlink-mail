package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE RULE, NOT A BEHAVIOUR TEST — and it exists because the behaviour cannot be tested here.
 *
 * The reader is a horizontal pager: one [MessageViewModel] serves one page, and `load(id, account)`
 * is what repoints it at another message. Every piece of per-message state it holds must be
 * rewritten there. A flow that is not costs nothing on the page that set it and shows the PREVIOUS
 * message's answer on the next one — an "Unsubscribed" line about a list this mail has nothing to
 * do with, a stale calendar card, an unread dot that will not clear. The reader already carries
 * fifteen such flows and this is the first thing that checks them.
 *
 * A test that instantiates the ViewModel would be the honest way to state this. It cannot exist:
 * [MessageViewModel] is an `AndroidViewModel` that reaches for the application container, and the
 * app module has neither Robolectric nor an instrumentation harness (see the module's test
 * dependencies — junit and coroutines-test, nothing else). Reading the source is the fallback, and
 * it is the same fallback `NavHostSourceRulesTest` and `TranslationParityTest` already use for
 * rules the compiler has no opinion about.
 *
 * What it checks is deliberately narrow: every `MutableStateFlow` the class DECLARES is ASSIGNED
 * somewhere inside `load`. It cannot tell a correct reset from a wrong value — the parser, the
 * unsubscribe decision and the outbox mapping are pinned by tests that EXECUTE them, in
 * `:core:data`. This one only closes the door that has no other lock.
 */
class ReaderStateResetOnLoadTest {

    @Test
    fun `every per-message flow the reader holds is rewritten by load`() {
        val source = viewModelSource()
        val declared = DECLARATION.findAll(source).map { it.groupValues[1] }.toList()
        assertTrue(
            "no MutableStateFlow found in MessageViewModel.kt — did the file move?",
            declared.size >= 15,
        )

        val body = loadBody(source)
        val unwritten = declared.filterNot { name -> "$name.value" in body }

        assertEquals(
            "state left over from the previous message in the pager (not reset in load())",
            emptyList<String>(),
            unwritten,
        )
    }

    /** The text of `fun load(…)`, up to the next declaration at class level. */
    private fun loadBody(source: String): String {
        val start = source.indexOf("fun load(")
        assertTrue("MessageViewModel has no load() any more", start > 0)
        val end = NEXT_MEMBER.find(source, start + 1)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    private companion object {
        val DECLARATION = Regex("""private val (_\w+)\s*=\s*MutableStateFlow""")

        /** The next member declared at class indentation, which is where load() ends. */
        val NEXT_MEMBER = Regex("""\n {4}(private )?(suspend )?fun \w""")

        val source: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt") }
                .firstOrNull { it.isFile }
                ?: error("cannot locate MessageViewModel.kt from ${File("").absolutePath}")
        }
    }

    private fun viewModelSource(): String = source.readText()
}
