package com.example.aicheck

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var btnImage: MaterialButton
    private lateinit var btnPdf: MaterialButton
    private lateinit var fileName: TextView
    private lateinit var preview: ImageView
    private lateinit var busy: View
    private lateinit var resultCard: View
    private lateinit var verdictText: TextView
    private lateinit var scoreText: TextView
    private lateinit var scoreBar: ProgressBar
    private lateinit var findingsText: TextView

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) handle(uri, isPdf = false)
        }

    private val pickPdf =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) handle(uri, isPdf = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnImage = findViewById(R.id.btnImage)
        btnPdf = findViewById(R.id.btnPdf)
        fileName = findViewById(R.id.fileName)
        preview = findViewById(R.id.preview)
        busy = findViewById(R.id.busy)
        resultCard = findViewById(R.id.resultCard)
        verdictText = findViewById(R.id.verdictText)
        scoreText = findViewById(R.id.scoreText)
        scoreBar = findViewById(R.id.scoreBar)
        findingsText = findViewById(R.id.findingsText)

        btnImage.setOnClickListener { pickImage.launch("image/*") }
        btnPdf.setOnClickListener { pickPdf.launch("application/pdf") }
    }

    private fun handle(uri: Uri, isPdf: Boolean) {
        setBusy(true)
        resultCard.visibility = View.GONE
        fileName.text = queryName(uri)
        preview.visibility = View.GONE

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = readBytes(uri, if (isPdf) 60_000_000 else 40_000_000)
                    if (isPdf) PdfAnalyzer.analyze(bytes) else ImageAnalyzer.analyze(bytes)
                }
            }
            if (!isPdf) {
                val bmp = withContext(Dispatchers.IO) { decodePreview(uri) }
                if (bmp != null) {
                    preview.setImageBitmap(bmp)
                    preview.visibility = View.VISIBLE
                }
            }
            setBusy(false)

            val report = outcome.getOrNull()
            if (report != null) {
                showReport(report)
            } else {
                val msg = outcome.exceptionOrNull()?.message ?: "نامشخص"
                showError(getString(R.string.error_fmt, msg))
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy.visibility = if (value) View.VISIBLE else View.GONE
        btnImage.isEnabled = !value
        btnPdf.isEnabled = !value
    }

    private fun showReport(report: Report) {
        val (label, color) = when (report.verdict) {
            Verdict.LIKELY_AI -> R.string.verdict_ai to R.color.verdict_ai
            Verdict.SUSPICIOUS -> R.string.verdict_suspicious to R.color.verdict_suspicious
            Verdict.NO_EVIDENCE -> R.string.verdict_clear to R.color.verdict_clear
        }
        verdictText.setText(label)
        verdictText.setBackgroundColor(ContextCompat.getColor(this, color))
        scoreText.text = getString(R.string.score_fmt, report.score)
        scoreBar.progress = report.score
        findingsText.text = report.findings.joinToString("\n\n") { f ->
            val mark = when {
                f.weight > 0 -> "⚠"
                f.weight < 0 -> "✓"
                else -> "•"
            }
            "$mark ${f.text}"
        }
        resultCard.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        verdictText.text = message
        verdictText.setBackgroundColor(ContextCompat.getColor(this, R.color.verdict_suspicious))
        scoreText.text = ""
        scoreBar.progress = 0
        findingsText.text = ""
        resultCard.visibility = View.VISIBLE
    }

    private fun queryName(uri: Uri): String {
        val name: String? = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
        return name ?: (uri.lastPathSegment ?: "")
    }

    private fun readBytes(uri: Uri, max: Int): ByteArray {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("فایل باز نشد")
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw IllegalStateException("حجم فایل بیشتر از حد مجاز است")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    private fun decodePreview(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var s = 1
            while (bounds.outWidth / s > 1200 || bounds.outHeight / s > 1200) s *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = s }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (t: Throwable) {
            null
        }
    }
}
