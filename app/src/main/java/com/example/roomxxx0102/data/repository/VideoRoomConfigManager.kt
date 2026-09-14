package com.example.roomxxx0102.data.repository

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.Locale

object VideoRoomConfigManager {
    private const val TAG = "VideoRoomConfigManager"
    private const val ROOT_DIR_NAME = "room_configs"
    private const val CONFIG_EXTENSION = ".Room"

    data class VideoConfigContext(
        val videoUri: String,
        val baseName: String,
        val folder: File
    )

    data class ConfiguredVideoMatch(
        val videoUri: String,
        val videoName: String,
        val videoLocation: String,
        val configFile: File
    )

    private data class MediaVideo(
        val uri: String,
        val displayName: String,
        val relativePath: String
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

    fun configFileForVideo(uriString: String): File? {
        val associatedPath = AppSettings.getVideoConfigAssociations()[uriString]
        val associatedFile = associatedPath?.let(::File)
            ?.takeIf { it.exists() && it.isFile && it.name.endsWith(CONFIG_EXTENSION, ignoreCase = true) }
        if (associatedFile != null) return associatedFile

        val context = contextForUri(uriString) ?: return null
        return File(context.folder, context.baseName + CONFIG_EXTENSION)
            .takeIf { it.exists() && it.isFile }
    }

    fun associateVideoWithConfig(uriString: String?, configFile: File?): Boolean {
        val uri = uriString?.trim().orEmpty()
        val file = configFile?.takeIf { it.exists() && it.isFile } ?: return false
        if (uri.isBlank()) return false
        AppSettings.setVideoConfigAssociation(uri, file.absolutePath)
        return true
    }

    fun associateCurrentVideoWithConfig(configFile: File?): Boolean {
        return associateVideoWithConfig(AppSettings.testVideoUri, configFile)
    }

    fun listAllConfigFiles(): List<File> {
        val rootDir = File(appContext.filesDir, ROOT_DIR_NAME)
        if (!rootDir.exists() || !rootDir.isDirectory) return emptyList()
        return rootDir.walkTopDown()
            .maxDepth(2)
            .filter { it.isFile && it.name.endsWith(CONFIG_EXTENSION, ignoreCase = true) }
            .sortedBy { it.absolutePath.lowercase(Locale.getDefault()) }
            .toList()
    }

    fun discoverConfiguredVideos(): List<ConfiguredVideoMatch> {
        val configFiles = listAllConfigFiles()
        if (configFiles.isEmpty()) return emptyList()
        val mediaVideos = queryMediaVideos()
        val matches = mutableListOf<ConfiguredVideoMatch>()
        val matchedConfigPaths = mutableSetOf<String>()

        AppSettings.getVideoConfigAssociations().forEach { (videoUri, configPath) ->
            val configFile = File(configPath)
            if (!configFile.exists() || !configFile.isFile) return@forEach
            val mediaVideo = mediaVideos.firstOrNull { sameMediaItem(it.uri, videoUri) }
            val displayName = mediaVideo?.displayName
                ?: runCatching { resolveVideoDisplayName(videoUri) }.getOrDefault("video")
            matches.add(
                ConfiguredVideoMatch(
                    videoUri = mediaVideo?.uri ?: videoUri,
                    videoName = displayName,
                    videoLocation = mediaVideo?.relativePath.orEmpty(),
                    configFile = configFile
                )
            )
            matchedConfigPaths.add(configFile.canonicalPath)
        }

        configFiles.forEach { configFile ->
            if (configFile.canonicalPath in matchedConfigPaths) return@forEach
            val configKeys = listOfNotNull(
                configFile.parentFile?.name,
                configFile.nameWithoutExtension
            ).map(::normalizeMatchKey).filter { it.isNotBlank() }.distinct()
            val exactCandidates = mediaVideos.filter { video ->
                normalizeMatchKey(video.displayName.substringBeforeLast('.')) in configKeys
            }
            val selectedVideo = exactCandidates.minByOrNull(::mediaPathPriority)
                ?: findUniqueDateCandidate(configKeys, mediaVideos)
                ?: return@forEach
            matches.add(
                ConfiguredVideoMatch(
                    videoUri = selectedVideo.uri,
                    videoName = selectedVideo.displayName,
                    videoLocation = selectedVideo.relativePath,
                    configFile = configFile
                )
            )
            matchedConfigPaths.add(configFile.canonicalPath)
        }

        return matches.distinctBy { it.videoUri to it.configFile.canonicalPath }
            .sortedBy { it.videoName.lowercase(Locale.getDefault()) }
    }

    fun listConfigFilesForCurrentVideo(): List<File> {
        val context = currentVideoContext() ?: return emptyList()
        return listConfigFiles(context)
    }

    fun hasConfigFilesForVideo(uriString: String): Boolean {
        val context = contextForUri(uriString) ?: return false
        return listConfigFiles(context).isNotEmpty()
    }

    private fun listConfigFiles(context: VideoConfigContext): List<File> {
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

    fun buildImportedConfigFileForCurrentVideo(sourceFile: File): File? {
        val context = currentVideoContext() ?: return null
        val sourceFolderName = sourceFile.parentFile?.name?.takeIf { it.isNotBlank() } ?: "import"
        val sourceName = sourceFile.nameWithoutExtension.ifBlank { "config" }
        val baseName = sanitizeFileStem("${sourceFolderName}_$sourceName")
        var candidate = File(context.folder, "$baseName$CONFIG_EXTENSION")
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(context.folder, "${baseName}_$suffix$CONFIG_EXTENSION")
            suffix += 1
        }
        return candidate
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
            try {
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
            } catch (e: SecurityException) {
                Log.w(TAG, "无法读取视频显示名，使用 URI 片段兜底: $uriString", e)
            }
        }
        val lastSegment = uri.lastPathSegment
        if (!lastSegment.isNullOrBlank()) {
            return File(lastSegment).name.substringBeforeLast('.')
        }
        return "video"
    }

