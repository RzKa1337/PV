package com.solartracker.pro.energy

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/** Plain multi-page A4 text report (no external libraries). */
object PdfReport {
    private const val WIDTH = 595
    private const val HEIGHT = 842
    private const val MARGIN = 40f
    private const val LINE = 14f

    fun write(title: String, lines: List<String>, out: OutputStream) {
        val doc = PdfDocument()
        val titlePaint = Paint().apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val body = Paint().apply { textSize = 9.5f; typeface = Typeface.MONOSPACE; isAntiAlias = true }
        val maxChars = ((WIDTH - 2 * MARGIN) / body.measureText("M")).toInt().coerceAtLeast(40)
        val wrapped = lines.flatMap { l -> if (l.isEmpty()) listOf("") else l.chunked(maxChars) }
        var pageNo = 0
        var i = 0
        try {
            do {
                pageNo++
                val page = doc.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, pageNo).create())
                val c = page.canvas
                var y = MARGIN + 16f
                if (pageNo == 1) { c.drawText(title, MARGIN, y, titlePaint); y += 24f }
                while (i < wrapped.size && y < HEIGHT - MARGIN) {
                    c.drawText(wrapped[i], MARGIN, y, body)
                    y += LINE
                    i++
                }
                c.drawText("— $pageNo —", WIDTH / 2f - 12f, HEIGHT - 20f, body)
                doc.finishPage(page)
            } while (i < wrapped.size)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
