package ru.petrovich.telemetry.util

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Простой одностраничный PDF-отчёт (заголовок + текст) — то, что Петрович прикладывает к письму.
 * Не замена полноценной выгрузке, но настоящий файл, а не выдуманная ссылка.
 */
object ReportPdf {
    private const val PAGE_W = 595 // A4 72dpi
    private const val PAGE_H = 842
    private const val MARGIN = 48f

    fun build(context: Context, fileName: String, title: String, body: String): Uri {
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        val canvas = page.canvas

        val titlePaint = TextPaint().apply { isAntiAlias = true; textSize = 18f; color = 0xFF1B1F24.toInt(); isFakeBoldText = true }
        val metaPaint = TextPaint().apply { isAntiAlias = true; textSize = 10f; color = 0xFF5B616B.toInt() }
        val bodyPaint = TextPaint().apply { isAntiAlias = true; textSize = 12f; color = 0xFF1B1F24.toInt() }

        var y = MARGIN
        canvas.drawText("Петрович", MARGIN, y, metaPaint)
        y += 20f
        canvas.drawText(title, MARGIN, y, titlePaint)
        y += 16f
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, Paint().apply { color = 0xFFE4DED2.toInt() })
        y += 20f

        val layout = StaticLayout.Builder.obtain(body, 0, body.length, bodyPaint, (PAGE_W - MARGIN * 2).toInt())
            .setLineSpacing(4f, 1.15f)
            .build()
        canvas.save()
        canvas.translate(MARGIN, y)
        layout.draw(canvas)
        canvas.restore()

        document.finishPage(page)
        val file = File(context.cacheDir, "reports").apply { mkdirs() }.resolve(fileName)
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
