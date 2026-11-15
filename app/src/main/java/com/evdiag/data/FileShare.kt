package com.evdiag.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object FileShare {
    /** Открывает системное меню «Поделиться» (Telegram, WhatsApp, почта и т.д.) для файла. */
    fun file(ctx: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, title).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        ctx.startActivity(chooser)
    }
}
