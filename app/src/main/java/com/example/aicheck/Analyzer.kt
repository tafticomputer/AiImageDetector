package com.example.aicheck

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Verdict { LIKELY_AI, SUSPICIOUS, NO_EVIDENCE }

/** weight > 0: evidence for AI, weight < 0: evidence for a real camera, 0: informational. */
data class Finding(val text: String, val weight: Int)

data class Report(
    val score: Int,
    val verdict: Verdict,
    val findings: List<Finding>,
    val width: Int = 0,
    val height: Int = 0
)

object Scoring {
    private const val BASE = 10
    private const val STRONG = 70

    fun verdictFor(score: Int): Verdict = when {
        score >= 75 -> Verdict.LIKELY_AI
        score >= 40 -> Verdict.SUSPICIOUS
        else -> Verdict.NO_EVIDENCE
    }

    fun build(findings: List<Finding>, width: Int = 0, height: Int = 0): Report {
        var score = (BASE + findings.sumOf { it.weight }).coerceIn(0, 100)
        // An explicit AI marker in the file dominates weak "real camera" hints.
        if (findings.any { it.weight >= STRONG }) score = max(score, 85)
        return Report(
            score = score,
            verdict = verdictFor(score),
            findings = findings.sortedByDescending { it.weight },
            width = width,
            height = height
        )
    }
}

/** Looks for explicit traces of AI tools inside file metadata (XMP, IPTC, PNG text chunks, C2PA...). */
object MetadataScanner {
    private class Marker(val needle: String, val label: String)

    private val generators = listOf(
        Marker("midjourney", "Midjourney"),
        Marker("dall-e", "DALL·E (OpenAI)"),
        Marker("openai", "OpenAI"),
        Marker("chatgpt", "ChatGPT"),
        Marker("gpt-4o", "GPT-4o"),
        Marker("stable diffusion", "Stable Diffusion"),
        Marker("stability.ai", "Stability AI"),
        Marker("dreamstudio", "DreamStudio"),
        Marker("adobe firefly", "Adobe Firefly"),
        Marker("novelai", "NovelAI"),
        Marker("comfyui", "ComfyUI"),
        Marker("automatic1111", "AUTOMATIC1111"),
        Marker("invokeai", "InvokeAI"),
        Marker("leonardo.ai", "Leonardo.Ai"),
        Marker("ideogram", "Ideogram"),
        Marker("craiyon", "Craiyon"),
        Marker("nightcafe", "NightCafe"),
        Marker("bing image creator", "Bing Image Creator"),
        Marker("black forest labs", "Black Forest Labs (FLUX)"),
        Marker("made with google ai", "Google AI")
    )

    fun scan(bytes: ByteArray): List<Finding> {
        val len = min(bytes.size, 12_000_000)
        return scanText(String(bytes, 0, len, Charsets.ISO_8859_1))
    }

    fun scanText(raw: String): List<Finding> {
        val t = raw.lowercase()
        val out = mutableListOf<Finding>()

        if (t.contains("algorithmicmedia")) {
            out += Finding(
                "برچسب استاندارد IPTC در متادیتا: محتوا با مدل هوش مصنوعی ساخته یا ترکیب شده است",
                75
            )
        }
        val names = generators.filter { t.contains(it.needle) }.map { it.label }
        if (names.isNotEmpty()) {
            out += Finding("نام ابزار تولید تصویر در متادیتا: ${names.joinToString("، ")}", 75)
        }
        if (t.contains("negative prompt") || (t.contains("steps:") && t.contains("sampler:"))) {
            out += Finding("پارامترهای تولید تصویر (پرامپت / Steps / Sampler) داخل فایل ذخیره شده است", 75)
        }
        if (t.contains("\"class_type\"") && t.contains("ksampler")) {
            out += Finding("گراف ComfyUI داخل فایل ذخیره شده است", 75)
        }
        if (t.contains("c2pa") || t.contains("jumbf")) {
            out += Finding(
                "اطلاعات Content Credentials (C2PA) وجود دارد؛ فقط اگر ابزار هوش مصنوعی در آن ذکر شده باشد نشانه‌ی تولید مصنوعی است",
                0
            )
        }
        return out
    }
}

object ImageAnalyzer {

