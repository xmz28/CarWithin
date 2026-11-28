package com.example.carwithin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

internal enum class CarLifeCanvasPreset(val title: String, val width: Int, val height: Int) {
    ULTRAWIDE_1200("1200×544（赛600超宽满屏）", 1200, 544),
    ULTRAWIDE_1600("1600×724（高清超宽满屏）", 1600, 724),
    ULTRAWIDE_1060("1060×480（标准超宽）", 1060, 480),
    NATIVE_800("800×362（仪表 1:1）", 800, 362),
    COMPAT_848("848×480（通用兼容）", 848, 480);
}

internal enum class InstrumentModel(val title: String, val profile: StreamProfile) {
    NINEBOT_FIVE("九号 5 寸", StreamProfile.defaults()),
    NINEBOT_SEVEN("九号 7 寸（实验）", StreamProfile.sevenInch()),
    TAILING("台铃 4.3 寸", StreamProfile.tailing43()),
    ZEEHO("极核 6.2 寸", StreamProfile.zeeho62()),
    NINEBOT_JPEG("九号 4.3 寸（实验）", StreamProfile.jpeg43()),
    EASYCONN_PXC("亿连 PXC（钱江/春风等）", StreamProfile.easyConnDefault());

    val isEasyConn: Boolean get() = this == EASYCONN_PXC
    val requestWidth: Int get() = if (isEasyConn) CarLifeCanvasPreset.ULTRAWIDE_1200.width else InstrumentOutput.WIDTH
    val requestHeight: Int get() = if (isEasyConn) CarLifeCanvasPreset.ULTRAWIDE_1200.height else InstrumentOutput.HEIGHT

    fun matches(name: String?) = when (this) {
        EASYCONN_PXC -> DeviceCatalog.easyConnCandidate(name)
        NINEBOT_SEVEN -> name?.matches(Regex("DIRECT-[A-Za-z0-9]{8}")) == true
        TAILING -> DeviceCatalog.tailingCandidate(name)
        ZEEHO -> DeviceCatalog.zeehoCandidate(name)
        else -> DeviceCatalog.ninebotCandidate(name)
    }
}

/** The decoder owns its input bitmap. Copy once only for an active instrument output. */
internal class VideoPreview {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    private var serial = 0L
    @Volatile var scaleMode: StreamProfile.Scale = StreamProfile.Scale.FIT
        set(value) {
            if (field != value) {
                field = value
                synchronized(this) { serial++ }
            }
        }

    @Synchronized
    fun update(frame: Bitmap) {
        val previous = bitmap
        val target = if (previous?.width == frame.width && previous.height == frame.height) {
            previous
        } else {
            previous?.recycle()
            Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        }
        Canvas(target).drawBitmap(frame, 0f, 0f, paint)
        serial++
    }

    @Synchronized
    fun clear() {
        bitmap?.recycle()
        bitmap = null
        serial++
    }

    fun source(profile: StreamProfile): MirrorSession.FrameSource = object : MirrorSession.FrameSource {
        private var drawn = -1L
        private var fresh = false
        private var updates = 0L
        private val area = RectF()

        override fun draw(canvas: Canvas, paint: Paint, frame: Int): Boolean = synchronized(this@VideoPreview) {
            val picture = bitmap ?: return@synchronized false
            if (drawn != serial) {
                val cw = canvas.width.toFloat()
                val ch = canvas.height.toFloat()
                when (scaleMode) {
                    StreamProfile.Scale.STRETCH -> area.set(0f, 0f, cw, ch)
                    StreamProfile.Scale.CROP -> {
                        val ratio = maxOf(cw / picture.width, ch / picture.height)
                        val rw = picture.width * ratio
                        val rh = picture.height * ratio
                        area.set((cw - rw) / 2f, (ch - rh) / 2f, (cw + rw) / 2f, (ch + rh) / 2f)
                    }
                    StreamProfile.Scale.FIT -> {
                        val ratio = minOf(cw / picture.width, ch / picture.height)
                        val rw = picture.width * ratio
                        val rh = picture.height * ratio
                        area.set((cw - rw) / 2f, (ch - rh) / 2f, (cw + rw) / 2f, (ch + rh) / 2f)
                    }
                }
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(picture, null, area, this@VideoPreview.paint)
                drawn = serial
                fresh = true
                updates++
            }
            true
        }

        override fun hasFreshFrame(): Boolean = synchronized(this@VideoPreview) {
            bitmap != null && (drawn != serial || fresh)
        }
        override fun freshFrame() = fresh
        override fun freshFrames() = updates
        override fun submitted() { fresh = false }
    }
}

internal class InstrumentOutput(
    context: Context,
    route: P2pController.Route,
    profile: StreamProfile,
    preview: VideoPreview?,
    onStatus: (String) -> Unit,
    onLog: (String) -> Unit,
    onFinished: () -> Unit,
) {
    private val session = MirrorSession(context, route, null, false, profile, preview?.source(profile), object : MirrorSession.Listener {
        override fun log(message: String) {
            if (!message.startsWith("FPS：") && !message.startsWith("手机耗时") &&
                !message.startsWith("新来源帧") && !message.startsWith("封包/发送") &&
                !message.startsWith("节奏：") && !message.startsWith("RTCP #") &&
                !message.startsWith("KCP待确认")) onLog(message)
        }
        override fun stats(message: String) = onStatus(message)
        override fun finished() = onFinished()
    })

    fun run() = session.run()
    fun stop() = session.stop()

    companion object {
        const val WIDTH = 848
        const val HEIGHT = 480
        const val FPS = 20
    }
}
