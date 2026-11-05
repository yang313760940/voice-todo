package com.voicetodo.data

import com.voicetodo.domain.BackupFileStore
import com.voicetodo.domain.RestoreCopyInfo
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 恢复副本保存在应用私有目录，只允许本应用按白名单文件名读取。
 * 文件名不含用户输入，避免路径穿越；写入先落临时文件再改名，防止半截文件。
 */
class PrivateBackupFileStore(private val root: File) : BackupFileStore {
    init {
        if (!root.exists() && !root.mkdirs()) {
            throw IllegalStateException("无法创建备份目录")
        }
    }

    override fun writeRestoreCopy(json: String, at: Instant, zone: ZoneId): String {
        val base = "restore-${FILE_TIME.format(at.atZone(zone))}"
        var name = "$base.json"
        var suffix = 2
        while (File(root, name).exists()) {
            name = "$base-$suffix.json"
            suffix++
        }
        val target = File(root, name)
        val temporary = File(root, ".$name.tmp")
        try {
            temporary.writeText(json, Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        check(target.isFile && target.length() > 0) { "恢复副本写入失败" }
        return name
    }

    override fun listRestoreCopies(): List<RestoreCopyInfo> =
        root.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
            .map {
                RestoreCopyInfo(
                    name = it.name,
                    createdAt = Instant.ofEpochMilli(it.lastModified()),
                    sizeBytes = it.length(),
                )
            }
            .sortedWith(compareByDescending<RestoreCopyInfo> { it.createdAt }.thenBy { it.name })

    override fun readRestoreCopy(name: String): String {
        require(name == File(name).name && SAFE_NAME.matches(name)) { "恢复副本文件名无效" }
        val file = File(root, name)
        val canonicalRoot = root.canonicalFile
        val canonicalFile = file.canonicalFile
        require(canonicalFile.parentFile == canonicalRoot) { "恢复副本路径无效" }
        require(canonicalFile.isFile) { "恢复副本不存在" }
        return canonicalFile.readText(Charsets.UTF_8)
    }

    companion object {
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]+\\.json")
        private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

        fun directoryName(): String = "backups"
    }
}
