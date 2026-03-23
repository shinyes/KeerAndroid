package site.lcyk.keer.ui.component

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun resolveObservedMemoResource_freezeKeepsLastObservedResource() {
        val source = testResource("res-1", localUri = "")
        val lastObserved = testResource("res-1", localUri = "file:///tmp/thumb.jpg")

        val resolved = resolveObservedMemoResource(
            sourceResource = source,
            observedResource = null,
            lastObservedResource = lastObserved,
            observeLiveResource = false,
        )
        val resolvedResource = resolved.resource as ResourceEntity

        assertEquals(lastObserved.identifier, resolvedResource.identifier)
        assertEquals(lastObserved.localUri, resolvedResource.localUri)
        assertTrue(resolved.tracked)
    }

    @Test
    fun resolveObservedMemoResource_unfreezeWithoutFreshEmissionStillKeepsLastObservedResource() {
        val source = testResource("res-2", localUri = "")
        val lastObserved = testResource("res-2", localUri = "file:///tmp/preview.jpg")

        val resolved = resolveObservedMemoResource(
            sourceResource = source,
            observedResource = null,
            lastObservedResource = lastObserved,
            observeLiveResource = true,
        )
        val resolvedResource = resolved.resource as ResourceEntity

        assertEquals(lastObserved.localUri, resolvedResource.localUri)
        assertTrue(resolved.tracked)
    }

    @Test
    fun resolveStablePreviewModelState_blankCandidateDoesNotOverrideUsableModel() {
        val state = resolveStablePreviewModelState(
            candidateModel = "   ",
            lastStableModel = "file:///tmp/usable.jpg",
        )

        assertEquals("file:///tmp/usable.jpg", state.model)
        assertEquals("file:///tmp/usable.jpg", state.retainedModel)
    }

    private fun testResource(
        identifier: String,
        localUri: String? = null,
    ): ResourceEntity {
        return ResourceEntity(
            identifier = identifier,
            remoteId = "remote-$identifier",
            accountKey = "acc-1",
            date = Instant.EPOCH,
            filename = "$identifier.jpg",
            uri = "https://example.com/$identifier",
            localUri = localUri,
            mimeType = "image/jpeg",
            memoId = "memo-1",
        )
    }
}
