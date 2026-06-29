package com.jm.sillydroid.data.runtime

import android.content.Context
import android.content.ContextWrapper
import com.jm.sillydroid.core.common.DispatcherProvider
import com.jm.sillydroid.core.model.bootstrap.BootstrapLifecycle
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepId
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepResult
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepStatus
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 纯 JVM 单测：Android 12+ 可能同步拒绝 startForegroundService，宿主必须记录启动失败而不是让 Activity 崩溃。
 */
class HostProcessManagerForegroundServiceStartTest {

    @Before
    fun resetRuntimeStore() {
        BootstrapSessionRuntimeStore.reset()
    }

    @Test
    fun `foreground service start rejection records bootstrap failure instead of throwing`() {
        val processManager = DefaultHostProcessManager(
            context = TestContext(),
            dispatchers = TestDispatchers,
            serviceStarter = StartupCoordinatorServiceStarter { _, _ ->
                throw IllegalStateException("startForegroundService() not allowed due to mAllowStartForeground false")
            }
        )

        processManager.start(retry = false)

        val snapshot = processManager.currentSnapshot()
        val failedStep = snapshot.findStep(BootstrapStepId.START_SERVER_PROCESS)
        assertEquals(BootstrapLifecycle.FAILED_ERROR, snapshot.lifecycle)
        assertEquals(BootstrapStepId.START_SERVER_PROCESS, snapshot.lastFailure?.stepId)
        assertEquals("ForegroundServiceStartRejected", snapshot.lastFailure?.errorKind)
        assertEquals(BootstrapStepStatus.FAILED, failedStep?.status)
        assertEquals(BootstrapStepResult.FAILED_ERROR, failedStep?.result)
        assertTrue(snapshot.statusDetails.contains("Android 系统拒绝启动前台服务"))
    }

    @Test
    fun `unexpected service start errors are still rethrown`() {
        val processManager = DefaultHostProcessManager(
            context = TestContext(),
            dispatchers = TestDispatchers,
            serviceStarter = StartupCoordinatorServiceStarter { _, _ ->
                throw IllegalArgumentException("Unexpected bad intent")
            }
        )

        assertThrows(IllegalArgumentException::class.java) {
            processManager.start(retry = false)
        }
    }

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private object TestDispatchers : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val mainImmediate = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }
}
