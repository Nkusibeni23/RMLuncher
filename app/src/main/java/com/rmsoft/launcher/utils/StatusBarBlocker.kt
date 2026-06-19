package com.rmsoft.launcher.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Blocks the notification-shade pull-down without suppressing heads-up notification banners.
 *
 * It places a transparent, always-on-top overlay window across the **top 80dp** of the screen
 * (covering the status-bar area) and consumes every touch — including the downward swipe that
 * would otherwise open the shade — before it reaches the system UI. Heads-up banners render
 * separately and still appear.
 *
 * The overlay is created on the **application** context and is a system-level
 * `TYPE_APPLICATION_OVERLAY` window, so it floats above the launcher *and* any app launched
 * from it, and persists until [hide] (or process death).
 *
 * Requires the "Display over other apps" (SYSTEM_ALERT_WINDOW) permission — see [canBlock].
 */
class StatusBarBlocker(context: Context) {

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var blockerView: View? = null

    /** True once the overlay permission has been granted and the blocker can be shown. */
    fun canBlock(): Boolean = Settings.canDrawOverlays(appContext)

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (blockerView != null || !canBlock()) return

        val heightPx = (OVERLAY_HEIGHT_DP * appContext.resources.displayMetrics.density).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        // Transparent, touchable strip that swallows every gesture in the top 80dp.
        val view = object : View(appContext) {
            override fun onTouchEvent(event: MotionEvent): Boolean = true
        }.apply {
            isClickable = true
            isFocusable = false
        }

        runCatching {
            windowManager.addView(view, params)
            blockerView = view
        }
    }

    fun hide() {
        blockerView?.let { runCatching { windowManager.removeView(it) } }
        blockerView = null
    }

    private companion object {
        const val OVERLAY_HEIGHT_DP = 80
    }
}
