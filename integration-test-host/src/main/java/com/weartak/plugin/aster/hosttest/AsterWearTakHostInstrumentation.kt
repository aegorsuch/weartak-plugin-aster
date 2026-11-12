package com.weartak.plugin.aster.hosttest

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.model.CotReadyEventParcel
import com.weartak.plugin.sdk.model.PluginErrorParcel
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SensorEventParcel
import com.weartak.plugin.sdk.model.SharedBufferParcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AsterWearTakHostInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        start()
    }

    override fun onStart() {
        try {
            val result = runProbe()
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("result", result)
            })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("error", error.stackTraceToString())
            })
        }
    }

    private fun runProbe(): String {
        val context = targetContext
        val binding = bindPlugin(context)
        val plugin = binding.plugin
        val resultCount = Semaphore(0)
        val armed = AtomicBoolean(false)
        val callbackError = AtomicReference<String?>()
        val callback = object : IWearTakHostCallback.Stub() {
            override fun onSensorEvent(event: SensorEventParcel?) = Unit
            override fun onSensorBatchEvent(events: MutableList<SensorEventParcel>?) = Unit
            override fun onCotReadyEvent(event: CotReadyEventParcel?) = Unit
            override fun onPluginSettingsSchemaChanged() = Unit
            override fun onPluginSettingsValuesChanged() = Unit
            override fun onPluginMainUiSchemaChanged() = Unit
            override fun onPluginMainUiStateChanged() {
                if (armed.get()) resultCount.release()
            }
            override fun onPluginStatusChanged(status: PluginStatusParcel?) = Unit
            override fun onPluginError(error: PluginErrorParcel?) {
                callbackError.set(error?.message)
            }
            override fun onSharedBufferAvailable(buffer: SharedBufferParcel?) = Unit
        }
        var bound = true
        try {
            val info = plugin.pluginInfo
            check(info.pluginId == PLUGIN_PACKAGE) { "Unexpected plugin id: ${info.pluginId}" }
            check(info.packageName == PLUGIN_PACKAGE) { "Unexpected plugin package: ${info.packageName}" }
            check(plugin.apiVersion == WearTakPluginContract.CONTRACT_API_VERSION) {
                "Unexpected plugin API version."
            }
            check(plugin.capabilities.hostedMainUi) { "Plugin did not expose hosted main UI." }
            check(plugin.mainUiSchema.cards.any { it.cardId == "aster-status" }) {
                "Plugin main UI schema was not returned over Binder."
            }

            plugin.registerHostCallback(callback)
            plugin.initializePluginV2(
                PluginInitParcel(
                    hostPackageName = context.packageName,
                    hostUid = context.applicationInfo.uid,
                    pluginIdExpected = PLUGIN_PACKAGE,
                    hostExtras = Bundle(),
                ),
                null,
                null,
            )
            plugin.startPlugin()
            check(plugin.status.state.name == "RUNNING") { "Plugin failed to start." }

            armed.set(true)
            plugin.invokeMainUiAction(PUBLISH_ACTION, Bundle())
            check(resultCount.tryAcquire(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "No SDK callback arrived after the host action."
            }
            val published = plugin.mainUiState
            check(published.getString("last_action") == PUBLISH_ACTION) {
                "Host action was not recorded: ${published.getString("last_action")}"
            }
            check(published.getString("action_state") == "succeeded") {
                "Event publish failed: ${published.getString("last_message")}; callback=$callbackError"
            }
            val eventId = requireNotNull(published.getString("event_id")) {
                "Host received no Event ID."
            }
            check(EVENT_ID_PATTERN.matches(eventId)) { "Invalid Event ID: $eventId" }
            check(published.getString("last_message")?.contains(eventId) == true) {
                "Host did not receive Event receipt through main UI state."
            }

            callbackError.set(null)
            plugin.invokeMainUiAction(PUBLISH_ACTION, Bundle())
            check(resultCount.tryAcquire(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "No SDK callback arrived after Event restart verification."
            }
            val reopened = plugin.mainUiState
            check(reopened.getString("action_state") == "succeeded") {
                "Event restart read failed: ${reopened.getString("last_message")}; callback=$callbackError"
            }
            check(reopened.getString("event_id") == eventId) {
                "Event ID changed after restart verification."
            }

            return "WEARTAK_SDK_BINDER_PUBLISH_RESTART_READ_OK event=$eventId"
        } finally {
            try {
                plugin.shutdownPlugin()
                plugin.unregisterHostCallback(callback)
            } catch (error: RemoteException) {
                android.util.Log.w(TAG, "Plugin shutdown callback failed", error)
            } finally {
                context.unbindService(binding.connection)
            }
        }
    }

    private fun bindPlugin(context: Context): BoundPlugin {
        val connected = CountDownLatch(1)
        var plugin: IWearTakPlugin? = null
        val intent = Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE).apply {
            component = ComponentName(
                PLUGIN_PACKAGE,
                "$PLUGIN_PACKAGE.service.AsterWearTakPluginService",
            )
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                plugin = IWearTakPlugin.Stub.asInterface(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected.countDown()
            }

            override fun onBindingDied(name: ComponentName?) {
                connected.countDown()
            }

            override fun onNullBinding(name: ComponentName?) {
                connected.countDown()
            }
        }
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            "WearTAK SDK service bind was rejected."
        }
        try {
            check(connected.await(30, TimeUnit.SECONDS)) { "WearTAK SDK service bind timed out." }
            return BoundPlugin(
                plugin = requireNotNull(plugin) { "WearTAK SDK returned no plugin Binder." },
                connection = connection,
            )
        } catch (error: Exception) {
            context.unbindService(connection)
            throw error
        }
    }

    private data class BoundPlugin(
        val plugin: IWearTakPlugin,
        val connection: ServiceConnection,
    )

    private companion object {
        const val TAG = "AsterHostInstrumentation"
        const val PLUGIN_PACKAGE = "com.weartak.plugin.aster"
        const val PUBLISH_ACTION = "aster.event.publish"
        const val ACTION_TIMEOUT_SECONDS = 180L
        val EVENT_ID_PATTERN = Regex("[0-9a-f]{64}")
    }
}
