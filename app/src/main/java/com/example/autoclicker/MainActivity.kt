package com.example.autoclicker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_CODE_OVERLAY = 1001
        const val REQUEST_CODE_MEDIA_PROJECTION = 1002
        const val REQUEST_CODE_NOTIFICATION = 1003
    }

    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)

        findViewById<Button>(R.id.btnOverlayPermission).setOnClickListener {
            requestOverlayPermission()
        }

        findViewById<Button>(R.id.btnAccessibilityPermission).setOnClickListener {
            openAccessibilitySettings()
        }

        findViewById<Button>(R.id.btnMediaProjection).setOnClickListener {
            requestMediaProjection()
        }

        findViewById<Button>(R.id.btnStartOverlay).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                startService(Intent(this, OverlayService::class.java))
                Toast.makeText(this, "เปิดปุ่มลอยแล้ว", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "กรุณาให้สิทธิ์ Overlay ก่อน", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnStopOverlay).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "ปิดปุ่มลอยแล้ว", Toast.LENGTH_SHORT).show()
            updateStatusText()
        }

        requestNotificationPermissionIfNeeded()
        updateStatusText()
    }

    override fun onResume() {
        super.onResume()
        updateStatusText()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE_NOTIFICATION
            )
        }
    }

    private fun updateStatusText() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessOk = AutoClickService.isEnabled(this)
        val projectionOk = ProjectionHolder.data != null
        tvStatus.text =
            "สิทธิ์ Overlay: ${mark(overlayOk)}\n" +
            "Accessibility Service: ${mark(accessOk)}\n" +
            "สิทธิ์แชร์หน้าจอ: ${mark(projectionOk)}\n\n" +
            "เมื่อให้สิทธิ์ครบแล้ว กด 'เปิดปุ่มลอย' แล้วไปที่เกม กดปุ่ม ▶ เพื่อเริ่มใช้งาน"
    }

    private fun mark(ok: Boolean) = if (ok) "✅ พร้อม" else "❌ ยังไม่ได้ให้"

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE_OVERLAY)
        } else {
            Toast.makeText(this, "ให้สิทธิ์ Overlay แล้ว", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(
            this,
            "กรุณาเปิดสวิตช์ 'AutoClicker' ในรายการ Accessibility Service",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun requestMediaProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE_MEDIA_PROJECTION)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ProjectionHolder.resultCode = resultCode
                ProjectionHolder.data = data
                Toast.makeText(this, "ได้สิทธิ์แชร์หน้าจอแล้ว พร้อมใช้งาน", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "ไม่ได้รับสิทธิ์แชร์หน้าจอ", Toast.LENGTH_SHORT).show()
            }
        }
        updateStatusText()
    }
}
