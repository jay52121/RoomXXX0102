package com.example.roomxxx0102.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

object VideoRoomConfigManager {
    private const val ROOT_DIR_NAME = "room_configs"
    private const val CONFIG_EXTENSION = ".Room"

    data class VideoConfigContext(
        val videoUri: String,
        val baseName: String,
        val folder: File
    )

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun currentVideoContext(): VideoConfigContext? {
        return contextForUri(AppSettings.testVideoUri)
    }

    fun contextForUri(uriString: String?): VideoConfigContext? {
        val normalized = uriString?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        val baseName = sanitizeFileStem(resolveVideoDisplayName(normalized))
        val folder = File(File(appContext.filesDir, ROOT_DIR_NAME), baseName)
        return VideoConfigContext(
            videoUri = normalized,
            baseName = baseName,
            folder = folder
        )
    }

    fun defaultConfigFileForCurrentVideo(): File? {
        val context = currentVideoContext() ?: return null
        return File(context.folder, context.baseName + CONFIG_EXTENSION)
    }

    fun listConfigFilesForCurrentVideo(): List<File> {
        val context = currentVideoContext() ?: return emptyList()
        val folder = context.folder
        if (!folder.exists() || !folder.isDirectory) return emptyList()
        return folder.listFiles()
            ?.filter { it.isFile && it.name.endsWith(CONFIG_EXTENSION, ignoreCase = true) }
            ?.sortedBy { it.name.lowercase(Locale.getDefault()) }
            .orEmpty()
    }

    fun buildConfigFileForCurrentVideo(displayName: String): File? {
        val context = currentVideoContext() ?: return null
        val fileStem = sanitizeFileStem(displayName)
        return File(context.folder, fileStem + CONFIG_EXTENSION)
    }

    fun isCurrentVideoConfigFile(file: File?): Boolean {
        val context = currentVideoContext() ?: return false
        val target = file ?: return false
        return try {
            target.canonicalFile.parentFile == context.folder.canonicalFile
        } catch (_: Exception) {
            false
        }
    }

    fun suggestNextConfigNameForCurrentVideo(): String? {
        val context = currentVideoContext() ?: return null
        val existingNames = listConfigFilesForCurrentVideo()
            .map { it.nameWithoutExtension }
            .toSet()
        if (!existingNames.contains(context.baseName)) {
            return context.baseName
        }
        var index = 2
        while (true) {
            val candidate = "${context.baseName}_$index"
            if (!existingNames.contains(candidate)) {
                return candidate
            }
            index += 1
        }
    }

    private fun resolveVideoDisplayName(uriString: String): String {
        val uri = Uri.parse(uriString)
        if (uri.scheme.equals("content", ignoreCase = true)) {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) {
                        val displayName = cursor.getString(index)
                        if (!displayName.isNullOrBlank()) {
                            return displayName.substringBeforeLast('.')
                        }
                    }
                }
        }
        val lastSegment = uri.lastPathSegment
        if (!lastSegment.isNullOrBlank()) {
            return File(lastSegment).name.substringBeforeLast('.')
        }
        return "video"
    }

    private fun sanitizeFileStem(name: String): String {
        val trimmed = name.trim().ifEmpty { "video" }
        val sanitized = trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim('.')
        return sanitized.ifEmpty { "video" }
    }
}
