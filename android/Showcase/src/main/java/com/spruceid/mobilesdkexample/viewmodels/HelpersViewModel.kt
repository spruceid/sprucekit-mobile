package com.spruceid.mobilesdkexample.viewmodels

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import java.io.File
import java.io.PrintWriter

fun File.clearText() {
    PrintWriter(this).also {
        it.print("")
        it.close()
    }
}

fun File.updateText(content: String) {
    clearText()
    appendText(content)
}

class HelpersViewModel(application: Application) : AndroidViewModel(application) {
    fun exportText(content: String, filename: String, fileType: String) {
        val app = getApplication<Application>()
        val file = File(app.cacheDir, filename)
        file.updateText(content)

        val uri =
            FileProvider.getUriForFile(
                app.baseContext,
                app.baseContext.packageName + ".provider",
                file,
            )
        Intent(Intent.ACTION_SEND).apply {
            type = fileType
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_STREAM, uri)
        }.also { intent ->
            ContextCompat.startActivity(app.baseContext, intent, null)
        }
    }
}