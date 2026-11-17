package com.example.photobatch

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.*
import java.io.BufferedOutputStream
import java.util.Calendar
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {

    private data class Img(val uri: Uri, val name: String, val taken: Long)

    private val prefs by lazy { getSharedPreferences("rules", MODE_PRIVATE) }
    private lateinit var etBatch: EditText
    private lateinit var etStartBatch: EditText
    private lateinit var etFolder: EditText
    private lateinit var etFile: EditText
    private lateinit var btnStart: Button
    private lateinit var btnEnd: Button
    private lateinit var btnRun: Button
    private lateinit var btnFolder: Button
    private lateinit var tvLog: TextView
    private var startMs = 0L
    private var endMs = 0L
    private var outputTree: Uri? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        askPermissions()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, 24, 0, 0) }
        fun edit(key: String, def: String, num: Boolean) = EditText(this).apply {
            setText(prefs.getString(key, def))
            if (num) inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        root.addView(label("① 规则：每批张数"))
        etBatch = edit("batchCount", "6", true); root.addView(etBatch)
        root.addView(label("起始批次号"))
        etStartBatch = edit("startBatch", "1", true); root.addView(etStartBatch)
        root.addView(label("文件夹命名（{batch}=批次号）"))
        etFolder = edit("folder", "Set{batch}", false); root.addView(etFolder)
        root.addView(label("每批文件名（按拍摄时间顺序，逗号分隔，不含扩展名）"))
        etFile = edit("fileNames", "a,b,c,d,e,f", false).apply {
            hint = "例如：a,b,c,d,e,f"
        }
        root.addView(etFile)

        root.addView(label("② 选择拍摄日期范围"))
        btnStart = Button(this).apply { text = "开始日期"; setOnClickListener { pick(true) } }
        btnEnd = Button(this).apply { text = "结束日期"; setOnClickListener { pick(false) } }
        root.addView(btnStart); root.addView(btnEnd)

        outputTree = prefs.getString("outputTree", null)?.let(Uri::parse)
        btnFolder = Button(this).apply {
            text = if (outputTree == null) "选择压缩包保存位置" else "已选择保存位置 · 更改"
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 2) }
        }
        root.addView(btnFolder)

        btnRun = Button(this).apply { text = "开始任务"; setOnClickListener { run() } }
        root.addView(btnRun)
        tvLog = TextView(this)
        root.addView(ScrollView(this).apply { addView(tvLog) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    @Deprecated("Use activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
            outputTree = uri
            prefs.edit().putString("outputTree", uri.toString()).apply()
            btnFolder.text = "已选择保存位置 · 更改"
        }
    }

    private fun askPermissions() {
        val p = mutableListOf(Manifest.permission.ACCESS_MEDIA_LOCATION)
        p += if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        val need = p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    private fun pick(isStart: Boolean) {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            val cal = Calendar.getInstance().apply {
                set(y, m, d, if (isStart) 0 else 23, if (isStart) 0 else 59, if (isStart) 0 else 59)
                set(Calendar.MILLISECOND, if (isStart) 0 else 999)
            }
            if (isStart) { startMs = cal.timeInMillis; btnStart.text = "开始：$y-${m + 1}-$d" }
            else { endMs = cal.timeInMillis; btnEnd.text = "结束：$y-${m + 1}-$d" }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun log(t: String) = runOnUiThread { tvLog.append(t + "\n") }

    private fun query(): List<Img> {
        val out = ArrayList<Img>()
        val M = MediaStore.Images.Media
        contentResolver.query(
            M.EXTERNAL_CONTENT_URI,
            arrayOf(M._ID, M.DISPLAY_NAME, M.DATE_TAKEN),
            "${M.DATE_TAKEN} BETWEEN ? AND ?",
            arrayOf(startMs.toString(), endMs.toString()),
            "${M.DATE_TAKEN} ASC, ${M._ID} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Img(
                    android.content.ContentUris.withAppendedId(M.EXTERNAL_CONTENT_URI, id),
                    c.getString(1) ?: "x.jpg", c.getLong(2)
                )
            }
        }
        return out
    }

    // 读取未被系统抹去GPS的原始文件，字节级复制，不重新编码 → EXIF完整保留
    private fun openOriginal(u: Uri) = try {
        contentResolver.openInputStream(MediaStore.setRequireOriginal(u))
    } catch (e: Exception) {
        contentResolver.openInputStream(u)
    }

    private fun run() {
        if (startMs == 0L || endMs == 0L || endMs < startMs) { log("请先选好日期范围"); return }
        if (outputTree == null) { log("请先选择压缩包保存位置"); return }
        val n = etBatch.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: run { log("每批张数无效"); return }
        val firstBatch = etStartBatch.text.toString().toIntOrNull() ?: 1
        val folderT = etFolder.text.toString()
        val fileT = etFile.text.toString()
        val names = fileT.split(Regex("[,，]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (names.size < n) {
            log("每批张数为 $n，请至少输入 $n 个文件名，并用逗号分隔")
            return
        }
        if (names.take(n).any { it.contains('/') || it.contains('\\') || it == "." || it == ".." }) {
            log("文件名不能包含斜线")
            return
        }
        if (names.take(n).distinct().size != n) {
            log("每批文件名不能重复")
            return
        }
        prefs.edit().putString("batchCount", "$n").putString("startBatch", "$firstBatch")
            .putString("folder", folderT).putString("fileNames", fileT).apply()

        btnRun.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            try {
                val imgs = query()
                log("共 ${imgs.size} 张，每批 $n 张")
                imgs.chunked(n).forEachIndexed { bi, chunk ->
                    val b = "%03d".format(firstBatch + bi)
                    val folder = folderT.replace("{batch}", b)
                    val tree = outputTree ?: error("未选择保存位置")
                    val parent = DocumentsContract.buildDocumentUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree)
                    )
                    val out = DocumentsContract.createDocument(
                        contentResolver, parent, "application/zip", "$folder.zip"
                    ) ?: error("无法在所选位置创建压缩包")
                    ZipOutputStream(BufferedOutputStream(contentResolver.openOutputStream(out)!!)).use { zos ->
                        zos.setLevel(Deflater.BEST_SPEED)
                        chunk.forEachIndexed { i, img ->
                            val ext = img.name.substringAfterLast('.', "jpg")
                            val name = names[i]
                            val e = ZipEntry("$folder/$name.$ext")
                            if (img.taken > 0) e.time = img.taken
                            zos.putNextEntry(e)
                            openOriginal(img.uri)?.use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    log("✔ $folder.zip（${chunk.size} 张）")
                }
                log("全部完成 → 所选文件夹")
            } catch (e: Exception) {
                log("出错：${e.message}")
            } finally {
                runOnUiThread {
                    btnRun.isEnabled = true
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }.start()
    }
}
