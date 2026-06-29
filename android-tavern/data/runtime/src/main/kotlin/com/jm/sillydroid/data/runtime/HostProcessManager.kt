package com.jm.sillydroid.data.runtime
import android.content.Context
import androidx.core.content.ContextCompat
import com.jm.sillydroid.core.model.bootstrap.BootstrapEvent
import com.jm.sillydroid.core.model.bootstrap.BootstrapFailureSnapshot
import com.jm.sillydroid.core.model.bootstrap.BootstrapLifecycle
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepId
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepResult
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepStatus
import com.jm.sillydroid.domain.bootstrap.BootstrapController
import com.jm.sillydroid.core.common.DispatcherProvider
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

interface HostProcessManager : BootstrapController {
    override fun start(retry: Boolean)

    fun stopForSettings()

    override fun restart() {
        start(retry = true)
    }

}

class DefaultHostProcessManager(
    context: Context,
    private val dispatchers: DispatcherProvider,
    private val serviceStarter: StartupCoordinatorServiceStarter = AndroidStartupCoordinatorServiceStarter
) : HostProcessManager {
    private val appContext = context.applicationContext

    private val stoppedLifecycles = setOf(
        BootstrapLifecycle.CONFIGURING,
        BootstrapLifecycle.IDLE,
        BootstrapLifecycle.FAILED_BLOCKED,
        BootstrapLifecycle.FAILED_ERROR,
        BootstrapLifecycle.STOPPED
    )

    override val snapshot: StateFlow<BootstrapSessionSnapshot>
        get() = BootstrapSessionRuntimeStore.snapshot

    override val events: SharedFlow<BootstrapEvent>
        get() = BootstrapSessionRuntimeStore.events

    override fun currentSnapshot(): BootstrapSessionSnapshot {
        return snapshot.value
    }

    override fun start(retry: Boolean) {
        try {
            serviceStarter.startForegroundService(appContext, retry)
        } catch (error: RuntimeException) {
            if (!error.isRecoverableForegroundServiceStartError()) {
                throw error
            }
            recordForegroundServiceStartRejected(error)
        }
    }

    override fun stopForSettings() {
        appContext.startService(StartupCoordinatorService.createStopForSettingsIntent(appContext))
    }

    override suspend fun stopForSettingsAndAwait(timeoutMessage: String) {
        val timeoutMillis = 15_000L
        if (currentSnapshot().lifecycle !in stoppedLifecycles) {
            withContext(dispatchers.mainImmediate) {
                stopForSettings()
            }
        }

        val stoppedState = withTimeoutOrNull(timeoutMillis) {
            snapshot.first { sessionSnapshot ->
                sessionSnapshot.lifecycle in stoppedLifecycles
            }
        }

        if (stoppedState == null) {
            throw BootstrapException(BootstrapError.RuntimeStopTimeout(timeoutMessage))
        }
    }

    private fun recordForegroundServiceStartRejected(error: RuntimeException) {
        val now = System.currentTimeMillis()
        val stepId = BootstrapStepId.START_SERVER_PROCESS
        val details = "Android 系统拒绝启动前台服务，已停止本次启动以避免 App 闪退。请保持 SillyDroid 在前台后重试。"
        val current = currentSnapshot()
        val steps = current.steps.map { step ->
            if (step.id == stepId) {
                step.copy(
                    status = BootstrapStepStatus.FAILED,
                    result = BootstrapStepResult.FAILED_ERROR,
                    details = details,
                    startedAtMillis = step.startedAtMillis.takeIf { it > 0L } ?: now,
                    finishedAtMillis = now
                )
            } else {
                step
            }
        }
        val failure = BootstrapFailureSnapshot(
            stepId = stepId,
            title = "无法启动 SillyDroid 启动服务",
            details = details,
            isBlocked = false,
            throwableType = error.javaClass.name,
            errorKind = "ForegroundServiceStartRejected",
            happenedAtMillis = now
        )
        val next = current.copy(
            lifecycle = BootstrapLifecycle.FAILED_ERROR,
            currentStepId = stepId,
            steps = steps,
            statusMessage = "无法启动 SillyDroid 启动服务。",
            statusDetails = details,
            lastFailure = failure,
            lastEventSummary = details,
            lastEventAtMillis = now
        )
        BootstrapSessionRuntimeStore.update(next)
        BootstrapSessionRuntimeStore.tryEmit(
            BootstrapEvent.StepFailed(
                appSessionId = next.appSessionId,
                attemptId = next.attemptId,
                happenedAtMillis = now,
                stepId = stepId,
                blocked = false,
                details = details
            )
        )
    }
}

fun interface StartupCoordinatorServiceStarter {
    fun startForegroundService(context: Context, retry: Boolean)
}

private object AndroidStartupCoordinatorServiceStarter : StartupCoordinatorServiceStarter {
    override fun startForegroundService(context: Context, retry: Boolean) {
        val intent = StartupCoordinatorService.createStartIntent(context, retry = retry)
        ContextCompat.startForegroundService(context, intent)
    }
}

private fun RuntimeException.isRecoverableForegroundServiceStartError(): Boolean {
    val className = javaClass.name
    val message = message.orEmpty()
    // Android 12+ 在受限时机可能同步拒绝前台服务启动；该错误属于用户可重试的启动状态，
    // 不能继续冒泡到 Activity，否则会变成远端 crash-log 289 这类主进程闪退。
    return className.contains("ForegroundServiceStartNotAllowedException") ||
        className.contains("ServiceStartNotAllowedException") ||
        message.contains("startForegroundService", ignoreCase = true) ||
        message.contains("mAllowStartForeground", ignoreCase = true)
}
