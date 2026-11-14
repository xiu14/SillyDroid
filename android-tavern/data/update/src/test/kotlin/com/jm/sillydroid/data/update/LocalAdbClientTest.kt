package com.jm.sillydroid.data.update

import com.jm.sillydroid.domain.update.AppUpdateAdbInstallResult
import java.io.DataInputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalAdbClientTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").run {
        initialize(2048)
        generateKeyPair()
    }

    private val signer = object : AdbAuthSigner {
        override val publicKey: RSAPublicKey = keyPair.public as RSAPublicKey

        override fun sign(token: ByteArray): ByteArray {
            return Signature.getInstance("NONEwithRSA").run {
                initSign(keyPair.private)
                update(LocalAdbClient.SHA1_DIGEST_INFO_PREFIX)
                update(token)
                sign()
            }
        }
    }

    @Test
    fun `public key uses adbd mincrypt layout`() {
        val encoded = LocalAdbClient.encodeAdbPublicKey(signer.publicKey, "stdroid@test")
        val (base64, name) = encoded.split(' ', limit = 2)
        assertEquals("stdroid@test", name)
        val modulus = decodeAdbPublicKeyModulus(base64)
        assertEquals(signer.publicKey.modulus, modulus)
    }

    @Test
    fun `first connection sends public key and runs shell after user allows`() {
        val authorizationRequests = mutableListOf<Unit>()
        val server = FakeAdbd(authorizedKeys = mutableSetOf(), userAllows = true, shellOutput = "STDROID_INSTALL_STARTED\n")
        server.start()

        val output = LocalAdbClient(signer, "stdroid@test", port = server.port)
            .shell("echo hi") { authorizationRequests += Unit }

        server.join()
        assertEquals("STDROID_INSTALL_STARTED\n", output)
        assertEquals(1, authorizationRequests.size)
        assertEquals("shell,v2,raw:echo hi", server.openedService.get())
        assertTrue(server.receivedPublicKey.get())
        assertTrue(server.clientClosedStream.get())
    }

    @Test
    fun `authorized key connects without prompting`() {
        val authorizedKeys = mutableSetOf(signer.publicKey.modulus)
        val server = FakeAdbd(authorizedKeys = authorizedKeys, userAllows = false, shellOutput = "ok")
        server.start()

        var prompted = false
        val output = LocalAdbClient(signer, "stdroid@test", port = server.port).shell("true") { prompted = true }

        server.join()
        assertEquals("ok", output)
        assertFalse(prompted)
        assertFalse(server.receivedPublicKey.get())
    }

    @Test
    fun `falls back to legacy shell service when device lacks shell_v2`() {
        val authorizedKeys = mutableSetOf(signer.publicKey.modulus)
        val server = FakeAdbd(authorizedKeys = authorizedKeys, userAllows = false, shellOutput = "legacy", shellV2 = false)
        server.start()

        val output = LocalAdbClient(signer, "stdroid@test", port = server.port).shell("true")

        server.join()
        assertEquals("legacy", output)
        assertEquals("shell:true", server.openedService.get())
    }

    @Test
    fun `shell v2 output keeps stdout and stderr and drops exit packet`() {
        val raw = shellV2Packet(1, "out\n".toByteArray()) + shellV2Packet(2, "err\n".toByteArray()) + shellV2Packet(3, byteArrayOf(0))
        assertEquals("out\nerr\n", LocalAdbClient.decodeShellV2Output(raw))
        assertEquals(
            setOf("shell_v2", "cmd"),
            LocalAdbClient.parseDeviceFeatures("device::ro.product.name=x;ro.product.model=y;features=shell_v2,cmd\u0000")
        )
        assertTrue(LocalAdbClient.parseDeviceFeatures("device::\u0000").isEmpty())
    }

    @Test
    fun `rejected authorization surfaces as authorization error`() {
        val server = FakeAdbd(authorizedKeys = mutableSetOf(), userAllows = false, shellOutput = "")
        server.start()

        try {
            LocalAdbClient(signer, "stdroid@test", port = server.port).shell("true")
            fail("expected AdbAuthorizationException")
        } catch (error: AdbAuthorizationException) {
            assertTrue(error.message.orEmpty().contains("拒绝"))
        } finally {
            server.join()
        }
    }

    @Test
    fun `install command is valid posix shell and only runs install after sha check`() {
        val command = AdbSelfInstallCommand.build(
            apkPath = "/storage/emulated/0/Android/data/com.jm.sillydroid.debug/files/Download/updates/stdroid-v1.0.7.apk",
            expectedSha256 = "E22A1CC2705CDB99E308FDB5950B7FBD3A1EB526064455DDB97CDD78802EA00E",
            launchComponent = "com.jm.sillydroid.debug/com.jm.sillydroid.feature.main.MainActivity"
        )
        val syntaxCheck = ProcessBuilder("sh", "-n", "-c", command).redirectErrorStream(true).start()
        val syntaxOutput = syntaxCheck.inputStream.bufferedReader().readText()
        assertEquals(syntaxOutput, 0, syntaxCheck.waitFor())
        assertTrue(command.contains("e22a1cc2705cdb99e308fdb5950b7fbd3a1eb526064455ddb97cdd78802ea00e"))
        assertTrue(command.indexOf("sha256sum") < command.indexOf("pm install"))

        // 读不到文件时只输出标记，不会执行安装。
        val missing = AdbSelfInstallCommand.build("/nonexistent/stdroid.apk", "", "a.b/c.D")
        val run = ProcessBuilder("sh", "-c", missing).redirectErrorStream(true).start()
        val runOutput = run.inputStream.bufferedReader().readText()
        run.waitFor()
        assertEquals(AdbSelfInstallCommand.UNREADABLE_MARKER, runOutput.trim())
    }

    @Test
    fun `install command rejects unsafe arguments and parses markers`() {
        assertTrue(runCatching { AdbSelfInstallCommand.build("/tmp/a b.apk", "", "a/b") }.isFailure)
        assertTrue(runCatching { AdbSelfInstallCommand.build("/tmp/a.apk", "", "a/b'; reboot") }.isFailure)
        assertTrue(runCatching { AdbSelfInstallCommand.build("/tmp/a.apk", "xyz", "a/b") }.isFailure)

        assertEquals(AppUpdateAdbInstallResult.Started, AdbSelfInstallCommand.parse("STDROID_INSTALL_STARTED\n"))
        assertTrue(AdbSelfInstallCommand.parse("STDROID_SHA_MISMATCH abc") is AppUpdateAdbInstallResult.Unavailable)
        assertTrue(AdbSelfInstallCommand.parse("") is AppUpdateAdbInstallResult.Unavailable)
    }

    private fun shellV2Packet(id: Int, data: ByteArray): ByteArray {
        return ByteBuffer.allocate(5 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByte())
            .putInt(data.size)
            .put(data)
            .array()
    }

    private fun decodeAdbPublicKeyModulus(base64: String): BigInteger {
        val bytes = Base64.getDecoder().decode(base64)
        assertEquals(4 + 4 + 256 + 256 + 4, bytes.size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buffer.int)
        val n0inv = buffer.int.toLong() and 0xffffffffL
        val modulusLittleEndian = ByteArray(256)
        buffer.get(modulusLittleEndian)
        val rrLittleEndian = ByteArray(256)
        buffer.get(rrLittleEndian)
        val exponent = buffer.int
        val modulus = BigInteger(1, modulusLittleEndian.reversedArray())
        val rr = BigInteger(1, rrLittleEndian.reversedArray())
        val r32 = BigInteger.ONE.shiftLeft(32)
        // n0inv * n[0] ≡ -1 (mod 2^32)
        assertEquals(r32.subtract(BigInteger.ONE), modulus.mod(r32).multiply(BigInteger.valueOf(n0inv)).mod(r32))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(modulus), rr)
        assertEquals(65537, exponent)
        return modulus
    }

    /** 只实现握手、认证和一次 shell 的假 adbd，行为参照真机 adbd。 */
    private inner class FakeAdbd(
        private val authorizedKeys: MutableSet<BigInteger>,
        private val userAllows: Boolean,
        private val shellOutput: String,
        private val shellV2: Boolean = true
    ) {
        private val serverSocket = ServerSocket(0)
        val port: Int = serverSocket.localPort
        val openedService = AtomicReference<String>()
        val receivedPublicKey = AtomicReference(false)
        val clientClosedStream = AtomicReference(false)
        private val failure = AtomicReference<Throwable>()
        private lateinit var worker: Thread

        fun start() {
            worker = thread(name = "fake-adbd") {
                try {
                    serverSocket.use { server -> server.accept().use { socket -> serve(socket) } }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        }

        fun join() {
            worker.join(10_000)
            failure.get()?.let { throw AssertionError("fake adbd failed", it) }
        }

        private fun serve(socket: java.net.Socket) {
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val hello = LocalAdbClient.readMessage(input)
            assertEquals(LocalAdbClient.CMD_CNXN, hello.command)

            var token = ByteArray(20) { it.toByte() }
            LocalAdbClient.writeMessage(output, LocalAdbClient.CMD_AUTH, LocalAdbClient.AUTH_TOKEN, 0, token)
            while (true) {
                val auth = LocalAdbClient.readMessage(input)
                assertEquals(LocalAdbClient.CMD_AUTH, auth.command)
                when (auth.arg0) {
                    LocalAdbClient.AUTH_SIGNATURE -> {
                        val accepted = authorizedKeys.any { modulus ->
                            verifies(modulus, token, auth.payload)
                        }
                        if (accepted) {
                            break
                        }
                        token = ByteArray(20) { (it + 100).toByte() }
                        LocalAdbClient.writeMessage(output, LocalAdbClient.CMD_AUTH, LocalAdbClient.AUTH_TOKEN, 0, token)
                    }

                    LocalAdbClient.AUTH_RSAPUBLICKEY -> {
                        receivedPublicKey.set(true)
                        val text = String(auth.payload, Charsets.UTF_8).trimEnd('\u0000')
                        val modulus = decodeAdbPublicKeyModulus(text.substringBefore(' '))
                        if (!userAllows) {
                            return // 用户拒绝：adbd 直接断开
                        }
                        authorizedKeys += modulus
                        break
                    }

                    else -> throw AssertionError("unexpected AUTH type ${auth.arg0}")
                }
            }
            assertTrue(String(hello.payload, Charsets.UTF_8).contains("features=shell_v2"))
            val banner = if (shellV2) "device::ro.product.name=fake;features=shell_v2,cmd\u0000" else "device::\u0000"
            LocalAdbClient.writeMessage(output, LocalAdbClient.CMD_CNXN, LocalAdbClient.ADB_VERSION, 4096, banner.toByteArray())

            val open = LocalAdbClient.readMessage(input)
            assertEquals(LocalAdbClient.CMD_OPEN, open.command)
            openedService.set(String(open.payload, Charsets.UTF_8).trimEnd('\u0000'))
            val clientId = open.arg0
            val serverId = 7
            LocalAdbClient.writeMessage(output, LocalAdbClient.CMD_OKAY, serverId, clientId, ByteArray(0))
            val chunks = if (shellV2) {
                // v2 包故意拆成两次 WRTE 发，模拟一个包跨消息的情况。
                val packets = (if (shellOutput.isNotEmpty()) shellV2Packet(1, shellOutput.toByteArray()) else ByteArray(0)) +
                    shellV2Packet(3, byteArrayOf(0))
                listOf(packets.copyOfRange(0, 3), packets.copyOfRange(3, packets.size))
            } else {
                listOf(shellOutput.toByteArray()).filter { chunk -> chunk.isNotEmpty() }
            }
            for (chunk in chunks) {
                LocalAdbClient.writeMessage(output, LocalAdbClient.CMD_WRTE, serverId, clientId, chunk)
                val ack = LocalAdbClient.readMessage(input)
                assertEquals(LocalAdbClient.CMD_OKAY, ack.command)
            }
            LocalAdbClient.writeMessage(output, LocalAdbClient.CMD_CLSE, serverId, clientId, ByteArray(0))
            val close = LocalAdbClient.readMessage(input)
            assertEquals(LocalAdbClient.CMD_CLSE, close.command)
            clientClosedStream.set(true)
        }

        private fun verifies(modulus: BigInteger, token: ByteArray, signature: ByteArray): Boolean {
            if (modulus != (keyPair.public as RSAPublicKey).modulus) {
                return false
            }
            return Signature.getInstance("NONEwithRSA").run {
                initVerify(keyPair.public)
                update(LocalAdbClient.SHA1_DIGEST_INFO_PREFIX)
                update(token)
                verify(signature)
            }
        }
    }
}
