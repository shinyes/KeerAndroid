package site.lcyk.keer.util

import java.time.Instant
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.viewmodel.GeoHeatBucket

class GlobalHeatmapProjectionTest {

    @Test
    fun `buildGeoHeatBucketsFromProjectedPoints becomes more granular as zoom increases`() {
        val points = listOf(
            projectedPoint(identifier = "a", x = 180f, y = 180f),
            projectedPoint(identifier = "b", x = 198f, y = 198f),
        )

        val lowZoomBuckets = buildGeoHeatBucketsFromProjectedPoints(
            points = points,
            widthPx = 1080,
            heightPx = 720,
            zoom = 4.0,
        )
        val highZoomBuckets = buildGeoHeatBucketsFromProjectedPoints(
            points = points,
            widthPx = 1080,
            heightPx = 720,
            zoom = 9.0,
        )

        assertEquals(1, lowZoomBuckets.size)
        assertEquals(2, highZoomBuckets.size)
    }

    @Test
    fun `projectBucketsToScreen centers anchor at viewport center`() {
        val viewport = defaultGlobalHeatmapViewport(
            widthPx = 1080,
            heightPx = 720,
        ).copy(
            centerLatitude = 39.9,
            centerLongitude = 116.4,
            zoom = 6.0,
        )
        val bucket = GeoHeatBucket(
            key = "bucket",
            centerX = 100f,
            centerY = 120f,
            anchorLatitude = 39.9,
            anchorLongitude = 116.4,
            memoCount = 3,
            intensity = 0.8f,
            radiusPx = 24f,
            referenceZoom = 5.0,
            earliestDate = Instant.parse("2026-04-06T00:00:00Z"),
            latestDate = Instant.parse("2026-04-06T00:00:00Z"),
        )

        val projected = projectBucketsToScreen(
            buckets = listOf(bucket),
            viewport = viewport,
        )

        assertEquals(1, projected.size)
        assertEquals(540f, projected.first().centerX, 0.01f)
        assertEquals(360f, projected.first().centerY, 0.01f)
        assertTrue(projected.first().radiusPx > bucket.radiusPx)
    }

    @Test
    fun `findGeoHeatBucketHit returns strongest bucket for overlap`() {
        val weakest = bucket("weak", 120f, 120f, 18f, 2, 0.3f)
        val strongest = bucket("strong", 128f, 124f, 22f, 5, 0.9f)

        val hit = findGeoHeatBucketHit(
            buckets = listOf(weakest, strongest),
            x = 126f,
            y = 124f,
        )

        assertEquals("strong", hit?.key)
    }

    @Test
    fun `buildGeoHeatBucketsFromProjectedPoints keeps anchor near cluster centroid`() {
        val points = listOf(
            projectedPoint(identifier = "a", latitude = 39.90, longitude = 116.39, x = 300f, y = 320f),
            projectedPoint(identifier = "b", latitude = 39.91, longitude = 116.40, x = 304f, y = 324f),
            projectedPoint(identifier = "c", latitude = 39.92, longitude = 116.41, x = 307f, y = 326f),
        )

        val bucket = buildGeoHeatBucketsFromProjectedPoints(
            points = points,
            widthPx = 1080,
            heightPx = 720,
            zoom = 7.0,
        ).single()

        assertTrue(abs(bucket.centerX - 303.67f) < 4f)
        assertTrue(bucket.anchorLatitude in 39.89..39.93)
        assertTrue(bucket.anchorLongitude in 116.38..116.42)
    }

    @Test
    fun `scaleViewportAround keeps focused point visually stable`() {
        val viewport = defaultGlobalHeatmapViewport(
            widthPx = 1080,
            heightPx = 720,
        ).copy(
            centerLatitude = 32.0,
            centerLongitude = 112.0,
            zoom = 4.4,
        )
        val targetPoint = projectCoordinateToScreen(
            viewport = viewport,
            latitude = 30.0,
            longitude = 120.0,
        ) ?: error("Projection failed")

        val zoomedViewport = scaleViewportAround(
            viewport = viewport,
            zoomScaleFactor = 1.7f,
            focusX = targetPoint.x,
            focusY = targetPoint.y,
        )
        val projectedAfterZoom = projectCoordinateToScreen(
            viewport = zoomedViewport,
            latitude = 30.0,
            longitude = 120.0,
        ) ?: error("Projection failed")

        assertEquals(targetPoint.x, projectedAfterZoom.x, 0.75f)
        assertEquals(targetPoint.y, projectedAfterZoom.y, 0.75f)
    }

    @Test
    fun `panViewport makes map content follow the drag direction`() {
        val viewport = defaultGlobalHeatmapViewport(
            widthPx = 1080,
            heightPx = 720,
        ).copy(
            centerLatitude = 28.0,
            centerLongitude = 108.0,
            zoom = 4.6,
        )
        val targetBeforePan = projectCoordinateToScreen(
            viewport = viewport,
            latitude = 31.0,
            longitude = 118.0,
        ) ?: error("Projection failed")

        val pannedViewport = panViewport(
            viewport = viewport,
            panXPx = 96f,
            panYPx = -24f,
        )
        val targetAfterPan = projectCoordinateToScreen(
            viewport = pannedViewport,
            latitude = 31.0,
            longitude = 118.0,
        ) ?: error("Projection failed")

        assertTrue(targetAfterPan.x > targetBeforePan.x)
        assertTrue(targetAfterPan.y < targetBeforePan.y)
    }

    @Test
    fun `wrapScreenXNear keeps antimeridian segments on the nearest world copy`() {
        val viewport = defaultGlobalHeatmapViewport(
            widthPx = 1080,
            heightPx = 720,
        ).copy(
            centerLatitude = 0.0,
            centerLongitude = 180.0,
            zoom = 3.2,
        )
        val left = projectCoordinateToScreenWrapped(
            viewport = viewport,
            latitude = 52.0,
            longitude = 179.0,
            wrapOffsetWorlds = 0,
        ) ?: error("Projection failed")
        val rightWrapped = projectCoordinateToScreenWrapped(
            viewport = viewport,
            latitude = 52.0,
            longitude = -179.0,
            wrapOffsetWorlds = 0,
        ) ?: error("Projection failed")

        val adjustedX = wrapScreenXNear(
            viewport = viewport,
            candidateX = rightWrapped.x,
            referenceX = left.x,
        )

        assertTrue(abs(adjustedX - left.x) < viewport.widthPx / 3f)
    }

    private fun projectedPoint(
        identifier: String,
        latitude: Double = 39.9,
        longitude: Double = 116.4,
        x: Float,
        y: Float,
    ): ProjectedMemoPoint {
        return ProjectedMemoPoint(
            identifier = identifier,
            latitude = latitude,
            longitude = longitude,
            x = x,
            y = y,
            date = Instant.parse("2026-04-06T00:00:00Z"),
        )
    }

    private fun bucket(
        key: String,
        centerX: Float,
        centerY: Float,
        radiusPx: Float,
        memoCount: Int,
        intensity: Float,
    ): GeoHeatBucket {
        return GeoHeatBucket(
            key = key,
            centerX = centerX,
            centerY = centerY,
            anchorLatitude = 39.9,
            anchorLongitude = 116.4,
            memoCount = memoCount,
            intensity = intensity,
            radiusPx = radiusPx,
            referenceZoom = 5.0,
            earliestDate = Instant.parse("2026-04-06T00:00:00Z"),
            latestDate = Instant.parse("2026-04-06T00:00:00Z"),
        )
    }
}
