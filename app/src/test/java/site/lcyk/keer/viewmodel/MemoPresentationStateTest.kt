package site.lcyk.keer.viewmodel

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.MemoEditorWorkflowPersistenceState
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
        assertEquals(state.initialFields, state.session.initialFields)
        assertEquals("", state.session.resetFields.content)
        assertEquals(emptyList<String>(), state.session.resetFields.selectedTags)
        assertEquals(emptyList<String>(), state.session.resetFields.selectedCollaborators)
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
        assertEquals(state.initialFields, state.session.initialFields)
        assertEquals("", state.session.resetFields.content)
        assertEquals(listOf("focus"), state.session.resetFields.selectedTags)
        assertEquals(emptyList<String>(), state.session.resetFields.selectedCollaborators)
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
    fun buildMemoEditorSessionState_exposesInitialAndResetFields() {
        val restoreState = buildMemoEditorRestoreState(
            content = "Draft",
            tags = listOf("focus"),
            collaboratorIds = listOf("alice"),
            resourceIdentifiers = listOf("remote-1"),
        )

        val sessionState = buildMemoEditorSessionState(
            restoreState = restoreState,
            resetTags = listOf("forced"),
        )

        assertEquals("Draft", sessionState.initialFields.content)
        assertEquals(listOf("focus"), sessionState.initialFields.selectedTags)
        assertEquals(listOf("alice"), sessionState.initialFields.selectedCollaborators)
        assertEquals("", sessionState.resetFields.content)
        assertEquals(listOf("forced"), sessionState.resetFields.selectedTags)
        assertEquals(emptyList<String>(), sessionState.resetFields.selectedCollaborators)
        assertEquals(emptyList<String>(), sessionState.resetFields.baseline.resourceIdentifiers)
    }

    @Test
    fun buildMemoEditorDraftPersistenceValue_respectsPersistAndClearFlags() {
        assertEquals(
            "Draft body",
            buildMemoEditorDraftPersistenceValue(
                persistDraft = true,
                currentContent = "Draft body",
            ),
        )
        assertEquals(
            "",
            buildMemoEditorDraftPersistenceValue(
                persistDraft = true,
                currentContent = "Draft body",
                clearDraft = true,
            ),
        )
        assertEquals(
            null,
            buildMemoEditorDraftPersistenceValue(
                persistDraft = false,
                currentContent = "Draft body",
            ),
        )
    }

    @Test
    fun buildMemoEditorDismissState_returnsConfirmationOnlyForDirtyEditors() {
        val dirtyState = buildMemoEditorDismissState(
            isDirty = true,
            persistDraft = true,
            currentContent = "Draft body",
        )
        val cleanState = buildMemoEditorDismissState(
            isDirty = false,
            persistDraft = true,
            currentContent = "Draft body",
        )

        assertTrue(dirtyState.shouldShowDiscardConfirmation)
        assertFalse(dirtyState.shouldDismiss)
        assertEquals(null, dirtyState.draftPersistenceValue)

        assertFalse(cleanState.shouldShowDiscardConfirmation)
        assertTrue(cleanState.shouldDismiss)
        assertEquals("Draft body", cleanState.draftPersistenceValue)
    }

    @Test
    fun buildMemoEditorCompletionState_combinesResetFieldsAndDraftPersistence() {
        val sessionState = buildMemoEditorSessionState(
            restoreState = buildMemoEditorRestoreState(
                content = "Draft",
                tags = listOf("focus"),
                collaboratorIds = listOf("alice"),
            ),
            resetTags = listOf("forced"),
        )

        val completionState = buildMemoEditorCompletionState(
            sessionState = sessionState,
            persistDraft = true,
            currentContent = "Draft",
            clearDraft = true,
        )

        assertEquals(sessionState.resetFields, completionState.fields)
        assertEquals("", completionState.draftPersistenceValue)
    }

    @Test
    fun buildMemoEditorDismissWorkflowState_onlyEnablesCleanupWhenDismissing() {
        val dirtyWorkflow = buildMemoEditorDismissWorkflowState(
            isDirty = true,
            persistDraft = true,
            currentContent = "Draft body",
            hideKeyboardOnDismiss = true,
        )
        val cleanWorkflow = buildMemoEditorDismissWorkflowState(
            isDirty = false,
            persistDraft = true,
            currentContent = "Draft body",
            clearUploadsOnDismiss = true,
            clearUploadTasksOnDismiss = true,
            hideKeyboardOnDismiss = true,
        )

        assertTrue(dirtyWorkflow.dismiss.shouldShowDiscardConfirmation)
        assertFalse(dirtyWorkflow.cleanup.hideKeyboard)

        assertTrue(cleanWorkflow.dismiss.shouldDismiss)
        assertEquals("Draft body", cleanWorkflow.dismiss.draftPersistenceValue)
        assertTrue(cleanWorkflow.cleanup.clearUploads)
        assertTrue(cleanWorkflow.cleanup.clearUploadTasks)
        assertTrue(cleanWorkflow.cleanup.hideKeyboard)
    }

    @Test
    fun buildMemoEditorCompletionWorkflowState_carriesCompletionAndCleanupState() {
        val sessionState = buildMemoEditorSessionState(
            restoreState = buildMemoEditorRestoreState(
                content = "Draft",
                tags = listOf("focus"),
                collaboratorIds = listOf("alice"),
            ),
            resetTags = listOf("forced"),
        )

        val workflowState = buildMemoEditorCompletionWorkflowState(
            sessionState = sessionState,
            persistDraft = true,
            currentContent = "Draft",
            clearDraft = true,
            clearUploads = true,
            clearUploadTasks = true,
            stopLocationTracking = true,
            clearPrefetchedLocation = true,
            resetPendingLocationPermission = true,
            refreshLocalSnapshot = true,
            hideKeyboard = true,
        )

        assertEquals(sessionState.resetFields, workflowState.completion.fields)
        assertEquals("", workflowState.completion.draftPersistenceValue)
        assertTrue(workflowState.cleanup.clearUploads)
        assertTrue(workflowState.cleanup.clearUploadTasks)
        assertTrue(workflowState.cleanup.stopLocationTracking)
        assertTrue(workflowState.cleanup.clearPrefetchedLocation)
        assertTrue(workflowState.cleanup.resetPendingLocationPermission)
        assertTrue(workflowState.cleanup.refreshLocalSnapshot)
        assertTrue(workflowState.cleanup.hideKeyboard)
    }

    @Test
    fun buildMemoEditorRestoreWorkflowState_carriesFieldsUploadsAndCleanup() {
        val restoreState = buildMemoEditorRestoreState(
            content = "Draft",
            tags = listOf("focus"),
            collaboratorIds = listOf("alice"),
        )
        val resource = resourceEntity(
            identifier = "local-a",
            remoteId = "remote-a",
        )

        val workflowState = buildMemoEditorRestoreWorkflowState(
            restoreState = restoreState,
            uploadResources = listOf(resource),
            clearUploads = true,
            clearUploadTasks = true,
        )

        assertEquals("Draft", workflowState.fields.content)
        assertEquals(listOf("focus"), workflowState.fields.selectedTags)
        assertEquals(listOf("alice"), workflowState.fields.selectedCollaborators)
        assertEquals(listOf(resource), workflowState.uploadResources)
        assertTrue(workflowState.cleanup.clearUploads)
        assertTrue(workflowState.cleanup.clearUploadTasks)
    }

    @Test
    fun buildMemoEditorSessionKey_joins_non_blank_parts() {
        assertEquals(
            "memo:edit:memo-1",
            buildMemoEditorSessionKey("memo", "edit", " memo-1 ", "", null),
        )
    }

    @Test
    fun buildMemoEditorPersistedContentState_normalizes_tags_and_collaborators() {
        val persistedState = buildMemoEditorPersistedContentState(
            MemoEditorWorkflowPersistenceState(
                editorSessionKey = " memo:edit:memo-1 ",
                editorContent = "Working copy",
                editorSelectedTags = listOf(" focus ", "focus"),
                editorSelectedCollaborators = listOf(" user/alice ", "alice"),
            ),
        )

        assertEquals("memo:edit:memo-1", persistedState.sessionKey)
        assertEquals("Working copy", persistedState.content)
        assertEquals(listOf("focus"), persistedState.selectedTags)
        assertEquals(listOf("alice"), persistedState.selectedCollaborators)
    }

    @Test
    fun buildMemoEditorEffectiveRestoreState_applies_matching_persisted_content() {
        val baseRestoreState = buildMemoEditorRestoreState(
            content = "Server copy",
            tags = listOf("work"),
            collaboratorIds = listOf("alice"),
            resourceIdentifiers = listOf("remote-1"),
        )

        val effectiveRestoreState = buildMemoEditorEffectiveRestoreState(
            baseRestoreState = baseRestoreState,
            persistedContentState = MemoEditorPersistedContentState(
                sessionKey = "memo:edit:memo-1",
                content = "Working copy",
                selectedTags = listOf("focus"),
                selectedCollaborators = listOf("bob"),
            ),
            expectedSessionKey = "memo:edit:memo-1",
        )

        assertEquals("Working copy", effectiveRestoreState.content)
        assertEquals(listOf("focus"), effectiveRestoreState.selectedTags)
        assertEquals(listOf("bob"), effectiveRestoreState.selectedCollaborators)
        assertEquals(baseRestoreState.baseline, effectiveRestoreState.baseline)
    }

    @Test
    fun buildMemoEditorEffectiveRestoreState_ignores_blank_persisted_content_without_payload() {
        val baseRestoreState = buildMemoEditorRestoreState(
            content = "Server copy",
            tags = listOf("work"),
            collaboratorIds = listOf("alice"),
            resourceIdentifiers = listOf("remote-1"),
        )

        val effectiveRestoreState = buildMemoEditorEffectiveRestoreState(
            baseRestoreState = baseRestoreState,
            persistedContentState = MemoEditorPersistedContentState(
                sessionKey = "memo:edit:memo-1",
                content = "",
                selectedTags = emptyList(),
                selectedCollaborators = emptyList(),
            ),
            expectedSessionKey = "memo:edit:memo-1",
            hasPersistedWorkflowPayload = false,
        )

        assertEquals(baseRestoreState, effectiveRestoreState)
    }

    @Test
    fun buildMemoEditorSubmitState_exposes_merged_tags_and_blocking_flags() {
        val uploadsState = buildMemoEditorUploadsState(
            uploadResources = listOf(
                resourceEntity(
                    identifier = "local-a",
                    remoteId = "remote-a",
                ),
            ),
            uploadTasks = listOf(
                UploadTaskState(
                    id = "task-1",
                    sequence = 1L,
                    filename = "file-a.png",
                    uploadedBytes = 10L,
                    totalBytes = 100L,
                    status = UploadTaskStatus.UPLOADING,
                ),
            ),
        )

        val submitState = buildMemoEditorSubmitState(
            content = "  Draft body  ",
            selectedTags = listOf("focus"),
            selectedCollaborators = listOf("user/alice"),
            quoteDescriptor = MemoQuoteDescriptor(
                sourceKind = MemoQuoteSourceKind.LOCAL,
                source = "quoted-1",
            ),
            uploadsState = uploadsState,
        )

        assertEquals("  Draft body  ", submitState.content)
        assertEquals("Draft body", submitState.trimmedContent)
        assertTrue(submitState.hasPayload)
        assertTrue(submitState.hasBlockingUpload)
        assertFalse(submitState.canSubmit)
        assertEquals(listOf("remote-a"), submitState.resourceIdentifiers)
        assertTrue(submitState.mergedTags.contains("focus"))
        assertTrue(submitState.mergedTags.contains("collab/alice"))
        assertTrue(submitState.mergedTags.any { it.startsWith("quote/") })
    }

    @Test
    fun buildMemoEditorSubmitRequest_preservesContentTagsResourcesAndCoordinates() {
        val submitState = MemoEditorSubmitState(
            content = "  Draft body  ",
            trimmedContent = "Draft body",
            mergedTags = listOf("focus", "collab/alice"),
            resourceIdentifiers = listOf("remote-a"),
            hasPayload = true,
        )

        val request = buildMemoEditorSubmitRequest(
            submitState = submitState,
            latitude = 12.34,
            longitude = 56.78,
        )

        assertEquals("  Draft body  ", request.content)
        assertEquals("Draft body", request.trimmedContent)
        assertEquals(listOf("focus", "collab/alice"), request.tags)
        assertEquals(listOf("remote-a"), request.resourceIdentifiers)
        assertEquals(12.34, request.latitude)
        assertEquals(56.78, request.longitude)
    }

    @Test
    fun executeMemoEditorSubmitWorkflow_handles_blocked_skipped_failed_and_succeeded_states() = runBlocking {
        val sessionState = MemoEditorSessionState(
            resetFields = MemoEditorFieldsState(),
        )

        val blocked = executeMemoEditorSubmitWorkflow(
            submitState = MemoEditorSubmitState(hasBlockingUpload = true),
            sessionState = sessionState,
            currentContent = "blocked",
            persistDraft = true,
            executor = { error("blocked should not execute") },
        )
        assertTrue(blocked is MemoEditorSubmitWorkflowResult.Blocked)

        val skipped = executeMemoEditorSubmitWorkflow(
            submitState = MemoEditorSubmitState(hasPayload = false),
            sessionState = sessionState,
            currentContent = "",
            persistDraft = true,
            executor = { error("skipped should not execute") },
        )
        assertEquals(MemoEditorSubmitWorkflowResult.Skipped, skipped)

        val failed = executeMemoEditorSubmitWorkflow(
            submitState = MemoEditorSubmitState(
                content = "raw",
                trimmedContent = "raw",
                mergedTags = listOf("focus"),
                resourceIdentifiers = listOf("remote-a"),
                hasPayload = true,
            ),
            sessionState = sessionState,
            currentContent = "raw",
            persistDraft = false,
            executor = { "submit failed" },
        )
        assertEquals(
            MemoEditorSubmitWorkflowResult.Failed("submit failed"),
            failed,
        )

        val succeeded = executeMemoEditorSubmitWorkflow(
            submitState = MemoEditorSubmitState(
                content = "raw",
                trimmedContent = "raw",
                mergedTags = listOf("focus"),
                resourceIdentifiers = listOf("remote-a"),
                hasPayload = true,
            ),
            sessionState = sessionState,
            currentContent = "raw",
            persistDraft = true,
            clearDraft = true,
            clearUploads = true,
            clearUploadTasks = true,
            executor = { null },
        )
        assertTrue(succeeded is MemoEditorSubmitWorkflowResult.Succeeded)
        val workflow = (succeeded as MemoEditorSubmitWorkflowResult.Succeeded).workflowState
        assertTrue(workflow.cleanup.clearUploads)
        assertTrue(workflow.cleanup.clearUploadTasks)
        assertEquals("", workflow.completion.draftPersistenceValue)
    }

    @Test
    fun buildGroupQuickMemoSubmitPlan_normalizesAndDetectsNewTags() {
        val plan = buildGroupQuickMemoSubmitPlan(
            existingTags = listOf("Focus", "archive"),
            requestedTags = listOf(" focus ", "deep/work", "archive"),
        )

        assertEquals(listOf("focus", "deep/work", "archive"), plan.normalizedTags)
        assertEquals(listOf("deep/work"), plan.tagsToCreate)
    }

    @Test
    fun buildMemoEditorLocationState_andSubmitState_followPermissionState() {
        val disabledState = buildMemoEditorLocationState(
            enabled = false,
            hasPermission = false,
        )
        val disabledSubmitState = buildMemoEditorLocationSubmitState(disabledState)
        assertFalse(disabledState.canPrefetch)
        assertFalse(disabledState.shouldCollectCoordinatesOnSubmit)
        assertFalse(disabledSubmitState.shouldRequestPermission)
        assertFalse(disabledSubmitState.shouldCollectCoordinates)

        val enabledWithoutPermission = buildMemoEditorLocationState(
            enabled = true,
            hasPermission = false,
        )
        val submitWithoutPermission = buildMemoEditorLocationSubmitState(enabledWithoutPermission)
        assertFalse(enabledWithoutPermission.canPrefetch)
        assertTrue(submitWithoutPermission.shouldRequestPermission)
        assertFalse(submitWithoutPermission.shouldCollectCoordinates)

        val enabledWithPermission = buildMemoEditorLocationState(
            enabled = true,
            hasPermission = true,
        )
        val submitWithPermission = buildMemoEditorLocationSubmitState(enabledWithPermission)
        assertTrue(enabledWithPermission.canPrefetch)
        assertTrue(enabledWithPermission.shouldCollectCoordinatesOnSubmit)
        assertFalse(submitWithPermission.shouldRequestPermission)
        assertTrue(submitWithPermission.shouldCollectCoordinates)
    }

    @Test
    fun buildMemoEditorUploadTargetIdentifier_prefersExplicitIdentifier_thenDisplayMemo() {
        val displayMemo = memoEntity(
            identifier = "memo-restore",
            content = "Memo",
            tags = emptyList(),
        )

        assertEquals(
            "explicit-id",
            buildMemoEditorUploadTargetIdentifier(
                memoIdentifier = " explicit-id ",
                displayMemo = displayMemo,
            ),
        )
        assertEquals(
            displayMemo.identifier,
            buildMemoEditorUploadTargetIdentifier(
                memoIdentifier = " ",
                displayMemo = displayMemo,
            ),
        )
        assertEquals(
            null,
            buildMemoEditorUploadTargetIdentifier(
                memoIdentifier = " ",
                displayMemo = null,
            ),
        )
    }

    @Test
    fun buildMemoEditorUploadWorkflowState_combinesEntryAndVisibleUploads() {
        val displayMemo = memoEntity(
            identifier = "memo-upload",
            content = "Memo",
            tags = emptyList(),
        )
        val resource = resourceEntity(
            identifier = "local-a",
            remoteId = "remote-a",
        )
        val task = UploadTaskState(
            id = "task-1",
            sequence = 1L,
            filename = "photo.jpg",
            uploadedBytes = 32L,
            totalBytes = 64L,
            status = UploadTaskStatus.UPLOADING,
        )

        val workflowState = buildMemoEditorUploadWorkflowState(
            memoIdentifier = null,
            displayMemo = displayMemo,
            uploadResources = listOf(resource),
            uploadTasks = listOf(task),
            highlightedResourceIdentifiers = listOf("local-a"),
            focusDelayMillis = 120L,
            showKeyboardAfterUpload = true,
        )

        assertEquals(displayMemo.identifier, workflowState.entry.targetMemoIdentifier)
        assertEquals(120L, workflowState.entry.focusDelayMillis)
        assertTrue(workflowState.entry.showKeyboardAfterUpload)
        assertEquals(listOf(resource), workflowState.uploads.resources)
        assertEquals(listOf(task), workflowState.uploads.tasks)
        assertEquals(listOf("remote-a"), workflowState.uploads.resourceIdentifiers)
        assertTrue(workflowState.uploads.hasActiveUpload)
        assertEquals(listOf(resource), workflowState.uploads.imageResources)
        assertEquals(emptyList<ResourceEntity>(), workflowState.uploads.attachmentResources)
        assertEquals(1, workflowState.uploads.imageItems.size)
        assertTrue(workflowState.uploads.imageItems.first().isHighlighted)
        assertEquals(1, workflowState.uploads.taskItems.size)
        assertFalse(workflowState.uploads.taskItems.first().canDismiss)
        assertFalse(workflowState.uploads.taskItems.first().canRetry)
        assertTrue(workflowState.uploads.taskItems.first().canCancel)
        assertEquals(1, workflowState.uploads.taskSection.totalCount)
        assertEquals(1, workflowState.uploads.taskSection.activeCount)
        assertEquals(0, workflowState.uploads.taskSection.failedCount)
        assertTrue(workflowState.uploads.taskSection.canCancelActiveTasks)
        assertFalse(workflowState.uploads.taskSection.canRetryFailedTasks)
        assertFalse(workflowState.uploads.taskSection.canClearFailedTasks)
        assertTrue(workflowState.uploads.feedback.showRecentCompletionHint)
        assertEquals(1, workflowState.uploads.feedback.recentlyCompletedResourceCount)
        assertEquals("local-a", workflowState.uploads.feedback.recentCompletionTriggerId)
        assertTrue(workflowState.uploads.feedback.shouldShowRecentCompletionSnackbar)
        assertEquals(1, workflowState.uploads.imageSection.totalCount)
        assertEquals(0, workflowState.uploads.attachmentSection.totalCount)
    }

    @Test
    fun buildMemoEditorUploadsState_marksFailedTasksClearable_and_splitsAttachmentItems() {
        val image = resourceEntity(
            identifier = "image-a",
            remoteId = null,
        )
        val attachment = resourceEntity(
            identifier = "file-b",
            remoteId = "remote-b",
        ).copy(
            filename = "file-b.pdf",
            mimeType = "application/pdf",
            uri = "content://file-b",
        )
        val failedTask = UploadTaskState(
            id = "task-failed",
            sequence = 2L,
            filename = "failed.pdf",
            uploadedBytes = 0L,
            totalBytes = 100L,
            status = UploadTaskStatus.FAILED,
            sourceUri = "content://failed",
        )

        val uploadsState = buildMemoEditorUploadsState(
            uploadResources = listOf(image, attachment),
            uploadTasks = listOf(failedTask),
            highlightedResourceIdentifiers = listOf("file-b"),
        )

        assertEquals(listOf(image), uploadsState.imageResources)
        assertEquals(listOf(attachment), uploadsState.attachmentResources)
        assertFalse(uploadsState.imageItems.first().isHighlighted)
        assertTrue(uploadsState.attachmentItems.first().isHighlighted)
        assertFalse(uploadsState.hasActiveUpload)
        assertEquals(1, uploadsState.taskSection.totalCount)
        assertEquals(0, uploadsState.taskSection.activeCount)
        assertEquals(1, uploadsState.taskSection.failedCount)
        assertFalse(uploadsState.taskSection.canCancelActiveTasks)
        assertTrue(uploadsState.taskSection.canRetryFailedTasks)
        assertTrue(uploadsState.taskSection.canClearFailedTasks)
        assertEquals(1, uploadsState.imageSection.totalCount)
        assertEquals(1, uploadsState.attachmentSection.totalCount)
        assertTrue(uploadsState.feedback.showRecentCompletionHint)
        assertEquals(1, uploadsState.feedback.recentlyCompletedResourceCount)
        assertEquals("file-b", uploadsState.feedback.recentCompletionTriggerId)
        assertTrue(uploadsState.feedback.shouldShowRecentCompletionSnackbar)
    }

    @Test
    fun buildMemoEditorUploadSectionState_andFeedbackState_trackItemsAndHighlights() {
        val items = listOf(
            MemoEditorUploadResourceItemState(
                resource = resourceEntity(identifier = "image-a", remoteId = null),
                isHighlighted = true,
            ),
            MemoEditorUploadResourceItemState(
                resource = resourceEntity(identifier = "image-b", remoteId = null),
                isHighlighted = false,
            ),
        )

        val sectionState = buildMemoEditorUploadSectionState(
            kind = MemoEditorUploadSectionKind.IMAGES,
            items = items,
        )
        val feedbackState = buildMemoEditorUploadFeedbackState(
            highlightedResourceIdentifiers = listOf("image-a"),
        )

        assertEquals(MemoEditorUploadSectionKind.IMAGES, sectionState.kind)
        assertEquals(2, sectionState.totalCount)
        assertEquals(items, sectionState.items)
        assertTrue(feedbackState.showRecentCompletionHint)
        assertEquals(1, feedbackState.recentlyCompletedResourceCount)
        assertEquals("image-a", feedbackState.recentCompletionTriggerId)
        assertTrue(feedbackState.shouldShowRecentCompletionSnackbar)
    }

    @Test
    fun buildMemoEditorUploadTaskSectionState_tracks_counts_and_actions() {
        val activeTask = UploadTaskState(
            id = "uploading",
            sequence = 1L,
            filename = "photo.jpg",
            uploadedBytes = 50L,
            totalBytes = 100L,
            status = UploadTaskStatus.UPLOADING,
        )
        val failedTask = UploadTaskState(
            id = "failed",
            sequence = 2L,
            filename = "doc.pdf",
            uploadedBytes = 0L,
            totalBytes = 100L,
            status = UploadTaskStatus.FAILED,
            sourceUri = "content://failed",
        )
        val sectionState = buildMemoEditorUploadTaskSectionState(
            items = buildMemoEditorUploadTaskItems(listOf(activeTask, failedTask)),
            activeTaskCount = 1,
            failedTaskCount = 1,
            canRetryFailedTasks = true,
            canClearFailedTasks = true,
        )

        assertEquals(2, sectionState.totalCount)
        assertEquals(1, sectionState.activeCount)
        assertEquals(1, sectionState.failedCount)
        assertTrue(sectionState.canCancelActiveTasks)
        assertTrue(sectionState.canRetryFailedTasks)
        assertTrue(sectionState.canClearFailedTasks)
    }

    @Test
    fun buildMemoEditorCanSubmit_requiresContentOrResources_and_no_active_upload() {
        val idleUploads = buildMemoEditorUploadsState(
            uploadResources = listOf(resourceEntity(identifier = "local-a", remoteId = null)),
            uploadTasks = emptyList(),
        )
        val busyUploads = buildMemoEditorUploadsState(
            uploadResources = emptyList(),
            uploadTasks = listOf(
                UploadTaskState(
                    id = "task-1",
                    sequence = 1L,
                    filename = "video.mp4",
                    uploadedBytes = 0L,
                    totalBytes = 100L,
                    status = UploadTaskStatus.PREPARING,
                )
            ),
        )

        assertTrue(buildMemoEditorCanSubmit(content = "", uploadsState = idleUploads))
        assertTrue(buildMemoEditorCanSubmit(content = "Memo body", uploadsState = busyUploads.copy(hasActiveUpload = false)))
        assertFalse(buildMemoEditorCanSubmit(content = "", uploadsState = busyUploads))
        assertFalse(buildMemoEditorCanSubmit(content = "Memo body", uploadsState = busyUploads))
    }

    @Test
    fun buildMemoEditorUploadTaskItemState_enablesRetryAndDismiss_forFailedTasksWithSource() {
        val failedTask = UploadTaskState(
            id = "task-failed",
            sequence = 2L,
            filename = "photo.jpg",
            uploadedBytes = 0L,
            totalBytes = 100L,
            status = UploadTaskStatus.FAILED,
            errorMessage = "boom",
            sourceUri = "content://photo",
        )
        val preparingTask = UploadTaskState(
            id = "task-preparing",
            sequence = 1L,
            filename = "video.mp4",
            uploadedBytes = 0L,
            totalBytes = 100L,
            status = UploadTaskStatus.PREPARING,
        )

        val failedItem = buildMemoEditorUploadTaskItemState(failedTask)
        val preparingItem = buildMemoEditorUploadTaskItemState(preparingTask)

        assertTrue(failedItem.canRetry)
        assertTrue(failedItem.canDismiss)
        assertFalse(failedItem.canCancel)
        assertFalse(preparingItem.canRetry)
        assertFalse(preparingItem.canDismiss)
        assertTrue(preparingItem.canCancel)
    }

    @Test
    fun buildMemoEditorUploadTaskItems_sortsUploadingPreparingThenFailed() {
        val failedTask = UploadTaskState(
            id = "failed",
            sequence = 3L,
            filename = "failed.jpg",
            uploadedBytes = 0L,
            totalBytes = 100L,
            status = UploadTaskStatus.FAILED,
            sourceUri = "content://failed",
        )
        val preparingTask = UploadTaskState(
            id = "preparing",
            sequence = 2L,
            filename = "preparing.jpg",
            uploadedBytes = 0L,
            totalBytes = 100L,
            status = UploadTaskStatus.PREPARING,
        )
        val uploadingTask = UploadTaskState(
            id = "uploading",
            sequence = 4L,
            filename = "uploading.jpg",
            uploadedBytes = 50L,
            totalBytes = 100L,
            status = UploadTaskStatus.UPLOADING,
        )

        val items = buildMemoEditorUploadTaskItems(
            listOf(failedTask, preparingTask, uploadingTask),
        )

        assertEquals(listOf("uploading", "preparing", "failed"), items.map { it.task.id })
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

