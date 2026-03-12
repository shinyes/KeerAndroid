package site.lcyk.keer.ext

import site.lcyk.keer.KeerApp

/**
 * Get the string resources by the R.string.xx.string
 *
 * To support i18n
 * @author Xeu<thankrain@qq.com>
 */
val Int.string get() = KeerApp.CONTEXT.getString(this)

fun Int.formatString(vararg args: Any) = KeerApp.CONTEXT.getString(this, *args)
