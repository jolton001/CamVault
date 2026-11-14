package com.example.network.cloud

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CloudStorageNaming {

    /**
     * Sanitizes camera label/name for use as a cloud folder name.
     * Removes dangerous path traversal characters and illegal path delimiters (/ and \),
     * preserving camera identity while ensuring safe cloud folder creation.
     */
    fun buildCameraFolderName(cameraName: String, cameraId: Long): String {
        val clean = cameraName.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
        return clean.ifBlank { "Camera_$cameraId" }
    }

    /**
     * Day-wise folder string in standard 'yyyy-MM-dd' format (e.g. '2026-11-14').
     */
    fun buildDayFolderName(startedAt: Long): String {
        val time = if (startedAt > 0) startedAt else System.currentTimeMillis()
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(time))
    }

    /**
     * Formats remote video filename following the user-requested DD/MM/YYYY-HH/MM/SS pattern.
     * Since forward slash ('/') is the filesystem and cloud directory separator (and forbidden in filenames),
     * hyphens are used as safe delimiters: 'dd-MM-yyyy-HH-mm-ss.mp4' (e.g. '14-11-2026-01-49-09.mp4').
     * Segment suffixes like '_p1' are preserved for split recordings.
     */
    fun buildRemoteFileName(originalFileName: String, startedAt: Long): String {
        val time = if (startedAt > 0) startedAt else System.currentTimeMillis()
        val timeStamp = SimpleDateFormat("dd-MM-yyyy-HH-mm-ss", Locale.US).format(Date(time))
        val partSuffix = Regex("(_p\\d+)\\.mp4$", RegexOption.IGNORE_CASE)
            .find(originalFileName)?.groupValues?.get(1).orEmpty()
        return "$timeStamp$partSuffix.mp4"
    }

    /**
     * Builds the complete hierarchical cloud folder path:
     * '<basePrefix>/<cameraName>/<YYYY-MM-DD>'
     */
    fun buildTargetFolderPrefix(
        basePrefix: String,
        cameraName: String,
        cameraId: Long,
        startedAt: Long
    ): String {
        val cleanBase = basePrefix.trim().trim('/')
        val cameraFolder = buildCameraFolderName(cameraName, cameraId)
        val dayFolder = buildDayFolderName(startedAt)
        return if (cleanBase.isBlank()) {
            "$cameraFolder/$dayFolder"
        } else {
            "$cleanBase/$cameraFolder/$dayFolder"
        }
    }
}

