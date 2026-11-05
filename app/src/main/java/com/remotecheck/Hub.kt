package com.remotecheck

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import org.webrtc.VideoTrack
import java.util.concurrent.TimeUnit

/** App-wide signaling + session state. Lives as long as the process, so a session survives the UI going to background. */
object Hub {
    // >>> PUT YOUR RENDER URL HERE (use wss:// not https://) <<<
    const val SERVER_URL = "wss://mobileremote.onrender.com/"

    enum class Role { NONE, VIEWER, HOST }

    interface ViewerListener {
        fun onTrack(t: VideoTrack)
        fun onNoAccess()
        fun onEnded()
    }

    var myId = ""
        private set
    var statusText = "Connecting..."
        private set
    var online = false
        private set
    var peerId: String? = null
        private set
    var role = Role.NONE
        private set
    var active = false
        private set
    var pendingFrom: String? = null
        private set
    var remoteTrack: VideoTrack? = null
        private set
    var noAccess = false
        private set
    var viewerOpened = false

    var onChange: (() -> Unit)? = null
    var viewerListener: ViewerListener? = null

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var rtc: RtcSession? = null
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("rc", Context.MODE_PRIVATE)
        connect()
    }

    private fun setStatus(t: String, on: Boolean) {
        statusText = t; online = on
        onChange?.invoke()
    }

    // ---------- server connection ----------
    private fun connect() {
        if (SERVER_URL.contains("YOUR-SERVER")) { setStatus("Server URL not set in code", false); return }
        setStatus("Connecting to server...", false)
        val old = ws
        ws = null
        old?.close(1000, null)
        try {
            ws = client.newWebSocket(Request.Builder().url(SERVER_URL).build(), listener)
        } catch (e: Exception) {
            setStatus("Invalid server URL", false)
        }
    }

    private fun send(obj: JSONObject) { ws?.send(obj.toString()) }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val saved = prefs.getString("id", "") ?: ""
            webSocket.send(JSONObject().put("type", "register").put("id", saved).toString())
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            main.post { try { handle(JSONObject(text)) } catch (_: Exception) {} }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
    }

    private fun dropped(webSocket: WebSocket) {
        main.post {
            if (webSocket !== ws) return@post
            if (!active) setStatus("Offline. Retrying...", false) else onChange?.invoke()
            main.postDelayed({ if (webSocket === ws) connect() }, 3000)
        }
    }

    private fun handle(m: JSONObject) {
        when (m.getString("type")) {
            "registered" -> {
                myId = m.getString("id")
                prefs.edit().putString("id", myId).apply()
                if (!active) setStatus("Online", true) else onChange?.invoke()
            }
            "request" -> {
                val from = m.getString("from")
                if (role != Role.NONE || pendingFrom != null) {
                    send(JSONObject().put("type", "deny").put("to", from))
                } else {
                    pendingFrom = from
                    onChange?.invoke()
                }
            }
            "accept" -> {
                val from = m.getString("from")
                if (role == Role.VIEWER && from == peerId && !active) {
                    active = true
                    rtc = RtcSession(
                        app, false, null,
                        sendSignal = ::sendSignal,
                        onRemoteTrack = { t -> main.post { remoteTrack = t; viewerListener?.onTrack(t) } },
                        onData = ::onData,
                        onProjectionStopped = {}
                    )
                    setStatus("Connected to $from", true)
                }
            }
            "deny" -> if (role == Role.VIEWER && !active) endLocal("Request denied")
            "end" -> {
                val f = m.optString("from")
                if (f == pendingFrom) {
                    pendingFrom = null
                    setStatus("Request cancelled", online)
                } else if (f == peerId) {
                    endLocal("Session ended by the other phone")
                }
            }
            "signal" -> if (m.optString("from") == peerId) rtc?.onSignal(m.getJSONObject("data"))
            "error" -> {
                if (role == Role.VIEWER && !active) endLocal(m.optString("message"))
                else setStatus(m.optString("message"), true)
            }
        }
    }

    private fun sendSignal(data: JSONObject) {
        val p = peerId ?: return
        send(JSONObject().put("type", "signal").put("to", p).put("data", data))
    }

    // ---------- viewer side ----------
    fun sendRequest(target: String) {
        if (role != Role.NONE) return
        peerId = target
        role = Role.VIEWER
        active = false
        setStatus("Waiting for $target to accept...", true)
        send(JSONObject().put("type", "request").put("to", target))
    }

    fun sendControl(obj: JSONObject) { rtc?.sendControl(obj) }

    // ---------- host side ----------
    fun denyPending() {
        val f = pendingFrom ?: return
        pendingFrom = null
        send(JSONObject().put("type", "deny").put("to", f))
        setStatus("Request denied", online)
    }

    /** Called by ScreenShareService once the foreground notification is up and projection permission is held. */
    fun startHost(from: String, projData: Intent) {
        if (role != Role.NONE) return
        pendingFrom = null
        peerId = from
        role = Role.HOST
        active = true
        rtc = RtcSession(
            app, true, projData,
            sendSignal = ::sendSignal,
            onRemoteTrack = {},
            onData = ::onData,
            onProjectionStopped = { main.post { endSession() } }
        )
        // accept must reach the server BEFORE any signal, so the pair is approved
        send(JSONObject().put("type", "accept").put("to", from))
        setStatus("Sharing screen with $from", true)
        try {
            rtc?.startHost()
        } catch (e: Exception) {
            endSession()
        }
    }

    private fun onData(m: JSONObject) {
        main.post {
            if (role == Role.VIEWER) {
                if (m.optString("t") == "noaccess" && !noAccess) {
                    noAccess = true
                    viewerListener?.onNoAccess()
                }
            } else if (role == Role.HOST && active) {
                val svc = RemoteControlService.instance
                if (svc == null) {
                    if (m.optString("t") == "down") {
                        rtc?.sendControl(JSONObject().put("t", "noaccess"))
                    }
                } else {
                    svc.handle(m)
                }
            }
        }
    }

    // ---------- ending ----------
    fun endSession() {
        peerId?.let { send(JSONObject().put("type", "end").put("to", it)) }
        endLocal("Session ended")
    }

    private fun endLocal(msg: String) {
        rtc?.close(); rtc = null
        remoteTrack = null
        peerId = null; role = Role.NONE; active = false
        pendingFrom = null; noAccess = false; viewerOpened = false
        app.stopService(Intent(app, ScreenShareService::class.java))
        viewerListener?.onEnded()
        setStatus(msg, online)
    }
}
