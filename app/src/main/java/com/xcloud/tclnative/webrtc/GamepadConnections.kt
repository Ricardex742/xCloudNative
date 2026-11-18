package com.xcloud.tclnative.webrtc

/** The touch/virtual source retains logical slot zero even when no physical gamepad sends reports. */
class GamepadConnections(private val primaryRegistered: () -> Boolean = { true }) {
    private val connected = mutableSetOf(0) // Reserved for the transport's startup registration.
    @Synchronized fun add(index: Int, send: () -> Boolean = { true }): Boolean {
        require(index in 0..3)
        return index !in connected && primaryRegistered() && send() && connected.add(index)
    }
    /** Secondary pads must never win the initial attachment race against reserved player one. */
    @Synchronized fun registerPending(indices: Collection<Int>,send: (Int) -> Boolean): Boolean {
        if(!primaryRegistered()) return false
        for(index in indices.sorted()) {
            require(index in 0..3)
            if (index !in connected && !add(index) { send(index) }) return false
        }
        return true
    }
    @Synchronized fun remove(index: Int, send: () -> Boolean = { true }): Boolean {
        require(index in 0..3)
        return index != 0 && index in connected && send() && connected.remove(index)
    }
}