    private fun queryMediaVideos(): List<MediaVideo> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH
        )
        return try {
            buildList {
                appContext.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val pathIndex = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIndex)
                        val name = cursor.getString(nameIndex).orEmpty()
                        val path = if (pathIndex >= 0) cursor.getString(pathIndex).orEmpty() else ""
                        if (name.isNotBlank()) {
                            add(
                                MediaVideo(
                                    uri = ContentUris.withAppendedId(
                                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                        id
                                    ).toString(),
                                    displayName = name,
                                    relativePath = path
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "扫描本机视频失败", e)
            emptyList()
        }
    }

    private fun findUniqueDateCandidate(keys: List<String>, videos: List<MediaVideo>): MediaVideo? {
        val dateTokens = keys.flatMap { key ->
            Regex("(0[1-9]|1[0-2])[0-3][0-9]").findAll(key).map { it.value }.toList()
        }.distinct()
        for (token in dateTokens) {
            val candidates = videos.filter { video -> normalizeMatchKey(video.displayName).contains(token) }
            if (candidates.size == 1) return candidates.single()
        }
        return null
    }

    private fun mediaPathPriority(video: MediaVideo): Int {
        val path = video.relativePath.lowercase(Locale.getDefault())
        return when {
            path.contains("download") -> 0
            path.contains("myalbums") -> 1
            path.contains("movies") -> 2
            path.contains("dcim") -> 3
            else -> 4
        }
    }

    private fun sameMediaItem(firstUri: String, secondUri: String): Boolean {
        if (firstUri == secondUri) return true
        val firstId = Uri.parse(firstUri).lastPathSegment?.substringAfterLast(':')
        val secondId = Uri.parse(secondUri).lastPathSegment?.substringAfterLast(':')
        return !firstId.isNullOrBlank() && firstId == secondId
    }

    private fun normalizeMatchKey(value: String): String {
        return value.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]"), "")
    }

    private fun sanitizeFileStem(name: String): String {
        val trimmed = name.trim().ifEmpty { "video" }
        val sanitized = trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim('.')
        return sanitized.ifEmpty { "video" }
    }
}
