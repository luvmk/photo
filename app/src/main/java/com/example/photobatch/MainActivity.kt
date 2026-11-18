package com.example.photobatch

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
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
    private lateinit var btnChoosePhotos: Button
    private lateinit var btnClearPhotos: Button

    private lateinit var tvLog: TextView
    private lateinit var tvPhotoSelection: TextView
    private lateinit var tvTaskStatus: TextView
    private lateinit var tvProgressCount: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var logScroll: ScrollView

    private var selectedPhotoUris = mutableListOf<Uri>()
    private var startMs = 0L
    private var endMs = 0L
    private var outputTree: Uri? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        askPermissions()

        selectedPhotoUris = prefs.getString("selectedPhotoUris", "")
            .orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }
            .map(Uri::parse)
            .toMutableList()

        outputTree = prefs.getString("outputTree", null)?.let(Uri::parse)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setBackgroundColor(Color.rgb(244, 246, 248))
        }

        val header = TextView(this).apply {
            text = "照片分批打包"
            textSize = 22f
            setTextColor(Color.rgb(28, 37, 48))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(42)))

        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            background = roundedCard()
        }

        fun section(title: String) {
            settings.addView(TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(Color.rgb(74, 91, 108))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(2))
            })
        }

        fun edit(
            key: String,
            defaultValue: String,
            numeric: Boolean,
            hintText: String = ""
        ) = EditText(this).apply {
            setText(prefs.getString(key, defaultValue))
            textSize = 16f
            setPadding(dp(8), dp(4), dp(8), dp(4))

            if (numeric) {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }

            if (hintText.isNotEmpty()) {
                hint = hintText
            }
        }

        fun compactButton(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            textSize = 14f
            minHeight = dp(44)
            setOnClickListener { onClick() }
        }

        section("批次规则")

        val batchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val batchCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val startCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        batchCol.addView(smallLabel("每批张数"))
        etBatch = edit("batchCount", "6", true)
        batchCol.addView(etBatch)

        startCol.addView(smallLabel("起始批次号"))
        etStartBatch = edit("startBatch", "1", true)
        startCol.addView(etStartBatch)

        batchRow.addView(
            batchCol,
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(10)
            }
        )
        batchRow.addView(
            startCol,
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        settings.addView(batchRow)

        settings.addView(smallLabel("文件夹名称（{batch} 表示批次号）"))
        etFolder = edit("folder", "Set{batch}", false)
        settings.addView(etFolder)

        settings.addView(smallLabel("每批文件名 · 按拍摄时间排序，逗号分隔"))
        etFile = edit(
            "fileNames",
            "a,b,c,d,e,f",
            false,
            "例如：a,b,c,d,e,f"
        ).apply {
            maxLines = 2
        }
        settings.addView(etFile)

        section("照片来源")

        val photoButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        btnChoosePhotos = compactButton("从相册选择照片") {
            choosePhotos()
        }
        btnClearPhotos = compactButton("清空") {
            clearSelectedPhotos()
        }

        photoButtons.addView(
            btnChoosePhotos,
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(8)
            }
        )
        photoButtons.addView(
            btnClearPhotos,
            LinearLayout.LayoutParams(dp(84), -2)
        )
        settings.addView(photoButtons)

        tvPhotoSelection = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(85, 101, 117))
            setPadding(0, dp(2), 0, dp(2))
        }
        settings.addView(tvPhotoSelection)

        section("日期筛选（未手动选图时使用）")

        val dates = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        btnStart = compactButton("开始日期") {
            pick(true)
        }
        btnEnd = compactButton("结束日期") {
            pick(false)
        }

        dates.addView(
            btnStart,
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(8)
            }
        )
        dates.addView(
            btnEnd,
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        settings.addView(dates)

        section("输出与分类")

        settings.addView(TextView(this).apply {
            text = "离线智能分类：People · PeopleGroup · Animal · Vehicle · DailyObject · Text · Plant · DetailObject · Distant"
            textSize = 13f
            setTextColor(Color.rgb(85, 101, 117))
        })

        btnFolder = compactButton(
            if (outputTree == null) {
                "选择压缩包保存位置"
            } else {
                "已选择保存位置 · 更改"
            }
        ) {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                2
            )
        }
        settings.addView(btnFolder)

        val settingsScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, dp(6), 0, dp(8))
            addView(settings)
        }

        root.addView(
            settingsScroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        btnRun = Button(this).apply {
            text = "开始任务"
            textSize = 17f
            minHeight = dp(50)
            setOnClickListener { run() }
        }
        root.addView(
            btnRun,
            LinearLayout.LayoutParams(-1, dp(52)).apply {
                bottomMargin = dp(8)
            }
        )

        val progressCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedCard()
        }

        val progressHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        tvTaskStatus = TextView(this).apply {
            text = "等待开始"
            textSize = 14f
            setTextColor(Color.rgb(35, 48, 61))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        tvProgressCount = TextView(this).apply {
            text = "0 / 0"
            textSize = 12f
            gravity = Gravity.END
            setTextColor(Color.rgb(94, 108, 122))
        }

        progressHeader.addView(
            tvTaskStatus,
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        progressHeader.addView(tvProgressCount)
        progressCard.addView(progressHeader)

        progressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
        }

        progressCard.addView(
            progressBar,
            LinearLayout.LayoutParams(-1, dp(6)).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
        )

        tvLog = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(69, 82, 96))
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        logScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(tvLog)
        }

        progressCard.addView(
            logScroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        root.addView(
            progressCard,
            LinearLayout.LayoutParams(-1, dp(190))
        )

        setContentView(root)
        refreshPhotoSelectionLabel()
    }

    @Deprecated("Use activity result API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 3 && resultCode == RESULT_OK && data != null) {
            val chosen = mutableListOf<Uri>()

            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let(chosen::add)
                }
            } ?: data.data?.let(chosen::add)

            val readFlag =
                data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION

            selectedPhotoUris = chosen.distinct().toMutableList()

            selectedPhotoUris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, readFlag)
                } catch (_: Exception) {
                }
            }

            prefs.edit()
                .putString(
                    "selectedPhotoUris",
                    selectedPhotoUris.joinToString("\n")
                )
                .apply()

            refreshPhotoSelectionLabel()
            log("已选择 ${selectedPhotoUris.size} 张照片作为工作图片池")
        }

        if (requestCode == 2 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

            contentResolver.takePersistableUriPermission(uri, flags)
            outputTree = uri
            prefs.edit().putString("outputTree", uri.toString()).apply()
            btnFolder.text = "已选择保存位置 · 更改"
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun roundedCard(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(14).toFloat()
        }
    }

    private fun smallLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.rgb(103, 115, 127))
        }
    }

    private fun choosePhotos() {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
                putExtra(
                    MediaStore.EXTRA_PICK_IMAGES_MAX,
                    MediaStore.getPickImagesMaxLimit()
                )
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
        }

        try {
            startActivityForResult(intent, 3)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
            startActivityForResult(fallback, 3)
        }
    }

    private fun clearSelectedPhotos() {
        selectedPhotoUris.clear()
        prefs.edit().remove("selectedPhotoUris").apply()
        refreshPhotoSelectionLabel()
        log("已清空手动选择，将使用日期范围中的照片")
    }

    private fun refreshPhotoSelectionLabel() {
        tvPhotoSelection.text = if (selectedPhotoUris.isEmpty()) {
            "未选择相册照片 · 将按日期范围筛选"
        } else {
            "已选 ${selectedPhotoUris.size} 张 · 运行时按拍摄时间排序"
        }
    }

    private fun updateProgress(total: Int, done: Int, status: String) {
        runOnUiThread {
            tvTaskStatus.text = status
            tvProgressCount.text = "$done / $total"
            progressBar.max = total.coerceAtLeast(1)
            progressBar.progress = done.coerceIn(0, progressBar.max)
        }
    }

    private fun askPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_MEDIA_LOCATION)
        permissions += if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val needed = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1)
        }
    }

    private fun pick(isStart: Boolean) {
        val calendar = Calendar.getInstance()

        DatePickerDialog(
            this,
            { _, year, month, day ->
                val date = Calendar.getInstance().apply {
                    set(
                        year,
                        month,
                        day,
                        if (isStart) 0 else 23,
                        if (isStart) 0 else 59,
                        if (isStart) 0 else 59
                    )
                    set(
                        Calendar.MILLISECOND,
                        if (isStart) 0 else 999
                    )
                }

                if (isStart) {
                    startMs = date.timeInMillis
                    btnStart.text = "开始：$year-${month + 1}-$day"
                } else {
                    endMs = date.timeInMillis
                    btnEnd.text = "结束：$year-${month + 1}-$day"
                }
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun log(message: String) {
        runOnUiThread {
            tvLog.append(message + "\n")

            val lines = tvLog.text.toString().lines()
            if (lines.size > 300) {
                tvLog.text = lines.takeLast(300).joinToString("\n")
            }

            logScroll.post {
                logScroll.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun querySelectedImages(uris: List<Uri>): List<Img> {
        return uris.mapNotNull { uri ->
            try {
                var name = "photo.jpg"
                var taken = 0L
                var modifiedTime = 0L

                val projection = arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                )

                try {
                    contentResolver.query(
                        uri,
                        projection,
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex =
                                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) {
                                name = cursor.getString(nameIndex) ?: name
                            }

                            val takenIndex =
                                cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                            if (takenIndex >= 0 && !cursor.isNull(takenIndex)) {
                                taken = cursor.getLong(takenIndex)
                            }

                            val modifiedIndex =
                                cursor.getColumnIndex(
                                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                                )
                            if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                                modifiedTime = cursor.getLong(modifiedIndex) * 1000L
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Some gallery providers expose only OpenableColumns; EXIF is tried below.
                }

                if (name == "photo.jpg") {
                    try {
                        contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                name = cursor.getString(0) ?: name
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                if (taken <= 0) {
                    val originalDate = try {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            ExifInterface(stream)
                                .getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        }
                    } catch (_: Exception) {
                        null
                    }

                    if (!originalDate.isNullOrBlank()) {
                        taken = try {
                            SimpleDateFormat(
                                "yyyy:MM:dd HH:mm:ss",
                                Locale.US
                            ).parse(originalDate)?.time ?: 0L
                        } catch (_: Exception) {
                            0L
                        }
                    }
                }

                if (taken <= 0) {
                    taken = modifiedTime
                }

                Img(uri, name, taken)
            } catch (e: Exception) {
                log("跳过无法读取的照片：${e.message ?: "未知原因"}")
                null
            }
        }.sortedWith(
            compareBy<Img> {
                if (it.taken > 0) it.taken else Long.MAX_VALUE
            }.thenBy {
                it.name.lowercase(Locale.ROOT)
            }
        )
    }

    private fun query(): List<Img> {
        val output = ArrayList<Img>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val dateTaken = MediaStore.Images.Media.DATE_TAKEN

        contentResolver.query(
            collection,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                dateTaken
            ),
            "$dateTaken BETWEEN ? AND ?",
            arrayOf(startMs.toString(), endMs.toString()),
            "$dateTaken ASC, ${MediaStore.Images.Media._ID} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                output += Img(
                    ContentUris.withAppendedId(collection, id),
                    cursor.getString(1) ?: "x.jpg",
                    cursor.getLong(2)
                )
            }
        }

        return output
    }

    // 字节级复制原照片，不重新编码，尽量保留完整 EXIF。
    private fun openOriginal(uri: Uri) = try {
        contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))
    } catch (_: Exception) {
        contentResolver.openInputStream(uri)
    }

    private fun classifyTheme(
        uri: Uri,
        labeler: ImageLabeler,
        objectDetector: ObjectDetector,
        latinRecognizer: TextRecognizer,
        chineseRecognizer: TextRecognizer
    ): String {
        return try {
            val bitmap = loadOrientedBitmap(uri)

            val detections = try {
                objectDetector.detect(TensorImage.fromBitmap(bitmap))
            } finally {
                bitmap.recycle()
            }

            val detectedLabels = detections
                .flatMap { it.categories }
                .filter { it.score >= 0.25f }
                .map { it.label.lowercase(Locale.ROOT) }

            val personCount = detections.count { detection ->
                detection.categories.any {
                    it.score >= 0.25f &&
                        it.label.equals("person", ignoreCase = true)
                }
            }

            if (personCount >= 2) return "PeopleGroup"
            if (personCount == 1) return "People"

            if (
                detectedLabels.any {
                    it in setOf(
                        "dog", "cat", "bird", "horse", "sheep",
                        "cow", "elephant", "bear", "zebra", "giraffe"
                    )
                }
            ) {
                return "Animal"
            }

            if (
                detectedLabels.any {
                    it in setOf(
                        "bicycle", "car", "motorcycle", "airplane",
                        "bus", "train", "truck", "boat"
                    )
                }
            ) {
                return "Vehicle"
            }

            if (detectedLabels.any { it == "potted plant" }) {
                return "Plant"
            }

            val image = InputImage.fromFilePath(this, uri)
            val labels = Tasks.await(labeler.process(image), 30, TimeUnit.SECONDS)

            val categories = linkedMapOf(
                "PeopleGroup" to setOf(
                    "crowd", "people", "group of people",
                    "people group", "team", "audience", "gathering"
                ),
                "People" to setOf(
                    "person", "human", "man", "woman",
                    "child", "boy", "girl", "face", "portrait"
                ),
                "Animal" to setOf(
                    "animal", "dog", "cat", "bird", "fish",
                    "horse", "insect", "reptile", "mammal", "wildlife"
                ),
                "Vehicle" to setOf(
                    "vehicle", "car", "automobile", "truck", "bus",
                    "van", "motorcycle", "bicycle", "scooter",
                    "train", "aircraft", "airplane", "boat", "ship", "tractor"
                ),
                "Text" to setOf(
                    "text", "sign", "book", "newspaper", "letter",
                    "document", "poster", "label", "menu",
                    "handwriting", "typography"
                ),
                "Plant" to setOf(
                    "plant", "flower", "tree", "leaf", "grass",
                    "foliage", "forest", "shrub", "mushroom"
                ),
                "DetailObject" to setOf(
                    "macro", "close-up", "closeup", "texture",
                    "reflection", "transparent", "glass",
                    "mirror", "detail", "water droplet"
                ),
                "Distant" to setOf(
                    "landscape", "mountain", "horizon", "seascape",
                    "skyline", "valley", "panorama", "countryside", "scenery"
                )
            )

            fun scoreFor(words: Set<String>): Float {
                return labels
                    .filter { label ->
                        val labelText = label.text
                            .lowercase(Locale.ROOT)
                            .replace(Regex("[^a-z0-9]+"), " ")
                            .trim()

                        words.any { word ->
                            val normalizedWord = word
                                .lowercase(Locale.ROOT)
                                .replace(Regex("[^a-z0-9]+"), " ")
                                .trim()

                            " $labelText ".contains(" $normalizedWord ")
                        }
                    }
                    .maxOfOrNull { it.confidence } ?: 0f
            }

            val scores = categories.map { (category, words) ->
                category to scoreFor(words)
            }
            val textScore = scores.first { it.first == "Text" }.second
            val bestSubject = scores
                .filter { it.first != "Text" }
                .maxByOrNull { it.second }
            val subjectScore = bestSubject?.second ?: 0f

            val hasText = imageHasText(uri, latinRecognizer, chineseRecognizer)

            when {
                bestSubject != null &&
                    bestSubject.first in setOf(
                        "PeopleGroup", "People", "Animal", "Vehicle"
                    ) &&
                    subjectScore >= 0.35f -> bestSubject.first

                hasText && subjectScore < 0.60f -> "Text"

                textScore >= 0.35f && textScore >= subjectScore -> "Text"

                bestSubject != null && subjectScore >= 0.35f ->
                    bestSubject.first

                hasText -> "Text"

                else -> "DailyObject"
            }
        } catch (e: Exception) {
            log("分类失败，使用默认标签 DailyObject：${e.message ?: "未知错误"}")
            "DailyObject"
        }
    }

    private fun loadOrientedBitmap(uri: Uri): Bitmap {
        val bytes = contentResolver.openInputStream(uri)?.use {
            it.readBytes()
        } ?: error("无法读取照片")

        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("照片格式不支持")

        val orientation = ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        if (degrees == 0f) return source

        val rotated = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            Matrix().apply { postRotate(degrees) },
            true
        )
        source.recycle()
        return rotated
    }

    private fun imageHasText(
        uri: Uri,
        latinRecognizer: TextRecognizer,
        chineseRecognizer: TextRecognizer
    ): Boolean {
        return try {
            val image = InputImage.fromFilePath(this, uri)
            val latin = Tasks.await(
                latinRecognizer.process(image),
                30,
                TimeUnit.SECONDS
            ).text

            if (latin.count { it.isLetterOrDigit() } >= 2) {
                true
            } else {
                val chinese = Tasks.await(
                    chineseRecognizer.process(image),
                    30,
                    TimeUnit.SECONDS
                ).text
                chinese.count { it.isLetterOrDigit() } >= 2
            }
        } catch (e: Exception) {
            log("文字识别失败：${e.message ?: "未知错误"}")
            false
        }
    }

    private fun run() {
        val chosenUris = selectedPhotoUris.toList()

        if (
            chosenUris.isEmpty() &&
            (startMs == 0L || endMs == 0L || endMs < startMs)
        ) {
            log("请先选择相册照片，或设置有效的拍摄日期范围")
            return
        }

        if (outputTree == null) {
            log("请先选择压缩包保存位置")
            return
        }

        val batchSize = etBatch.text.toString()
            .toIntOrNull()
            ?.takeIf { it > 0 }
            ?: run {
                log("每批张数无效")
                return
            }

        val firstBatch = etStartBatch.text.toString().toIntOrNull() ?: 1
        val folderTemplate = etFolder.text.toString()
        val fileTemplate = etFile.text.toString()

        val names = fileTemplate
            .split(Regex("[,，]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (names.size < batchSize) {
            log("每批张数为 $batchSize，请至少输入 $batchSize 个文件名，并用逗号分隔")
            return
        }

        if (
            names.take(batchSize).any {
                it.contains('/') ||
                    it.contains('\\') ||
                    it == "." ||
                    it == ".."
            }
        ) {
            log("文件名不能包含斜线")
            return
        }

        if (names.take(batchSize).distinct().size != batchSize) {
            log("每批文件名不能重复")
            return
        }

        prefs.edit()
            .putString("batchCount", "$batchSize")
            .putString("startBatch", "$firstBatch")
            .putString("folder", folderTemplate)
            .putString("fileNames", fileTemplate)
            .apply()

        btnRun.isEnabled = false
        btnChoosePhotos.isEnabled = false
        btnClearPhotos.isEnabled = false
        btnFolder.isEnabled = false
        btnStart.isEnabled = false
        btnEnd.isEnabled = false
        etBatch.isEnabled = false
        etStartBatch.isEnabled = false
        etFolder.isEnabled = false
        etFile.isEnabled = false

        tvLog.text = ""
        updateProgress(1, 0, "正在准备照片…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Thread {
            var labeler: ImageLabeler? = null
            var objectDetector: ObjectDetector? = null
            var latinRecognizer: TextRecognizer? = null
            var chineseRecognizer: TextRecognizer? = null

            try {
                val activeLabeler = ImageLabeling.getClient(
                    ImageLabelerOptions.Builder()
                        .setConfidenceThreshold(0.15f)
                        .build()
                )
                labeler = activeLabeler

                val detectorOptions =
                    ObjectDetector.ObjectDetectorOptions.builder()
                        .setScoreThreshold(0.25f)
                        .setMaxResults(20)
                        .build()

                val activeObjectDetector =
                    ObjectDetector.createFromFileAndOptions(
                        this,
                        "efficientdet-lite2.tflite",
                        detectorOptions
                    )
                objectDetector = activeObjectDetector

                val activeLatinRecognizer =
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                latinRecognizer = activeLatinRecognizer

                val activeChineseRecognizer = TextRecognition.getClient(
                    ChineseTextRecognizerOptions.Builder().build()
                )
                chineseRecognizer = activeChineseRecognizer

                val images = if (chosenUris.isNotEmpty()) {
                    querySelectedImages(chosenUris)
                } else {
                    query()
                }

                if (images.isEmpty()) {
                    error("没有可处理的照片，请检查所选工作图片池或日期范围")
                }

                var completed = 0

                updateProgress(
                    images.size,
                    0,
                    "照片已就绪 · 共 ${images.size} 张"
                )

                log(
                    if (chosenUris.isNotEmpty()) {
                        "使用手动选择的工作图片池"
                    } else {
                        "使用拍摄日期筛选结果"
                    }
                )
                log("共 ${images.size} 张，按拍摄时间排序，每批 $batchSize 张")

                images.chunked(batchSize).forEachIndexed { batchIndex, chunk ->
                    val batchNumber = "%03d".format(firstBatch + batchIndex)
                    val baseFolder = folderTemplate.replace(
                        "{batch}",
                        batchNumber
                    )

                    val totalBatches =
                        (images.size + batchSize - 1) / batchSize

                    updateProgress(
                        images.size,
                        completed,
                        "正在识别第 ${batchIndex + 1} / $totalBatches 批"
                    )

                    val theme = classifyTheme(
                        chunk.first().uri,
                        activeLabeler,
                        activeObjectDetector,
                        activeLatinRecognizer,
                        activeChineseRecognizer
                    )

                    val folderName = "${baseFolder}_$theme"
                    log("$baseFolder 分类：$theme")

                    val tree = outputTree ?: error("未选择保存位置")
                    val parent = DocumentsContract.buildDocumentUriUsingTree(
                        tree,
                        DocumentsContract.getTreeDocumentId(tree)
                    )

                    val outputZip = DocumentsContract.createDocument(
                        contentResolver,
                        parent,
                        "application/zip",
                        "$folderName.zip"
                    ) ?: error("无法在所选位置创建压缩包")

                    ZipOutputStream(
                        BufferedOutputStream(
                            contentResolver.openOutputStream(outputZip)
                                ?: error("无法打开压缩包输出流")
                        )
                    ).use { zip ->
                        zip.setLevel(Deflater.BEST_SPEED)

                        chunk.forEachIndexed { index, image ->
                            updateProgress(
                                images.size,
                                completed,
                                "正在打包 ${completed + 1} / ${images.size}"
                            )

                            val extension =
                                image.name.substringAfterLast('.', "jpg")
                            val fileName = names[index]
                            val entry = ZipEntry(
                                "$folderName/$fileName.$extension"
                            )

                            if (image.taken > 0) {
                                entry.time = image.taken
                            }

                            zip.putNextEntry(entry)
                            openOriginal(image.uri)?.use { input ->
                                input.copyTo(zip)
                            } ?: error("无法读取照片：${image.name}")
                            zip.closeEntry()

                            completed++
                            updateProgress(
                                images.size,
                                completed,
                                "正在打包照片"
                            )
                        }
                    }

                    log("✔ $folderName.zip（${chunk.size} 张）")
                }

                log("全部完成 → 所选文件夹")
                updateProgress(images.size, images.size, "任务完成")
            } catch (e: Exception) {
                log("出错：${e.message}")
                updateProgress(1, 0, "任务出错")
            } finally {
                labeler?.close()
                objectDetector?.close()
                latinRecognizer?.close()
                chineseRecognizer?.close()

                runOnUiThread {
                    btnRun.isEnabled = true
                    btnChoosePhotos.isEnabled = true
                    btnClearPhotos.isEnabled = true
                    btnFolder.isEnabled = true
                    btnStart.isEnabled = true
                    btnEnd.isEnabled = true
                    etBatch.isEnabled = true
                    etStartBatch.isEnabled = true
                    etFolder.isEnabled = true
                    etFile.isEnabled = true
                    window.clearFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    )
                }
            }
        }.start()
    }
}
