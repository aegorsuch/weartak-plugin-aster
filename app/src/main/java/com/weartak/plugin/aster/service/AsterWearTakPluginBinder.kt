package com.weartak.plugin.aster.service

import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import com.weartak.plugin.aster.BuildConfig
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.binder.PluginBinderErrorReporter
import com.weartak.plugin.sdk.binder.PluginBinderGuard
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleTransition
import com.weartak.plugin.sdk.model.MainUiCardParcel
import com.weartak.plugin.sdk.model.MainUiCardTypes
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.PluginErrorCodes
import com.weartak.plugin.sdk.model.PluginErrorParcel
import com.weartak.plugin.sdk.model.PluginInfoParcel
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SettingsApplyMode
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.model.SettingsValidationErrorParcel
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.MainUiSchemaContractValidator
import com.weartak.plugin.sdk.validation.PluginMetadataValidator
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2

class AsterWearTakPluginBinder(
    private val lifecycle: PluginLifecycleMachine,
) : IWearTakPlugin.Stub() {
    @Volatile
    private var hostCallback: IWearTakHostCallback? = null

    private val guard by lazy { PluginBinderGuard(TAG, lifecycle) }

    override fun getPluginInfo() = PluginInfoParcel(
        pluginId = BuildConfig.APPLICATION_ID,
        displayName = "WearTAK Aster Evaluation",
        versionName = BuildConfig.VERSION_NAME,
        packageName = BuildConfig.APPLICATION_ID,
        description = "Experimental Aster Event persistence through the WearTAK plugin SDK.",
    )

    override fun getCapabilities() = PluginCapabilitiesParcel(
        hostedMainUi = true,
        externalMainActivity = true,
    )

    override fun getApiVersion() = WearTakPluginContract.CONTRACT_API_VERSION

    override fun getStatus(): PluginStatusParcel = guard.query(
        operation = "getStatus",
        fallback = PluginStatusParcel(lifecycle.snapshot(), message = "unavailable"),
    ) {
        AsterPluginRuntime.getStatus(lifecycle.snapshot())
    }

    override fun initializePlugin(init: PluginInitParcel?, pluginControl: IWearTakPluginControl?) {
        initializeHost("initializePlugin", init)
    }

    override fun initializePluginV2(
        init: PluginInitParcel?,
        pluginControl: IWearTakPluginControl?,
        pluginControlV2: IWearTakPluginHostControlV2?,
    ) {
        initializeHost("initializePluginV2", init)
    }

    private fun initializeHost(operation: String, init: PluginInitParcel?) {
        guard.command(
            operation = operation,
            onShutdown = { emitIllegalState(operation) },
        ) {
            if (init != null) {
                when (
                    val result = PluginMetadataValidator.validateInitAgainstPlugin(
                        init,
                        declaredPluginId = BuildConfig.APPLICATION_ID,
                    )
                ) {
                    is ContractValidationResult.Ok -> Unit
                    is ContractValidationResult.Err ->
                        throw IllegalArgumentException("Invalid host initialization: ${result.issues}")
                }
            }
            dispatch(PluginLifecycleEvent.INITIALIZED)
        }
    }

    override fun startPlugin() {
        guard.command(
            operation = "startPlugin",
            onShutdown = { emitIllegalState("startPlugin") },
        ) {
            dispatch(PluginLifecycleEvent.HOST_STARTED)
        }
    }

    override fun stopPlugin() {
        guard.command(
            operation = "stopPlugin",
            onShutdown = { emitIllegalState("stopPlugin") },
        ) {
            dispatch(PluginLifecycleEvent.HOST_STOPPED)
        }
    }

    override fun shutdownPlugin() {
        guard.command(
            operation = "shutdownPlugin",
            onShutdown = { emitIllegalState("shutdownPlugin") },
        ) {
            hostCallback = null
            AsterPluginRuntime.setHostCallback(null)
            dispatch(PluginLifecycleEvent.HOST_SHUTDOWN)
        }
    }

    override fun registerHostCallback(callback: IWearTakHostCallback?) {
        guard.command(
            operation = "registerHostCallback",
            onShutdown = { emitIllegalState("registerHostCallback") },
        ) {
            hostCallback = callback
            AsterPluginRuntime.setHostCallback(callback)
            dispatch(PluginLifecycleEvent.HOST_CALLBACK_REGISTERED)
        }
    }

    override fun unregisterHostCallback(callback: IWearTakHostCallback?) {
        if (hostCallback === callback) {
            hostCallback = null
            AsterPluginRuntime.setHostCallback(null)
        }
    }

    override fun getSettingsSchema() = SettingsSchemaParcel()

    override fun getSettingsValues() = Bundle()

    override fun onHostSettingsChanged(hostSettings: com.weartak.plugin.sdk.model.HostSettingsParcel?) {
        Log.d(TAG, "host settings update received; no settings are declared")
    }

    override fun applySettingsWithResult(
        values: Bundle?,
        applyMode: Int,
    ): SettingsApplyResultParcel {
        val valid = values == null || values.isEmpty
        return SettingsApplyResultParcel(
            success = valid,
            applyMode = applyMode,
            values = Bundle(),
            restartRequired = false,
            anyValueChanged = false,
            errors = if (valid) {
                emptyList()
            } else {
                listOf(
                    SettingsValidationErrorParcel(
                        fieldId = "settings",
                        code = "UNSUPPORTED",
                        message = "Aster evaluation plugin declares no settings.",
                    ),
                )
            },
            hostDerivedFieldIds = emptyList(),
        )
    }

    override fun applySettings(values: Bundle?) {
        require(values == null || values.isEmpty) {
            "Aster evaluation plugin declares no settings."
        }
    }

    override fun getMainUiSchema(): PluginMainUiSchemaParcel {
        val state = AsterPluginRuntime.getMainUiState()
        val schema = PluginMainUiSchemaParcel(
            cards = listOf(
                MainUiCardParcel(
                    cardType = MainUiCardTypes.STATUS,
                    cardId = "aster-status",
                    title = "Aster Event (evaluation)",
                    subtitle = state.getString("last_message")?.let {
                        hostedSubtitle(it)
                    },
                ),
                MainUiCardParcel(
                    cardType = MainUiCardTypes.KEY_VALUE_LIST,
                    cardId = "aster-event",
                    title = "Latest Local Event",
                    subtitle = state.getString("event_id") ?: "No local Event committed.",
                ),
            ),
        )
        return when (
            val result = MainUiSchemaContractValidator.validate(
                schema,
                strictV1CardTypesOnly = true,
            )
        ) {
            is ContractValidationResult.Ok -> result.value
            is ContractValidationResult.Err -> MainUiSchemaContractValidator.sanitize(schema)
        }
    }

    override fun getMainUiState() = AsterPluginRuntime.getMainUiState()

    private fun hostedSubtitle(message: String): String {
        val bytes = message.toByteArray(Charsets.UTF_8)
        var end = minOf(bytes.size, WearTakPluginContract.MAX_MAIN_UI_TITLE_BYTES)
        // The bundled SDK's helper can retain a partial leading byte at this boundary.
        while (end > 0 && end < bytes.size && bytes[end].toInt() and 0xc0 == 0x80) {
            end--
        }
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    override fun invokeMainUiAction(actionId: String?, args: Bundle?) {
        guard.command(
            operation = "invokeMainUiAction",
            onShutdown = { emitIllegalState("invokeMainUiAction") },
        ) {
            if (lifecycle.snapshot() != PluginLifecycleState.RUNNING) {
                AsterPluginRuntime.rejectAction(
                    actionId ?: "unknown",
                    "Start the Aster plugin before invoking host actions.",
                )
            } else if (args != null && !args.isEmpty) {
                AsterPluginRuntime.rejectAction(
                    actionId ?: "unknown",
                    "This evaluation action accepts no arguments.",
                )
            } else {
                AsterPluginRuntime.invokeAction(actionId)
            }
        }
    }

    private fun dispatch(event: PluginLifecycleEvent) {
        when (val transition = lifecycle.dispatch(event)) {
            is PluginLifecycleTransition.Moved ->
                Log.d(TAG, "lifecycle ${transition.previous} -> ${transition.state}")
            is PluginLifecycleTransition.Rejected ->
                Log.w(TAG, "lifecycle transition rejected: ${transition.reason}")
            is PluginLifecycleTransition.Unchanged -> Unit
        }
        AsterPluginRuntime.updateLifecycle(lifecycle.snapshot())
    }

    private fun emitIllegalState(operation: String) {
        PluginBinderErrorReporter.report(
            tag = TAG,
            operation = operation,
            error = PluginErrorParcel(
                code = PluginErrorCodes.ILLEGAL_STATE,
                message = "$operation ignored after shutdown",
                fatal = false,
            ),
            callback = hostCallback,
        )
    }

    private companion object {
        const val TAG = "AsterWearTakPlugin"
    }
}
