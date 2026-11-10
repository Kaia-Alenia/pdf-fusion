package com.aleniastudios.pdffusion

import android.content.res.ColorStateList
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aleniastudios.pdffusion.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val pdfList = mutableListOf<PdfItem>()

    companion object {
        private const val STATE_URIS = "state_uris"
        private const val STATE_NAMES = "state_names"
        private const val STATE_SIZES = "state_sizes"
        private const val STATE_PAGES = "state_pages"
        private const val STATE_SELECTIONS = "state_selections"
    }

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

        if (savedInstanceState != null) {
            restoreListState(savedInstanceState)
        }

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

        updateUi()
        checkAndShowTutorial()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(STATE_URIS, ArrayList(pdfList.map { it.uri }))
        outState.putStringArrayList(STATE_NAMES, ArrayList(pdfList.map { it.fileName }))
        outState.putStringArrayList(STATE_SIZES, ArrayList(pdfList.map { it.fileSize }))
        outState.putIntegerArrayList(STATE_PAGES, ArrayList(pdfList.map { it.totalPages }))
        outState.putStringArrayList(STATE_SELECTIONS, ArrayList(pdfList.map { it.selection.name }))
    }

    private fun restoreListState(savedInstanceState: Bundle) {
        val uris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            savedInstanceState.getParcelableArrayList(STATE_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            savedInstanceState.getParcelableArrayList(STATE_URIS)
        }
        val names = savedInstanceState.getStringArrayList(STATE_NAMES)
        val sizes = savedInstanceState.getStringArrayList(STATE_SIZES)
        val pages = savedInstanceState.getIntegerArrayList(STATE_PAGES)
        val selections = savedInstanceState.getStringArrayList(STATE_SELECTIONS)

        if (uris != null && names != null && sizes != null && pages != null && selections != null) {
            pdfList.clear()
            val count = minOf(uris.size, names.size, sizes.size, pages.size, selections.size)
            for (i in 0 until count) {
                val selection = runCatching { PageSelection.valueOf(selections[i]) }
                    .getOrDefault(PageSelection.ALL)
                pdfList.add(PdfItem(uris[i], names[i], sizes[i], pages[i], selection))
            }
        }
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
                if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
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

        val normalizedName = fileName.lowercase(Locale.ROOT)
        val isStatement = normalizedName.contains("estado de cuenta") ||
            normalizedName.contains("bank statement") ||
            normalizedName.contains("statement")
        val defaultSelection = if (isStatement && !isImage && pageCount > 1) {
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
            String.format(Locale.getDefault(), "%.1f MB", mb)
        } else {
            String.format(Locale.getDefault(), "%.0f KB", kb)
        }
    }

    private fun updateUi() {
        binding.txtCount.text = if (pdfList.isEmpty()) {
            getString(R.string.files_ready_zero)
        } else {
            getString(R.string.files_loaded_count, pdfList.size)
        }
        binding.btnMergePdf.isEnabled = pdfList.isNotEmpty()
        binding.btnClearAll.isEnabled = pdfList.isNotEmpty()
        renderListSummary()
    }

    private fun renderListSummary() {
        binding.fileListContainer.removeAllViews()
        if (pdfList.isEmpty()) {
            binding.txtSummary.visibility = View.VISIBLE
            binding.txtSummary.text = getString(R.string.no_file_selected)
            binding.fileListContainer.visibility = View.GONE
            return
        }

        binding.txtSummary.visibility = View.GONE
        binding.fileListContainer.visibility = View.VISIBLE

        val primaryColor = ContextCompat.getColor(this, R.color.color_text_primary)
        val secondaryColor = ContextCompat.getColor(this, R.color.color_text_secondary)
        val clearBgColor = ContextCompat.getColor(this, R.color.color_btn_clear_bg)
        val clearTextColor = ContextCompat.getColor(this, R.color.color_btn_clear_text)
        val mergeBgColor = ContextCompat.getColor(this, R.color.color_btn_merge_bg)
        val mergeTextColor = ContextCompat.getColor(this, R.color.color_btn_merge_text)

        pdfList.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val verticalPad = dpToPx(6)
                val horizontalPad = dpToPx(8)
                setPadding(horizontalPad, verticalPad, horizontalPad, verticalPad)
            }

            val infoText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dpToPx(8)
                }
                text = "${index + 1}. ${item.fileName} (${item.fileSize})"
                setTextColor(primaryColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }

            val pagesButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dpToPx(4)
                }
                val pagesLabel = if (item.selection == PageSelection.FIRST_ONLY) {
                    getString(R.string.only_page_1)
                } else {
                    getString(R.string.all_pages_count, item.totalPages)
                }
                text = pagesLabel
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                minHeight = dpToPx(34)
                minimumHeight = dpToPx(34)
                insetTop = 0
                insetBottom = 0
                cornerRadius = dpToPx(12)
                setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4))

                if (item.selection == PageSelection.FIRST_ONLY) {
                    backgroundTintList = ColorStateList.valueOf(mergeBgColor)
                    setTextColor(mergeTextColor)
                } else {
                    backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                    setTextColor(secondaryColor)
                }

                isEnabled = item.totalPages > 1
                setOnClickListener {
                    item.selection = if (item.selection == PageSelection.ALL) {
                        PageSelection.FIRST_ONLY
                    } else {
                        PageSelection.ALL
                    }
                    renderListSummary()
                }
            }

            val removeButton = ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(34), dpToPx(34))
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                backgroundTintList = ColorStateList.valueOf(clearBgColor)
                imageTintList = ColorStateList.valueOf(clearTextColor)
                contentDescription = getString(R.string.btn_remove_file)
                setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
                setOnClickListener {
                    pdfList.removeAt(index)
                    updateUi()
                }
            }

            row.addView(infoText)
            row.addView(pagesButton)
            row.addView(removeButton)
            binding.fileListContainer.addView(row)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
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
