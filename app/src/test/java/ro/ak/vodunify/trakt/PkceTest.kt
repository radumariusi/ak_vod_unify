package ro.ak.vodunify.trakt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.trakt.Pkce

class PkceTest {
    @Test fun rfc7636Example() {
        // Exemplul din RFC 7636, anexa B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun verifierLengthIsValid() {
        val v = Pkce.newVerifier()
        assertTrue(v.length in 43..128)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}
