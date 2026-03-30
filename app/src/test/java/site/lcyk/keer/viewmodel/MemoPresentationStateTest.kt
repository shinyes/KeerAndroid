package site.lcyk.keer.viewmodel

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.MemoQuotePreview
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.util.MemoQuoteDescriptor
import site.lcyk.keer.util.MemoQuoteSourceKind
import site.lcyk.keer.util.buildMemoQuoteTag

class MemoPresentationStateTest {

    @Test
    fun buildRequestedLocalQuoteDescriptor_trimsAndRejectsBlankSources() {
        assertEquals(
            MemoQuoteDescriptor(
                sourceKind = MemoQuoteSourceKind.LOCAL,
                source = "quoted-1",
            ),
            buildRequestedLocalQuoteDescriptor("  quoted-1  "),
        )
        assertEquals(null, buildRequestedLocalQuoteDescriptor("   "))
    }

    @Test
    fun buildMemoDisplayMeta_collectsDisplayTagsCollaboratorsAndQuoteDescriptor() {
        val descriptor = MemoQuoteDescriptor(
            sourceKind = MemoQuoteSourceKind.REMOTE,
            source = "quoted-1",
        )
        val memo = memoEntity(
            identifier = "memo-1",
            content = "Memo",
            tags = listOf(
                "work/project",
                "collab/alice",
                buildMemoQuoteTag(descriptor),
            ),
            quoteSourceKind = descriptor.sourceKind.tagSegment,
            quoteSource = descriptor.source,
        )

        val displayMeta = buildMemoDisplayMeta(memo)

        assertEquals(listOf("work/project"), displayMeta.displayTags)
        assertEquals(listOf("alice"), displayMeta.collaboratorIds)
        assertEquals(descriptor, displayMeta.quoteDescriptor)
    }

    @Test
    fun buildMemoEditorSeed_usesNormalizedEditableFields() {
        val memo = memoEntity(
            identifier = "memo-2",
            content = "Hello",
            tags = listOf("focus", "collab/bob"),
        )

        val seed = buildMemoEditorSeed(memo)

        assertEquals("Hello", seed.content)
        assertEquals(listOf("focus"), seed.tags)
        assertEquals(listOf("bob"), seed.collaboratorIds)
    }

    @Test
    fun buildMemoDisplayMeta_andEditorSeed_handleNullMemo() {
        assertEquals(MemoDisplayMeta(), buildMemoDisplayMeta(null))
        assertEquals(MemoEditorSeed(), buildMemoEditorSeed(null))
    }

    @Test
    fun buildActiveMemoQuoteDescriptor_prefersStoredMemoDescriptor_whenMemoExists() {
        val storedDescriptor = MemoQuoteDescriptor(
            sourceKind = MemoQuoteSourceKind.REMOTE,
            source = "stored-remote-id",
        )
        val requestedDescriptor = MemoQuoteDescriptor(
            sourceKind = MemoQuoteSourceKind.LOCAL,
            source = "requested-local-id",
        )
        val memo = memoEntity(
            identifier = "memo-3",
            content = "Memo",
            tags = listOf(buildMemoQuoteTag(storedDescriptor)),
            quoteSourceKind = storedDescriptor.sourceKind.tagSegment,
            quoteSource = storedDescriptor.source,
        )

        val activeDescriptor = buildActiveMemoQuoteDescriptor(memo, requestedDescriptor)

        assertEquals(storedDescriptor, activeDescriptor)
    }

    @Test
    fun buildMemoQuotePresentation_prefersQuotedMemo_forPreviewAndSubmit() {
        val requestedDescriptor = MemoQuoteDescriptor(
            sourceKind = MemoQuoteSourceKind.LOCAL,
            source = "requested-local-id",
        )
        val quotedMemo = memoEntity(
            identifier = "memo-quoted",
            content = "Quoted memo body",
            tags = listOf("focus"),
        )

        val presentation = buildMemoQuotePresentation(
            displayMemo = null,
            requestedQuoteDescriptor = requestedDescriptor,
            quotedMemo = quotedMemo,
        )

        assertEquals(requestedDescriptor, presentation.activeDescriptor)
        assertEquals(
            MemoQuoteDescriptor(
                sourceKind = MemoQuoteSourceKind.LOCAL,
                source = quotedMemo.identifier,
            ),
            presentation.submitDescriptor,
        )
        assertEquals(
            MemoQuotePreview(
                previewText = "Quoted memo body",
                date = quotedMemo.date,
                hasResources = false,
            ),
            presentation.preview,
        )
    }

