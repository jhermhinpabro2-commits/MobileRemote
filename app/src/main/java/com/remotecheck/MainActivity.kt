package com.remotecheck

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val handler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private lateinit var prefs: SharedPreferences

    private lateinit var idText: TextView
    private lateinit var status: TextView
    private lateinit var targetInput: EditText
    private lateinit var serverInput: EditText
    private lateinit var connectBtn: Button
    private lateinit var endBtn: Button

    private var peerId: String? = null
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("rc", MODE_PRIVATE)
        buildUi()
        connectServer()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(20))
            setBackgroundColor(Color.parseColor("#F4F6FA"))
        }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(Color.GRAY) }

        root.addView(TextView(this).apply {
            text = "RemoteCheck"; textSize = 26f; setTextColor(Color.parseColor("#1A1A2E"))
        })

        root.addView(label("Ang ID mo (ibigay sa kausap)").also { it.setPadding(0, dp(24), 0, 0) })
        idText = TextView(this).apply {
            text = "---"; textSize = 32f; setTextColor(Color.parseColor("#2563EB"))
            setTextIsSelectable(true)
        }
        root.addView(idText)

        status = TextView(this).apply { text = "Connecting..."; textSize = 14f; setPadding(0, dp(4), 0, dp(20)) }
        root.addView(status)

        root.addView(label("Ilagay ang ID ng ico-connect"))
        targetInput = EditText(this).apply {
            hint = "123456789"; inputType = InputType.TYPE_CLASS_NUMBER; textSize = 22f
        }
        root.addView(targetInput)

        connectBtn = Button(this).apply {
            text = "Connect"
            setOnClickListener { sendRequest() }
        }
        root.addView(connectBtn)

        endBtn = Button(this).apply {
            text = "End session"; visibility = View.GONE
            setOnClickListener { endSession() }
        }
        root.addView(endBtn)

        root.addView(label("Server URL").also { it.setPadding(0, dp(32), 0, 0) })
        serverInput = EditText(this).apply {
            setText(prefs.getString("server", "wss://YOUR-SERVER.onrender.com"))
            textSize = 14f; inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(serverInput)
        root.addView(Button(this).apply {
            text = "Save & reconnect"
            setOnClickListener { connectServer() }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun connectServer() {
        closing = true
        ws?.close(1000, null)
        closing = false
        val url = serverInput.text.toString().trim()
        prefs.edit().putString("server", url).apply()
        status.text = "Connecting..."
        try {
            ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
        } catch (e: Exception) {
            status.text = "Mali ang server URL"
        }
    }

    private fun send(obj: JSONObject) { ws?.send(obj.toString()) }

    private fun sendRequest() {
        val target = targetInput.text.toString().trim()
        if (target.length != 9) { toast("9 digits ang ID"); return }
        peerId = target
        status.text = "Naghihintay ng permission ni $target..."
        send(JSONObject().put("type", "request").put("to", target))
    }

    private fun endSession() {
        peerId?.let { send(JSONObject().put("type", "end").put("to", it)) }
        sessionEnded("Tapos na ang session")
    }

    private fun sessionEnded(msg: String) {
        peerId = null
        status.text = msg
        endBtn.visibility = View.GONE
        connectBtn.isEnabled = true
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
            runOnUiThread { status.text = "Offline. Nagre-retry..." }
            handler.postDelayed({ if (!closing && webSocket == ws) connectServer() }, 3000)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket != ws || closing) return
            handler.postDelayed({ connectServer() }, 3000)
        }
    }

    private fun handle(m: JSONObject) {
        when (m.getString("type")) {
            "registered" -> {
                val id = m.getString("id")
                prefs.edit().putString("id", id).apply()
                idText.text = id.chunked(3).joinToString(" ")
                status.text = "Online"
            }
            "request" -> askPermission(m.getString("from"))
            "accept" -> {
                status.text = "Pinayagan ka! Connected kay ${m.getString("from")}"
                connectBtn.isEnabled = false
                endBtn.visibility = View.VISIBLE
            }
            "deny" -> sessionEnded("Tinanggihan ng kabilang phone")
            "end" -> sessionEnded("Tinapos ng kabilang phone ang session")
            "error" -> { status.text = m.optString("message"); toast(m.optString("message")) }
        }
    }

    private fun askPermission(from: String) {
        AlertDialog.Builder(this)
            .setTitle("Remote access request")
            .setMessage("Gusto ng ID $from na i-view/i-control ang phone mo. Payagan?\n\nIpayag lang kung kilala mo siya.")
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ ->
                peerId = from
                send(JSONObject().put("type", "accept").put("to", from))
                status.text = "Connected kay $from"
                endBtn.visibility = View.VISIBLE
            }
            .setNegativeButton("Deny") { _, _ ->
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
