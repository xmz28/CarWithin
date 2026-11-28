package com.example.carwithin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.example.carwithin.vendor.carlife.CarLifeLink
import com.example.carwithin.vendor.media.H264Decoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

internal data class ProjectionState(
    val source: String = "CarLife 未连接",
    val connection: String = "仪表未连接",
    val output: String = "投屏未开始",
    val peers: List<WifiP2pDevice> = emptyList(),
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val carLifeRunning: Boolean = false,
    val width: Int = InstrumentOutput.WIDTH,
    val height: Int = InstrumentOutput.HEIGHT,
    val outputRunning: Boolean = false,
    val testPattern: Boolean = false,
    val fps: Int = 20,
    val model: InstrumentModel = InstrumentModel.NINEBOT_FIVE,
    val scale: StreamProfile.Scale = StreamProfile.Scale.FIT,
    val canvasPreset: CarLifeCanvasPreset = CarLifeCanvasPreset.ULTRAWIDE_1200,
    val qrSsid: String = "",
    val qrPassword: String = "",
    val qrMac: String = "",
    val qrModelId: String = "",
    val qrLinkMode: EasyConnQrParser.LinkMode = EasyConnQrParser.LinkMode.WIFI_DIRECT,
    val logs: List<String> = emptyList(),
)

class ProjectionService : Service() {
    internal inner class LocalBinder : Binder() {
        val service get() = this@ProjectionService
    }

