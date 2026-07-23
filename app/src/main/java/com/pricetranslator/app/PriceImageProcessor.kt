package com.pricetranslator.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.max

object PriceImageProcessor {
    private val explicitPricePattern = Regex(
        "^(?:USD\\s*|\\$\\s*)(\\d{1,3}(?:[ ,.]\\d{3})*(?:[.,]\\d{1,2})?|\\d{1,6}(?:[.,]\\d{1,2})?)$",
        RegexOption.IGNORE_CASE
    )

    private val plainPricePattern = Regex("^(\\d{1,6}(?:[.,]\\d{1,2})?)$")
    private val lettersPattern = Regex("[A-Za-zА-Яа-я]")
    private val dateOrTimePattern = Regex("[/:%]|\\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\b", RegexOption.IGNORE_CASE)

    fun process(
        bitmap: Bitmap,
        rate: ExchangeRateRepository.Rate,
        onSuccess: (Bitmap, Int) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                try {
                    val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = Canvas(output)
                    val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    var count = 0

                    result.textBlocks.flatMap { it.lines }.forEach { line ->
                        val lineText = line.text.trim()
                        val lineContainsContext = lettersPattern.containsMatchIn(lineText) ||
                            dateOrTimePattern.containsMatchIn(lineText)

                        line.elements.forEach { element ->
                            val text = element.text.trim()
                            val compact = text.replace(" ", "")
                            val explicitMatch = explicitPricePattern.matchEntire(text)
                            val plainMatch = plainPricePattern.matchEntire(compact)

                            val numericText = when {
                                explicitMatch != null -> explicitMatch.groupValues[1]
                                plainMatch != null && !lineContainsContext -> plainMatch.groupValues[1]
                                else -> return@forEach
                            }

                            val amount = parseAmount(numericText) ?: return@forEach
                            if (amount <= 0.0) return@forEach

                            val box = element.boundingBox ?: return@forEach
                            val label = formatCurrency(amount * rate.value, rate.quote)
                            val padded = Rect(
                                max(0, box.left - 6),
                                max(0, box.top - 4),
                                minOf(output.width, box.right + 6),
                                minOf(output.height, box.bottom + 4)
                            )
                            canvas.drawRect(padded, backgroundPaint)
                            textPaint.textSize = max(18f, box.height() * 0.8f)
                            while (textPaint.measureText(label) > padded.width() && textPaint.textSize > 12f) {
                                textPaint.textSize -= 1f
                            }
                            val baseline = padded.centerY() - (textPaint.ascent() + textPaint.descent()) / 2
                            canvas.drawText(label, padded.left.toFloat(), baseline, textPaint)
                            count++
                        }
                    }

                    val footerHeight = max(52, output.height / 18)
                    val withFooter = Bitmap.createBitmap(output.width, output.height + footerHeight, Bitmap.Config.ARGB_8888)
                    val footerCanvas = Canvas(withFooter)
                    footerCanvas.drawColor(Color.WHITE)
                    footerCanvas.drawBitmap(output, 0f, 0f, null)
                    val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.DKGRAY
                        textSize = max(18f, footerHeight * 0.34f)
                    }
                    val footer = "Rate: 1 ${rate.base} = ${"%.4f".format(Locale.US, rate.value)} ${rate.quote} · ${rate.date}"
                    footerCanvas.drawText(footer, 16f, output.height + footerHeight * 0.65f, footerPaint)
                    onSuccess(withFooter, count)
                } catch (t: Throwable) {
                    onFailure(t)
                } finally {
                    recognizer.close()
                }
            }
            .addOnFailureListener {
                recognizer.close()
                onFailure(it)
            }
    }

    private fun parseAmount(raw: String): Double? {
        val normalized = raw.trim().replace(" ", "")
        return when {
            normalized.count { it == ',' } == 1 && normalized.substringAfter(',').length <= 2 ->
                normalized.replace(',', '.').toDoubleOrNull()
            normalized.count { it == '.' } == 1 && normalized.substringAfter('.').length <= 2 ->
                normalized.toDoubleOrNull()
            else -> normalized.replace(",", "").toDoubleOrNull()
        }
    }

    private fun formatCurrency(value: Double, code: String): String = runCatching {
        NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
            currency = Currency.getInstance(code)
            maximumFractionDigits = if (code == "JPY") 0 else 2
        }.format(value)
    }.getOrElse { "${"%.2f".format(Locale.US, value)} $code" }
}
