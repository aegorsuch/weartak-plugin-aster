package com.weartak.plugin.aster.service

import android.content.Intent
import android.os.Bundle
import com.weartak.plugin.aster.BuildConfig
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.model.CotReadyEventParcel
import com.weartak.plugin.sdk.model.PluginErrorParcel
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SensorEventParcel
import com.weartak.plugin.sdk.model.SharedBufferParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsterHostLifecycleTest {
    @Test
    fun serviceDiscoveryAndActionFailuresAreReportedWithoutNativeRuntime() {
        val service = Robolectric.buildService(AsterWearTakPluginService::class.java).create()
        try {
            val plugin = IWearTakPlugin.Stub.asInterface(
                service.get().onBind(Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE)),
            )
            assertEquals(BuildConfig.APPLICATION_ID, plugin.pluginInfo.pluginId)
            assertEquals(PluginLifecycleState.BOUND, plugin.status.state)
            assertTerminalFailure(withFixture = false, expectedMessage = "reference.bundle missing")
            assertTerminalFailure(withFixture = true, expectedMessage = "native runtime is unavailable")
        } finally {
            service.destroy()
        }
    }

    @Test
    fun legacyAndV2InitializationBothAllowStart() {
        for (legacy in listOf(true, false)) {
            val binder = boundPlugin()
            val init = hostInit()
            if (legacy) binder.initializePlugin(init, null)
            else binder.initializePluginV2(init, null, null)
            assertEquals(PluginLifecycleState.READY, binder.status.state)
            binder.startPlugin()
            assertEquals(PluginLifecycleState.RUNNING, binder.status.state)
        }
    }

    @Test
    fun hostActionsBeforeStartAndAfterStopDoNotScheduleNativeWork() {
        val binder = boundPlugin()
        binder.invokeMainUiAction(AsterPluginRuntime.ACTION_PUBLISH_EVENT, Bundle())
        assertRejected(binder)
        binder.initializePluginV2(hostInit(), null, null)
        binder.startPlugin()
        binder.stopPlugin()
        binder.invokeMainUiAction(AsterPluginRuntime.ACTION_PUBLISH_EVENT, Bundle())
        assertRejected(binder)
        assertEquals(PluginLifecycleState.STOPPED, binder.status.state)
        val beforeShutdown = binder.mainUiState
        binder.shutdownPlugin()
        binder.invokeMainUiAction("after-shutdown", Bundle())
        assertEquals(
            beforeShutdown.getString("last_action"),
            binder.mainUiState.getString("last_action"),
        )
    }

    private fun assertTerminalFailure(withFixture: Boolean, expectedMessage: String) {
        val context = RuntimeEnvironment.getApplication()
        AsterPluginRuntime.initialize(context)
        val bundle = File(context.filesDir, "aster-evaluation/reference.bundle")
        if (withFixture) {
            assertTrue(bundle.parentFile!!.mkdirs())
            bundle.writeText("synthetic-test-only")
        }
        val binder = boundPlugin()
        val completed = CountDownLatch(1)
        val reported = AtomicReference<PluginErrorParcel?>()
        binder.registerHostCallback(object : IWearTakHostCallback.Stub() {
            override fun onSensorEvent(event: SensorEventParcel?) = Unit
            override fun onSensorBatchEvent(events: MutableList<SensorEventParcel>?) = Unit
            override fun onCotReadyEvent(event: CotReadyEventParcel?) = Unit
            override fun onPluginSettingsSchemaChanged() = Unit
            override fun onPluginSettingsValuesChanged() = Unit
            override fun onPluginMainUiSchemaChanged() = Unit
            override fun onPluginMainUiStateChanged() = Unit
            override fun onPluginStatusChanged(status: PluginStatusParcel?) = Unit
            override fun onSharedBufferAvailable(buffer: SharedBufferParcel?) = Unit
            override fun onPluginError(error: PluginErrorParcel?) {
                reported.set(error)
                completed.countDown()
            }
        })
        try {
            binder.initializePluginV2(hostInit(), null, null)
            binder.startPlugin()
            binder.invokeMainUiAction(AsterPluginRuntime.ACTION_PUBLISH_EVENT, Bundle())
            assertTrue("No terminal error callback", completed.await(10, TimeUnit.SECONDS))
            val state = binder.mainUiState
            assertFalse(state.getBoolean("action_pending"))
            assertEquals("failed", state.getString("action_state"))
            assertTrue(state.getString("last_message")!!.contains(expectedMessage))
            assertTrue(reported.get()!!.message.contains(expectedMessage))
        } finally {
            binder.shutdownPlugin()
            if (withFixture) assertTrue(bundle.delete())
        }
    }

    private fun boundPlugin(): AsterWearTakPluginBinder {
        val lifecycle = PluginLifecycleMachine()
        lifecycle.dispatch(PluginLifecycleEvent.BIND_ATTACHED)
        return AsterWearTakPluginBinder(lifecycle)
    }

    private fun hostInit() = PluginInitParcel(
        hostPackageName = "com.weartak.test.host",
        hostUid = 12345,
        pluginIdExpected = BuildConfig.APPLICATION_ID,
        hostExtras = Bundle(),
    )

    private fun assertRejected(binder: AsterWearTakPluginBinder) {
        val state = binder.mainUiState
        assertEquals("failed", state.getString("action_state"))
        assertFalse(state.getBoolean("action_pending"))
        assertTrue(state.getString("last_message")!!.contains("Start the Aster plugin"))
    }
}
