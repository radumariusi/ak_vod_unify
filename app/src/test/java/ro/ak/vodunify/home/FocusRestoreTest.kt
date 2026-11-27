package ro.ak.vodunify.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ro.ak.vodunify.ui.components.FocusRestore

class FocusRestoreTest {
    @Test fun nestedDetailsKeepTheHomeRowAndTitle() {
        val focus = FocusRestore()
        focus.show("home")
        focus.remember("home", "Pe VOYO (RO)|movie:tmdb:13", "service_voyo_RO")
        assertNull(focus.pending)
        focus.show("first-details")
        focus.remember("first-details", "Recomandări|movie:tmdb:25", null)
        focus.show("next-details")
        assertNull(focus.pending)

        focus.show("first-details")
        val recommendation = checkNotNull(focus.pending)
        assertEquals("Recomandări|movie:tmdb:25", recommendation.cardKey)
        focus.consume(recommendation)
        focus.show("home")
        val home = checkNotNull(focus.pending)
        assertEquals(FocusRestore.Position("home", "Pe VOYO (RO)|movie:tmdb:13", "service_voyo_RO"), home)
        focus.consume(home)
        assertNull(focus.pending)

        // Schimbarea filei păstrează selecția și după ce restaurarea anterioară a reușit.
        focus.show("calendar")
        focus.show("home")
        assertEquals(home, focus.pending)
        focus.clear("home")
        focus.show("calendar")
        focus.show("home")
        assertNull(focus.pending)
    }
}
