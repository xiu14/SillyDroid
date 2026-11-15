package com.jm.sillydroid.data.runtime

import android.content.Context
import android.os.Looper
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.domain.app.SillyDroidAppGraphProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 进程内唯一的 [BootstrapSessionManager] 持有者。
 *
 * 以前会话管理器在 [StartupCoordinatorService.onCreate] 里创建。startForegroundService 之后，
 * 服务的 onCreate / onStartCommand 要排在主线程队列里，等 MainActivity 的 onCreate、onResume、
 * 第一帧（含 WebView 内核初始化）全部做完才执行，Node 因此在点开 App 后白等约 0.4 秒。
 * 现在 [DefaultHostProcessManager.start] 在前台服务启动请求成功后直接从这里拿到会话管理器开始启动，
 * 服务起来后拿到的是同一个实例，只负责前台通知和保活；服务销毁时照旧关闭会话。
 *
 * 只能在主线程调用：服务生命周期和 Activity 的启动入口都在主线程，不需要额外加锁。
 */
internal object BootstrapSessionHost {
    private var manager: BootstrapSessionManager? = null
    private var scope: CoroutineScope? = null

    @Volatile
    private var snapshotListener: ((BootstrapSessionSnapshot) -> Unit)? = null

    /** 拿到当前会话管理器，没有就新建；新建时复位内存快照，避免上一个服务实例的残留状态被新会话误用。 */
    fun obtain(context: Context): BootstrapSessionManager {
        manager?.let { return it }
        val appContext = context.applicationContext
        val graph = (appContext as SillyDroidAppGraphProvider).sillyDroidAppGraph
        BootstrapSessionRuntimeStore.reset()
        graph.runtimeLogManager.initializeForAppStart()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val created = BootstrapSessionManager(
            context = appContext,
            scope = sessionScope,
            runtimeLogs = graph.runtimeLogManager,
            appForegroundState = graph.appForegroundState,
            hostPreferences = graph.hostConfigStore,
            settingsConfig = graph.tavernConfigRepository(),
            onSnapshotChanged = { snapshot -> snapshotListener?.invoke(snapshot) }
        )
        scope = sessionScope
        manager = created
        return created
    }

    /** 前台服务注册通知刷新回调；服务销毁前传 null 取消。 */
    fun setSnapshotListener(listener: ((BootstrapSessionSnapshot) -> Unit)?) {
        snapshotListener = listener
    }

    /** 服务销毁时调用：停掉会话和本地服务进程，下次启动重新创建。 */
    fun release() {
        snapshotListener = null
        manager?.close()
        scope?.cancel()
        manager = null
        scope = null
    }
}

/** 前台服务启动请求成功后，在主线程上立即开始启动会话，不等服务实例被创建。 */
fun interface EarlyBootstrapSessionStarter {
    fun startEarly(context: Context)
}

internal object AndroidEarlyBootstrapSessionStarter : EarlyBootstrapSessionStarter {
    override fun startEarly(context: Context) {
        // 只在主线程提前启动；其他线程调用时交给服务的 onStartCommand，和以前一样。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return
        }
        if (context.applicationContext !is SillyDroidAppGraphProvider) {
            return
        }
        BootstrapSessionHost.obtain(context).start(forceRestart = false)
    }
}
