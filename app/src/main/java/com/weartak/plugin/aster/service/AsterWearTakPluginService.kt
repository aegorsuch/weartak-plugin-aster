package com.weartak.plugin.aster.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine

class AsterWearTakPluginService : Service() {
    private val lifecycle = PluginLifecycleMachine()
    private val binder by lazy { AsterWearTakPluginBinder(lifecycle) }

    override fun onCreate() {
        super.onCreate()
        AsterPluginRuntime.initialize(applicationContext)
        AsterPluginRuntime.updateLifecycle(lifecycle.snapshot())
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != WearTakPluginContract.ACTION_PLUGIN_SERVICE) {
            Log.w(TAG, "rejected unexpected bind action=${intent?.action}")
            return null
        }
        lifecycle.dispatch(PluginLifecycleEvent.BIND_ATTACHED)
        AsterPluginRuntime.updateLifecycle(lifecycle.snapshot())
        return binder
    }

    override fun onDestroy() {
        lifecycle.dispatch(PluginLifecycleEvent.RESET)
        AsterPluginRuntime.detachHost()
        AsterPluginRuntime.updateLifecycle(lifecycle.snapshot())
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AsterWearTakService"
    }
}
