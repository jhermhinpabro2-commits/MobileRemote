package com.remotecheck

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer

/** Shared WebRTC factory + EGL context. */
object Rtc {
    val egl: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null

    // Optional TURN server (needed when the two phones can't reach each other directly).
    private const val TURN_URL = ""        // e.g. "turn:your-turn-server.com:3478"
    private const val TURN_USER = ""
    private const val TURN_PASS = ""

    val iceServers: List<PeerConnection.IceServer>
        get() {
            val l = mutableListOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )
            if (TURN_URL.isNotBlank()) {
                l.add(PeerConnection.IceServer.builder(TURN_URL)
                    .setUsername(TURN_USER).setPassword(TURN_PASS).createIceServer())
            }
            return l
        }

    @Synchronized
    fun factory(ctx: Context): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                .createInitializationOptions()
        )
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        return f
    }
}

open class SimpleSdp : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

/**
 * One WebRTC session.
 * HOST   = phone being viewed: captures its screen, sends the offer, receives control events.
 * VIEWER = phone that views/controls: receives video, sends control events.
 */
class RtcSession(
    ctx: Context,
    private val isHost: Boolean,
    private val projData: Intent?,
    private val sendSignal: (JSONObject) -> Unit,
    private val onRemoteTrack: (VideoTrack) -> Unit,
    private val onData: (JSONObject) -> Unit,
    private val onProjectionStopped: () -> Unit
) {
    private val appCtx = ctx.applicationContext
    private val factory = Rtc.factory(appCtx)
    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var remoteSet = false
    private val pendingIce = ArrayList<IceCandidate>()
    @Volatile private var closed = false

    init {
        val cfg = PeerConnection.RTCConfiguration(Rtc.iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(c: IceCandidate) {
                sendSignal(
                    JSONObject().put("k", "ice").put("mid", c.sdpMid)
                        .put("idx", c.sdpMLineIndex).put("cand", c.sdp)
                )
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(ch: DataChannel) { attach(ch) }
            override fun onRenegotiationNeeded() {}
            override fun onTrack(t: RtpTransceiver) {
                (t.receiver.track() as? VideoTrack)?.let { onRemoteTrack(it) }
            }
        })
    }

    private fun attach(ch: DataChannel) {
        dc = ch
        ch.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(l: Long) {}
            override fun onStateChange() {}
            override fun onMessage(b: DataChannel.Buffer) {
                try {
                    val bytes = ByteArray(b.data.remaining())
                    b.data.get(bytes)
                    onData(JSONObject(String(bytes)))
                } catch (_: Exception) {}
            }
        })
    }

    fun sendControl(obj: JSONObject) {
        val ch = dc ?: return
        if (ch.state() != DataChannel.State.OPEN) return
        ch.send(DataChannel.Buffer(ByteBuffer.wrap(obj.toString().toByteArray()), false))
    }

    private fun captureSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        var w = dm.widthPixels
        var h = dm.heightPixels
        val max = maxOf(w, h)
        if (max > 1280) {
            val s = 1280f / max
            w = (w * s).toInt(); h = (h * s).toInt()
        }
        return Pair(w and 1.inv(), h and 1.inv())
    }

    /** Host only: start screen capture and send the offer. */
    fun startHost() {
        val p = pc ?: return
        val init = DataChannel.Init().apply { ordered = true }
        p.createDataChannel("ctrl", init)?.let { attach(it) }

        source = factory.createVideoSource(true)
        helper = SurfaceTextureHelper.create("rc-capture", Rtc.egl.eglBaseContext)
        capturer = ScreenCapturerAndroid(projData, object : MediaProjection.Callback() {
            override fun onStop() { if (!closed) onProjectionStopped() }
        })
        capturer!!.initialize(helper, appCtx, source!!.capturerObserver)
        val (w, h) = captureSize()
        capturer!!.startCapture(w, h, 20)

        track = factory.createVideoTrack("screen", source)
        p.addTrack(track, listOf("rc"))

        p.createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(d: SessionDescription) {
                p.setLocalDescription(SimpleSdp(), d)
                sendSignal(JSONObject().put("k", "offer").put("sdp", d.description))
            }
        }, MediaConstraints())
    }

    fun onSignal(d: JSONObject) {
        val p = pc ?: return
        when (d.optString("k")) {
            "offer" -> if (!isHost) {
                p.setRemoteDescription(object : SimpleSdp() {
                    override fun onSetSuccess() {
                        remoteReady()
                        p.createAnswer(object : SimpleSdp() {
                            override fun onCreateSuccess(a: SessionDescription) {
                                p.setLocalDescription(SimpleSdp(), a)
                                sendSignal(JSONObject().put("k", "answer").put("sdp", a.description))
                            }
                        }, MediaConstraints())
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, d.getString("sdp")))
            }
            "answer" -> if (isHost) {
                p.setRemoteDescription(object : SimpleSdp() {
                    override fun onSetSuccess() { remoteReady() }
                }, SessionDescription(SessionDescription.Type.ANSWER, d.getString("sdp")))
            }
            "ice" -> {
                val c = IceCandidate(d.optString("mid", ""), d.optInt("idx"), d.getString("cand"))
                synchronized(this) {
                    if (remoteSet) p.addIceCandidate(c) else pendingIce.add(c)
                }
            }
        }
    }

    private fun remoteReady() = synchronized(this) {
        remoteSet = true
        pendingIce.forEach { pc?.addIceCandidate(it) }
        pendingIce.clear()
    }

    fun close() {
        if (closed) return
        closed = true
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        try { helper?.dispose() } catch (_: Exception) {}
        try { track?.dispose() } catch (_: Exception) {}
        try { source?.dispose() } catch (_: Exception) {}
        try { dc?.unregisterObserver(); dc?.close(); dc?.dispose() } catch (_: Exception) {}
        try { pc?.close(); pc?.dispose() } catch (_: Exception) {}
        pc = null; dc = null
    }
}
