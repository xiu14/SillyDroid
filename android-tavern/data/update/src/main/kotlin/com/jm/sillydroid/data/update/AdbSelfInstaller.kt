package com.jm.sillydroid.data.update

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.jm.sillydroid.domain.update.AppUpdateAdbInstallResult
import java.net.ConnectException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec

/**
 * adb 认证私钥放在 Android Keystore 里、不可导出：酒馆的服务端插件和 App 同一个 uid，
 * 私钥要是落成普通文件，插件就能读走它拿到 adb shell。Keystore 只给 App 进程签名用。
 */
internal class AndroidKeyStoreAdbSigner(
    private val alias: String = DEFAULT_ALIAS
) : AdbAuthSigner {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) } }

    override val publicKey: RSAPublicKey
        get() {
            ensureKey()
            return keyStore.getCertificate(alias).publicKey as RSAPublicKey
        }

    override fun sign(token: ByteArray): ByteArray {
        ensureKey()
        val privateKey = keyStore.getKey(alias, null) as PrivateKey
        return Signature.getInstance("NONEwithRSA").run {
            initSign(privateKey)
            update(LocalAdbClient.SHA1_DIGEST_INFO_PREFIX)
            update(token)
            sign()
        }
    }

    @Synchronized
    private fun ensureKey() {
        if (keyStore.containsAlias(alias)) {
            return
        }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(LocalAdbClient.ADB_KEY_BITS, RSAKeyGenParameterSpec.F4))
            .setDigests(KeyProperties.DIGEST_NONE)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEY_STORE).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "stdroid-self-update-adb"
    }
}

/**
 * 用本机 adb（shell 身份）安装已下载并校验过的更新包：adb 装的包不走 ColorOS 安装器的风险确认和密码。
 * 安装在 adbd 那边用 setsid 脱离会话后台执行，App 进程在安装提交时被系统结束也不影响；装完用 am start 重新打开 App。
 */
internal object AdbSelfInstallCommand {
    const val STARTED_MARKER = "STDROID_INSTALL_STARTED"
    const val UNREADABLE_MARKER = "STDROID_APK_UNREADABLE"
    const val SHA_MISMATCH_MARKER = "STDROID_SHA_MISMATCH"
    const val LOG_PATH = "/data/local/tmp/stdroid-adb-install.log"

    private val safeArgument = Regex("^[A-Za-z0-9._/:-]+$")
    private val sha256Hex = Regex("^[0-9a-f]{64}$")

    fun build(apkPath: String, expectedSha256: String, launchComponent: String): String {
        require(safeArgument.matches(apkPath)) { "更新包路径含有不支持的字符：$apkPath" }
        require(safeArgument.matches(launchComponent)) { "启动组件名含有不支持的字符：$launchComponent" }
        val sha = expectedSha256.trim().lowercase()
        require(sha.isEmpty() || sha256Hex.matches(sha)) { "SHA-256 格式不对：$expectedSha256" }
        return buildString {
            append("f='").append(apkPath).append("'; ")
            append("[ -r \"\$f\" ] || { echo ").append(UNREADABLE_MARKER).append("; exit 0; }; ")
            if (sha.isNotEmpty()) {
                append("s=\$(sha256sum \"\$f\"); s=\${s%% *}; ")
                append("[ \"\$s\" = '").append(sha).append("' ] || { echo \"").append(SHA_MISMATCH_MARKER).append(" \$s\"; exit 0; }; ")
            }
            // pm install <路径> 是 system_server 自己去打开文件，SELinux 不让它读 /storage/emulated（fuse）；
            // 由 shell 读文件、经管道交给 pm install -S（adb install 本身也是这么做的）。
            append("setsid nohup sh -c ")
            append("'n=\$(stat -c %s \"\$1\") && cat \"\$1\" | pm install -r -S \"\$n\" > ").append(LOG_PATH)
            append(" 2>&1 && am start -n \"\$2\" >> ").append(LOG_PATH).append(" 2>&1' ")
            append("stdroid-install \"\$f\" '").append(launchComponent).append("' ")
            append("< /dev/null > /dev/null 2>&1 & ")
            append("echo ").append(STARTED_MARKER)
        }
    }

    fun parse(output: String): AppUpdateAdbInstallResult {
        return when {
            output.contains(STARTED_MARKER) -> AppUpdateAdbInstallResult.Started
            output.contains(UNREADABLE_MARKER) -> AppUpdateAdbInstallResult.Unavailable("adb shell 读不到更新包")
            output.contains(SHA_MISMATCH_MARKER) -> AppUpdateAdbInstallResult.Unavailable("adb shell 读到的更新包 SHA-256 不一致")
            else -> AppUpdateAdbInstallResult.Unavailable("adb 命令输出异常：${output.trim().take(200)}")
        }
    }

    fun describeFailure(error: Throwable): String {
        return when (error) {
            is ConnectException -> "本机 adb（127.0.0.1:5555）连不上：手机重启后要先开 adb tcpip 5555"
            is AdbAuthorizationException -> error.message.orEmpty()
            else -> "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(240)
        }
    }
}
