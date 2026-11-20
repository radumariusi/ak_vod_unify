package ro.ak.vodunify.core.trakt

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636): verificator aleator + challenge S256, pentru aplicațiile Trakt fără client secret. */
object Pkce {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun newVerifier(): String = ByteArray(48).also(random::nextBytes).let(encoder::encodeToString)

    fun challenge(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}
