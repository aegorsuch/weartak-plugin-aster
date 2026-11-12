package com.weartak.plugin.aster.spike

import android.content.Context

object AsterProbe {
    init {
        System.loadLibrary("weartak_aster_spike")
    }

    @JvmStatic
    external fun initialize(context: Context)

    @JvmStatic
    external fun configureControlDirectory(directory: String): String

    @JvmStatic
    external fun validateSecurity(root: String, mode: String): String

    @JvmStatic
    external fun describePeer(root: String): String

    @JvmStatic
    external fun runIndependentPeer(
        root: String, peer: String, bind: String, receiver: Boolean, eventId: String,
    ): String

    @JvmStatic
    external fun reconnect(
        root: String,
        publisherBundle: String,
        receiverBundle: String,
        eventId: String,
    ): String

    @JvmStatic
    external fun publishAndQuery(
        stateDirectory: String,
        referenceBundle: String,
        scope: String,
        topic: String,
        initializeJournal: Boolean,
    ): String

    @JvmStatic
    external fun readAfterRestart(
        stateDirectory: String,
        referenceBundle: String,
        scope: String,
        topic: String,
        eventId: String,
        ownPublisher: Boolean = true,
    ): String
}
