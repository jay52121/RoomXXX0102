package com.example.roomxxx_vocie.kws

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object AssetFileCopier {
    fun copyAssetDirToFiles(context: Context, assetDirPath: String): File {
        val targetDir = resolveTargetDir(context, assetDirPath)
        copyAssetDir(context, assetDirPath, targetDir)
        return targetDir
    }

    fun copyAssetFileToFiles(
        context: Context,
        assetPath: String,
        forceOverwrite: Boolean = false
    ): File {
        val targetFile = resolveTargetFile(context, assetPath)
        copyAssetFile(context, assetPath, targetFile, forceOverwrite)
        return targetFile
    }

    private fun resolveTargetDir(context: Context, assetDirPath: String): File {
        val (root, relative) = splitAssetPath(assetDirPath)
        return File(context.filesDir, "$root/$relative")
    }

    private fun resolveTargetFile(context: Context, assetPath: String): File {
        val (root, relative) = splitAssetPath(assetPath)
        return File(context.filesDir, "$root/$relative")
    }

    private fun splitAssetPath(assetPath: String): Pair<String, String> {
        return when {
            assetPath.startsWith("kws_model/") ->
                "kws_model_cache" to assetPath.removePrefix("kws_model/")
            assetPath.startsWith("kws_keywords/") ->
                "kws_keywords_cache" to assetPath.removePrefix("kws_keywords/")
            else ->
                "kws_assets_cache" to assetPath
        }
    }

    private fun copyAssetDir(context: Context, assetDirPath: String, targetDir: File) {
        val assetManager = context.assets
        val entries = assetManager.list(assetDirPath) ?: emptyArray()
        if (entries.isEmpty()) {
            copyAssetFile(context, assetDirPath, targetDir, forceOverwrite = false)
            return
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        for (entry in entries) {
            val childAssetPath = "$assetDirPath/$entry"
            val childTarget = File(targetDir, entry)
            val childEntries = assetManager.list(childAssetPath) ?: emptyArray()
            if (childEntries.isEmpty()) {
                copyAssetFile(context, childAssetPath, childTarget, forceOverwrite = false)
            } else {
                copyAssetDir(context, childAssetPath, childTarget)
            }
        }
    }

    private fun copyAssetFile(
        context: Context,
        assetPath: String,
        targetFile: File,
        forceOverwrite: Boolean
    ) {
        if (!forceOverwrite && targetFile.exists()) {
            val assetSize = getAssetSize(context, assetPath)
            if (assetSize <= 0L || assetSize == targetFile.length()) {
                return
            }
        }

        targetFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun getAssetSize(context: Context, assetPath: String): Long {
        return try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (_: Exception) {
            -1L
        }
    }
}
