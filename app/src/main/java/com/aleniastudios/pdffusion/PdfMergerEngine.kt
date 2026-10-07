package com.aleniastudios.pdffusion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.OutputStream

object PdfMergerEngine {

    fun mergePdfs(
        context: Context,
        items: List<PdfItem>,
        outputStream: OutputStream
    ): Boolean {
        val pdfDocument = PdfDocument()

        try {
            for (item in items) {
                val pfd: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(item.uri, "r")
                if (pfd != null) {
                    val renderer = PdfRenderer(pfd)
                    val pageCount = renderer.pageCount

                    val pagesToInclude = when (item.selection) {
                        PageSelection.FIRST_ONLY -> listOf(0)
                        PageSelection.ALL -> (0 until pageCount).toList()
                    }

                    for (pageIndex in pagesToInclude) {
                        if (pageIndex < pageCount) {
                            val page = renderer.openPage(pageIndex)
                            val width = page.width
                            val height = page.height

                            val pageInfo = PdfDocument.PageInfo.Builder(width, height, pdfDocument.pages.size + 1).create()
                            val newPage = pdfDocument.startPage(pageInfo)

                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                            val canvas: Canvas = newPage.canvas
                            canvas.drawBitmap(bitmap, 0f, 0f, null)

                            pdfDocument.finishPage(newPage)
                            bitmap.recycle()
                            page.close()
                        }
                    }
                    renderer.close()
                    pfd.close()
                }
            }

            pdfDocument.writeTo(outputStream)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            pdfDocument.close()
        }
    }
}
