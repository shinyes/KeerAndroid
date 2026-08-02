package site.lcyk.keer

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import dagger.hilt.android.AndroidEntryPoint
import site.lcyk.keer.ui.page.common.Navigation
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import site.lcyk.keer.viewmodel.MemosViewModel
import site.lcyk.keer.viewmodel.UserStateViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val userStateViewModel: UserStateViewModel by viewModels()
    private val memosViewModel: MemosViewModel by viewModels()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var releaseHighRefreshRunnable: Runnable? = null
    private var highestModeId: Int = 0

    /** 只要有 UI 重绘（滚动/动画/内容更新）就保持高刷；静止约 300ms 后恢复系统默认。 */
    private val onDrawListener = ViewTreeObserver.OnDrawListener {
        releaseHighRefreshRunnable?.let(mainHandler::removeCallbacks)
        setPreferredDisplayMode(highestModeId)
        scheduleIdleRefreshRelease()
    }

    companion object {
        const val ACTION_NEW_MEMO = "site.lcyk.keer.action.NEW_MEMO"
        const val ACTION_EDIT_MEMO = "site.lcyk.keer.action.EDIT_MEMO"
        const val ACTION_VIEW_MEMO = "site.lcyk.keer.action.VIEW_MEMO"
        const val EXTRA_MEMO_ID = "memoId"

        /** 无 UI 重绘视为静止，超过该时长后恢复系统默认刷新率。 */
        private const val REFRESH_IDLE_DELAY_MILLIS = 300L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 预取设备最高刷新率模式 id；操作时启用，空闲时恢复系统默认以省电。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            highestModeId = display
                ?.supportedModes
                ?.maxByOrNull { mode -> mode.refreshRate }
                ?.takeIf { mode -> mode.refreshRate > 60.0f }
                ?.modeId
                ?: 0
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            CompositionLocalProvider(
                LocalUserState provides userStateViewModel,
                LocalMemos provides memosViewModel
            ) {
                Navigation()
            }
        }
        if (highestModeId != 0) {
            window.decorView.viewTreeObserver.addOnDrawListener(onDrawListener)
        }
    }

    override fun onDestroy() {
        if (highestModeId != 0) {
            window.decorView.viewTreeObserver.removeOnDrawListener(onDrawListener)
        }
        super.onDestroy()
    }

    private fun setPreferredDisplayMode(modeId: Int) {
        val params = window.attributes
        if (params.preferredDisplayModeId != modeId) {
            params.preferredDisplayModeId = modeId
            window.attributes = params
        }
    }

    private fun scheduleIdleRefreshRelease() {
        releaseHighRefreshRunnable?.let(mainHandler::removeCallbacks)
        releaseHighRefreshRunnable = Runnable {
            setPreferredDisplayMode(0) // 0 = 系统选择，回到智能/自适应省电
        }.also { runnable ->
            mainHandler.postDelayed(runnable, REFRESH_IDLE_DELAY_MILLIS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
