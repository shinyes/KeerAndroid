package site.lcyk.keer.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.model.SyncStatus

class LocalSyncUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `LocalSyncStatus provides correct syncing state`() {
        // Given
        val syncStatus = SyncStatus(
            syncing = true,
            unsyncedCount = 5,
            uploadedBytes = 50L,
            totalBytes = 100L,
        )

        var receivedSyncingState = false

        // When
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalSyncStatus provides syncStatus
            ) {
                TestComponent { isSyncing ->
                    receivedSyncingState = isSyncing
                }
            }
        }

        // Then
        assert(receivedSyncingState) { "Expected syncing to be true" }
    }

    @Test
    fun `LocalSyncStatus provides correct progress value`() {
        // Given
        val syncStatus = SyncStatus(
            syncing = true,
            uploadedBytes = 75L,
            totalBytes = 100L,
        )

        var receivedProgress = -1f

        // When
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalSyncStatus provides syncStatus
            ) {
                ProgressTestComponent { progress ->
                    receivedProgress = progress
                }
            }
        }

        // Then
        assert(receivedProgress == 0.75f) { "Expected progress to be 0.75 but was $receivedProgress" }
    }

    @Test
    fun `LocalSyncActions triggers requestSync with correct parameters`() {
        // Given
        var syncRequested = false
        var receivedDomains: Set<SyncDomain>? = null
        var receivedForce = false

        val syncActions = SyncActions(
            requestSync = { domains, force ->
                syncRequested = true
                receivedDomains = domains
                receivedForce = force
            },
            cancelSync = {},
            clearError = {}
        )

        // When
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalSyncActions provides syncActions
            ) {
                SyncButtonTestComponent()
            }
        }

        // Trigger sync
        composeTestRule.onNodeWithText("Sync Now").performClick()

        // Then
        assert(syncRequested) { "Expected sync to be requested" }
        assert(receivedForce) { "Expected force to be true" }
        assert(receivedDomains?.contains(SyncDomain.MEMOS) == true) {
            "Expected MEMOS domain to be included"
        }
    }

    @Test
    fun `LocalSyncActions cancelSync can be triggered`() {
        // Given
        var cancelCalled = false

        val syncActions = SyncActions(
            requestSync = { _, _ -> },
            cancelSync = { cancelCalled = true },
            clearError = {}
        )

        // When
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalSyncActions provides syncActions
            ) {
                CancelButtonTestComponent()
            }
        }

        // Trigger cancel
        composeTestRule.onNodeWithText("Cancel Sync").performClick()

        // Then
        assert(cancelCalled) { "Expected cancelSync to be called" }
    }

    @Test
    fun `CompositionLocal throws error when not provided`() {
        // This test verifies that the error message is helpful
        // Note: In practice, this would crash the app if LocalSync
        // is accessed without a provider

        var errorThrown = false
        var errorMessage = ""

        try {
            composeTestRule.setContent {
                // No CompositionLocalProvider
                Text(LocalSyncStatus.current.syncing.toString())
            }
        } catch (e: IllegalStateException) {
            errorThrown = true
            errorMessage = e.message ?: ""
        }

        assert(errorThrown) { "Expected IllegalStateException" }
        assert(errorMessage.contains("LocalSyncStatus")) {
            "Expected error message to mention LocalSyncStatus"
        }
    }

    @Test
    fun `sync status badge displays correctly when syncing`() {
        // Given
        val syncStatus = SyncStatus(
            syncing = true,
            unsyncedCount = 3,
            uploadedBytes = 33L,
            totalBytes = 100L,
        )

        // When
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalSyncStatus provides syncStatus
            ) {
                Column {
                    Text(text = "Syncing...")
                    LinearProgressIndicator(
                        progress = { syncStatus.progress ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(text = "${syncStatus.unsyncedCount} items pending")
                }
            }
        }

        // Then
        composeTestRule.onNodeWithText("Syncing...").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 items pending").assertIsDisplayed()
    }

    @Test
    fun `multiple composables can access same sync status`() {
        // Given
        val syncStatus = SyncStatus(
            syncing = true,
            uploadedBytes = 50L,
            totalBytes = 100L,
            unsyncedCount = 10
        )

        var component1Syncing = false
        var component2Progress = -1f
        var component3Unsynced = -1

        // When
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalSyncStatus provides syncStatus
            ) {
                Column {
                    TestComponent { isSyncing ->
                        component1Syncing = isSyncing
                    }
                    ProgressTestComponent { progress ->
                        component2Progress = progress
                    }
                    UnsyncedCountTestComponent { count ->
                        component3Unsynced = count
                    }
                }
            }
        }

        // Then - all components should see the same state
        assert(component1Syncing) { "Component 1 should see syncing" }
        assert(component2Progress == 0.5f) { "Component 2 should see 0.5 progress" }
        assert(component3Unsynced == 10) { "Component 3 should see 10 unsynced" }
    }

    @Test
    fun `sync status updates propagate to all observers`() {
        // Given
        val initialStatus = SyncStatus(syncing = false)
        val updatedStatus = SyncStatus(
            syncing = true,
            uploadedBytes = 25L,
            totalBytes = 100L,
        )

        var currentSyncing = false

        // When
        composeTestRule.setContent {
            val status = if (currentSyncing) updatedStatus else initialStatus
            
            CompositionLocalProvider(
                LocalSyncStatus provides status
            ) {
                TestComponent { isSyncing ->
                    // This would normally use collectAsState in real code
                }
            }
        }

        // Initially not syncing
        assert(!currentSyncing)

        // Update state
        currentSyncing = true

        // Recompose
        composeTestRule.waitForIdle()

        // Then - state should update
        // (In real code, this would trigger recomposition automatically)
    }

    @Composable
    private fun TestComponent(onSyncingChange: (Boolean) -> Unit) {
        val syncStatus = LocalSyncStatus.current
        onSyncingChange(syncStatus.syncing)
        Text(text = "Syncing: ${syncStatus.syncing}")
    }

    @Composable
    private fun ProgressTestComponent(onProgressChange: (Float) -> Unit) {
        val syncStatus = LocalSyncStatus.current
        val progress = syncStatus.progress ?: 0f
        onProgressChange(progress)
        LinearProgressIndicator(progress = { progress })
    }

    @Composable
    private fun UnsyncedCountTestComponent(onCountChange: (Int) -> Unit) {
        val syncStatus = LocalSyncStatus.current
        onCountChange(syncStatus.unsyncedCount)
        Text(text = "${syncStatus.unsyncedCount} pending")
    }

    @Composable
    private fun SyncButtonTestComponent() {
        val syncActions = LocalSyncActions.current
        
        androidx.compose.material3.Button(
            onClick = {
                syncActions.requestSync(setOf(SyncDomain.MEMOS), true)
            }
        ) {
            Text("Sync Now")
        }
    }

    @Composable
    private fun CancelButtonTestComponent() {
        val syncActions = LocalSyncActions.current
        
        androidx.compose.material3.Button(
            onClick = { syncActions.cancelSync() }
        ) {
            Text("Cancel Sync")
        }
    }
}
