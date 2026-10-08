package com.example.autoclicker

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var params: WindowManager.LayoutParams
    private var viewAdded = false
    private var isRunning = false
    private lateinit var prefs: SharedPreferences

    companion object {
        const val PREFS_NAME = "autoclick_prefs"
        const val KEY_INTERVAL = "capture_interval_ms"
        const val ACTION_UPDATE_INTERVAL = "com.example.autoclicker.ACTION_UPDATE_INTERVAL"
        const val DEFAULT_INTERVAL = 300L
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 200

        windowManager.addView(floatingView, params)
        viewAdded = true

        setupDrag()
        setupToggleButton()
        setupSettingsPanel()
        setupCloseButton()
    }

    /** ลากย้ายปุ่มลอยได้จากทุกส่วนของแผง; ถ้าลากแล้วจะไม่นับเป็นการคลิก */
    private fun setupDrag() {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop

        fun listener(consumeDown: Boolean) = View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    consumeDown
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!moved && (dx * dx + dy * dy) > slop * slop) moved = true
                    if (moved) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(floatingView, params)
                    }
                    moved
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) v.isPressed = false
                    moved // ถ้าลาก ให้กลืน event เพื่อไม่ให้เกิด click
                }
                else -> false
            }
        }

        floatingView.findViewById<View>(R.id.btnToggle).setOnTouchListener(listener(false))
        floatingView.findViewById<View>(R.id.btnExpand).setOnTouchListener(listener(false))
        floatingView.findViewById<View>(R.id.overlayRoot).setOnTouchListener(listener(true))
    }

    private fun setupToggleButton() {
        val btn = floatingView.findViewById<ImageButton>(R.id.btnToggle)
        btn.setOnClickListener {
            if (!isRunning) {
                if (ProjectionHolder.data == null) {
                    toast("ยังไม่ได้ให้สิทธิ์แชร์หน้าจอ — เปิดแอปแล้วกดข้อ 3 ก่อน")
                    return@setOnClickListener
                }
                if (!AutoClickService.isEnabled(this)) {
                    toast("ยังไม่ได้เปิด Accessibility Service ของ AutoClicker")
                    return@setOnClickListener
                }
                isRunning = true
                val interval = prefs.getLong(KEY_INTERVAL, DEFAULT_INTERVAL)
                val svc = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra("interval", interval)
                }
                ContextCompat.startForegroundService(this, svc)
                btn.setImageResource(R.drawable.ic_stop)
            } else {
                isRunning = false
                val svc = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_PAUSE
                }
                startService(svc)
                btn.setImageResource(R.drawable.ic_play)
            }
        }
    }

    private fun setupSettingsPanel() {
        val btnExpand = floatingView.findViewById<ImageButton>(R.id.btnExpand)
        val panel = floatingView.findViewById<LinearLayout>(R.id.settingsPanel)
        val seekBar = floatingView.findViewById<SeekBar>(R.id.seekSpeed)
        val label = floatingView.findViewById<TextView>(R.id.tvSpeedLabel)

        val savedInterval = prefs.getLong(KEY_INTERVAL, DEFAULT_INTERVAL)
        seekBar.progress = (savedInterval - 100).toInt().coerceIn(0, 1900)
        label.text = "ความเร็วจับภาพ: ทุก ${savedInterval}ms"

        btnExpand.setOnClickListener {
            panel.visibility = if (panel.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val intervalMs = (progress + 100).toLong()
                label.text = "ความเร็วจับภาพ: ทุก ${intervalMs}ms"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val intervalMs = (sb!!.progress + 100).toLong()
                prefs.edit().putLong(KEY_INTERVAL, intervalMs).apply()

                val intent = Intent(ACTION_UPDATE_INTERVAL)
                intent.putExtra("interval", intervalMs)
                intent.setPackage(packageName)
                sendBroadcast(intent)
            }
        })
    }

    private fun setupCloseButton() {
        floatingView.findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            stopSelf()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        // ปิดปุ่มลอย = หยุดการจับภาพและเลิกแชร์หน้าจอด้วย
        stopService(Intent(this, ScreenCaptureService::class.java))
        if (viewAdded) {
            try {
                windowManager.removeView(floatingView)
            } catch (_: Exception) {
            }
            viewAdded = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
