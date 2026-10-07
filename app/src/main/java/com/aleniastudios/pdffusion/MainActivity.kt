package com.aleniastudios.pdffusion

import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.aleniastudios.pdffusion.databinding.ActivityMainBinding
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val pdfList = mutableListOf<PdfItem>()

    private val selectPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            for (uri in uris) {
                addPdfFromUri(uri)
            }
            updateUi()
        }
    }

    private val savePdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null) {
            processMerge(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSelectPdf.setOnClickListener {
            selectPdfLauncher.launch(arrayOf("application/pdf"))
        }

        binding.btnMergePdf.setOnClickListener {
            if (pdfList.isNotEmpty()) {
                savePdfLauncher.launch("Documentos_Unificados.pdf")
            }
        }

        binding.btnClearAll.setOnClickListener {
            pdfList.clear()
            updateUi()
        }
    }

    private fun addPdfFromUri(uri: Uri) {
        var fileName = "Documento.pdf"
        var fileSize = "0 KB"
        var pageCount = 0

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                if (sizeIndex != -1) fileSize = formatSize(cursor.getLong(sizeIndex))
            }
        }

        try {
            val pfd: ParcelFileDescriptor? = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val renderer = PdfRenderer(pfd)
                pageCount = renderer.pageCount
                renderer.close()
                pfd.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val defaultSelection = if (fileName.lowercase().contains("estado de cuenta")) {
            PageSelection.FIRST_ONLY
        } else {
            PageSelection.ALL
        }

        pdfList.add(PdfItem(uri, fileName, fileSize, pageCount, defaultSelection))
    }

    private fun formatSize(sizeInBytes: Long): String {
        val kb = sizeInBytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format("%.1f MB", mb)
        } else {
            String.format("%.0f KB", kb)
        }
    }

    private fun updateUi() {
        binding.txtCount.text = "Archivos cargados: ${pdfList.size}"
        binding.btnMergePdf.isEnabled = pdfList.isNotEmpty()
        binding.btnClearAll.isEnabled = pdfList.isNotEmpty()
        renderListSummary()
    }

    private fun renderListSummary() {
        val sb = StringBuilder()
        pdfList.forEachIndexed { index, item ->
            val pagesText = if (item.selection == PageSelection.FIRST_ONLY) "Solo pág 1" else "Todas (${item.totalPages} págs)"
            sb.append("${index + 1}. ${item.fileName} -> $pagesText\n")
        }
        binding.txtSummary.text = if (sb.isNotEmpty()) sb.toString() else "Ningún archivo seleccionado"
    }

    private fun processMerge(destinationUri: Uri) {
        try {
            val outputStream: OutputStream? = contentResolver.openOutputStream(destinationUri)
            if (outputStream != null) {
                val success = PdfMergerEngine.mergePdfs(this, pdfList, outputStream)
                outputStream.close()
                if (success) {
                    Toast.makeText(this, "¡PDF unificado creado con éxito!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Error al unificar PDFs", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
