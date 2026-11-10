package com.aleniastudios.pdffusion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.OutputStream

object PdfMergerEngine {

    private const val MAX_IMAGE_DIMENSION = 2480

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
                    val bitmap = decodeSampledAndOrientedBitmap(context, item.uri)
                    if (bitmap != null) {
                        val imgWidth = bitmap.width.toFloat()
                        val imgHeight = bitmap.height.toFloat()
                        
                        val a4Width = PDRectangle.A4.width
                        val a4Height = PDRectangle.A4.height
                        
                        val scaleX = a4Width / imgWidth
                        val scaleY = a4Height / imgHeight
                        val scale = minOf(scaleX, scaleY)
                        
                        val scaledWidth = imgWidth * scale
                        val scaledHeight = imgHeight * scale
                        
                        val page = PDPage(PDRectangle.A4)
                        mergedDocument.addPage(page)
                        
                        val startX = (a4Width - scaledWidth) / 2f
                        val startY = (a4Height - scaledHeight) / 2f
                        
                        val pdImage = JPEGFactory.createFromImage(mergedDocument, bitmap, 0.8f)
                        val contentStream = PDPageContentStream(mergedDocument, page)
                        contentStream.drawImage(pdImage, startX, startY, scaledWidth, scaledHeight)
                        contentStream.close()
                        bitmap.recycle()
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
                        // Keep sourceDoc open until mergedDocument is saved
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

    private fun decodeSampledAndOrientedBitmap(context: Context, uri: Uri): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            return null
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                boundsOptions.outWidth,
                boundsOptions.outHeight,
                MAX_IMAGE_DIMENSION,
                MAX_IMAGE_DIMENSION
            )
        }

        val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            try {
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return rawBitmap
        }

        return try {
            val rotated = Bitmap.createBitmap(
                rawBitmap,
                0,
                0,
                rawBitmap.width,
                rawBitmap.height,
                matrix,
                true
            )
            if (rotated != rawBitmap) {
                rawBitmap.recycle()
            }
            rotated
        } catch (_: OutOfMemoryError) {
            rawBitmap
        }
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