    private class ExifInfo(
        val make: String?,
        val model: String?,
        val hasShotData: Boolean,
        val hasAny: Boolean
    )

    fun analyze(bytes: ByteArray): Report {
        val findings = mutableListOf<Finding>()
        findings += MetadataScanner.scan(bytes)

        // --- EXIF / camera information ---
        val exif = readExif(bytes)
        val hasCamera = !exif.make.isNullOrBlank() || !exif.model.isNullOrBlank()
        if (hasCamera) {
            val cam = listOfNotNull(exif.make, exif.model).joinToString(" ").trim()
            if (exif.hasShotData) {
                findings += Finding("اطلاعات دوربین و پارامترهای عکاسی (EXIF) وجود دارد: $cam (قابل جعل است)", -20)
            } else {
                findings += Finding("نام دوربین در EXIF وجود دارد: $cam (قابل جعل است)", -10)
            }
        } else if (!exif.hasAny) {
            findings += Finding(
                "هیچ اطلاعات دوربین (EXIF) در فایل نیست؛ در تصاویر ساخته‌شده رایج است ولی شبکه‌های اجتماعی هم آن را حذف می‌کنند",
                8
            )
        }

        // --- Dimensions ---
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) {
            findings += Finding("تصویر قابل خواندن نیست یا فرمت آن پشتیبانی نمی‌شود", 0)
            return Scoring.build(findings)
        }
        if (!hasCamera && min(w, h) >= 512 && w % 64 == 0 && h % 64 == 0) {
            findings += Finding(
                "ابعاد تصویر ($w×$h) مضرب ۶۴ است؛ اندازه‌ی رایج خروجی مولدهای تصویر (نشانه‌ی ضعیف)",
                10
            )
        }

        // --- Pixel statistics (weak signal) ---
        try {
            var sample = 1
            while ((w / sample).toLong() * (h / sample) > 8_000_000L) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (bmp != null) {
                val stats = noiseStats(bmp)
                bmp.recycle()
                if (stats != null) {
                    val (median, cv) = stats
                    if (median < 1.0) {
                        findings += Finding(
                            "نویز تصویر بسیار کم و سطح تصویر بیش‌ازحد صاف است (نشانه‌ی ضعیف؛ گرافیک و تصاویر روتوش‌شده هم این‌گونه‌اند)",
                            12
                        )
                    } else if (cv < 0.35) {
                        findings += Finding(
                            "نویز تصویر در همه‌ی نواحی بسیار یکنواخت است (نشانه‌ی ضعیف)",
                            6
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            // Out of memory or decode problem: skip the pixel statistics.
        }

        return Scoring.build(findings, w, h)
    }

    private fun readExif(bytes: ByteArray): ExifInfo {
        return try {
            val e = ExifInterface(ByteArrayInputStream(bytes))
            val make = e.getAttribute(ExifInterface.TAG_MAKE)?.trim()
            val model = e.getAttribute(ExifInterface.TAG_MODEL)?.trim()
            val shot = listOf(
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                ExifInterface.TAG_FOCAL_LENGTH
            ).any { !e.getAttribute(it).isNullOrBlank() }
            val any = !make.isNullOrBlank() || !model.isNullOrBlank() || shot ||
                !e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).isNullOrBlank()
            ExifInfo(make, model, shot, any)
        } catch (t: Throwable) {
            ExifInfo(null, null, false, false)
        }
    }

    /** Returns (median block noise sigma, coefficient of variation across blocks) or null. */
    private fun noiseStats(bmp: Bitmap): Pair<Double, Double>? {
        val size = min(512, min(bmp.width, bmp.height))
        if (size < 128) return null
        val x0 = (bmp.width - size) / 2
        val y0 = (bmp.height - size) / 2
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, x0, y0, size, size)
        val lum = FloatArray(size * size) { i ->
            val c = px[i]
            0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }

        val bs = 32
        val nb = size / bs
        val sum = DoubleArray(nb * nb)
        val sq = DoubleArray(nb * nb)
        val cnt = IntArray(nb * nb)
        for (y in 1 until size - 1) {
            val byi = y / bs
            if (byi >= nb) continue
            for (x in 1 until size - 1) {
                val bxi = x / bs
                if (bxi >= nb) continue
                val i = y * size + x
                val r = lum[i] - 0.25f * (lum[i - 1] + lum[i + 1] + lum[i - size] + lum[i + size])
                val b = byi * nb + bxi
                sum[b] += r.toDouble()
                sq[b] += (r * r).toDouble()
                cnt[b]++
            }
        }
        val sigmas = ArrayList<Double>()
        for (b in 0 until nb * nb) {
            if (cnt[b] > 100) {
                val m = sum[b] / cnt[b]
                sigmas.add(sqrt(max(0.0, sq[b] / cnt[b] - m * m)))
            }
        }
        if (sigmas.size < 4) return null
        sigmas.sort()
        val median = sigmas[sigmas.size / 2]
        val mean = sigmas.average()
        val sd = sqrt(sigmas.sumOf { (it - mean) * (it - mean) } / sigmas.size)
        return Pair(median, if (mean > 0) sd / mean else 0.0)
    }
}

object PdfAnalyzer {

