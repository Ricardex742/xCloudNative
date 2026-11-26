package com.xcloud.tclnative.catalog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** Start background catalog requests only while the catalog can use their result. */
class CatalogWorkGate(foreground: Boolean = true) {
    private data class State(val open: Boolean, val foreground: Boolean)
    private val state = MutableStateFlow(State(true, foreground))
    fun pause() { state.update { it.copy(open = false) } }
    fun resume() { state.update { it.copy(open = true) } }
    fun setForeground(value: Boolean) { state.update { it.copy(foreground = value) } }
    suspend fun await() { state.first { it.open && it.foreground } }
}
