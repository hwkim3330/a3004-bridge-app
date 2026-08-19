package re.keti.a3004bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The header slam2d writes and this app reads, checked against each other.
 *
 * Two programs in two languages agreeing about a byte layout is the kind of
 * thing that is either exactly right or quietly wrong, and quietly wrong here
 * means a map drawn with the robot somewhere it is not. The bytes below are
 * built to the specification in slam2d.h rather than copied from a capture, so
 * this fails if either side drifts.
 */
class MapFrameTest {

    private fun header(
        w: Int, h: Int, res: Int, ox: Int, oy: Int, px: Int, py: Int, pa: Int,
        level: Int = 2, version: Int = 1, magic: String = "S2MP",
    ): ByteArray {
        val b = ByteArray(32 + w * h)
        magic.forEachIndexed { i, c -> b[i] = c.code.toByte() }
        b[4] = version.toByte()
        b[5] = level.toByte()
        fun le16(o: Int, v: Int) {
            b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte()
        }
        fun le32(o: Int, v: Int) {
            b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte()
            b[o + 2] = ((v shr 16) and 0xFF).toByte(); b[o + 3] = ((v shr 24) and 0xFF).toByte()
        }
        le16(6, w); le16(8, h); le16(10, res)
        le32(12, ox); le32(16, oy); le32(20, px); le32(24, py); le32(28, pa)
        return b
    }

    @Test fun parsesTheDocumentedLayout() {
        val b = header(4, 3, 20, -2000, -2000, 150, -75, 1024)
        b[32 + 0] = 255.toByte()          // cell (0,0) occupied
        b[32 + 4 + 1] = 0                 // cell (1,1) free
        val m = MapFrame.parse(b)
        assertNotNull(m); m!!
        assertEquals(2, m.level)
        assertEquals(4, m.w); assertEquals(3, m.h)
        assertEquals(20, m.resCm)
        assertEquals(-2000, m.originXCm); assertEquals(-2000, m.originYCm)
        assertEquals(150, m.poseXCm); assertEquals(-75, m.poseYCm)
        assertEquals(1024, m.poseA)
        assertEquals(90f, m.headingDeg, 0.01f)   // a quarter of 4096
        assertEquals(255, m.at(0, 0))
        assertEquals(0, m.at(1, 1))
    }

    /** Negative world coordinates are the normal case: the map is centred on
     *  where the robot started, so half of it has negative x and y. Truncating
     *  division instead of flooring would fold the two cells either side of the
     *  origin into one. */
    @Test fun cellIndexingFloorsThroughZero() {
        val m = MapFrame.parse(header(10, 10, 20, -100, -100, 0, 0, 0))!!
        assertEquals(5, m.cellXOf(0))
        assertEquals(4, m.cellXOf(-1))
        assertEquals(4, m.cellXOf(-20))
        assertEquals(3, m.cellXOf(-21))
        // and back again, to the centre of the cell
        assertEquals(-90, m.xCmOf(0))
        assertEquals(10, m.xCmOf(5))
    }

    @Test fun outOfRangeCellsReadAsUnknown() {
        val m = MapFrame.parse(header(4, 4, 20, 0, 0, 0, 0, 0))!!
        assertEquals(MapFrame.S2_UNKNOWN, m.at(-1, 0))
        assertEquals(MapFrame.S2_UNKNOWN, m.at(0, 4))
    }

    @Test fun rejectsRatherThanThrows() {
        assertNull(MapFrame.parse(ByteArray(8)))
        assertNull(MapFrame.parse(header(4, 4, 20, 0, 0, 0, 0, 0, magic = "NOPE")))
        assertNull(MapFrame.parse(header(4, 4, 20, 0, 0, 0, 0, 0, version = 2)))
        // truncated body, which is what a half-fetched map looks like
        assertNull(MapFrame.parse(header(4, 4, 20, 0, 0, 0, 0, 0).copyOfRange(0, 40)))
    }
}
