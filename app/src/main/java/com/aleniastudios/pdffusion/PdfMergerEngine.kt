package com.aleniastudios.pdffusion

import android.content.Context
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.OutputStream

object PdfMergerEngine {

    fun mergePdfs(
        context: Context,
        items: List<PdfItem>,
        outputStream: OutputStream
    ): Boolean {
        val mergedDocument = PDDocument()
        val sourceDocs = mutableListOf<PDDocument>()
        
        try {
            for (item in items) {
                val mimeType = context.contentResolver.getType(item.uri) ?: ""
                
                if (mimeType.startsWith("image/")) {
                    context.contentResolver.openInputStream(item.uri)?.use { inputStream ->
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        if (bitmap != null) {
                            val imgWidth = bitmap.width.toFloat()
                            val imgHeight = bitmap.height.toFloat()
                            
                            val a4Width = PDRectangle.A4.width
                            val a4Height = PDRectangle.A4.height
                            
                            val scaleX = a4Width / imgWidth
                            val scaleY = a4Height / imgHeight
                            // Scale down if image is larger than A4, or scale up? Let's fit within A4.
                            val scale = minOf(scaleX, scaleY) 
                            
                            val scaledWidth = imgWidth * scale
                            val scaledHeight = imgHeight * scale
                            
                            // Create standard A4 page so it doesn't look small compared to others
                            val page = PDPage(PDRectangle.A4)
                            mergedDocument.addPage(page)
                            
                            // Center the image
                            val startX = (a4Width - scaledWidth) / 2f
                            val startY = (a4Height - scaledHeight) / 2f
                            
                            // Use JPEGFactory with 0.8f quality
                            val pdImage = JPEGFactory.createFromImage(mergedDocument, bitmap, 0.8f)
                            val contentStream = PDPageContentStream(mergedDocument, page)
                            contentStream.drawImage(pdImage, startX, startY, scaledWidth, scaledHeight)
                            contentStream.close()
                            bitmap.recycle()
                        }
                    }
                } else {
                    context.contentResolver.openInputStream(item.uri)?.use { inputStream ->
                        val sourceDoc = PDDocument.load(inputStream)
                        sourceDocs.add(sourceDoc)
                        val pageCount = sourceDoc.numberOfPages
                        
                        val pagesToInclude = when (item.selection) {
                            PageSelection.FIRST_ONLY -> listOf(0)
                            PageSelection.ALL -> (0 until pageCount).toList()
                        }
                        
                        for (pageIndex in pagesToInclude) {
                            if (pageIndex < pageCount) {
                                val page = sourceDoc.getPage(pageIndex)
                                mergedDocument.importPage(page)
                            }
                        }
                        // Do NOT close sourceDoc here, it needs to be open when mergedDocument is saved
                    }
                }
            }
            
            mergedDocument.save(outputStream)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            return false
        } finally {
            mergedDocument.close()
            for (doc in sourceDocs) {
                try {
                    doc.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
