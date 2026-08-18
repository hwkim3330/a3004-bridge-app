package re.keti.a3004bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the wire contract, so changing it is deliberate.
 *
 * These numbers are duplicated across a language boundary: the firmware owns
 * them and this app repeats them. Nothing makes them agree automatically, and
 * disagreement does not fail to compile - it shows up as one panel that stays
 * empty while everything else works. A test that fails when a constant moves is
 * the cheapest way to turn a silent mismatch into a loud one.
 *
 * If one of these ever fails, the right fix is to check which side actually
 * changed, not to update the number until it passes.
 */
class WireTest {

    /** Each of these is a default in the named firmware package. */
    @Test fun portsMatchTheFirmwareDefaults() {
        assertEquals("uhttpd", 80, Wire.DASHBOARD_PORT)
        assertEquals("ustreamer, sensorkit uci", 8080, Wire.CAMERA_PORT)
        assertEquals("mic-stream.config", 8082, Wire.MIC_PORT)
        assertEquals("ouster-edge ring", 7602, Wire.RING_PORT)
        assertEquals("ouster-edge sse", 7603, Wire.RING_SSE_PORT)
        assertEquals("teleop cmd_port, not agx-cmd", 7721, Wire.TELE_PORT)
        assertEquals("navigate cmd_port", 7604, Wire.GOAL_PORT)
    }

    @Test fun statusNamesMatchTheDashboardSymlinks() {
        // Checked against a running router's /www/sensors, where each of these
        // is a symlink the dashboard package installs. slam2d is there and was
        // simply not being read: the map was drawn with no indication of
        // whether the mapper was still tracking.
        assertEquals(
            listOf("ouster", "can", "rc", "teleop", "navigate", "slam2d"),
            Wire.STATUS_NAMES)
        assertEquals("map.s2mp", Wire.MAP_PATH)
    }

    @Test fun aBareHostUsesTheDefaultPort() {
        val e = Endpoints("192.168.1.1")
        assertEquals("192.168.1.1", e.host)
        assertEquals(80, e.dashboardPort)
        assertEquals("http://192.168.1.1/sensors", e.sensors)
        assertEquals("http://192.168.1.1:8080/stream", e.cameraStream)
        assertEquals("http://192.168.1.1:8082", e.audioBase)
        assertEquals("192.168.1.1", e.toString())
    }

    @Test fun anExplicitPortIsUsedForTheDashboardOnly() {
        val e = Endpoints("127.0.0.1:8099")
        assertEquals("127.0.0.1", e.host)
        assertEquals(8099, e.dashboardPort)
        assertEquals("http://127.0.0.1:8099/sensors", e.sensors)
        // the camera and microphone keep their own ports
        assertEquals("http://127.0.0.1:8080/stream", e.cameraStream)
        assertEquals("http://127.0.0.1:8082", e.audioBase)
        assertEquals("127.0.0.1:8099", e.toString())
    }

    @Test fun nonsenseAfterTheColonIsNotAPort() {
        // A hostname with a stray colon must not silently lose everything after
        // it, which is what splitting unconditionally would do.
        assertEquals("router.local", Endpoints("router.local:").host)
        assertEquals(80, Endpoints("router.local:").dashboardPort)
        assertEquals("router.local:abc", Endpoints("router.local:abc").host)
        assertEquals(80, Endpoints("192.168.1.1:99999").dashboardPort)
        assertEquals("192.168.1.1:99999", Endpoints("192.168.1.1:99999").host)
    }

    @Test fun surroundingSpaceIsIgnored() {
        assertEquals("192.168.1.1", Endpoints("  192.168.1.1  ").host)
    }
}
