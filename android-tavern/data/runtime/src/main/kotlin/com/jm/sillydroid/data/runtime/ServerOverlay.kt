package com.jm.sillydroid.data.runtime

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * server 覆盖层：用户对 App 自带酒馆程序文件（bootstrap/server）的改动放在持久数据目录的 overrides/ 下，
 * 结构跟 serverDir 一一对应（overrides/public/img/logo.png → serverDir/public/img/logo.png）。
 * 升级包重新解压 server 源码会把同名文件换回原版，所以每次启动、server 资产准备完之后再铺一遍覆盖层。
 *
 * 只允许覆盖 public/ 下的普通文件；不跟随符号链接，拒绝越界路径，不碰 config.yaml、node_modules、third-party。
 */
internal const val SERVER_OVERLAY_DIRECTORY_NAME = "overrides"

private const val SERVER_OVERLAY_ALLOWED_ROOT = "public"
private val serverOverlayForbiddenSegments = setOf("node_modules", "third-party")
private const val SERVER_OVERLAY_FORBIDDEN_FILE_NAME = "config.yaml"

internal data class ServerOverlaySkip(
    val relativePath: String,
    val reason: String
)

internal data class ServerOverlayResult(
    val overlayDirectoryPresent: Boolean,
    val applied: List<String>,
    val unchanged: List<String>,
    val rejected: List<ServerOverlaySkip>
) {
    fun toLogLine(): String {
        if (!overlayDirectoryPresent) {
            return "server 覆盖层：$SERVER_OVERLAY_DIRECTORY_NAME/ 不存在，跳过。"
        }
        if (applied.isEmpty() && unchanged.isEmpty() && rejected.isEmpty()) {
            return "server 覆盖层：$SERVER_OVERLAY_DIRECTORY_NAME/ 是空的，跳过。"
        }
        return buildString {
            append("server 覆盖层：已覆盖 ").append(applied.size).append(" 个")
            if (applied.isNotEmpty()) append(" [").append(applied.joinToString(", ")).append(']')
            append("；内容相同跳过 ").append(unchanged.size).append(" 个")
            if (unchanged.isNotEmpty()) append(" [").append(unchanged.joinToString(", ")).append(']')
            append("；拒绝 ").append(rejected.size).append(" 个")
            if (rejected.isNotEmpty()) {
                append(" [")
                append(rejected.joinToString(", ") { skip -> "${skip.relativePath}（${skip.reason}）" })
                append(']')
            }
            append('。')
        }
    }
}

internal sealed interface ServerOverlayTarget {
    data class Allowed(val target: File) : ServerOverlayTarget
    data class Rejected(val reason: String) : ServerOverlayTarget
}

internal fun serverOverlayDirectory(paths: HostPaths): File = File(paths.serverDataDir, SERVER_OVERLAY_DIRECTORY_NAME)

internal fun applyServerOverlay(paths: HostPaths): ServerOverlayResult {
    return applyServerOverlay(overlayRoot = serverOverlayDirectory(paths), serverDir = paths.serverDir)
}

internal fun applyServerOverlay(overlayRoot: File, serverDir: File): ServerOverlayResult {
    val overlayPath = overlayRoot.toPath()
    if (Files.isSymbolicLink(overlayPath)) {
        return ServerOverlayResult(
            overlayDirectoryPresent = true,
            applied = emptyList(),
            unchanged = emptyList(),
            rejected = listOf(ServerOverlaySkip("$SERVER_OVERLAY_DIRECTORY_NAME/", "覆盖层目录本身是符号链接"))
        )
    }
    if (!Files.isDirectory(overlayPath, LinkOption.NOFOLLOW_LINKS)) {
        return ServerOverlayResult(
            overlayDirectoryPresent = false,
            applied = emptyList(),
            unchanged = emptyList(),
            rejected = emptyList()
        )
    }

    val applied = mutableListOf<String>()
    val unchanged = mutableListOf<String>()
    val rejected = mutableListOf<ServerOverlaySkip>()

    // walkFileTree 默认不跟随符号链接：指向目录的链接也只会作为一个条目交给 visitFile，不会钻进去。
    Files.walkFileTree(overlayPath, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            val relativePath = overlayRelativePath(overlayPath, file)
            when {
                attrs.isSymbolicLink -> rejected += ServerOverlaySkip(relativePath, "是符号链接")
                !attrs.isRegularFile -> rejected += ServerOverlaySkip(relativePath, "不是普通文件")
                else -> when (val target = resolveServerOverlayTarget(serverDir, relativePath)) {
                    is ServerOverlayTarget.Rejected -> rejected += ServerOverlaySkip(relativePath, target.reason)
                    is ServerOverlayTarget.Allowed -> try {
                        if (sameFileContent(file.toFile(), target.target)) {
                            unchanged += relativePath
                        } else {
                            copyOverlayFile(file.toFile(), target.target)
                            applied += relativePath
                        }
                    } catch (error: IOException) {
                        rejected += ServerOverlaySkip(relativePath, "复制失败：${error.message ?: error.javaClass.simpleName}")
                    } catch (error: SecurityException) {
                        rejected += ServerOverlaySkip(relativePath, "复制失败：${error.message ?: error.javaClass.simpleName}")
                    }
                }
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            rejected += ServerOverlaySkip(overlayRelativePath(overlayPath, file), "读取失败：${exc.message ?: exc.javaClass.simpleName}")
            return FileVisitResult.CONTINUE
        }
    })

    return ServerOverlayResult(
        overlayDirectoryPresent = true,
        applied = applied.sorted(),
        unchanged = unchanged.sorted(),
        rejected = rejected.sortedBy { skip -> skip.relativePath }
    )
}

