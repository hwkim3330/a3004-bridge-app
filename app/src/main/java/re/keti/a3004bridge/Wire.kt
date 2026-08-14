package re.keti.a3004bridge

/**
 * Every port and path this app speaks to the router on, in one place.
 *
 * They were literals scattered through the screen: 8080 next to the camera
 * reader, 7602 next to the ring reader, 7604 next to the goal sender, and 80
 * implied by every URL. That is four independent chances for this end and the
 * firmware to drift apart, and the failure it produces is not a compile error -
 * it is a panel that stays empty while everything else works, which is the
 * slowest kind of bug to chase.
 *
 * Each constant below names the firmware package that owns the other end, so
 * there is exactly one place to look when one of them changes and exactly one
 * place to change it. `WireTest` pins the values, so changing one is a
 * deliberate act rather than a typo.
 */
object Wire {

    /** uhttpd. Serves the dashboard, the status JSON and the map. */
    const val DASHBOARD_PORT = 80

    /** ustreamer, from package/keti/a3004-sensorkit. */
    const val CAMERA_PORT = 8080

    /** mic-stream, a separate daemon on its own port - not the camera's. */
    const val MIC_PORT = 8082

    /** ouster-edge's Server-Sent Events push, which the dashboard uses. */
    const val RING_SSE_PORT = 7603

    /** ouster-edge's ring, arriving here. This app listens; it does not ask. */
    const val RING_PORT = 7602

    /**
     * teleop's TCMD input. Not agx-cmd, which this said before and which is on
     * 7722 and speaks a different frame - see doc/PORTS.md in the firmware tree.
     * What leaves this app is operator intent, 24 bytes, and teleop is the thing
     * that decides whether to believe it.
     */
    const val TELE_PORT = 7721

    /** navigate's command port: "GOAL x_cm y_cm" and "STOP". */
    const val GOAL_PORT = 7604

    /** Names under /sensors that StatusPoller fetches, one JSON each. */
    val STATUS_NAMES = listOf("ouster", "can", "rc", "teleop", "navigate")

    /** The exported map, written by slam2d or navigate. See slam2d.h. */
    const val MAP_PATH = "map.s2mp"

    const val TELE_HZ_ARMED = 50
    const val TELE_HZ_IDLE = 5
}

/**
 * Where to reach the router, resolved once from what the user typed.
 *
 * The host field accepts `192.168.1.1` or `192.168.1.1:8099`. The port is there
 * because the dashboard is on 80 in the field and 80 is not always reachable
 * from a development machine - adbd cannot bind it over USB, so without this
 * the map could only ever be tested against a real router. A setting that only
 * exists for testing would be a smell; a host field that accepts a port is what
 * every other tool does.
 */
class Endpoints(field: String) {
    val host: String
    val dashboardPort: Int

    init {
        // A trailing colon with nothing after it is a half-typed port, not part
        // of the name. Left in place it produces "http://router.local:/sensors",
        // which is malformed in a way that reads as a network fault rather than
        // a typing one.
        val t = field.trim().removeSuffix(":")
        // Rightmost colon, and only if what follows is a number, so an IPv6
        // literal is left alone rather than truncated.
        val i = t.lastIndexOf(':')
        val p = if (i > 0) t.substring(i + 1).toIntOrNull() else null
        if (p != null && p in 1..65535) {
            host = t.substring(0, i)
            dashboardPort = p
        } else {
            host = t
            dashboardPort = Wire.DASHBOARD_PORT
        }
    }

    /** Base for everything uhttpd serves: status JSON and the map. */
    val sensors: String
        get() = if (dashboardPort == 80) "http://$host/sensors"
                else "http://$host:$dashboardPort/sensors"

    val cameraStream: String get() = "http://$host:${Wire.CAMERA_PORT}/stream"

    /** mic-stream, which is its own daemon on its own port. */
    val audioBase: String get() = "http://$host:${Wire.MIC_PORT}"

    override fun toString() =
        if (dashboardPort == 80) host else "$host:$dashboardPort"
}
