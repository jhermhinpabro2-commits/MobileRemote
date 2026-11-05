package com.remotecheck

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import kotlin.math.abs

/**
 * Performs taps / swipes / Back / Home / Recents on this phone.
 * Must be enabled manually by the phone's owner: Settings > Accessibility > RemoteCheck.
 * Hub only forwards events while a session the owner approved is active.
 */
class RemoteControlService : AccessibilityService() {

    companion object {
        @Volatile var instance: RemoteControlService? = null
    }

    private val pts = ArrayList<PointF>()
    private var downTime = 0L

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun screen(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun point(m: JSONObject): PointF {
        val (w, h) = screen()
        val x = (m.getDouble("x").toFloat() * w).coerceIn(1f, (w - 1).toFloat())
        val y = (m.getDouble("y").toFloat() * h).coerceIn(1f, (h - 1).toFloat())
        return PointF(x, y)
    }

    fun handle(m: JSONObject) {
        when (m.optString("t")) {
            "down" -> { pts.clear(); pts.add(point(m)); downTime = SystemClock.uptimeMillis() }
            "move" -> if (pts.isNotEmpty()) {
                val p = point(m); val l = pts.last()
                if (abs(p.x - l.x) + abs(p.y - l.y) > 4f) pts.add(p)
            }
            "up" -> if (pts.isNotEmpty()) {
                pts.add(point(m))
                perform()
                pts.clear()
            }
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
    }

    /** Replays the finished touch (tap, long press or swipe) as one gesture. */
    private fun perform() {
        val first = pts.first()
        val last = pts.last()
        val moved = abs(last.x - first.x) + abs(last.y - first.y) > 24f
        var dur = (SystemClock.uptimeMillis() - downTime).coerceIn(50L, 4000L)

        val path = Path()
        path.moveTo(first.x, first.y)
        if (moved) {
            pts.drop(1).forEach { path.lineTo(it.x, it.y) }
        } else if (dur < 300L) {
            dur = 50L // quick tap
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
            .build()
        dispatchGesture(g, null, null)
    }
}
