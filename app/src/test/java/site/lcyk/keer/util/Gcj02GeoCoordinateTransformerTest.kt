package site.lcyk.keer.util

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gcj02GeoCoordinateTransformerTest {

    private val transformer = Gcj02GeoCoordinateTransformer()

    @Test
    fun `toDisplayCoordinate transforms Beijing WGS84 into GCJ02`() {
        val transformed = transformer.toDisplayCoordinate(
            latitude = 39.908823,
            longitude = 116.39747,
        )

        assertTrue(abs(transformed.latitude - 39.910226) < 0.001)
        assertTrue(abs(transformed.longitude - 116.403714) < 0.001)
    }

    @Test
    fun `toDisplayCoordinate leaves points outside China unchanged`() {
        val transformed = transformer.toDisplayCoordinate(
            latitude = 37.7749,
            longitude = -122.4194,
        )

        assertEquals(37.7749, transformed.latitude, 0.0000001)
        assertEquals(-122.4194, transformed.longitude, 0.0000001)
    }
}
