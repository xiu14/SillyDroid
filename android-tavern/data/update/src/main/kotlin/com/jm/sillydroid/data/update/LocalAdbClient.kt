package com.jm.sillydroid.data.update

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** adb 认证用的签名者：私钥不出 [sign]，正式环境放在 Android Keystore 里。 */
internal interface AdbAuthSigner {
    val publicKey: RSAPublicKey

    /** 对 adbd 发来的 20 字节 token 做 adb 约定的签名（SHA1 DigestInfo 前缀 + token，PKCS#1 v1.5）。 */
    fun sign(token: ByteArray): ByteArray
}

internal class AdbAuthorizationException(message: String) : IOException(message)

/**
 * 最小的 adb 客户端：直连本机 adbd 的 TCP 端口（默认 127.0.0.1:5555），认证后执行一条 shell 命令并读回输出。
 * 只实现自更新需要的部分：CNXN / AUTH / OPEN / WRTE / OKAY / CLSE，不支持 TLS（无线调试的配对端口）。
 * 第一次用某个公钥连接时，手机会弹出「允许 USB 调试吗？」，用户勾选「一律允许」后以后不再询问。
 */
internal class LocalAdbClient(
    private val signer: AdbAuthSigner,
    private val publicKeyName: String,
    private val host: String = "127.0.0.1",
    private val port: Int = 5555,
    private val connectTimeoutMillis: Int = 2_000,
    private val readTimeoutMillis: Int = 15_000,
    private val authorizationTimeoutMillis: Int = 60_000
) {
    /**
     * 执行 `shell:<command>`，返回命令输出（stdout/stderr 混在一起）。
     * [onAuthorizationRequested] 在发出公钥、手机即将弹授权框时调用。
     */
    fun shell(command: String, onAuthorizationRequested: () -> Unit = {}): String {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            socket.soTimeout = readTimeoutMillis
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = socket.getOutputStream()

            writeMessage(output, CMD_CNXN, ADB_VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray(Charsets.UTF_8))
            authenticate(socket, input, output, onAuthorizationRequested)
            socket.soTimeout = readTimeoutMillis
            return runShell(input, output, command)
        }
    }

    private fun authenticate(
        socket: Socket,
        input: DataInputStream,
        output: java.io.OutputStream,
        onAuthorizationRequested: () -> Unit
    ) {
        var signatureSent = false
        var publicKeySent = false
        while (true) {
            val message = try {
                readMessage(input)
            } catch (error: SocketTimeoutException) {
                throw AdbAuthorizationException(
                    if (publicKeySent) "等待「允许 USB 调试」授权超时" else "adbd 没有响应握手"
                )
            } catch (error: EOFException) {
                throw AdbAuthorizationException(
                    if (publicKeySent) "「允许 USB 调试」被拒绝，或授权框被关闭" else "adbd 断开了连接"
                )
            }
            when (message.command) {
                CMD_CNXN -> return
                CMD_STLS -> throw IOException("adbd 要求 TLS（无线调试端口），这里只支持 adb tcpip 端口")
                CMD_AUTH -> {
                    if (message.arg0 != AUTH_TOKEN) {
                        throw IOException("adbd 发来意外的 AUTH 类型：${message.arg0}")
                    }
                    when {
                        !signatureSent -> {
                            writeMessage(output, CMD_AUTH, AUTH_SIGNATURE, 0, signer.sign(message.payload))
                            signatureSent = true
                        }

                        !publicKeySent -> {
                            val publicKey = encodeAdbPublicKey(signer.publicKey, publicKeyName) + "\u0000"
                            writeMessage(output, CMD_AUTH, AUTH_RSAPUBLICKEY, 0, publicKey.toByteArray(Charsets.UTF_8))
                            publicKeySent = true
                            socket.soTimeout = authorizationTimeoutMillis
                            onAuthorizationRequested()
                        }

                        else -> throw AdbAuthorizationException("「允许 USB 调试」被拒绝")
                    }
                }

                else -> Unit
            }
        }
    }

    private fun runShell(input: DataInputStream, output: java.io.OutputStream, command: String): String {
        val localId = 1
        var remoteId = 0
        writeMessage(output, CMD_OPEN, localId, 0, "shell:$command\u0000".toByteArray(Charsets.UTF_8))
        val result = ByteArrayOutputStream()
        while (true) {
            val message = try {
                readMessage(input)
            } catch (error: EOFException) {
                // adbd 关连接前没发 CLSE：已经拿到的输出照样返回。
                return result.toString(Charsets.UTF_8.name())
            }
            if (message.arg1 != localId) {
                continue
            }
            when (message.command) {
                CMD_OKAY -> remoteId = message.arg0
                CMD_WRTE -> {
                    remoteId = message.arg0
                    result.write(message.payload)
                    writeMessage(output, CMD_OKAY, localId, remoteId, ByteArray(0))
                }

                CMD_CLSE -> {
                    if (remoteId == 0) {
                        throw IOException("adbd 拒绝打开 shell 服务")
                    }
                    writeMessage(output, CMD_CLSE, localId, remoteId, ByteArray(0))
                    return result.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    internal class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    internal companion object {
        const val CMD_CNXN = 0x4e584e43
        const val CMD_AUTH = 0x48545541
        const val CMD_OPEN = 0x4e45504f
        const val CMD_OKAY = 0x59414b4f
        const val CMD_CLSE = 0x45534c43
        const val CMD_WRTE = 0x45545257
        const val CMD_STLS = 0x534c5453
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3
        const val ADB_VERSION = 0x01000001
        const val MAX_PAYLOAD = 256 * 1024
        private const val HEADER_SIZE = 24
        private const val MAX_ACCEPTED_PAYLOAD = 1024 * 1024

        fun writeMessage(output: java.io.OutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
            val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(command)
            header.putInt(arg0)
            header.putInt(arg1)
            header.putInt(payload.size)
            header.putInt(payload.fold(0) { sum, byte -> sum + (byte.toInt() and 0xff) })
            header.putInt(command.inv())
            output.write(header.array())
            if (payload.isNotEmpty()) {
                output.write(payload)
            }
            output.flush()
        }

        fun readMessage(input: DataInputStream): AdbMessage {
            val headerBytes = ByteArray(HEADER_SIZE)
            input.readFully(headerBytes)
            val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val command = header.int
            val arg0 = header.int
            val arg1 = header.int
            val length = header.int
            header.int // checksum：协议版本 0x01000001 起不再校验
            val magic = header.int
            if (magic != command.inv()) {
                throw IOException("adb 消息头损坏")
            }
            if (length < 0 || length > MAX_ACCEPTED_PAYLOAD) {
                throw IOException("adb 消息长度异常：$length")
            }
            val payload = ByteArray(length)
            input.readFully(payload)
            return AdbMessage(command, arg0, arg1, payload)
        }

        /** Android adbd 的公钥格式（mincrypt RSAPublicKey 结构的 base64 + 空格 + 名字），见 adb 源码 android_pubkey.cpp。 */
        fun encodeAdbPublicKey(publicKey: RSAPublicKey, name: String): String {
            val modulus = publicKey.modulus
            val wordCount = ADB_KEY_BITS / 32
            require(modulus.bitLength() <= ADB_KEY_BITS) { "adb 只接受 2048 位 RSA 公钥" }
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = r32.subtract(modulus.mod(r32).modInverse(r32))
            val rr = BigInteger.ONE.shiftLeft(ADB_KEY_BITS * 2).mod(modulus)
            val buffer = ByteBuffer.allocate(4 + 4 + ADB_KEY_BYTES + ADB_KEY_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(wordCount)
            buffer.putInt(n0inv.toInt())
            buffer.put(toLittleEndian(modulus))
            buffer.put(toLittleEndian(rr))
            buffer.putInt(publicKey.publicExponent.toInt())
            return Base64.getEncoder().encodeToString(buffer.array()) + " " + name
        }

        private fun toLittleEndian(value: BigInteger): ByteArray {
            val bigEndian = value.toByteArray()
            val result = ByteArray(ADB_KEY_BYTES)
            // toByteArray() 可能带一个符号位的前导 0，也可能比 256 字节短；按低位对齐拷贝后整体反转。
            val copyLength = minOf(bigEndian.size, ADB_KEY_BYTES)
            System.arraycopy(bigEndian, bigEndian.size - copyLength, result, ADB_KEY_BYTES - copyLength, copyLength)
            result.reverse()
            return result
        }

        /** SHA-1 的 DigestInfo 前缀：adbd 用 RSA_sign(NID_sha1, token) 校验，token 本身被当成摘要。 */
        val SHA1_DIGEST_INFO_PREFIX: ByteArray = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )

        const val ADB_KEY_BITS = 2048
        private const val ADB_KEY_BYTES = ADB_KEY_BITS / 8
    }
}
