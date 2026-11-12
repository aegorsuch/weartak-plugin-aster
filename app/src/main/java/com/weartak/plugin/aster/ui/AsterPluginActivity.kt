package com.weartak.plugin.aster.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.weartak.plugin.aster.R
import com.weartak.plugin.aster.service.AsterPluginRuntime

class AsterPluginActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView

    private val refreshStatus = object : Runnable {
        override fun run() {
            if (!::status.isInitialized) return
            val state = AsterPluginRuntime.getMainUiState()
            status.text = buildString {
                append(state.getString("last_message") ?: getString(R.string.waiting_for_host))
                state.getString("event_id")?.let {
                    append("\n")
                    append(getString(R.string.event_id_label, it))
                }
            }
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 14f
            text = getString(R.string.evaluation_warning)
        }
        val publish = Button(this).apply {
            text = getString(R.string.publish_button)
            setOnClickListener {
                try {
                    AsterPluginRuntime.invokeAction(AsterPluginRuntime.ACTION_PUBLISH_EVENT)
                    refreshStatus.run()
                } catch (error: Exception) {
                    status.text = error.message ?: error.javaClass.simpleName
                }
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                addView(TextView(this@AsterPluginActivity).apply {
                    text = getString(R.string.screen_title)
                    textSize = 20f
                })
                addView(status)
                addView(publish)
            },
        )
        try {
            AsterPluginRuntime.initialize(applicationContext)
        } catch (error: Exception) {
            status.text = getString(
                R.string.initialization_failed,
                error.message ?: error.javaClass.simpleName,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        refreshStatus.run()
    }

    override fun onStop() {
        handler.removeCallbacks(refreshStatus)
        super.onStop()
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 500L
    }
}
