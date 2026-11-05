package com.remotecheck

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** Shows the other phone's screen. Touches here are sent to that phone as taps / swipes. */
class ViewerActivity : Activity(), Hub.ViewerListener {

    private val BG = Color.parseColor("#06112B")
    private val ACCENT = Color.parseColor("#2F6BFF")
    private val LIGHT = Color.parseColor("#5C8DFF")
    private val WARN = Color.parseColor("#FFB020")

    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var area: FrameLayout
    private lateinit var info: TextView
    private lateinit var banner: TextView
    private var attached: VideoTrack? = null
    private var vw = 0
    private var vh = 0
    private var lastMove = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = BG

        if (Hub.role != Hub.Role.VIEWER) { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        area = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(Color.BLACK)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
        }

        renderer = SurfaceViewRenderer(this)
        renderer.init(Rtc.egl.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() { runOnUiThread { info.visibility = android.view.View.GONE } }
            override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                runOnUiThread {
                    if (rotation % 180 == 0) { vw = w; vh = h } else { vw = h; vh = w }
                    fit()
                }
            }
        })
        renderer.setEnableHardwareScaler(true)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setOnTouchListener { v, e ->
            val x = (e.x / v.width).coerceIn(0f, 1f)
            val y = (e.y / v.height).coerceIn(0f, 1f)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> sendTouch("down", x, y)
                MotionEvent.ACTION_MOVE -> {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastMove >= 25) { lastMove = now; sendTouch("move", x, y) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sendTouch("up", x, y)
            }
            true
        }
        area.addView(renderer, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        info = TextView(this).apply {
            text = "Waiting for the other phone's screen..."
            setTextColor(Color.WHITE); textSize = 14f
        }
        area.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        banner = TextView(this).apply {
            text = "Remote control is OFF on the other phone. Ask them to turn on RemoteCheck in Settings > Accessibility. You can still view the screen."
            setTextColor(Color.BLACK); setBackgroundColor(WARN); textSize = 12f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = android.view.View.GONE
        }
        area.addView(banner, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        root.addView(area)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        bar.addView(barButton("Back") { Hub.sendControl(JSONObject().put("t", "back")) })
        bar.addView(barButton("Home") { Hub.sendControl(JSONObject().put("t", "home")) })
        bar.addView(barButton("Recents") { Hub.sendControl(JSONObject().put("t", "recents")) })
        bar.addView(barButton("End") { confirmEnd() })
        root.addView(bar)

        setContentView(root)

        Hub.viewerListener = this
        Hub.remoteTrack?.let { onTrack(it) }
        if (Hub.noAccess) onNoAccess()
    }

    private fun barButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = true; textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (t == "End") Color.WHITE else LIGHT)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            if (t == "End") setColor(Color.parseColor("#C0392B")) else { setColor(Color.TRANSPARENT); setStroke(dp(2), ACCENT) }
        }
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
        setOnClickListener { onClick() }
    }

    private fun sendTouch(t: String, x: Float, y: Float) {
        Hub.sendControl(JSONObject().put("t", t).put("x", x.toDouble()).put("y", y.toDouble()))
    }

    private fun fit() {
        if (vw == 0 || vh == 0 || area.width == 0 || area.height == 0) return
        val s = minOf(area.width.toFloat() / vw, area.height.toFloat() / vh)
        val lp = FrameLayout.LayoutParams((vw * s).toInt(), (vh * s).toInt(), Gravity.CENTER)
        renderer.layoutParams = lp
    }

    private fun confirmEnd() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("End session?")
            .setPositiveButton("END") { _, _ -> Hub.endSession() }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { confirmEnd() }

    // ---- Hub.ViewerListener ----
    override fun onTrack(t: VideoTrack) {
        runOnUiThread {
            attached?.removeSink(renderer)
            attached = t
            t.addSink(renderer)
        }
    }

    override fun onNoAccess() { runOnUiThread { banner.visibility = android.view.View.VISIBLE } }

    override fun onEnded() { runOnUiThread { finish() } }

    override fun onDestroy() {
        if (Hub.viewerListener === this) Hub.viewerListener = null
        Hub.viewerOpened = false
        try { attached?.removeSink(renderer) } catch (_: Exception) {}
        try { renderer.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
