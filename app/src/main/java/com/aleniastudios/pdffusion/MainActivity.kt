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
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.OutputStream
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.View

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
        
        // Initialize PDFBox
        PDFBoxResourceLoader.init(applicationContext)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSelectPdf.setOnClickListener {
            // Allow both PDFs and images
            selectPdfLauncher.launch(arrayOf("application/pdf", "image/*"))
        }

        binding.btnMergePdf.setOnClickListener {
            if (pdfList.isNotEmpty()) {
                savePdfLauncher.launch(getString(R.string.default_merged_filename))
            }
        }

        binding.btnClearAll.setOnClickListener {
            pdfList.clear()
            updateUi()
        }

        checkAndShowTutorial()
    }

    private fun checkAndShowTutorial() {
        val prefs = getSharedPreferences("pdf_fusion_prefs", android.content.Context.MODE_PRIVATE)
        val hasSeenTutorial = prefs.getBoolean("has_seen_tutorial", false)
        if (!hasSeenTutorial) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tutorial_title)
                .setMessage(R.string.tutorial_desc)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    prefs.edit().putBoolean("has_seen_tutorial", true).apply()
                    dialog.dismiss()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun addPdfFromUri(uri: Uri) {
        var fileName = getString(R.string.file_default_name)
        var fileSize = "0 KB"
        var pageCount = 0

        val mimeType = contentResolver.getType(uri) ?: ""
        val isImage = mimeType.startsWith("image/")

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                if (sizeIndex != -1) fileSize = formatSize(cursor.getLong(sizeIndex))
            }
        }

        if (isImage) {
            pageCount = 1
        } else {
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
        }

        val defaultSelection = if (fileName.lowercase().contains("estado de cuenta") && !isImage) {
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
        binding.txtCount.text = getString(R.string.files_loaded_count, pdfList.size)
        binding.btnMergePdf.isEnabled = pdfList.isNotEmpty()
        binding.btnClearAll.isEnabled = pdfList.isNotEmpty()
        renderListSummary()
    }

    private fun renderListSummary() {
        val sb = StringBuilder()
        pdfList.forEachIndexed { index, item ->
            val pagesText = if (item.selection == PageSelection.FIRST_ONLY) getString(R.string.only_page_1) else getString(R.string.all_pages_count, item.totalPages)
            sb.append("${index + 1}. ${item.fileName} -> $pagesText\n")
        }
        binding.txtSummary.text = if (sb.isNotEmpty()) sb.toString() else getString(R.string.no_file_selected_short)
    }

    private fun processMerge(destinationUri: Uri) {
        binding.progressLayout.visibility = View.VISIBLE
        binding.btnMergePdf.isEnabled = false
        binding.btnClearAll.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val outputStream: OutputStream? = contentResolver.openOutputStream(destinationUri)
                if (outputStream != null) {
                    val success = PdfMergerEngine.mergePdfs(this@MainActivity, pdfList, outputStream)
                    outputStream.close()
                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(this@MainActivity, getString(R.string.merge_success), Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, getString(R.string.merge_error), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.merge_error_with_msg, e.message), Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.progressLayout.visibility = View.GONE
                    updateUi()
                }
            }
        }
    }
}
