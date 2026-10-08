package com.example.autoclicker

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

/**
 * หน้าตัดกรอบเทมเพลต: โหลดภาพหน้าจอที่ถ่ายจากปุ่มลอย (snapshot.png)
 * ลากกรอบให้พอดีปุ่ม ตั้งชื่อ แล้วบันทึกลง filesDir/templates
 */
class TemplateCaptureActivity : AppCompatActivity() {

    private lateinit var cropView: CropOverlayView
    private lateinit var imageView: ImageView
    private var snapshot: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val file = File(filesDir, ScreenCaptureService.SNAPSHOT_FILE)
        val bmp = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        if (bmp == null) {
            Toast.makeText(this, "ยังไม่มีภาพที่ถ่ายไว้ ใช้ปุ่มกล้องบนปุ่มลอยในเกมก่อน", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        snapshot = bmp

        // หมุนหน้านี้ให้ตรงกับแนวภาพ (เกมแนวนอน/แนวตั้ง) จะได้ไม่ต้องย่อภาพเล็ก
        requestedOrientation = if (bmp.width > bmp.height)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

        setContentView(R.layout.activity_template_capture)

        imageView = findViewById(R.id.ivCaptured)
        cropView = findViewById(R.id.cropOverlay)
        imageView.setImageBitmap(bmp)
        cropView.resetBox()

        findViewById<Button>(R.id.btnSaveTemplate).setOnClickListener { saveCroppedTemplate() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
    }

    private fun saveCroppedTemplate() {
        val bitmap = snapshot ?: return

        val rect = cropView.getCropRectOnBitmap(imageView, bitmap)
        if (rect.width() < 8 || rect.height() < 8) {
            Toast.makeText(this, "กรอบเล็กเกินไปหรืออยู่นอกภาพ ลองใหม่อีกครั้ง", Toast.LENGTH_SHORT).show()
            return
        }

        val cropped = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())

        var name = findViewById<EditText>(R.id.etTemplateName).text.toString().trim()
        name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (name.isEmpty()) name = "template_${System.currentTimeMillis()}"
        if (!name.endsWith(".png", ignoreCase = true)) name += ".png"

        val dir = File(filesDir, ScreenCaptureService.TEMPLATE_DIR)
        if (!dir.exists()) dir.mkdirs()

        FileOutputStream(File(dir, name)).use { out ->
            cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        cropped.recycle()

        Toast.makeText(
            this,
            "บันทึกเทมเพลต $name แล้ว (มีผลตอนกด ▶ ครั้งถัดไป)",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }
}