    @Test
    fun buildRetainedHydrationState_marksHydratingOnlyForWarmRetainedTargets() {
        assertEquals(
            UiHydrationState(
                hasWarmSnapshot = true,
                isHydrating = true,
                isStale = false,
            ),
            buildRetainedHydrationState(
                hasTarget = true,
                liveValueAvailable = false,
                retainedValueAvailable = true,
            ),
        )
        assertEquals(
            UiHydrationState(
                hasWarmSnapshot = false,
                isHydrating = false,
                isStale = false,
            ),
            buildRetainedHydrationState(
                hasTarget = true,
                liveValueAvailable = false,
                retainedValueAvailable = false,
            ),
        )
    }

    @Test
    fun buildMemoSurfaceMetaState_combinesDisplayMetaAndHydration() {
        val memo = memoEntity(
            identifier = "memo-surface",
            content = "Memo",
            tags = listOf("focus", "collab/alice"),
        )

        val state = buildMemoSurfaceMetaState(
            hasTarget = true,
            liveValueAvailable = false,
            displayMemo = memo,
        )

        assertEquals(listOf("focus"), state.displayMeta.displayTags)
        assertEquals(listOf("alice"), state.displayMeta.collaboratorIds)
        assertEquals(
            UiHydrationState(
                hasWarmSnapshot = true,
                isHydrating = true,
                isStale = false,
            ),
            state.hydrationState,
        )
    }

    @Test
    fun buildMemoSurfaceLookupState_exposesMetaAndActiveQuoteDescriptor() {
        val requestedDescriptor = MemoQuoteDescriptor(
            sourceKind = MemoQuoteSourceKind.LOCAL,
            source = "requested-local-id",
        )
        val memo = memoEntity(
            identifier = "memo-lookup",
            content = "Memo",
            tags = listOf("focus", buildMemoQuoteTag(requestedDescriptor)),
            quoteSourceKind = requestedDescriptor.sourceKind.tagSegment,
            quoteSource = requestedDescriptor.source,
        )

        val state = buildMemoSurfaceLookupState(
            hasTarget = true,
            liveValueAvailable = false,
            displayMemo = memo,
            requestedQuoteDescriptor = requestedDescriptor,
        )

        assertEquals(listOf("focus"), state.meta.displayMeta.displayTags)
        assertEquals(requestedDescriptor, state.activeQuoteDescriptor)
    }

    @Test
    fun resolveQuotedMemoLookup_prefersPrimaryMemo_beforeFallbackSearchSpace() {
        val descriptor = MemoQuoteDescriptor(
            sourceKind = MemoQuoteSourceKind.LOCAL,
            source = "quoted-local-id",
        )
        val primaryQuotedMemo = memoEntity(
            identifier = "quoted-local-id",
            content = "Primary quoted memo",
            tags = listOf("primary"),
        )
        val fallbackQuotedMemo = memoEntity(
            identifier = "quoted-local-id",
            content = "Fallback quoted memo",
            tags = listOf("fallback"),
        )

        val resolvedMemo = resolveQuotedMemoLookup(
            activeQuoteDescriptor = descriptor,
            primaryQuotedMemo = primaryQuotedMemo,
            memos = listOf(fallbackQuotedMemo),
        )

        assertEquals(primaryQuotedMemo, resolvedMemo)
    }

    @Test
    fun buildResolvedQuoteLookupState_prefersStoredDescriptor_and_resolvesFromSearchSpace() {
        val requestedDescriptor = MemoQuoteDescriptor(
            sourceKind = MemoQuoteSourceKind.LOCAL,
            source = "requested-local-id",
        )
        val storedDescriptor = MemoQuoteDescriptor(
            sourceKind = MemoQuoteSourceKind.REMOTE,
            source = "quoted-remote-id",
        )
        val displayMemo = memoEntity(
            identifier = "memo-with-quote",
            content = "Memo",
            tags = listOf("focus", buildMemoQuoteTag(storedDescriptor)),
            quoteSourceKind = storedDescriptor.sourceKind.tagSegment,
            quoteSource = storedDescriptor.source,
        )
        val quotedMemo = memoEntity(
            identifier = "explore:quoted-remote-id",
            remoteId = "quoted-remote-id",
            content = "Quoted from fallback search",
            tags = listOf("quoted"),
        )

        val state = buildResolvedQuoteLookupState(
            displayMemo = displayMemo,
            requestedQuoteDescriptor = requestedDescriptor,
            primaryQuotedMemo = null,
            memos = listOf(quotedMemo),
        )

        assertEquals(storedDescriptor, state.activeQuoteDescriptor)
        assertEquals(quotedMemo, state.quotedMemo)
    }

