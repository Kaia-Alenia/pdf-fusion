package com.aleniastudios.pdffusion

import android.net.Uri

enum class PageSelection {
    ALL,
    FIRST_ONLY
}

data class PdfItem(
    val uri: Uri,
    val fileName: String,
    val fileSize: String,
    val totalPages: Int,
    var selection: PageSelection = PageSelection.ALL
)
