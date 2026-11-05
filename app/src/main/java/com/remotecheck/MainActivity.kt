package com.remotecheck

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {

    private val REQ_CAPTURE = 1001
    private val REQ_NOTIF = 1002

    private val BG = Color.parseColor("#06112B")
    private val CARD = Color.parseColor("#0D1F4A")
    private val BORDER = Color.parseColor("#1E3A8A")
    private val ACCENT = Color.parseColor("#2F6BFF")
    private val LIGHT = Color.parseColor("#5C8DFF")
    private val SUB = Color.parseColor("#8FA8E0")
    private val DIM = Color.parseColor("#3A4A75")
    private val GOOD = Color.parseColor("#3DDC97")
    private val WARN = Color.parseColor("#FFB020")

    private lateinit var idText: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var accessText: TextView
    private lateinit var targetInput: EditText
    private lateinit var connectBtn: Button
    private lateinit var endBtn: Button

    private var dialog: AlertDialog? = null
    private var handledFor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        buildUi()
        Hub.start(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onResume() {
        super.onResume()
        Hub.onChange = { render() }
        render()
    }

    override fun onPause() {
        Hub.onChange = null
        super.onPause()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(2), stroke)
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(SUB)
        typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.15f
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = shape(CARD, 20, BORDER)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
    }

    private fun button(t: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = true; textSize = 14f
        typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.12f
        setTextColor(if (filled) Color.WHITE else LIGHT)
        background = if (filled) shape(ACCENT, 14) else shape(Color.TRANSPARENT, 14, ACCENT)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(24))
            setBackgroundColor(BG)
        }

        root.addView(TextView(this).apply {
            text = "REMOTECHECK"; textSize = 28f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.18f
        })
        root.addView(TextView(this).apply {
            text = "Secure remote phone check"; textSize = 14f; setTextColor(SUB)
            setPadding(0, dp(4), 0, dp(18))
        })

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = shape(CARD, 30, BORDER)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(18) }
        }
        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(DIM) }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) }
        }
        statusText = TextView(this).apply { text = "Connecting..."; textSize = 13f; setTextColor(Color.WHITE) }
        pill.addView(statusDot); pill.addView(statusText)
        root.addView(pill)

        // --- Your ID ---
        val idCard = card()
        idCard.addView(label("YOUR ID"))
        idText = TextView(this).apply {
            text = "--- --- ---"; textSize = 36f; setTextColor(LIGHT)
            typeface = Typeface.MONOSPACE; letterSpacing = 0.1f
            setPadding(0, dp(8), 0, 0)
        }
        idCard.addView(idText)
        idCard.addView(button("Copy ID", false) { copyId() })
        root.addView(idCard)

        // --- Allow control (this phone) ---
        val accCard = card()
        accCard.addView(label("ALLOW CONTROL OF THIS PHONE"))
        accessText = TextView(this).apply {
            text = "Remote control: OFF"; textSize = 16f; setTextColor(WARN)
            typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, 0)
        }
        accCard.addView(accessText)
        accCard.addView(TextView(this).apply {
            text = "Turn this on if someone will control THIS phone. Open Accessibility settings, choose RemoteCheck, then switch it on."
            textSize = 12f; setTextColor(SUB); setPadding(0, dp(6), 0, 0)
        })
        accCard.addView(button("Open Accessibility settings", false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(accCard)

        // --- Connect ---
        val connectCard = card()
        connectCard.addView(label("CONNECT TO A DEVICE"))
        targetInput = EditText(this).apply {
            hint = "Enter 9-digit ID"; setHintTextColor(DIM)
            setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER
            typeface = Typeface.MONOSPACE; letterSpacing = 0.1f
            background = shape(BG, 14, BORDER)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        }
        connectCard.addView(targetInput)
        connectBtn = button("Connect", true) { sendRequest() }
        connectCard.addView(connectBtn)
        endBtn = button("End session", false) { Hub.endSession() }
        endBtn.visibility = View.GONE
        connectCard.addView(endBtn)
        root.addView(connectCard)

        root.addView(TextView(this).apply {
            text = "The other phone must tap ALLOW before you can connect."
            textSize = 12f; setTextColor(SUB); gravity = Gravity.CENTER
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
    }

    private fun fmt(id: String) = id.chunked(3).joinToString(" ")

    private fun render() {
        idText.text = if (Hub.myId.isEmpty()) "--- --- ---" else fmt(Hub.myId)
        statusText.text = Hub.statusText
        (statusDot.background as GradientDrawable).setColor(if (Hub.online) LIGHT else DIM)

        val busy = Hub.role != Hub.Role.NONE
        connectBtn.isEnabled = !busy
        connectBtn.alpha = if (busy) 0.4f else 1f
        endBtn.visibility = if (busy) View.VISIBLE else View.GONE

        val on = RemoteControlService.instance != null
        accessText.text = if (on) "Remote control: ON" else "Remote control: OFF"
        accessText.setTextColor(if (on) GOOD else WARN)

        // incoming request dialog
        val p = Hub.pendingFrom
        if (p == null) {
            handledFor = null
            if (dialog?.isShowing == true) dialog?.dismiss()
        } else if (handledFor != p && dialog?.isShowing != true) {
            askPermission(p)
        }

        // open the viewer screen once the other phone accepted
        if (Hub.role == Hub.Role.VIEWER && Hub.active && !Hub.viewerOpened) {
            Hub.viewerOpened = true
            startActivity(Intent(this, ViewerActivity::class.java))
        }
    }

    private fun copyId() {
        if (Hub.myId.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("id", Hub.myId))
        toast("ID copied")
    }

    private fun sendRequest() {
        val target = targetInput.text.toString().trim()
        if (target.length != 9) { toast("Enter a 9-digit ID"); return }
        if (!Hub.online) { toast("Not connected to the server yet"); return }
        Hub.sendRequest(target)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun askPermission(from: String) {
        dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Remote access request")
            .setMessage(
                "ID ${fmt(from)} wants to view and control this phone.\n\n" +
                "Only allow someone you know and trust. You can stop at any time " +
                "from the notification or by ending the session."
            )
            .setCancelable(false)
            .setPositiveButton("ALLOW") { _, _ ->
                handledFor = from
                if (RemoteControlService.instance == null) {
                    toast("Remote control is OFF: they can view but not control. Turn it on in Accessibility settings.")
                }
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
            }
            .setNegativeButton("DENY") { _, _ ->
                handledFor = from
                Hub.denyPending()
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        val from = Hub.pendingFrom
        if (resultCode == RESULT_OK && data != null && from != null) {
            ScreenShareService.start(this, data, from)
        } else {
            toast("Screen sharing was not allowed")
            Hub.denyPending()
        }
    }
}
