package com.weartak.plugin.aster.spike.runtime

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.weartak.plugin.aster.spike.AsterProbe
import java.io.File

class ProbeInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle?) {
        this.arguments = requireNotNull(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        try {
            val root = File(targetContext.filesDir, "aster-evaluation")
            val state = File(root, "state")
            val bundle = File(root, "reference.bundle")
            val receipt = File(root, "event-id")
            check(bundle.isFile) { "App-private disposable reference bundle missing" }
            AsterProbe.initialize(targetContext.applicationContext)
            results.putBoolean("jni_initialized", true)
            if (arguments.getString("stage") == "control-path-limit") {
                val parent = targetContext.filesDir.toPath().toRealPath().toString()
                val prefix = parent.toByteArray(Charsets.UTF_8).size + 1
                check(prefix < 63) { "App root too long for boundary test" }
                val accepted = File(parent, "p".repeat(63 - prefix))
                val refused = File(parent, "q".repeat(64 - prefix))
                android.system.Os.mkdir(accepted.path, 448)
                android.system.Os.mkdir(refused.path, 448)
                try {
                    check(AsterProbe.configureControlDirectory(accepted.path) == "ANDROID_CONTROL_DIRECTORY_PINNED")
                    check(AsterProbe.readAfterRestart(
                        state.path, bundle.path, "weartak/spike", "jni-evaluation", receipt.readText(),
                    ) == receipt.readText())
                    expectRejected("pathname limit") {
                        AsterProbe.configureControlDirectory(refused.path)
                    }
                    results.putString("result", "CONTROL_SOCKET_PATH_107_ACCEPTED_108_REFUSED")
                } finally {
                    java.nio.file.Files.delete(accepted.toPath())
                    java.nio.file.Files.delete(refused.toPath())
                }
                finish(Activity.RESULT_OK, results)
                return
            }
            if (arguments.getString("stage") == "control-uninitialized") {
                expectRejected("explicitly initialized") {
                    AsterProbe.readAfterRestart(
                        state.path, bundle.path, "weartak/spike", "jni-evaluation", receipt.readText(),
                    )
                }
                results.putString("result", "CONTROL_MISSING_INITIALIZER_REFUSED")
                finish(Activity.RESULT_OK, results)
                return
            }
            val control = File(targetContext.filesDir, "c")
            if (!control.exists()) android.system.Os.mkdir(control.path, 448)
            results.putString(
                "control_directory",
                AsterProbe.configureControlDirectory(control.toPath().toRealPath().toString()),
            )
            if (arguments.getString("stage") == "control-validation") {
                val unsafe = File(targetContext.filesDir, "aster-control-unsafe")
                if (!unsafe.exists()) android.system.Os.mkdir(unsafe.path, 448)
                android.system.Os.chmod(unsafe.path, 511)
                expectRejected("owner-only") { AsterProbe.configureControlDirectory(unsafe.toPath().toRealPath().toString()) }
                android.system.Os.chmod(unsafe.path, 448)
                expectRejected("already pinned") { AsterProbe.configureControlDirectory(unsafe.toPath().toRealPath().toString()) }
                expectRejected("absolute") { AsterProbe.configureControlDirectory("relative-control") }
                val longRoot = File(targetContext.filesDir, "long-" + "x".repeat(80))
                if (!longRoot.exists()) android.system.Os.mkdir(longRoot.path, 448)
                try {
                    expectRejected("pathname limit") {
                        AsterProbe.configureControlDirectory(longRoot.toPath().toRealPath().toString())
                    }
                } finally {
                    java.nio.file.Files.delete(longRoot.toPath())
                }
                val alias = File(targetContext.filesDir, "aster-control-alias")
                android.system.Os.symlink(control.path, alias.path)
                try {
                    expectRejected("owner-only") { AsterProbe.configureControlDirectory(alias.path) }
                } finally {
                    java.nio.file.Files.delete(alias.toPath())
                }
                check(AsterProbe.configureControlDirectory(control.toPath().toRealPath().toString()) == "ANDROID_CONTROL_DIRECTORY_PINNED")
                results.putString("result", "CONTROL_UNSAFE_ALIAS_REBIND_REFUSED")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (arguments.getString("stage") == "control-inode") {
                val canonical = control.toPath().toRealPath()
                val moved = canonical.resolveSibling("d")
                java.nio.file.Files.move(canonical, moved)
                try {
                    android.system.Os.mkdir(canonical.toString(), 448)
                    expectRejected("already pinned") {
                        AsterProbe.configureControlDirectory(canonical.toString())
                    }
                    expectRejected("identity changed") {
                        AsterProbe.readAfterRestart(
                            state.path, bundle.path, "weartak/spike", "jni-evaluation", receipt.readText(),
                        )
                    }
                } finally {
                    java.nio.file.Files.delete(canonical)
                    java.nio.file.Files.move(moved, canonical)
                }
                check(AsterProbe.readAfterRestart(
                    state.path, bundle.path, "weartak/spike", "jni-evaluation", receipt.readText(),
                ) == receipt.readText())
                results.putString("result", "CONTROL_INODE_REPLACEMENT_REFUSED_ORIGINAL_RESTORED")
                finish(Activity.RESULT_OK, results)
                return
            }
            results.putBoolean("global_tmp_writable", File("/tmp").canWrite())
            results.putInt("uid", android.os.Process.myUid())
            try {
                results.putString("global_tmp", File("/tmp").toPath().toRealPath().toString())
            } catch (error: java.io.IOException) {
                results.putString("global_tmp_error", error.toString())
            }
            when (val stage = arguments.getString("stage")) {
                "describe-peer" -> results.putString("peer", AsterProbe.describePeer(root.path))
                "independent-receiver", "independent-publisher" -> {
                    results.putString("result", AsterProbe.runIndependentPeer(
                        root.path, requireNotNull(arguments.getString("peer")),
                        requireNotNull(arguments.getString("bind")),
                        stage == "independent-receiver", requireNotNull(arguments.getString("event")),
                    ))
                }
                "carrier", "mission", "zeroize-live", "zeroize-stopped",
                "zeroize-wrong-authority",
                "inspect-zeroize-live", "inspect-zeroize-stopped",
                "inspect-interrupted-zeroize", "independent-read" -> {
                    results.putString("result", AsterProbe.validateSecurity(root.path, stage))
                }
                "initialize" -> results.putString("result", "JNI_INITIALIZATION_OK")
                "publish" -> {
                    check(!state.exists() && !receipt.exists()) { "Publish requires fresh state" }
                    val event = AsterProbe.publishAndQuery(
                        state.path, bundle.path, "weartak/spike", "jni-evaluation", true,
                    )
                    check(event.isNotBlank()) { "Empty Event receipt" }
                    receipt.writeText(event)
                    results.putString("event", event)
                    results.putString("result", "JNI_PUBLISH_QUERY_OK")
                }
                "read" -> {
                    val expected = receipt.readText()
                    val actual = AsterProbe.readAfterRestart(
                        state.path, bundle.path, "weartak/spike", "jni-evaluation", expected,
                    )
                    check(actual == expected) { "Restart Event ID differs" }
                    results.putString("event", actual)
                    results.putString("result", "JNI_RESTART_READ_OK")
                }
                "reconnect" -> {
                    val expected = receipt.readText()
                    val actual = AsterProbe.reconnect(
                        root.path, bundle.path, File(root, "receiver.bundle").path, expected,
                    )
                    check(actual == expected) { "Reconnected Event differs" }
                    results.putString("event", actual)
                    results.putString("result", "JNI_AUTHENTICATED_RECONNECT_RETAINED")
                }
                "read-receiver" -> {
                    val expected = receipt.readText()
                    val actual = AsterProbe.readAfterRestart(
                        File(root, "receiver-state").path, File(root, "receiver.bundle").path,
                        "weartak/spike", "jni-evaluation", expected, false,
                    )
                    check(actual == expected) { "Restarted receiver Event differs" }
                    results.putString("event", actual)
                    results.putString("result", "JNI_RECEIVER_RESTART_EVENT_ACK_RETAINED")
                }
                else -> error("Unknown stage: $stage")
            }
            results.putInt("pid", android.os.Process.myPid())
            finish(Activity.RESULT_OK, results)
        } catch (error: Exception) {
            results.putString("error", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, results)
        }

    }

    private fun expectRejected(message: String, operation: () -> Unit) {
        try {
            operation()
        } catch (expected: IllegalStateException) {
            check(expected.message?.contains(message) == true) { "Wrong rejection: $expected" }
            return
        }
        error("Unsafe control-directory configuration accepted")
    }
}