    @Test
    fun buildQuotedMemoLookupIdentifier_preserves_surface_prefixes() {
        val localDescriptor = MemoQuoteDescriptor(
            sourceKind = MemoQuoteSourceKind.LOCAL,
            source = "quoted-local-id",
        )

        assertEquals(
            "quoted-local-id",
            buildQuotedMemoLookupIdentifier(
                currentMemoIdentifier = "memo-plain",
                descriptor = localDescriptor,
            ),
        )
        assertEquals(
            "explore:quoted-local-id",
            buildQuotedMemoLookupIdentifier(
                currentMemoIdentifier = "explore:memo-remote-id",
                descriptor = localDescriptor,
            ),
        )
        assertEquals(
            "group:group-1:quoted-local-id",
            buildQuotedMemoLookupIdentifier(
                currentMemoIdentifier = "group:group-1:memo-remote-id",
                descriptor = localDescriptor,
            ),
        )
    }

    @Test
    fun buildMemoEditorSurfaceState_combinesPresentationAndInitialEditorSeed() {
        val descriptor = MemoQuoteDescriptor(
            sourceKind = MemoQuoteSourceKind.LOCAL,
            source = "quoted-local-id",
        )
        val displayMemo = memoEntity(
            identifier = "memo-editor",
            content = "Memo body",
            tags = listOf("focus", "collab/alice"),
        )
        val quotedMemo = memoEntity(
            identifier = "quoted-local-id",
            content = "Quoted memo body",
            tags = listOf("quoted"),
        )

        val state = buildMemoEditorSurfaceState(
            hasTarget = true,
            liveValueAvailable = false,
            displayMemo = displayMemo,
            requestedQuoteDescriptor = descriptor,
            quotedMemo = quotedMemo,
        )

        assertEquals(listOf("focus"), state.initialEditorSeed.tags)
        assertEquals(listOf("alice"), state.initialEditorSeed.collaboratorIds)
        assertEquals("Memo body", state.initialEditorSeed.content)
        assertEquals(listOf("focus"), state.initialRestoreState.selectedTags)
        assertEquals(listOf("alice"), state.initialRestoreState.selectedCollaborators)
        assertEquals(
            UiHydrationState(
                hasWarmSnapshot = true,
                isHydrating = true,
                isStale = false,
            ),
            state.presentation.meta.hydrationState,
        )
        assertEquals(
            MemoQuotePreview(
                previewText = "Quoted memo body",
                date = quotedMemo.date,
                hasResources = false,
            ),
            state.presentation.quote.preview,
        )
    }

    @Test
    fun buildMemoEditorScreenState_flattensEditorFacingFields() {
        val descriptor = MemoQuoteDescriptor(
            sourceKind = MemoQuoteSourceKind.LOCAL,
            source = "quoted-local-id",
        )
        val displayMemo = memoEntity(
            identifier = "memo-editor-screen",
            content = "Memo body",
            tags = listOf("focus", "collab/alice"),
        )
        val quotedMemo = memoEntity(
            identifier = "quoted-local-id",
            content = "Quoted memo body",
            tags = listOf("quoted"),
        )

        val state = buildMemoEditorScreenState(
            hasTarget = true,
            liveValueAvailable = false,
            displayMemo = displayMemo,
            requestedQuoteDescriptor = descriptor,
            quotedMemo = quotedMemo,
        )

        assertEquals(listOf("focus"), state.initialRestoreState.selectedTags)
        assertEquals(listOf("alice"), state.initialRestoreState.selectedCollaborators)
        assertEquals(
            MemoQuotePreview(
                previewText = "Quoted memo body",
                date = quotedMemo.date,
                hasResources = false,
            ),
            state.quotePreview,
        )
        assertEquals(
            MemoQuoteDescriptor(
                sourceKind = MemoQuoteSourceKind.LOCAL,
                source = quotedMemo.identifier,
            ),
            state.quoteDescriptorForSubmit,
        )
        assertEquals("Memo body", state.initialFields.content)
        assertEquals(listOf("focus"), state.initialFields.selectedTags)
        assertEquals(listOf("alice"), state.initialFields.selectedCollaborators)
    }

