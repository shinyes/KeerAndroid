package site.lcyk.keer.ui.component

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import site.lcyk.keer.data.local.entity.ResourceEntity

@OptIn(ExperimentalCoroutinesApi::class)
class MemoResourcePreviewTest {

    @Test
    fun buildObservedResourceFlow_skipsSubscriptionWhenObservationDisabled() = runTest {
        var observeInvocations = 0
        val flow = buildObservedResourceFlow(
            identifier = "res-1",
            observeLiveResource = false,
            observeResource = {
                observeInvocations += 1
                flowOf(testResource(it))
            },
        )

        assertNull(flow.first())
        assertEquals(0, observeInvocations)
    }

    @Test
    fun buildObservedResourceFlow_skipsSubscriptionWhenIdentifierBlank() = runTest {
        var observeInvocations = 0
        val flow = buildObservedResourceFlow(
            identifier = "   ",
            observeLiveResource = true,
            observeResource = {
                observeInvocations += 1
                flowOf(testResource(it))
            },
        )

        assertNull(flow.first())
        assertEquals(0, observeInvocations)
    }

    @Test
    fun buildObservedResourceFlow_subscribesAndEmitsTrackedResourceWhenEnabled() = runTest {
        var observeInvocations = 0
        val expected = testResource("res-42")
        val flow = buildObservedResourceFlow(
            identifier = " res-42 ",
            observeLiveResource = true,
            observeResource = { identifier ->
                observeInvocations += 1
                flowOf(testResource(identifier))
            },
        )

        val actual = flow.first()
        assertEquals(expected.identifier, actual?.identifier)
        assertEquals(1, observeInvocations)
    }

    private fun testResource(identifier: String): ResourceEntity {
        return ResourceEntity(
            identifier = identifier,
            remoteId = "remote-$identifier",
            accountKey = "acc-1",
            date = Instant.EPOCH,
            filename = "$identifier.jpg",
            uri = "https://example.com/$identifier",
            mimeType = "image/jpeg",
            memoId = "memo-1",
        )
    }
}
