package com.weartak.plugin.aster.service

import android.content.Context
import android.os.Bundle
import android.system.Os
import android.util.Log
import com.weartak.plugin.aster.spike.AsterProbe
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.binder.PluginHostNotifier
import com.weartak.plugin.sdk.model.PluginErrorCodes
import com.weartak.plugin.sdk.model.PluginErrorParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.PluginStatusParcel
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

object AsterPluginRuntime {
    const val ACTION_PUBLISH_EVENT = "aster.event.publish"

    private const val TAG = "AsterPluginRuntime"
    private const val SCOPE = "weartak/spike"
    private const val TOPIC = "jni-evaluation"
    private const val CONTROL_DIRECTORY_PINNED = "ANDROID_CONTROL_DIRECTORY_PINNED"
    private val stateLock = Any()

    private var appContext: Context? = null
    private var executor: ExecutorService? = null
    private var lifecycleState = PluginLifecycleState.BOUND
    private var hostCallback: IWearTakHostCallback? = null
    private var lastAction = "idle"
    private var actionState = "idle"
    private var lastMessage = "Waiting for WearTAK host."
    private var lastEventId: String? = null
    private var actionPending = false

    fun initialize(context: Context) {
        val application = context.applicationContext
        synchronized(stateLock) {
            val existing = appContext
            if (existing != null) {
                check(existing.filesDir.canonicalPath == application.filesDir.canonicalPath) {
                    "Aster runtime already initialized for a different app-private directory."
                }
                return
            }

            AsterProbe.initialize(application)
            val control = File(application.filesDir, "c")
            if (!control.exists()) {
                Os.mkdir(control.path, OWNER_ONLY_DIRECTORY_MODE)
            }
            check(!Files.isSymbolicLink(control.toPath())) {
                "Aster control root must not be a symlink."
            }
            check(Os.stat(control.path).st_mode and FILE_MODE_MASK == OWNER_ONLY_DIRECTORY_MODE) {
                "Aster control root must have owner-only permissions."
            }
            val controlPath = control.toPath().toRealPath().toString()
            check(AsterProbe.configureControlDirectory(controlPath) == CONTROL_DIRECTORY_PINNED) {
                "Aster control root was not pinned."
            }

            appContext = application
            if (executor == null || executor?.isShutdown == true) {
                executor = Executors.newSingleThreadExecutor { task ->
                    Thread(task, "aster-event-publisher").apply { isDaemon = true }
                }
            }
        }
    }

    fun setHostCallback(callback: IWearTakHostCallback?) {
        synchronized(stateLock) {
            hostCallback = callback
        }
    }

    fun detachHost() {
        synchronized(stateLock) {
            hostCallback = null
        }
    }

    fun updateLifecycle(state: PluginLifecycleState) {
        synchronized(stateLock) {
            lifecycleState = state
        }
        notifyHost()
    }

    fun getStatus(state: PluginLifecycleState): PluginStatusParcel =
        synchronized(stateLock) {
            PluginStatusParcel(
                state = state,
                message = lastMessage,
                extras = stateBundleLocked(),
            )
        }

    fun getMainUiState(): Bundle = synchronized(stateLock) {
        stateBundleLocked()
    }

    fun invokeAction(actionId: String?) {
        if (actionId != ACTION_PUBLISH_EVENT) {
            rejectAction(actionId ?: "unknown", "Unsupported Aster action.")
            return
        }

        val work = synchronized(stateLock) {
            val context = appContext
            val worker = executor
            when {
                context == null -> {
                    rejectActionLocked(actionId, "Aster native runtime is not initialized.")
                    null
                }
                actionPending -> {
                    rejectActionLocked(actionId, "An Aster Event operation is already running.")
                    null
                }
                worker == null || worker.isShutdown -> {
                    rejectActionLocked(actionId, "Aster Event worker is unavailable.")
                    null
                }
                else -> {
                    actionPending = true
                    lastAction = actionId
                    actionState = "running"
                    lastMessage = "Publishing or verifying local synthetic Event."
                    context to worker
                }
            }
        }
        if (work == null) {
            notifyHost()
            return
        }
        val (context, worker) = work

        try {
            worker.execute {
                try {
                    val eventId = publishOrVerify(context)
                    synchronized(stateLock) {
                        actionPending = false
                        lastAction = ACTION_PUBLISH_EVENT
                        actionState = "succeeded"
                        lastEventId = eventId
                        lastMessage = "Event committed and verified: $eventId"
                    }
                    notifyHost()
                } catch (error: Exception) {
                    completeFailure(ACTION_PUBLISH_EVENT, error)
                }
            }
        } catch (error: RejectedExecutionException) {
            completeFailure(ACTION_PUBLISH_EVENT, error)
        }
    }