    fun analyze(raw: ByteArray): Report {
        val text = String(raw, Charsets.ISO_8859_1)
        if (!text.take(1024).contains("%PDF")) {
            throw IllegalArgumentException("فایل انتخاب‌شده یک PDF معتبر نیست")
        }

        // --- Document metadata (Info dictionary + XMP) ---
        val metaFindings = mutableListOf<Finding>()
        val meta = StringBuilder()
        for (key in listOf("Producer", "Creator", "Title", "Subject", "Keywords", "Author")) {
            val m = Regex("/$key\\s*\\(([^)]{0,300})\\)").find(text) ?: continue
            val value = m.groupValues[1].trim()
            meta.append(value).append('\n')
            if (key == "Producer" || key == "Creator") {
                metaFindings += Finding("$key در PDF: $value", 0)
            }
        }
        var from = 0
        var xmpCount = 0
        while (xmpCount < 3) {
            val s = text.indexOf("<x:xmpmeta", from)
            if (s < 0) break
            val e = text.indexOf("</x:xmpmeta>", s)
            val end = if (e < 0) min(text.length, s + 30_000) else min(e, s + 30_000)
            meta.append(text, s, end).append('\n')
            from = end
            xmpCount++
        }
        metaFindings += MetadataScanner.scanText(meta.toString())
        val metaReport = Scoring.build(metaFindings)

        // --- Embedded JPEG images ---
        var best = 0
        var index = 0
        val imageLines = mutableListOf<Finding>()
        for (jpeg in extractJpegs(text, raw, 8)) {
            val r = ImageAnalyzer.analyze(jpeg)
            if (r.width < 150 || r.height < 150) continue
            index++
            val top = r.findings.firstOrNull { it.weight != 0 }
            val reason = if (top != null) " — ${top.text}" else ""
            imageLines += Finding("تصویر $index (${r.width}×${r.height}): امتیاز ${r.score}$reason", 0)
            best = max(best, r.score)
        }
        if (index == 0) {
            imageLines += Finding(
                "تصویر JPEG قابل‌استخراجی در PDF پیدا نشد و فقط متادیتای سند بررسی شد. " +
                    "اگر تصاویر با فشرده‌سازی دیگری ذخیره شده‌اند، آن را جدا ذخیره کنید و از «انتخاب تصویر» استفاده کنید",
                0
            )
        }

        val score = max(metaReport.score, best)
        return Report(
            score = score,
            verdict = Scoring.verdictFor(score),
            findings = metaReport.findings + imageLines
        )
    }

    private fun extractJpegs(text: String, raw: ByteArray, maxCount: Int): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var pos = 0
        while (out.size < maxCount) {
            val d = text.indexOf("/DCTDecode", pos)
            if (d < 0) break
            val s = text.indexOf("stream", d)
            if (s < 0) break
            var start = s + 6
            if (start < raw.size && raw[start] == '\r'.code.toByte()) start++
            if (start < raw.size && raw[start] == '\n'.code.toByte()) start++
            val e = text.indexOf("endstream", start)
            if (e < 0) break
            pos = e + 9
            val length = e - start
            if (length > 1000 && length < 40_000_000 &&
                raw[start] == 0xFF.toByte() && raw[start + 1] == 0xD8.toByte()
            ) {
                out += raw.copyOfRange(start, e)
            }
        }
        return out
    }
}
