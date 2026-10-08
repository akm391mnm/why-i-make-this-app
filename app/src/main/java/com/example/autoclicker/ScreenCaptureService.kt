package com.example.autoclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.example.autoclicker.ACTION_START_CAPTURE"
        const val ACTION_PAUSE = "com.example.autoclicker.ACTION_PAUSE_CAPTURE"
        const val NOTIFICATION_CHANNEL_ID = "autoclick_channel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "ScreenCaptureService"

        /** ย่อภาพหน้าจอ/ต้นแบบลงเพื่อให้ matchTemplate เร็วขึ้น (0.5 = ครึ่งหนึ่ง) */
        private const val SCALE = 0.5
        private const val MATCH_THRESHOLD = 0.75
    }

    @Volatile private var captureIntervalMs = OverlayService.DEFAULT_INTERVAL
    @Volatile private var isCapturing = false
    private var released = false

    private lateinit var workThread: HandlerThread
    private lateinit var workHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private var templates: List<Pair<String, Mat>> = emptyList()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var lastClickTime = 0L
    private val clickCooldownMs = 800L

    private val intervalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val newInterval = intent?.getLongExtra("interval", captureIntervalMs) ?: return
            captureIntervalMs = newInterval
        }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isCapturing) return
            try {
                captureAndProcessFrame()
            } catch (e: Throwable) {
                Log.w(TAG, "frame error", e)
            }
            if (isCapturing) workHandler.postDelayed(this, captureIntervalMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        OpenCVLoader.initDebug()
        workThread = HandlerThread("autoclick-capture").also { it.start() }
        workHandler = Handler(workThread.looper)
        templates = loadTemplates()

        ContextCompat.registerReceiver(
            this,
            intervalReceiver,
            IntentFilter(OverlayService.ACTION_UPDATE_INTERVAL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val size = currentScreenSize()
        screenWidth = size.first
        screenHeight = size.second
        screenDensity = resources.displayMetrics.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_PAUSE) {
            pauseLoop()
            if (mediaProjection == null) stopSelf()
            return START_NOT_STICKY
        }

        // ACTION_START — ต้อง startForeground ก่อนเรียก getMediaProjection (Android 14+)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        captureIntervalMs =
            intent.getLongExtra("interval", OverlayService.DEFAULT_INTERVAL)

        if (!setupMediaProjection()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startLoop()
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "AutoClicker Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AutoClicker กำลังทำงาน")
            .setContentText("กำลังสแกนหาปุ่มอัตโนมัติ")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    /** คืน true ถ้ามี MediaProjection พร้อมใช้งาน (สร้างใหม่หรือใช้ตัวเดิม) */
    private fun setupMediaProjection(): Boolean {
        if (mediaProjection != null) return true

        val resultCode = ProjectionHolder.resultCode
        val data = ProjectionHolder.data
        if (resultCode == null || data == null) return false

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection? = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: SecurityException) {
            Log.w(TAG, "projection token invalid", e)
            ProjectionHolder.clear()
            null
        }
        if (projection == null) return false

        // Android 14+: ต้องลงทะเบียน callback ก่อนสร้าง VirtualDisplay
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // ผู้ใช้กดหยุดแชร์จากระบบ
                if (!released) stopSelf()
            }
        }, mainHandler)
        mediaProjection = projection

        val size = currentScreenSize()
        screenWidth = size.first
        screenHeight = size.second

        val reader = ImageReader.newInstance(
            screenWidth, screenHeight, android.graphics.PixelFormat.RGBA_8888, 2
        )
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "AutoClickerCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
        return true
    }

    private fun startLoop() {
        workHandler.removeCallbacks(captureRunnable)
        isCapturing = true
        workHandler.post(captureRunnable)
    }

    private fun pauseLoop() {
        isCapturing = false
        workHandler.removeCallbacks(captureRunnable)
    }

    /** ขนาดจอจริงตามการหมุนปัจจุบัน (รวมแถบระบบ) — ต้องตรงกับพิกัดที่ใช้แตะ */
    private fun currentScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels, m.heightPixels)
        }
    }

    /** ถ้าผู้ใช้หมุนจอ ให้ปรับ VirtualDisplay/ImageReader ตามขนาดใหม่ */
    private fun syncScreenSize() {
        val (w, h) = currentScreenSize()
        if (w == screenWidth && h == screenHeight) return
        screenWidth = w
        screenHeight = h

        val oldReader = imageReader
        val newReader = ImageReader.newInstance(
            w, h, android.graphics.PixelFormat.RGBA_8888, 2
        )
        imageReader = newReader
        virtualDisplay?.resize(w, h, screenDensity)
        virtualDisplay?.surface = newReader.surface
        oldReader?.close()
    }

    private fun captureAndProcessFrame() {
        syncScreenSize()
        val reader = imageReader ?: return
        val image = reader.acquireLatestImage() ?: return

        var bitmap: Bitmap? = null
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(plane.buffer)

            processFrame(bitmap, image.width, image.height)
        } finally {
            image.close()
            bitmap?.recycle()
        }
    }

    private fun processFrame(screenBitmap: Bitmap, width: Int, height: Int) {
        if (templates.isEmpty()) return

        val full = Mat()
        var cropped: Mat? = null
        val small = Mat()
        try {
            Utils.bitmapToMat(screenBitmap, full)
            // ตัดส่วน padding ด้านขวาของ buffer ออก
            cropped = Mat(full, Rect(0, 0, width, height))
            Imgproc.resize(cropped, small, Size(), SCALE, SCALE, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, small, Imgproc.COLOR_RGBA2RGB)

            var bestScore = 0.0
            var bestX = 0.0
            var bestY = 0.0

            for ((_, template) in templates) {
                if (template.cols() > small.cols() || template.rows() > small.rows()) continue

                val result = Mat()
                try {
                    Imgproc.matchTemplate(small, template, result, Imgproc.TM_CCOEFF_NORMED)
                    val mmr = Core.minMaxLoc(result)
                    if (mmr.maxVal > bestScore) {
                        bestScore = mmr.maxVal
                        bestX = mmr.maxLoc.x + template.cols() / 2.0
                        bestY = mmr.maxLoc.y + template.rows() / 2.0
                    }
                } finally {
                    result.release()
                }
            }

            if (bestScore >= MATCH_THRESHOLD) {
                tapAt((bestX / SCALE).toFloat(), (bestY / SCALE).toFloat())
            }
        } finally {
            small.release()
            cropped?.release()
            full.release()
        }
    }

    private fun tapAt(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < clickCooldownMs) return
        lastClickTime = now

        val intent = Intent(AutoClickService.ACTION_PERFORM_TAP)
        intent.putExtra("x", x)
        intent.putExtra("y", y)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    /** โหลดทุกไฟล์ .png ในโฟลเดอร์ assets เป็นภาพต้นแบบ (btn_x2.png, btn_x5.png, ...) */
    private fun loadTemplates(): List<Pair<String, Mat>> {
        val list = mutableListOf<Pair<String, Mat>>()
        val files = try {
            assets.list("")?.filter { it.endsWith(".png", ignoreCase = true) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        for (name in files) {
            try {
                val bitmap = assets.open(name).use { BitmapFactory.decodeStream(it) } ?: continue
                val mat = Mat()
                Utils.bitmapToMat(bitmap, mat)
                bitmap.recycle()
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)

                val scaled = Mat()
                Imgproc.resize(mat, scaled, Size(), SCALE, SCALE, Imgproc.INTER_AREA)
                mat.release()

                if (scaled.cols() >= 4 && scaled.rows() >= 4) {
                    list.add(name to scaled)
                } else {
                    scaled.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "template load failed: $name", e)
            }
        }
        Log.i(TAG, "templates loaded: ${list.size}")
        return list
    }

    override fun onDestroy() {
        super.onDestroy()
        released = true
        isCapturing = false
        workHandler.removeCallbacksAndMessages(null)
        workThread.quitSafely()

        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        imageReader?.close()
        imageReader = null
        templates.forEach { it.second.release() }

        // token เดิมใช้ซ้ำไม่ได้บน Android 14+ — ให้ผู้ใช้กดให้สิทธิ์ใหม่
        ProjectionHolder.clear()
        try {
            unregisterReceiver(intervalReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
