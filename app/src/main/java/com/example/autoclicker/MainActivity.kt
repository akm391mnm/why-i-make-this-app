package com.example.autoclicker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.IntentFilter
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
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
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_CODE_OVERLAY = 1001
        const val REQUEST_CODE_MEDIA_PROJECTION = 1002
        const val REQUEST_CODE_NOTIFICATION = 1003
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var btnCheckUpdate: Button
    private lateinit var btnDownloadUpdate: Button
    private var pendingUpdateUrl: String? = null
    private var pendingDownloadId = -1L
    private var downloadReceiver: BroadcastReceiver? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val updatePrefs by lazy { getSharedPreferences("update_prefs", Context.MODE_PRIVATE) }

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

        findViewById<Button>(R.id.btnManageTemplates).setOnClickListener {
            showTemplateDialog()
        }

        findViewById<Button>(R.id.btnExportLogs).setOnClickListener { showLogDialog() }

        findViewById<TextView>(R.id.tvVersion).text =
            "เวอร์ชัน v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})"
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnDownloadUpdate = findViewById(R.id.btnDownloadUpdate)
        btnCheckUpdate.setOnClickListener { checkForUpdate() }
        btnDownloadUpdate.setOnClickListener { pendingUpdateUrl?.let { downloadAndInstall(it) } }
        btnDownloadUpdate.isEnabled = false
        confirmUpdateAppliedIfVersionBumped()

        requestNotificationPermissionIfNeeded()
        updateStatusText()
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        downloadReceiver = null
    }

    // ===================== Logs: ดู / ส่งออก .txt =====================

    private fun showLogDialog() {
        val files = AppLog.listFiles(this)
        if (files.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("ยังไม่มี log")
                .setMessage("log จะถูกบันทึกอัตโนมัติทุกครั้งที่กด ▶ เริ่มบนปุ่มลอย")
                .setPositiveButton("ตกลง", null)
                .show()
            return
        }

        val labels = mutableListOf("📦 ส่งออกทุกไฟล์รวมเป็นไฟล์เดียว", "🗑 ลบ log ทั้งหมด")
        files.forEach { labels.add("${it.name}  (${it.length() / 1024} KB)") }

        AlertDialog.Builder(this)
            .setTitle("Logs (${files.size} ไฟล์ — ใหม่สุดอยู่บนสุด)")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        val combined = AppLog.buildCombined(this)
                        if (combined != null) askExportAction(combined)
                    }
                    1 -> confirmDeleteLogs()
                    else -> askExportAction(files[which - 2])
                }
            }
            .setNegativeButton("ปิด", null)
            .show()
    }

    private fun confirmDeleteLogs() {
        AlertDialog.Builder(this)
            .setTitle("ลบ log ทั้งหมด?")
            .setPositiveButton("ลบ") { _, _ ->
                AppLog.deleteAll(this)
                Toast.makeText(this, "ลบ log แล้ว", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun askExportAction(file: File) {
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            arrayOf("แชร์/ส่งออก (.txt)", "บันทึกลงโฟลเดอร์ Download")
        else
            arrayOf("แชร์/ส่งออก (.txt)")

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(options) { _, which ->
                if (which == 0) shareLogFile(file) else saveToDownloads(file)
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun shareLogFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "ส่งออก log"))
        } catch (e: Exception) {
            Toast.makeText(this, "ส่งออกไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToDownloads(file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            shareLogFile(file)
            return
        }
        try {
            val name = if (file.name.startsWith("AutoClicker")) file.name else "AutoClicker_${file.name}"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("สร้างไฟล์ใน Download ไม่ได้")
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            Toast.makeText(this, "บันทึกแล้วที่ Download/$name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "บันทึกไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ===================== อัปเดตในแอป (วิธีเดียวกับ kline) =====================

    private val repoApiUrl =
        "https://api.github.com/repos/akm391mnm/why-i-make-this-app/releases/tags/latest"

    /**
     * ยืนยันว่าอัปเดตติดตั้งสำเร็จจริง โดยดูว่า versionCode ที่รันอยู่เพิ่มขึ้นจากครั้งก่อน
     * (ถ้าผู้ใช้กดยกเลิกหน้าติดตั้ง versionCode จะไม่เปลี่ยน อัปเดตจึงยังถูกเสนออยู่)
     */
    private fun confirmUpdateAppliedIfVersionBumped() {
        val last = updatePrefs.getInt("lastKnownVersionCode", -1)
        val current = BuildConfig.VERSION_CODE
        if (last != -1 && current > last) {
            val pending = updatePrefs.getString("pendingReleaseTime", "")
            if (!pending.isNullOrEmpty()) {
                updatePrefs.edit().putString("lastSeenReleaseTime", pending).apply()
            }
        }
        updatePrefs.edit().putInt("lastKnownVersionCode", current).apply()
    }

    private fun checkForUpdate() {
        tvUpdateStatus.text = "กำลังตรวจสอบ..."
        btnDownloadUpdate.isEnabled = false
        pendingUpdateUrl = null

        executor.submit {
            try {
                val conn = URL(repoApiUrl).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "AutoClicker")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                val code = conn.responseCode
                if (code != 200) {
                    mainHandler.post {
                        tvUpdateStatus.text = "ตรวจสอบไม่สำเร็จ (HTTP $code)" +
                            if (code == 404) " — ยังไม่มี release 'latest' หรือ repo เป็น private" else ""
                    }
                    return@submit
                }

                val body = conn.inputStream.bufferedReader().readText()
                val assets = JSONObject(body).optJSONArray("assets")
                var apkUrl: String? = null
                var apkUpdatedAt: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url")
                            // updated_at ของไฟล์ APK เปลี่ยนทุกครั้งที่อัปโหลด build ใหม่
                            apkUpdatedAt = asset.optString("updated_at")
                            break
                        }
                    }
                }

                mainHandler.post {
                    if (apkUrl == null) {
                        tvUpdateStatus.text = "ไม่พบไฟล์ APK ใน release ล่าสุด"
                        return@post
                    }
                    val lastSeen = updatePrefs.getString("lastSeenReleaseTime", "")
                    if (!apkUpdatedAt.isNullOrEmpty() && apkUpdatedAt == lastSeen) {
                        tvUpdateStatus.text = "ใช้เวอร์ชันล่าสุดอยู่แล้ว (v${BuildConfig.VERSION_NAME})"
                    } else {
                        tvUpdateStatus.text = "มีอัปเดตใหม่พร้อมติดตั้ง"
                        pendingUpdateUrl = apkUrl
                        updatePrefs.edit().putString("pendingReleaseTime", apkUpdatedAt ?: "").apply()
                        btnDownloadUpdate.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                mainHandler.post { tvUpdateStatus.text = "ตรวจสอบไม่สำเร็จ: ${e.message}" }
            }
        }
    }

    private fun updateApkFile() =
        File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")

    private fun downloadAndInstall(apkUrl: String) {
        tvUpdateStatus.text = "กำลังดาวน์โหลด..."
        btnDownloadUpdate.isEnabled = false

        // ลบไฟล์เก่าจากความพยายามครั้งก่อน กันเข้าใจผิดว่าไฟล์เก่าคือไฟล์ที่ดาวน์โหลดใหม่
        val target = updateApkFile()
        if (target.exists()) target.delete()

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("AutoClicker — อัปเดต")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "update.apk")
        pendingDownloadId = dm.enqueue(request)

        downloadReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != pendingDownloadId) return
                try {
                    unregisterReceiver(this)
                } catch (_: Exception) {
                }
                downloadReceiver = null

                // เช็คสถานะการดาวน์โหลดจริง ไม่ใช่แค่ดูว่าไฟล์มีอยู่
                var statusOk = false
                dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (c.moveToFirst()) {
                        val si = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        statusOk = si >= 0 && c.getInt(si) == DownloadManager.STATUS_SUCCESSFUL
                    }
                }
                if (!statusOk || !updateApkFile().exists()) {
                    tvUpdateStatus.text = "ดาวน์โหลดไม่สำเร็จ ลองกดใหม่อีกครั้ง"
                    btnDownloadUpdate.isEnabled = true
                    return
                }

                val apkUri = FileProvider.getUriForFile(
                    this@MainActivity, "$packageName.fileprovider", updateApkFile()
                )
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                tvUpdateStatus.text = "ดาวน์โหลดเสร็จแล้ว เปิดหน้าติดตั้ง..."
                // ไม่ mark ว่า "เห็นแล้ว" ตรงนี้ — รอให้ confirmUpdateAppliedIfVersionBumped()
                // ยืนยันจาก versionCode ที่เพิ่มขึ้นหลังติดตั้งจริง (กันบั๊กกดยกเลิกแล้วแอปบอกว่าล่าสุด)
                startActivity(install)
            }
        }
        downloadReceiver = onComplete
        ContextCompat.registerReceiver(
            this,
            onComplete,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun templateDir() = File(filesDir, ScreenCaptureService.TEMPLATE_DIR)

    private fun savedTemplates(): List<File> =
        templateDir().listFiles { f -> f.extension.equals("png", ignoreCase = true) }
            ?.sortedBy { it.name } ?: emptyList()

    /** รายการเทมเพลตที่บันทึกไว้ — แตะเพื่อลบ / ครอปภาพล่าสุดที่ถ่ายไว้ */
    private fun showTemplateDialog() {
        val files = savedTemplates()
        val snapshotFile = File(filesDir, ScreenCaptureService.SNAPSHOT_FILE)
        val labels = mutableListOf<String>()
        if (snapshotFile.exists()) labels.add("✂ ครอปภาพล่าสุดที่ถ่ายไว้")
        files.forEach { labels.add("🗑 ลบ ${it.name}") }

        if (labels.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("ยังไม่มีเทมเพลต")
                .setMessage("วิธีสร้าง: กด 'เปิดปุ่มลอย' ไปที่เกม แล้วกดไอคอนกล้องบนปุ่มลอย จากนั้นลากกรอบครอบปุ่มที่ต้องการ")
                .setPositiveButton("ตกลง", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("เทมเพลต (${files.size}) — ถ่ายใหม่ได้จากไอคอนกล้องบนปุ่มลอย")
            .setItems(labels.toTypedArray()) { _, which ->
                val hasSnapshot = snapshotFile.exists()
                if (hasSnapshot && which == 0) {
                    startActivity(Intent(this, TemplateCaptureActivity::class.java))
                } else {
                    val f = files[which - (if (hasSnapshot) 1 else 0)]
                    f.delete()
                    Toast.makeText(this, "ลบ ${f.name} แล้ว", Toast.LENGTH_SHORT).show()
                    updateStatusText()
                }
            }
            .setNegativeButton("ปิด", null)
            .show()
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
            "สิทธิ์แชร์หน้าจอ: ${mark(projectionOk)}\n" +
            "เทมเพลตที่บันทึกไว้: ${savedTemplates().size} รูป\n\n" +
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
