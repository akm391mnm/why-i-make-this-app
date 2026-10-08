package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

class AutoClickService : AccessibilityService() {

    companion object {
        const val ACTION_PERFORM_TAP = "com.example.autoclicker.ACTION_PERFORM_TAP"

        /** เช็คว่าผู้ใช้เปิด Accessibility Service ของแอปนี้แล้วหรือยัง */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val cn = ComponentName(context, AutoClickService::class.java)
            return enabled.split(':').any {
                it.equals(cn.flattenToString(), ignoreCase = true) ||
                    it.equals(cn.flattenToShortString(), ignoreCase = true)
            }
        }
    }

    private var receiverRegistered = false

    private val tapReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val x = intent?.getFloatExtra("x", -1f) ?: return
            val y = intent.getFloatExtra("y", -1f)
            if (x < 0 || y < 0) return
            performTap(x, y)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!receiverRegistered) {
            // Android 14: ต้องระบุ flag export เสมอสำหรับ receiver ที่ลงทะเบียนตอนรัน
            ContextCompat.registerReceiver(
                this,
                tapReceiver,
                IntentFilter(ACTION_PERFORM_TAP),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        AppLog.log("ACCESSIBILITY", "AutoClickService เชื่อมต่อแล้ว")
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                AppLog.log("TAP", "แตะสำเร็จ (${x.toInt()}, ${y.toInt()})")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                AppLog.log("TAP", "แตะถูกยกเลิกโดยระบบ (${x.toInt()}, ${y.toInt()})")
            }
        }, null)
        if (!ok) AppLog.log("TAP", "dispatchGesture คืนค่า false (ระบบไม่รับคำสั่ง)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // ไม่ใช้ event-based detection เพราะเกม Roblox ไม่ส่ง accessibility tree
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (receiverRegistered) {
            unregisterReceiver(tapReceiver)
            receiverRegistered = false
        }
        return super.onUnbind(intent)
    }
}