    private val main = Handler(Looper.getMainLooper())
    private val receiver = Executors.newSingleThreadExecutor()
    private val sender = Executors.newSingleThreadExecutor()
    private val mutableState = MutableStateFlow(ProjectionState())
    internal val state = mutableState.asStateFlow()
    private val preview = VideoPreview()
    private var previewSurface: Surface? = null
    private lateinit var p2p: P2pController
    private lateinit var wifiAp: WifiApConnector
    private var route: P2pController.Route? = null
    private var source: CarLifeSession? = null
    private var output: InstrumentOutput? = null
    private var foreground = false
    private var autoOutput = false
    private val settings by lazy { getSharedPreferences("projection", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()

        val savedModel = InstrumentModel.entries.find { it.name == settings.getString("model", null) }
        val savedScale = StreamProfile.Scale.values().find { it.name == settings.getString("scale", null) } ?: StreamProfile.Scale.FIT
        val savedCanvas = CarLifeCanvasPreset.entries.find { it.name == settings.getString("canvas_preset", null) } ?: CarLifeCanvasPreset.ULTRAWIDE_1200
        val savedQrSsid = settings.getString("qr_ssid", "").orEmpty()
        val savedQrPwd = settings.getString("qr_password", "").orEmpty()
        val savedQrMac = settings.getString("qr_mac", "").orEmpty()
        val savedQrModelId = settings.getString("qr_model_id", "").orEmpty()
        val savedQrMode = EasyConnQrParser.LinkMode.values().find { it.name == settings.getString("qr_link_mode", null) }
            ?: EasyConnQrParser.LinkMode.WIFI_DIRECT
        preview.scaleMode = savedScale
        mutableState.update {
            val activeModel = savedModel ?: it.model
            val reqW = if (activeModel.isEasyConn) savedCanvas.width else activeModel.requestWidth
            val reqH = if (activeModel.isEasyConn) savedCanvas.height else activeModel.requestHeight
            it.copy(fps = settings.getInt("fps", 20).takeIf { fps -> fps in listOf(20, 25, 30) } ?: 20,
                model = activeModel,
                scale = savedScale,
                canvasPreset = savedCanvas,
                qrSsid = savedQrSsid,
                qrPassword = savedQrPwd,
                qrMac = savedQrMac,
                qrModelId = savedQrModelId,
                qrLinkMode = savedQrMode,
                width = reqW,
                height = reqH)
        }
        p2p = P2pController(this, object : P2pController.Listener {
            override fun log(message: String) = record(message)
            override fun peers(peers: List<WifiP2pDevice>) = mutableState.update { it.copy(peers = peers) }
            override fun route(next: P2pController.Route?) = onRouteChanged(next)
            override fun connectionStatus(message: String, connecting: Boolean) {
                mutableState.update { it.copy(connection = message, connecting = connecting) }
            }
        })
        wifiAp = WifiApConnector(this, object : WifiApConnector.Listener {
            override fun log(message: String) = record(message)
            override fun route(next: P2pController.Route?) = onRouteChanged(next)
            override fun status(message: String, connecting: Boolean) {
                mutableState.update { it.copy(connection = message, connecting = connecting) }
            }
        })
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "投屏会话", NotificationManager.IMPORTANCE_LOW))
    }

    private fun onRouteChanged(next: P2pController.Route?) {
        if (next != route) stopOutput()
        route = next
        mutableState.update { it.copy(
            connected = next != null,
            connecting = false,
            connection = next?.let { "已连接 " + it.target().name() } ?: it.connection,
            output = if (next == null && autoOutput) "等待连接仪表" else it.output,
        ) }
        if (next != null) {
            val key = state.value.model.name
            settings.edit().putString("target_name_" + key, next.target().name())
                .putString("target_mac_" + key, next.target().mac()).apply()
            if (autoOutput) startOutput(false)
        }
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopOutput()
            stopCarLife()
        }
        return START_NOT_STICKY
    }

    internal fun scan() = p2p.scan()

    internal fun selectModel(model: InstrumentModel) {
        if (state.value.connecting || route != null) return
        val preset = state.value.canvasPreset
        val reqW = if (model.isEasyConn) preset.width else model.requestWidth
        val reqH = if (model.isEasyConn) preset.height else model.requestHeight
        mutableState.update { it.copy(model = model, width = reqW, height = reqH) }
        settings.edit().putString("model", model.name).apply()
    }

    internal fun selectScale(scale: StreamProfile.Scale) {
        preview.scaleMode = scale
        mutableState.update { it.copy(scale = scale) }
        settings.edit().putString("scale", scale.name).apply()
        record("画面填充模式已切换为：" + scaleTitle(scale))
    }

    internal fun selectCanvasPreset(preset: CarLifeCanvasPreset) {
        if (source != null) return
        val model = state.value.model
        val reqW = if (model.isEasyConn) preset.width else model.requestWidth
        val reqH = if (model.isEasyConn) preset.height else model.requestHeight
        mutableState.update { it.copy(canvasPreset = preset, width = reqW, height = reqH) }
        settings.edit().putString("canvas_preset", preset.name).apply()
    }

    internal fun connect(device: WifiP2pDevice) {
        if (!state.value.model.matches(device.deviceName)) return
        wifiAp.disconnect()
        runCatching { Target(device.deviceName.orEmpty(), device.deviceAddress.orEmpty()) }
            .onSuccess { p2p.connect(it, false, state.value.model.isEasyConn) }
            .onFailure { record("设备缺少完整名称或地址") }
    }

    internal fun applyQrText(raw: String): Result<EasyConnQrParser.ParsedQr> = runCatching {
        val parsed = EasyConnQrParser.parse(raw)
        saveQrConfig(parsed.ssid(), parsed.password(), parsed.mac(), parsed.modelId(), parsed.recommendedMode())
        val modeLabel = if (parsed.recommendedMode() == EasyConnQrParser.LinkMode.WIFI_DIRECT) "WLAN 直连" else "车机热点"
        record("二维码解析成功：SSID=${parsed.ssid()}，ModelID=${parsed.modelId().ifEmpty { "-" }}，MAC=${parsed.mac().ifEmpty { "-" }}，推荐=${modeLabel}")
        parsed
    }

    internal fun saveQrConfig(
        ssid: String,
        password: String,
        mac: String,
        modelId: String,
        mode: EasyConnQrParser.LinkMode,
    ) {
        val cleanSsid = ssid.trim()
        val cleanPwd = password.trim()
        val cleanMac = EasyConnQrParser.normalizeMac(mac)
        val cleanModel = modelId.trim()
        mutableState.update {
            it.copy(
                qrSsid = cleanSsid,
                qrPassword = cleanPwd,
                qrMac = cleanMac,
                qrModelId = cleanModel,
                qrLinkMode = mode,
            )
        }
        settings.edit()
            .putString("qr_ssid", cleanSsid)
            .putString("qr_password", cleanPwd)
            .putString("qr_mac", cleanMac)
            .putString("qr_model_id", cleanModel)
            .putString("qr_link_mode", mode.name)
            .apply()
    }

    internal fun connectSavedEasyConn() {
        val current = state.value
        val ssid = current.qrSsid.trim()
        if (ssid.isEmpty()) {
            record("请先扫描仪表二维码或填写 Wi-Fi 名称")
            mutableState.update { it.copy(connection = "请先扫码或输入 Wi-Fi 名称") }
            return
        }
        if (current.qrLinkMode == EasyConnQrParser.LinkMode.WIFI_AP) {
            p2p.disconnect()
            wifiAp.connect(ssid, current.qrPassword, current.qrMac)
        } else {
            wifiAp.disconnect()
            val mac = current.qrMac.ifBlank { "02:00:00:00:00:00" }
            runCatching { Target(ssid, mac) }
                .onSuccess { p2p.connect(it, false, true) }
                .onFailure { record("二维码中的名称或 MAC 格式无效：" + it.message) }
        }
    }

    internal fun adoptActiveP2p() {
        wifiAp.disconnect()
        p2p.adoptActiveGroup(state.value.qrSsid.ifBlank { "EasyConn" })
    }

    internal fun adoptCurrentWifi() {
        p2p.disconnect()
        wifiAp.adoptCurrentWifi(state.value.qrSsid, state.value.qrMac)
    }

    internal fun disconnect() {
        p2p.disconnect()
        wifiAp.disconnect()
    }

    internal fun selectFps(fps: Int) {
        if (source != null || output != null || fps !in listOf(20, 25, 30)) return
        mutableState.update { it.copy(fps = fps) }
        settings.edit().putInt("fps", fps).apply()
    }

    internal fun startCarLife(withInstrument: Boolean = false, canReconnect: Boolean = false) {
        if (source != null) return
        if (state.value.testPattern) stopOutput()
        keepRunning()
        val current = state.value
        val reqW = if (current.model.isEasyConn) current.canvasPreset.width else current.model.requestWidth
        val reqH = if (current.model.isEasyConn) current.canvasPreset.height else current.model.requestHeight
        val session = CarLifeSession(reqW, reqH, current.fps)
        source = session
        autoOutput = withInstrument
        session.attach(previewSurface)
        mutableState.update { it.copy(carLifeRunning = true, source = "等待小米 CarWith / Car+ 的本机 CarLife 连接") }
        receiver.execute { session.link.connect(60_000) }
        if (autoOutput) prepareInstrument(canReconnect)
        main.postDelayed({
            if (source === session && !session.hasPicture) {
                receiver.execute { session.link.requestKeyframe() }
            }
        }, 65_000)
        main.postDelayed({
            if (source === session && !session.hasPicture) {
                finishSource(session, "等待 CarLife 首帧超时，请检查小米 CarWith 无线 CarLife 是否已点开始连接")
            }
        }, 90_000)
    }

    private fun prepareInstrument(canReconnect: Boolean) {
        if (route != null) {
            startOutput(false)
            return
        }
        val current = state.value
        val model = current.model
        if (model.isEasyConn && current.qrSsid.isNotBlank()) {
            if (current.qrLinkMode == EasyConnQrParser.LinkMode.WIFI_AP) {
                mutableState.update { it.copy(output = "正在连接已扫码的车机热点 " + current.qrSsid) }
                record("CarLife 自动输出 · 连接已扫码车机热点 " + current.qrSsid)
                if (!current.connecting) wifiAp.connect(current.qrSsid, current.qrPassword, current.qrMac)
                return
            } else if (canReconnect) {
                val mac = current.qrMac.ifBlank { "02:00:00:00:00:00" }
                val target = runCatching { Target(current.qrSsid, mac) }.getOrNull()
                if (target != null && !current.connecting) {
                    mutableState.update { it.copy(output = "正在重连已扫码的仪表 " + current.qrSsid) }
                    record("CarLife 自动输出 · 重连已扫码仪表 " + current.qrSsid)
                    p2p.connect(target, true, true)
                    return
                }
            }
        }
        val key = model.name
        val name = settings.getString("target_name_" + key, null)
        val mac = settings.getString("target_mac_" + key, null)
        val target = if (name != null && mac != null && model.matches(name)) {
            runCatching { Target(name, mac) }.getOrNull()
        } else null
        val message = when {
            state.value.connecting -> "等待连接仪表"
            target == null -> "请在当前输出中选择仪表"
            !canReconnect -> "请在当前输出中授权并连接仪表"
            else -> "正在重连上次的仪表"
        }
        mutableState.update { it.copy(output = message) }
        record("CarLife 自动输出 · " + message)
        if (target != null && canReconnect && !state.value.connecting) p2p.connect(target, true, model.isEasyConn)
    }

    internal fun stopCarLife() {
        source?.let { finishSource(it, "CarLife 已停止") }
    }

    private fun finishSource(session: CarLifeSession, message: String) {
        if (source !== session) return
        source = null
        autoOutput = false
        if (!state.value.testPattern) {
            stopOutput()
            mutableState.update { it.copy(output = "投屏已停止") }
        }
        session.close()
        mutableState.update { it.copy(carLifeRunning = false, source = message) }
        record(message)
        releaseForegroundIfIdle()
    }

    internal fun startOutput(testPattern: Boolean) {
        val target = route ?: return
        if ((!testPattern && source == null) || output != null) return
        keepRunning()
        lateinit var session: InstrumentOutput
        val settings = state.value
        val model = settings.model
        val profile = if (model == InstrumentModel.NINEBOT_FIVE) {
            StreamProfile(model.profile.width(), model.profile.height(), settings.fps,
                model.profile.bitrate(), 0, StreamProfile.Scale.FIT)
        } else model.profile
        var failed: String? = null
        session = InstrumentOutput(
            this, target, profile, if (testPattern) null else preview,
            onStatus = { status ->
                main.post {
                    if (output === session) mutableState.update { it.copy(output = status) }
                }
            },
            onLog = { message ->
                main.post {
                    if (output === session) {
                        if (message.contains("失败")) failed = message
                        record(message)
                    }
                }
            },
            onFinished = {
                main.post {
                    if (output === session) stopOutput(failed ?: "仪表投屏会话已结束")
                }
            },
        )
        output = session
        if (!testPattern) source?.enableInstrument(true)
        mutableState.update { it.copy(
            outputRunning = true,
            testPattern = testPattern,
            output = if (testPattern) "正在发送动态测试图" else "等待 CarLife 画面",
        ) }
        sender.execute { session.run() }
    }

    internal fun pauseOutput() {
        autoOutput = false
        stopOutput()
    }

    private fun stopOutput(message: String = "投屏已停止") {
        val session = output ?: return
        output = null
        session.stop()
        source?.enableInstrument(false)
        mutableState.update { it.copy(outputRunning = false, testPattern = false, output = message) }
        releaseForegroundIfIdle()
    }

    internal fun attachPreview(surface: Surface) {
        previewSurface = surface
        source?.let { session ->
            session.attach(surface)
            receiver.execute { session.requestFrame() }
        }
    }

    internal fun detachPreview(surface: Surface) {
        if (previewSurface !== surface) return
        previewSurface = null
        source?.attach(null)
    }

    internal fun touch(action: Int, x: Float, y: Float): Boolean {
        val session = source ?: return false
        val width = session.link.canvasWidth
        val height = session.link.canvasHeight
        if (!session.hasPicture || width <= 0 || height <= 0) return false
        return session.link.touch(action, (x * width).toInt(), (y * height).toInt())
    }

    internal fun record(message: String) {
        if (message.isBlank()) return
        android.util.Log.i("CarWithin", message)
        mutableState.update { it.copy(logs = (listOf(message) + it.logs).take(150)) }
    }

    private fun keepRunning() {
        if (foreground) return
        startService(Intent(this, ProjectionService::class.java))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, ProjectionService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle("CarWithin")
            .setContentText("车联会话运行中")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
        startForeground(1, notification)
        foreground = true
    }

    private fun releaseForegroundIfIdle() {
        if (source == null && output == null && foreground) {
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        output?.stop()
        output = null
        val session = source
        source = null
        session?.close()
        p2p.close()
        wifiAp.close()
        receiver.shutdownNow()
        sender.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private inner class CarLifeSession(reqW: Int, reqH: Int, fps: Int) : CarLifeLink.Callback, H264Decoder.Listener {
        val link = CarLifeLink(this, reqW, reqH, fps)
        private val surfaceDecoder = SurfaceAvcDecoder(main)
        private var instrumentDecoder: H264Decoder? = null
        private var instrumentWaitingForKey = false
        private var sps: ByteArray? = null
        private var pps: ByteArray? = null
        private var received = 0L
        private var lastReceived = 0L
        private var lastRendered = 0L
        private var lastRequested = 0L
        private var width = reqW
        private var height = reqH
        private var lastPts = 0L
        private var lastReport = 0L
        private var closed = false
        @Volatile var hasPicture = false
            private set

        override fun onState(message: String) = dispatch {
            mutableState.update { it.copy(source = message) }
            record(message)
        }

        @Synchronized
        override fun onVideoInit(width: Int, height: Int, fps: Int) {
            if (width !in 16..3840 || height !in 16..2160) {
                dispatch { finishSource(this, "收到不支持的 CarLife 画布 " + width + "×" + height) }
                return
            }
            this.width = width
            this.height = height
            dispatch { mutableState.update { it.copy(width = width, height = height) } }
            onState("CarLife 已协商 " + width + "×" + height + " · " + fps + " FPS，等待画面")
        }

        @Synchronized
        override fun onVideoFrame(data: ByteArray, timestampSec: Long) {
            if (closed) return
            try {
                val bytes = Avc.annexB(data)
                lastPts = maxOf(lastPts + 1, SystemClock.elapsedRealtimeNanos() / 1000)
                received++
                val key = Avc.hasNal(bytes, 5)
                sps = Avc.parameterSet(bytes, 7) ?: sps
                pps = Avc.parameterSet(bytes, 8) ?: pps
                if (key && sps != null && pps != null && !hasPicture) {
                    hasPicture = true
                    dispatch { record("CarLife 已接收首个完整关键帧") }
                }
                surfaceDecoder.feed(bytes, key, sps, pps, lastPts, width, height)
                feedInstrument(bytes, key)
                report()
            } catch (error: Exception) {
                dispatch { finishSource(this, "CarLife 视频格式异常 · " + error.message) }
            }
        }

        override fun onClosed(reason: String) = dispatch { finishSource(this, reason) }
        override fun onDecoderReady(width: Int, height: Int) = Unit
        override fun onDecoderError(message: String) = dispatch {
            stopOutput("仪表转码失败 · " + message)
            record(message)
        }

        override fun onBitmap(bitmap: Bitmap) = preview.update(bitmap)
        override fun onFrameRendered(totalFrames: Long, luminance: Int) = Unit

        @Synchronized
        fun attach(surface: Surface?) {
            if (!closed) surfaceDecoder.attach(surface)
        }

        fun requestFrame() {
            if (!link.isClosed) link.requestKeyframe()
        }

        @Synchronized
        fun enableInstrument(enabled: Boolean) {
            instrumentDecoder?.release()
            instrumentDecoder = if (enabled) H264Decoder(this) else null
            instrumentWaitingForKey = enabled
            preview.clear()
            if (enabled) receiver.execute { requestFrame() }
        }

        private fun feedInstrument(bytes: ByteArray, key: Boolean) {
            val decoder = instrumentDecoder ?: return
            var frame = bytes
            if (instrumentWaitingForKey) {
                if (!key) return
                val serial = sps ?: return
                val picture = pps ?: return
                frame = Avc.join(Avc.join(serial, picture), bytes)
                instrumentWaitingForKey = false
            }
            decoder.feed(frame, 0, frame.size, lastPts, width, height)
        }

        private fun report() {
            val now = SystemClock.elapsedRealtime()
            if (lastReport == 0L) {
                lastReport = now
                return
            }
            val duration = now - lastReport
            if (duration < 1000) return
            val rendered = surfaceDecoder.framesRendered
            val actualWidth = surfaceDecoder.width.takeIf { it > 0 } ?: width
            val actualHeight = surfaceDecoder.height.takeIf { it > 0 } ?: height
            val description = String.format(
                java.util.Locale.ROOT,
                "CarLife %d×%d · 接收 %.1f / 预览 %.1f FPS",
                actualWidth, actualHeight,
                (received - lastReceived) * 1000.0 / duration,
                (rendered - lastRendered) * 1000.0 / duration,
            )
            lastReport = now
            lastReceived = received
            lastRendered = rendered
            android.util.Log.i("CarWithinPreview", description + " · 解码输入丢帧 " + surfaceDecoder.dropped)
            dispatch { mutableState.update { it.copy(source = description, width = actualWidth, height = actualHeight) } }
            if (surfaceDecoder.needsKeyFrame && now - lastRequested >= 3000) {
                lastRequested = now
                receiver.execute { requestFrame() }
            }
        }

        private fun dispatch(block: () -> Unit) {
            main.post { if (source === this) block() }
        }

        @Synchronized
        fun close() {
            closed = true
            link.stop()
            surfaceDecoder.closeCodec()
            instrumentDecoder?.release()
            instrumentDecoder = null
            preview.clear()
        }
    }

    companion object {
        private const val CHANNEL = "projection"
        private const val STOP = "com.example.carwithin.STOP_PROJECTION"

        internal fun scaleTitle(scale: StreamProfile.Scale) = when (scale) {
            StreamProfile.Scale.STRETCH -> "拉伸铺满（无黑边）"
            StreamProfile.Scale.CROP -> "裁切铺满（不变形）"
            StreamProfile.Scale.FIT -> "完整显示（留黑边）"
        }
    }
}
