package ro.ak.vodunify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.update.Version

class VersionTest {
    @Test fun parsesVersionCodeFromAssetName() {
        assertEquals(12, Version.versionCodeFromAssetName("ak-vod-unify-0.2.0-vc12.apk"))
        assertEquals(null, Version.versionCodeFromAssetName("app-release.apk"))
    }

    @Test fun comparesSemanticNames() {
        assertTrue(Version.compareNames("v1.2.10", "1.2.3") > 0)
        assertEquals(0, Version.compareNames("1.0", "1.0.0"))
        assertTrue(Version.compareNames("0.1.0-debug", "0.2.0") < 0)
    }

    @Test fun prefersVersionCode() {
        assertTrue(Version.isNewer(5, "0.1.0", 4, "0.9.0"))
        assertFalse(Version.isNewer(4, "9.0.0", 4, "0.1.0"))
        assertTrue(Version.isNewer(null, "0.2.0", 4, "0.1.0"))
    }
}