    @Test
    fun buildMemoEditorResolvedScreenState_combinesLookupAndEditorState() {
        val descriptor = MemoQuoteDescriptor(
            sourceKind = MemoQuoteSourceKind.LOCAL,
            source = "quoted-local-id",
        )
        val quotedMemo = memoEntity(
            identifier = "quoted-local-id",
            content = "Quoted memo body",
            tags = listOf("quoted"),
        )

        val state = buildMemoEditorResolvedScreenState(
            hasTarget = true,
            liveValueAvailable = false,
            displayMemo = null,
            requestedQuoteDescriptor = descriptor,
            primaryQuotedMemo = null,
            memos = listOf(quotedMemo),
        )

        assertEquals(quotedMemo, state.lookup.quotedMemo)
        assertEquals(
            MemoQuotePreview(
                previewText = "Quoted memo body",
                date = quotedMemo.date,
                hasResources = false,
            ),
            state.screen.quotePreview,
        )
    }

    @Test
    fun buildMemoViewSurfaceState_usesRetainedPreviewFallback_whenQuoteNotYetResolved() {
        val descriptor = MemoQuoteDescriptor(
            sourceKind = MemoQuoteSourceKind.LOCAL,
            source = "quoted-local-id",
        )
        val displayMemo = memoEntity(
            identifier = "memo-view",
            content = "Memo body",
            tags = listOf(buildMemoQuoteTag(descriptor)),
            quoteSourceKind = descriptor.sourceKind.tagSegment,
            quoteSource = descriptor.source,
        )
        val retainedPreview = MemoQuotePreview(
            previewText = "Retained preview",
            date = displayMemo.date,
            hasResources = false,
        )

        val state = buildMemoViewSurfaceState(
            hasTarget = true,
            liveValueAvailable = false,
            displayMemo = displayMemo,
            requestedQuoteDescriptor = null,
            quotedMemo = null,
            retainedQuotePreview = retainedPreview,
        )

        assertEquals(retainedPreview, state.displayQuotePreview)
        assertEquals(
            UiHydrationState(
                hasWarmSnapshot = true,
                isHydrating = true,
                isStale = false,
            ),
            state.presentation.meta.hydrationState,
        )
    }

    @Test
    fun buildMemoViewResolvedScreenState_combinesLookupAndRetainedPreviewFallback() {
        val descriptor = MemoQuoteDescriptor(
            sourceKind = MemoQuoteSourceKind.LOCAL,
            source = "quoted-local-id",
        )
        val displayMemo = memoEntity(
            identifier = "memo-view-resolved",
            content = "Memo body",
            tags = listOf(buildMemoQuoteTag(descriptor)),
            quoteSourceKind = descriptor.sourceKind.tagSegment,
            quoteSource = descriptor.source,
        )
        val retainedPreview = MemoQuotePreview(
            previewText = "Retained preview",
            date = displayMemo.date,
            hasResources = false,
        )

        val state = buildMemoViewResolvedScreenState(
            hasTarget = true,
            liveValueAvailable = false,
            displayMemo = displayMemo,
            requestedQuoteDescriptor = null,
            primaryQuotedMemo = null,
            memos = emptyList(),
            retainedQuotePreview = retainedPreview,
        )

        assertEquals(descriptor, state.lookup.activeQuoteDescriptor)
        assertEquals(retainedPreview, state.screen.displayQuotePreview)
    }

    @Test
    fun buildDraftHydrationState_tracksPersistedDraftAvailability() {
        assertEquals(
            UiHydrationState(
                hasWarmSnapshot = true,
                isHydrating = true,
                isStale = false,
            ),
            buildDraftHydrationState(
                hasLiveDraft = false,
                currentContent = "Draft body",
            ),
        )
        assertEquals(
            UiHydrationState(
                hasWarmSnapshot = false,
                isHydrating = false,
                isStale = false,
            ),
            buildDraftHydrationState(
                hasLiveDraft = true,
                currentContent = "",
            ),
        )
    }

    @Test
    fun buildDraftEditorScreenState_combinesHydrationAndRestoreState() {
        val state = buildDraftEditorScreenState(
            persistedDraft = "Draft body",
            forcedTags = listOf("focus", " focus "),
            hasLiveDraft = false,
            currentContent = "Draft body",
        )

        assertEquals(
            UiHydrationState(
                hasWarmSnapshot = true,
                isHydrating = true,
                isStale = false,
            ),
            state.hydrationState,
        )
        assertEquals("Draft body", state.initialRestoreState.content)
        assertEquals(listOf("focus"), state.initialRestoreState.selectedTags)
        assertEquals(emptyList<String>(), state.initialRestoreState.selectedCollaborators)
        assertEquals("Draft body", state.initialFields.content)
        assertEquals(listOf("focus"), state.initialFields.selectedTags)
    }

