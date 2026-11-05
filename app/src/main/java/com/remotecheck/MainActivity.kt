package com.remotecheck

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    // >>> PUT YOUR RENDER URL HERE (use wss:// not https://) <<<
    private val SERVER_URL = "wss://mobileremote.onrender.com/"

    private val BG = Color.parseColor("#06112B")
    private val CARD = Color.parseColor("#0D1F4A")
    private val BORDER = Color.parseColor("#1E3A8A")
    private val ACCENT = Color.parseColor("#2F6BFF")
    private val LIGHT = Color.parseColor("#5C8DFF")
    private val SUB = Color.parseColor("#8FA8E0")
    private val DIM = Color.parseColor("#3A4A75")

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val handler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private lateinit var prefs: SharedPreferences

    private lateinit var idText: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var targetInput: EditText
    private lateinit var connectBtn: Button
    private lateinit var endBtn: Button

    private var myId = ""
    private var peerId: String? = null
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("rc", MODE_PRIVATE)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        buildUi()
        connectServer()
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
        endBtn = button("End session", false) { endSession() }
        endBtn.visibility = View.GONE
        connectCard.addView(endBtn)
        root.addView(connectCard)

        root.addView(TextView(this).apply {
            text = "The other phone must tap ALLOW before you can connect."
            textSize = 12f; setTextColor(SUB); gravity = Gravity.CENTER
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
    }

    private fun setStatus(text: String, online: Boolean) {
        statusText.text = text
        (statusDot.background as GradientDrawable).setColor(if (online) LIGHT else DIM)
    }

    private fun copyId() {
        if (myId.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("id", myId))
        toast("ID copied")
    }

    private fun connectServer() {
        if (SERVER_URL.contains("YOUR-SERVER")) {
            setStatus("Server URL not set in code", false); return
        }
        closing = true
        ws?.close(1000, null)
        closing = false
        setStatus("Connecting to server...", false)
        try {
            ws = client.newWebSocket(Request.Builder().url(SERVER_URL).build(), listener)
        } catch (e: Exception) {
            setStatus("Invalid server URL", false)
        }
    }

    private fun send(obj: JSONObject) { ws?.send(obj.toString()) }

    private fun sendRequest() {
        val target = targetInput.text.toString().trim()
        if (target.length != 9) { toast("Enter a 9-digit ID"); return }
        peerId = target
        setStatus("Waiting for $target to accept...", true)
        send(JSONObject().put("type", "request").put("to", target))
    }

    private fun endSession() {
        peerId?.let { send(JSONObject().put("type", "end").put("to", it)) }
        sessionEnded("Session ended")
    }

    private fun sessionEnded(msg: String) {
        peerId = null
        setStatus(msg, true)
        endBtn.visibility = View.GONE
        connectBtn.isEnabled = true
        connectBtn.alpha = 1f
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val saved = prefs.getString("id", "") ?: ""
            webSocket.send(JSONObject().put("type", "register").put("id", saved).toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = JSONObject(text)
            runOnUiThread { handle(m) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket != ws) return
            runOnUiThread { setStatus("Offline. Retrying...", false) }
            handler.postDelayed({ if (!closing && webSocket == ws) connectServer() }, 3000)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket != ws || closing) return
            runOnUiThread { setStatus("Offline. Retrying...", false) }
            handler.postDelayed({ connectServer() }, 3000)
        }
    }

    private fun handle(m: JSONObject) {
        when (m.getString("type")) {
            "registered" -> {
                myId = m.getString("id")
                prefs.edit().putString("id", myId).apply()
                idText.text = myId.chunked(3).joinToString(" ")
                setStatus("Online", true)
            }
            "request" -> askPermission(m.getString("from"))
            "accept" -> {
                setStatus("Connected to ${m.getString("from")}", true)
                connectBtn.isEnabled = false
                connectBtn.alpha = 0.4f
                endBtn.visibility = View.VISIBLE
            }
            "deny" -> sessionEnded("Request denied")
            "end" -> sessionEnded("Session ended by the other phone")
            "error" -> { setStatus(m.optString("message"), true); toast(m.optString("message")) }
        }
    }

    private fun askPermission(from: String) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Remote access request")
            .setMessage("ID $from wants to view and control this phone.\n\nOnly allow someone you know and trust.")
            .setCancelable(false)
            .setPositiveButton("ALLOW") { _, _ ->
                peerId = from
                send(JSONObject().put("type", "accept").put("to", from))
                setStatus("Connected to $from", true)
                endBtn.visibility = View.VISIBLE
            }
            .setNegativeButton("DENY") { _, _ ->
                send(JSONObject().put("type", "deny").put("to", from))
            }
            .show()
    }

    override fun onDestroy() {
        closing = true
        ws?.close(1000, null)
        super.onDestroy()
    }
}
