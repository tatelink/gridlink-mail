package app.gridlink.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** RemoteViews ignores a call on a view the layout lacks, so a missing id would fail in silence. */
class WidgetBackgroundLayoutTest {

    @Test
    fun `every widget layout has the card the opacity setting fades`() {
        val layouts = listOf("widget_inbox", "widget_agenda", "widget_unread")
        layouts.forEach { name ->
            val xml = File("src/main/res/layout/$name.xml").readText()
            assertTrue("$name has no widget_background", "@+id/widget_background" in xml)
            assertTrue("$name still paints the card on its root", "@drawable/widget_card\" />" in xml)
            assertTrue("$name paints the card twice", "android:background=\"@drawable/widget_card" !in xml)
        }
    }
}
