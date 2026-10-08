package com.example.autoclicker

import android.content.Intent

/**
 * เก็บผลลัพธ์สิทธิ์ MediaProjection ไว้ใช้ข้าม Activity/Service
 * ScreenCaptureService จะเคลียร์ค่านี้ตอนถูกปิด (Android 14+ ห้ามใช้ token เดิมซ้ำ)
 */
object ProjectionHolder {
    @Volatile var resultCode: Int? = null
    @Volatile var data: Intent? = null

    fun clear() {
        resultCode = null
        data = null
    }
}
