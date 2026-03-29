package site.lcyk.keer.viewmodel

import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiInteractionGateTest {

    @Test
    fun `drawer hidden alone does not freeze drawer scope`() = runTest {
        val gate = UiInteractionGate()

        gate.setActive(MemoUiScope.DRAWER, UiInteractionType.DRAWER_HIDDEN, true)

        assertEquals(false, gate.observeScopeFrozen(MemoUiScope.DRAWER).first())
    }

    @Test
    fun `drawer transition freezes drawer scope`() = runTest {
        val gate = UiInteractionGate()

        gate.setActive(MemoUiScope.DRAWER, UiInteractionType.DRAWER_TRANSITION, true)

        assertEquals(true, gate.observeScopeFrozen(MemoUiScope.DRAWER).first())
    }
}
