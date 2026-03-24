package site.lcyk.keer.ext

import android.content.Context
import androidx.annotation.StringRes
import site.lcyk.keer.KeerApp

/**
 * Get the string resources by the R.string.xx.string
 *
 * To support i18n
 * @author Xeu<thankrain@qq.com>
 */
@Suppress("DEPRECATION")
val Int.string get() = KeerApp.INSTANCE.getString(this)

@Suppress("DEPRECATION")
fun Int.formatString(vararg args: Any) = KeerApp.INSTANCE.getString(this, *args)

/**
 * Recommended alternative: Use Context-aware extension functions
 * Example: context.getString(R.string.xxx) or context.string(R.string.xxx)
 */
fun Context.string(@StringRes resId: Int): String = getString(resId)

fun Context.string(@StringRes resId: Int, vararg args: Any): String = getString(resId, *args)
