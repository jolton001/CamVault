package com.example.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

object MediaStoreExporter {

    fun exportToGallery(context: Context, videoFile: File, displayName: String): Result<Uri> {
        return runCatching {
            require(videoFile.exists() && videoFile.length() > 0) { "Video file does not exist or is empty" }
            val cleanName = if (displayName.endsWith(".mp4", ignoreCase = true)) displayName else "$displayName.mp4"
            val resolver = context.contentResolver

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, cleanName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CamVault")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Failed to create MediaStore entry in Movies/CamVault")

            resolver.openOutputStream(uri)?.use { out ->
                videoFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            } ?: throw IllegalStateException("Failed to open MediaStore output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, updateValues, null, null)
            }
            uri
        }
    }
}

