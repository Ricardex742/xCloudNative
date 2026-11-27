package com.xcloud.tclnative.diagnostics

/** Evidence from one native playback attempt and the tester's own observation. */
internal class NanoCloudConnectTest {
    private var generation: Int? = null
    val armed get() = generation == null
    var videoPresented = false
        private set
    var localControllerSeen = false
        private set
    var ended = false
        private set
    var responseObserved: Boolean? = null
        private set

    fun begin(generation: Int, localController: Boolean) {
        check(armed)
        this.generation = generation
        localControllerSeen = localController
    }

    fun running(generation: Int) = this.generation == generation && !ended

    fun noteVideo() {
        if (!armed && !ended) videoPresented = true
    }

    fun noteLocalController() {
        if (!armed && !ended) localControllerSeen = true
    }

    fun finish() {
        if (!armed) ended = true
    }

    fun recordResponse(responded: Boolean) {
        if (videoPresented) responseObserved = responded
    }

    fun report() = "CLOUD_CONNECT_TEST/XBOX_NANO; VIDEO_PRESENTED=$videoPresented; " +
        "LOCAL_CONTROLLER_SEEN=$localControllerSeen; USER_OBSERVED_RESPONSE=${responseObserved ?: "UNRECORDED"}"
}