/**
 * 把覆盖层里的相对路径（用 / 分隔）映射到 serverDir 里的目标文件。目录遍历本身产生不了 ..，
 * 这里仍按任意输入校验，并逐级确认 serverDir 里已有的上级目录不是符号链接（public/scripts/extensions/third-party 就是）。
 */
internal fun resolveServerOverlayTarget(serverDir: File, relativePath: String): ServerOverlayTarget {
    if (relativePath.isEmpty() || relativePath.startsWith("/") || relativePath.contains('\\') || relativePath.contains('\u0000')) {
        return ServerOverlayTarget.Rejected("路径越界")
    }
    val segments = relativePath.split('/')
    if (segments.any { segment -> segment.isEmpty() || segment == "." || segment == ".." }) {
        return ServerOverlayTarget.Rejected("路径越界")
    }
    if (segments.size < 2 || segments.first() != SERVER_OVERLAY_ALLOWED_ROOT) {
        return ServerOverlayTarget.Rejected("只允许 public/ 下的文件")
    }
    segments.firstOrNull { segment -> segment in serverOverlayForbiddenSegments }?.let { segment ->
        return ServerOverlayTarget.Rejected("不允许覆盖 $segment")
    }
    if (segments.last() == SERVER_OVERLAY_FORBIDDEN_FILE_NAME) {
        return ServerOverlayTarget.Rejected("不允许覆盖 $SERVER_OVERLAY_FORBIDDEN_FILE_NAME")
    }

    var current = serverDir
    for (segment in segments.dropLast(1)) {
        current = File(current, segment)
        val currentPath = current.toPath()
        if (Files.isSymbolicLink(currentPath)) {
            return ServerOverlayTarget.Rejected("目标上级目录是符号链接")
        }
        if (Files.exists(currentPath, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(currentPath, LinkOption.NOFOLLOW_LINKS)) {
            return ServerOverlayTarget.Rejected("目标上级路径不是目录")
        }
    }
    val target = File(current, segments.last())
    val targetPath = target.toPath()
    if (Files.isSymbolicLink(targetPath)) {
        return ServerOverlayTarget.Rejected("目标文件是符号链接")
    }
    if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(targetPath, LinkOption.NOFOLLOW_LINKS)) {
        return ServerOverlayTarget.Rejected("目标不是普通文件")
    }
    return ServerOverlayTarget.Allowed(target)
}

private fun overlayRelativePath(overlayRoot: Path, file: Path): String {
    return overlayRoot.relativize(file).joinToString("/") { segment -> segment.toString() }
}

private fun sameFileContent(source: File, target: File): Boolean {
    if (!Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) || source.length() != target.length()) {
        return false
    }
    source.inputStream().buffered().use { sourceStream ->
        target.inputStream().buffered().use { targetStream ->
            val sourceBuffer = ByteArray(64 * 1024)
            val targetBuffer = ByteArray(64 * 1024)
            while (true) {
                val read = sourceStream.readNBytesCompat(sourceBuffer)
                val targetRead = targetStream.readNBytesCompat(targetBuffer)
                if (read != targetRead) return false
                if (read <= 0) return true
                // 不在 Kotlin 里逐字节循环：debug 包的 App 代码基本是解释执行，比 1MB 多的 logo 要一百多毫秒。
                // Arrays.equals 在系统预编译的核心库里。最后一块不满时把两边剩余部分清零，再整块比较。
                if (read < sourceBuffer.size) {
                    sourceBuffer.fill(0, read)
                    targetBuffer.fill(0, read)
                }
                if (!sourceBuffer.contentEquals(targetBuffer)) return false
            }
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val read = read(buffer, total, buffer.size - total)
        if (read < 0) break
        total += read
    }
    return total
}

/** 先写同目录临时文件再改名替换，启动中途被杀也不会留下写了一半的文件。 */
private fun copyOverlayFile(source: File, target: File) {
    val parent = target.parentFile ?: throw IOException("目标没有上级目录：${target.path}")
    Files.createDirectories(parent.toPath())
    val temporary = Files.createTempFile(parent.toPath(), ".${target.name}.", ".overlay-tmp")
    try {
        Files.copy(source.toPath(), temporary, StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (atomicMoveFailed: IOException) {
            Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
