package com.jm.sillydroid.data.runtime

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class ServerOverlayTest {
    private lateinit var root: File
    private lateinit var overlayRoot: File
    private lateinit var serverDir: File

    @Before
    fun setUp() {
        root = createTempDirectory(prefix = "server-overlay").toFile()
        overlayRoot = File(root, "data/server/overrides")
        serverDir = File(root, "bootstrap/server").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `overlay file replaces server file and creates missing directories`() {
        val logo = serverFile("public/img/logo.png", byteArrayOf(1, 2, 3))
        overlayFile("public/img/logo.png", byteArrayOf(9, 8, 7, 6))
        overlayFile("public/css/extra/custom.css", "body{}".toByteArray())

        val result = applyServerOverlay(overlayRoot, serverDir)

        assertEquals(listOf("public/css/extra/custom.css", "public/img/logo.png"), result.applied)
        assertTrue(result.unchanged.isEmpty())
        assertTrue(result.rejected.isEmpty())
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), logo.readBytes())
        assertEquals("body{}", File(serverDir, "public/css/extra/custom.css").readText())
        assertTrue("不应留下临时文件", File(serverDir, "public/img").list()!!.none { it.endsWith(".overlay-tmp") })
        assertTrue(result.toLogLine().contains("已覆盖 2 个"))
    }

    @Test
    fun `identical content is skipped without rewriting`() {
        val logo = serverFile("public/img/logo.png", byteArrayOf(4, 5, 6))
        overlayFile("public/img/logo.png", byteArrayOf(4, 5, 6))
        val oldTime = System.currentTimeMillis() - 60_000
        assertTrue(logo.setLastModified(oldTime))

        val result = applyServerOverlay(overlayRoot, serverDir)

        assertTrue(result.applied.isEmpty())
        assertEquals(listOf("public/img/logo.png"), result.unchanged)
        assertEquals(oldTime / 1000, logo.lastModified() / 1000)

        // 同样大小、内容不同仍要覆盖。
        overlayFile("public/img/logo.png", byteArrayOf(4, 5, 7))
        assertEquals(listOf("public/img/logo.png"), applyServerOverlay(overlayRoot, serverDir).applied)
        assertArrayEquals(byteArrayOf(4, 5, 7), logo.readBytes())
    }

    @Test
    fun `multi chunk files compare every byte including the partial last chunk`() {
        // 比对按 64KB 分块，这里故意用不整除的大小，覆盖中间整块和最后不满的一块。
        val size = 3 * 64 * 1024 + 1234
        val content = ByteArray(size) { index -> (index * 31 + 7).toByte() }
        val logo = serverFile("public/img/logo.png", content)
        overlayFile("public/img/logo.png", content.copyOf())

        assertEquals(listOf("public/img/logo.png"), applyServerOverlay(overlayRoot, serverDir).unchanged)

        for (changedIndex in listOf(size - 1, 64 * 1024 + 5)) {
            val changed = content.copyOf().also { bytes -> bytes[changedIndex] = (bytes[changedIndex] + 1).toByte() }
            overlayFile("public/img/logo.png", changed)
            assertEquals("第 $changedIndex 字节不同必须覆盖", listOf("public/img/logo.png"), applyServerOverlay(overlayRoot, serverDir).applied)
            assertArrayEquals(changed, logo.readBytes())
            // 恢复成和 server 端一致，再测下一个位置。
            overlayFile("public/img/logo.png", content.copyOf())
            applyServerOverlay(overlayRoot, serverDir)
            assertArrayEquals(content, logo.readBytes())
        }
    }

    @Test
    fun `escaping paths are rejected`() {
        listOf(
            "../outside.txt",
            "public/../config.yaml",
            "public/./img/logo.png",
            "/etc/passwd",
            "public//img/logo.png",
            "public\\img\\logo.png",
            ""
        ).forEach { path ->
            val target = resolveServerOverlayTarget(serverDir, path)
            assertEquals(path, ServerOverlayTarget.Rejected("路径越界"), target)
        }
        assertFalse(File(root, "bootstrap/outside.txt").exists())
    }

    @Test
    fun `paths outside public and protected names are rejected`() {
        val serverJs = serverFile("server.js", "original".toByteArray())
        val config = serverFile("config.yaml", "port: 8000\n".toByteArray())
        overlayFile("server.js", "evil".toByteArray())
        overlayFile("config.yaml", "port: 1\n".toByteArray())
        overlayFile("src/endpoints/x.js", "evil".toByteArray())
        overlayFile("publicity/x.png", "evil".toByteArray())
        overlayFile("public/node_modules/x.js", "evil".toByteArray())
        overlayFile("public/scripts/extensions/third-party/x/index.js", "evil".toByteArray())
        overlayFile("public/config.yaml", "evil".toByteArray())

        val result = applyServerOverlay(overlayRoot, serverDir)

        assertTrue(result.applied.isEmpty())
        val reasons = result.rejected.associate { it.relativePath to it.reason }
        assertEquals("只允许 public/ 下的文件", reasons["server.js"])
        assertEquals("只允许 public/ 下的文件", reasons["config.yaml"])
        assertEquals("只允许 public/ 下的文件", reasons["src/endpoints/x.js"])
        assertEquals("只允许 public/ 下的文件", reasons["publicity/x.png"])
        assertEquals("不允许覆盖 node_modules", reasons["public/node_modules/x.js"])
        assertEquals("不允许覆盖 third-party", reasons["public/scripts/extensions/third-party/x/index.js"])
        assertEquals("不允许覆盖 config.yaml", reasons["public/config.yaml"])
        assertEquals("original", serverJs.readText())
        assertEquals("port: 8000\n", config.readText())
        assertFalse(File(serverDir, "src/endpoints/x.js").exists())
    }

    @Test
    fun `symlinks in overlay and server are rejected`() {
        val secret = File(root, "secret.txt").apply { writeText("secret") }
        val linkedOverlayFile = File(overlayRoot, "public/img/link.png").apply { parentFile!!.mkdirs() }
        assumeTrue(createSymlink(linkedOverlayFile, secret))

        // 覆盖层里指向目录的链接不能被钻进去。
        val outsideDirectory = File(root, "outside-dir").apply { mkdirs() }
        File(outsideDirectory, "a.css").writeText("evil")
        assumeTrue(createSymlink(File(overlayRoot, "public/css"), outsideDirectory))

        // serverDir 里的上级目录是符号链接（如 third-party 之外的别的链接），也不能顺着写出去。
        val persistentDir = File(root, "data/server/persistent").apply { mkdirs() }
        File(serverDir, "public").mkdirs()
        assumeTrue(createSymlink(File(serverDir, "public/linked"), persistentDir))
        overlayFile("public/linked/x.js", "evil".toByteArray())

        // serverDir 里的目标文件本身是符号链接。
        val linkTarget = File(root, "link-target.png").apply { writeBytes(byteArrayOf(1)) }
        File(serverDir, "public/img").mkdirs()
        assumeTrue(createSymlink(File(serverDir, "public/img/logo.png"), linkTarget))
        overlayFile("public/img/logo.png", byteArrayOf(2))

        val result = applyServerOverlay(overlayRoot, serverDir)

        assertTrue(result.applied.isEmpty())
        val reasons = result.rejected.associate { it.relativePath to it.reason }
        assertEquals("是符号链接", reasons["public/img/link.png"])
        assertEquals("是符号链接", reasons["public/css"])
        assertEquals("目标上级目录是符号链接", reasons["public/linked/x.js"])
        assertEquals("目标文件是符号链接", reasons["public/img/logo.png"])
        assertFalse(File(persistentDir, "x.js").exists())
        assertArrayEquals(byteArrayOf(1), linkTarget.readBytes())
        assertEquals("secret", secret.readText())
    }

    @Test
    fun `symlinked overlay root is rejected`() {
        val realOverlay = File(root, "real-overlay/public/img").apply { mkdirs() }
        File(realOverlay, "logo.png").writeBytes(byteArrayOf(1))
        overlayRoot.parentFile!!.mkdirs()
        assumeTrue(createSymlink(overlayRoot, File(root, "real-overlay")))

        val result = applyServerOverlay(overlayRoot, serverDir)

        assertTrue(result.applied.isEmpty())
        assertEquals(1, result.rejected.size)
        assertFalse(File(serverDir, "public/img/logo.png").exists())
    }

    @Test
    fun `missing overlay directory is reported and does nothing`() {
        val result = applyServerOverlay(overlayRoot, serverDir)

        assertFalse(result.overlayDirectoryPresent)
        assertEquals("server 覆盖层：overrides/ 不存在，跳过。", result.toLogLine())
    }

    @Test
    fun `log line lists applied unchanged and rejected files with reasons`() {
        val result = ServerOverlayResult(
            overlayDirectoryPresent = true,
            applied = listOf("public/img/logo.png"),
            unchanged = listOf("public/a.css"),
            rejected = listOf(ServerOverlaySkip("server.js", "只允许 public/ 下的文件"))
        )

        assertEquals(
            "server 覆盖层：已覆盖 1 个 [public/img/logo.png]；内容相同跳过 1 个 [public/a.css]；" +
                "拒绝 1 个 [server.js（只允许 public/ 下的文件）]。",
            result.toLogLine()
        )
    }

    private fun serverFile(relativePath: String, content: ByteArray): File {
        return File(serverDir, relativePath).apply {
            parentFile!!.mkdirs()
            writeBytes(content)
        }
    }

    private fun overlayFile(relativePath: String, content: ByteArray): File {
        return File(overlayRoot, relativePath).apply {
            parentFile!!.mkdirs()
            writeBytes(content)
        }
    }

    private fun createSymlink(link: File, target: File): Boolean {
        return runCatching {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        }.getOrDefault(false)
    }
}