    @Test
    fun buildMemoEditorFieldsState_flattensRestoreStateForUiConsumption() {
        val restoreState = buildMemoEditorRestoreState(
            content = "Draft",
            tags = listOf("focus"),
            collaboratorIds = listOf("alice"),
            resourceIdentifiers = listOf("remote-1"),
        )

        val fieldsState = buildMemoEditorFieldsState(restoreState)

        assertEquals("Draft", fieldsState.content)
        assertEquals(listOf("focus"), fieldsState.selectedTags)
        assertEquals(listOf("alice"), fieldsState.selectedCollaborators)
        assertEquals(restoreState.baseline, fieldsState.baseline)
    }

    @Test
    fun buildMemoEditorBaseline_normalizesCollaboratorsAndResourceIdentifiers() {
        val baseline = buildMemoEditorBaseline(
            content = "Draft",
            tags = listOf(" focus ", "focus"),
            collaboratorIds = listOf(" Alice ", "alice", ""),
            resourceIdentifiers = listOf("  ", "remote-1", "remote-1", "local-2"),
        )

        assertEquals("Draft", baseline.content)
        assertEquals(listOf("focus"), baseline.tags)
        assertEquals(listOf("Alice", "alice"), baseline.collaboratorIds)
        assertEquals(listOf("remote-1", "local-2"), baseline.resourceIdentifiers)
    }

    @Test
    fun buildMemoEditorDirtyState_usesNormalizedTagsCollaboratorsAndResources() {
        val baseline = buildMemoEditorBaseline(
            content = "Hello",
            tags = listOf("focus"),
            collaboratorIds = listOf("bob"),
            resourceIdentifiers = listOf("remote-1"),
        )

        assertFalse(
            buildMemoEditorDirtyState(
                baseline = baseline,
                content = "Hello",
                selectedTags = listOf(" focus "),
                selectedCollaborators = listOf(" bob "),
                resourceIdentifiers = listOf("remote-1"),
            )
        )

        assertTrue(
            buildMemoEditorDirtyState(
                baseline = baseline,
                content = "Hello world",
                selectedTags = listOf("focus"),
                selectedCollaborators = listOf("bob"),
                resourceIdentifiers = listOf("remote-1"),
            )
        )
    }

    @Test
    fun buildMemoEditorResourceIdentifiers_prefersRemoteIdsAndDeduplicates() {
        val identifiers = buildMemoEditorResourceIdentifiers(
            listOf(
                resourceEntity(identifier = "local-a", remoteId = null),
                resourceEntity(identifier = "local-b", remoteId = "remote-b"),
                resourceEntity(identifier = "local-c", remoteId = "remote-b"),
            )
        )

        assertEquals(listOf("local-a", "remote-b"), identifiers)
    }

    @Test
    fun buildMemoEditorRestoreState_normalizesEditorSelectionsAndBaseline() {
        val restoreState = buildMemoEditorRestoreState(
            content = " Draft ",
            tags = listOf(" focus ", "focus"),
            collaboratorIds = listOf(" Alice ", "alice", ""),
            resourceIdentifiers = listOf(" remote-1 ", "remote-1", "local-2"),
        )

        assertEquals(" Draft ", restoreState.content)
        assertEquals(listOf("focus"), restoreState.selectedTags)
        assertEquals(listOf("Alice", "alice"), restoreState.selectedCollaborators)
        assertEquals(
            MemoEditorBaseline(
                content = " Draft ",
                tags = listOf("focus"),
                collaboratorIds = listOf("Alice", "alice"),
                resourceIdentifiers = listOf("remote-1", "local-2"),
            ),
            restoreState.baseline,
        )
    }

    private fun memoEntity(
        identifier: String,
        remoteId: String? = null,
        content: String,
        tags: List<String>,
        quoteSourceKind: String? = null,
        quoteSource: String? = null,
    ): MemoEntity = MemoEntity(
        identifier = identifier,
        remoteId = remoteId,
        accountKey = "account-key",
        content = content,
        date = Instant.parse("2026-03-30T12:00:00Z"),
        visibility = MemoVisibility.PRIVATE,
        pinned = false,
        archived = false,
        quoteSourceKind = quoteSourceKind,
        quoteSource = quoteSource,
    ).also { memo ->
        memo.tags = tags
    }

    private fun resourceEntity(
        identifier: String,
        remoteId: String?,
    ): ResourceEntity = ResourceEntity(
        identifier = identifier,
        remoteId = remoteId,
        accountKey = "account-key",
        date = Instant.parse("2026-03-30T12:00:00Z"),
        filename = "$identifier.jpg",
        uri = "content://$identifier",
        mimeType = "image/jpeg",
    )
}
