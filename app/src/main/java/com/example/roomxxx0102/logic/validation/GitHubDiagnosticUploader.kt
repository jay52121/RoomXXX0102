package com.example.roomxxx0102.logic.validation

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

internal data class GitHubDiagnosticUploadResult(
    val success: Boolean,
    val remotePath: String,
    val htmlUrl: String? = null,
    val message: String,
)

/**
 * 把 EventDiagnosticRecorder 生成的单个 JSON 上传到专用 diagnostics 分支。
 *
 * Token 不进入 SharedPreferences/日志/JSON/源码，而是保存在应用私有 noBackupFilesDir，
 * 这样既不会提交进仓库，也不会跟随 Android 自动备份。
 */
internal object GitHubDiagnosticUploader {
    private const val OWNER = "jay52121"
    private const val REPO = "RoomXXX0102"
    private const val BRANCH = "diagnostics"
    private const val ROOT = "v4a"
    private const val API_VERSION = "2022-11-28"
    private const val TOKEN_FILE = "github_diagnostic_upload.token"
    private val executor = Executors.newSingleThreadExecutor()

    fun hasToken(context: Context): Boolean = readToken(context).isNotBlank()

    fun saveToken(context: Context, rawToken: String) {
        val token = rawToken.trim()
        require(token.isNotEmpty()) { "GitHub Token 不能为空" }
        tokenFile(context).writeText(token, Charsets.UTF_8)
    }

    fun clearToken(context: Context) {
        runCatching { tokenFile(context).delete() }
    }

    fun uploadAsync(
        context: Context,
        file: File,
        callback: (GitHubDiagnosticUploadResult) -> Unit,
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val result = runCatching { upload(appContext, file) }
                .getOrElse { error ->
                    GitHubDiagnosticUploadResult(
                        success = false,
                        remotePath = remotePath(file),
                        message = error.message ?: error.javaClass.simpleName,
                    )
                }
            callback(result)
        }
    }

    internal fun remotePath(file: File, now: Date = Date()): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(now)
        return "$ROOT/$day/${file.name}"
    }

    private fun upload(context: Context, file: File): GitHubDiagnosticUploadResult {
        require(file.exists() && file.isFile) { "诊断文件不存在: ${file.name}" }
        val token = readToken(context)
        require(token.isNotBlank()) { "尚未配置 GitHub Token" }

        val remotePath = remotePath(file)
        val encodedPath = remotePath.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
        val url = URI(
            "https://api.github.com/repos/$OWNER/$REPO/contents/$encodedPath"
        ).toURL()
        val body = JSONObject()
            .put("message", "diag: ${file.nameWithoutExtension}")
            .put("content", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            .put("branch", BRANCH)
            .toString()

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            setRequestProperty("User-Agent", "RoomXXX0102-EventDiagnosticUploader")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        return try {
            connection.outputStream.use { out ->
                out.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_CREATED) {
                val json = JSONObject(response)
                GitHubDiagnosticUploadResult(
                    success = true,
                    remotePath = remotePath,
                    htmlUrl = json.optJSONObject("content")?.optString("html_url")?.takeIf { it.isNotBlank() },
                    message = "uploaded",
                )
            } else {
                val message = runCatching { JSONObject(response).optString("message") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "HTTP $code"
                GitHubDiagnosticUploadResult(
                    success = false,
                    remotePath = remotePath,
                    message = "GitHub $code: $message",
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readToken(context: Context): String = runCatching {
        tokenFile(context).takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim().orEmpty()
    }.getOrDefault("")

    private fun tokenFile(context: Context): File = File(context.noBackupFilesDir, TOKEN_FILE)
}