    fun rejectAction(actionId: String, message: String) {
        synchronized(stateLock) {
            rejectActionLocked(actionId, message)
        }
        notifyHost()
    }

    private fun rejectActionLocked(actionId: String, message: String) {
        lastAction = actionId
        actionState = "failed"
        lastMessage = message
    }

    private fun publishOrVerify(context: Context): String {
        val root = File(context.filesDir, "aster-evaluation")
        val bundle = File(root, "reference.bundle")
        check(bundle.isFile) {
            "App-private synthetic reference.bundle missing; no operational provisioning is bundled."
        }
        val state = File(root, "plugin-state")
        val journal = File(state, "weartak-spike-publication.redb")
        val receipt = File(root, "plugin-event-id")
        if (receipt.isFile) {
            val expected = receipt.readText(StandardCharsets.UTF_8).trim()
            check(expected.matches(EVENT_ID_PATTERN)) { "Persisted Event receipt is invalid." }
            val actual = AsterProbe.readAfterRestart(
                state.path,
                bundle.path,
                SCOPE,
                TOPIC,
                expected,
            )
            check(actual == expected) { "Persisted Event differs after Node restart." }
            return actual
        }

        val eventId = AsterProbe.publishAndQuery(
            state.path,
            bundle.path,
            SCOPE,
            TOPIC,
            !journal.exists(),
        )
        check(eventId.matches(EVENT_ID_PATTERN)) { "Aster returned an invalid Event ID." }
        root.mkdirs()
        val pendingReceipt = File(root, "plugin-event-id.pending")
        Files.write(pendingReceipt.toPath(), eventId.toByteArray(StandardCharsets.UTF_8))
        Files.move(
            pendingReceipt.toPath(),
            receipt.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        return eventId
    }

    private fun completeFailure(actionId: String, error: Exception) {
        synchronized(stateLock) {
            actionPending = false
            lastAction = actionId
            actionState = "failed"
            lastMessage = error.message ?: error.javaClass.simpleName
        }
        Log.e(TAG, "Aster action failed", error)
        notifyHost(error)
    }

    private fun notifyHost(error: Exception? = null) {
        val callback: IWearTakHostCallback?
        val status: PluginStatusParcel
        synchronized(stateLock) {
            callback = hostCallback
            status = PluginStatusParcel(
                state = lifecycleState,
                message = lastMessage,
                extras = stateBundleLocked(),
            )
        }
        PluginHostNotifier.mainUiStateChanged(TAG, callback)
        PluginHostNotifier.statusChanged(TAG, callback, status)
        if (error != null) {
            try {
                callback?.onPluginError(
                    PluginErrorParcel(
                        code = PluginErrorCodes.ILLEGAL_STATE,
                        message = error.message ?: error.javaClass.simpleName,
                        fatal = false,
                    ),
                )
            } catch (remoteError: android.os.RemoteException) {
                Log.w(TAG, "host error callback failed", remoteError)
            }
        }
    }

    private fun stateBundleLocked() = Bundle().apply {
        putString("last_action", lastAction)
        putString("action_state", actionState)
        putString("last_message", lastMessage)
        putString("event_id", lastEventId)
        putBoolean("action_pending", actionPending)
    }

    private val EVENT_ID_PATTERN = Regex("[0-9a-f]{64}")

    private const val OWNER_ONLY_DIRECTORY_MODE = 448
    private const val FILE_MODE_MASK = 511
}
