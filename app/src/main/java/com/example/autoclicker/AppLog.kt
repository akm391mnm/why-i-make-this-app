package com.example.autoclicker

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger เขียนลงไฟล์ .txt — เริ่มไฟล์ใหม่ทุกครั้งที่กดเริ่ม (▶)
 * ไฟล์อยู่ที่ filesDir/logs/run_YYYYMMDD_HHmmss.txt (เก็บล่าสุด 15 ไฟล์)
 */
object AppLog {
    private const val DIR = "logs"
    private const val MAX_FILES = 15
    private const val MAX_BYTES = 3_000_000L

    private var writer: BufferedWriter? = null
    private var bytes = 0L
    private var limitNoted = false
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun logDir(ctx: Context) = File(ctx.filesDir, DIR)

    /** เริ่มไฟล์ log ใหม่ (เรียกตอนกดเริ่ม) */
    @Synchronized
    fun startSession(ctx: Context, title: String) {
        closeInternal()
        val dir = logDir(ctx)
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "run_$stamp.txt")
        try {
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
            bytes = 0L
            limitNoted = false
        } catch (e: Exception) {
            Log.w("AppLog", "cannot open log file", e)
            writer = null
            return
        }
        rawWrite("===== AutoClicker log: $title =====\n")
        rawWrite("เวลา: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
        rawWrite("แอป: v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\n")
        rawWrite("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        rawWrite("เครื่อง: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        rawWrite("=====================================\n")
        prune(dir)
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        Log.d("AutoClicker", "[$tag] $msg")
        if (writer == null) return
        if (bytes > MAX_BYTES) {
            if (!limitNoted) {
                limitNoted = true
                rawWrite("... log ใหญ่เกินกำหนด หยุดบันทึกต่อในไฟล์นี้ ...\n")
            }
            return
        }
        rawWrite("${timeFmt.format(Date())} [$tag] $msg\n")
    }

    fun error(tag: String, msg: String, t: Throwable) {
        val trace = t.stackTrace.take(6).joinToString("\n    at ")
        log(tag, "$msg: $t\n    at $trace")
    }

    @Synchronized
    fun close() = closeInternal()

    private fun rawWrite(s: String) {
        try {
            writer?.write(s)
            writer?.flush()
            bytes += s.length
        } catch (e: Exception) {
            Log.w("AppLog", "write failed", e)
        }
    }

    private fun closeInternal() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
    }

    /** ไฟล์ log ทั้งหมด เรียงใหม่สุดก่อน */
    @Synchronized
    fun listFiles(ctx: Context): List<File> =
        logDir(ctx).listFiles { f -> f.extension.equals("txt", ignoreCase = true) }
            ?.sortedByDescending { it.name } ?: emptyList()

    @Synchronized
    fun deleteAll(ctx: Context) {
        closeInternal()
        logDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** รวมทุกไฟล์เป็นไฟล์เดียว (เก่า → ใหม่) ไว้ใน cache สำหรับส่งออก */
    @Synchronized
    fun buildCombined(ctx: Context): File? {
        val files = listFiles(ctx).reversed()
        if (files.isEmpty()) return null
        val out = File(ctx.cacheDir, "AutoClicker_all_logs.txt")
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            files.forEach { f ->
                w.write("##### ${f.name} #####\n")
                w.write(f.readText(Charsets.UTF_8))
                w.write("\n\n")
            }
        }
        return out
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.extension.equals("txt", ignoreCase = true) }
            ?.sortedByDescending { it.name } ?: return
        files.drop(MAX_FILES).forEach { it.delete() }
    }
}
