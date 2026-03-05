package com.example.roomxxx0102.logic.presence

/**
 * Presence 基线存档（只读）。
 *
 * 目的：
 * 1) 记录历史算法版本，避免继续做多分支运行管理；
 * 2) 主线只保留一个可运行 baseline（由 Registry 决定）；
 * 3) 历史版本仅用于追溯，不再作为日常切换入口。
 */
object PresenceBaselineArchive {
    data class ArchiveEntry(
        val versionId: String,
        val archivedAt: String,
        val note: String
    )

    const val ACTIVE_BASELINE_ID: String = PresenceAlgorithmRegistry.ACTIVE_BASELINE_ID
    const val ACTIVE_VERSION_ID: String = PresenceAlgorithmRegistry.VERSION_V1_5_3_B03052330

    val archived: List<ArchiveEntry> = PresenceAlgorithmRegistry.archivedVersionIds().map { id ->
        ArchiveEntry(
            versionId = id,
            archivedAt = "2026-03-03",
            note = "已归档：仅用于追溯，不再作为运行分支切换"
        )
    }
}
