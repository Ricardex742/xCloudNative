package com.xcloud.tclnative.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.WeakHashMap

/** Serializes token rotation and invalidates work from an account replaced during a request. */
internal class AccountCredentials(private val store: OAuthTokenStore) {
    data class Snapshot(val generation: Long, val token: OAuthToken?)

    private class State {
        var generation = 0L
        var rotation = Mutex()
    }

    private companion object {
        val states = WeakHashMap<Any, State>()
    }

    private val state = synchronized(states) { states.getOrPut(store.coordinationKey) { State() } }

    fun snapshot(): Snapshot = synchronized(state) { Snapshot(state.generation, store.read()) }

    fun requireCurrent(generation: Long?) = synchronized(state) {
        if (generation != null && generation != state.generation) throw AuthenticationRequiredException()
    }

    fun replace(expectedGeneration: Long?, token: (OAuthToken?) -> OAuthToken): Snapshot = synchronized(state) {
        requireCurrent(expectedGeneration)
        val replacement = token(store.read())
        store.write(replacement)
        state.rotation = Mutex()
        Snapshot(++state.generation, replacement)
    }

    fun clear() = synchronized(state) {
        store.clear()
        state.rotation = Mutex()
        state.generation += 1
    }

    suspend fun <T> rotate(generation: Long?, action: suspend (Snapshot) -> T): T {
        val (expected, rotation) = synchronized(state) {
            requireCurrent(generation)
            state.generation to state.rotation
        }
        return rotation.withLock {
            val current = snapshot()
            if (current.generation != expected) throw AuthenticationRequiredException()
            action(current)
        }
    }

    fun saveRotation(snapshot: Snapshot, replacement: OAuthToken) = synchronized(state) {
        requireCurrent(snapshot.generation)
        if (snapshot.token != store.read()) throw AuthenticationRequiredException()
        if (snapshot.token != replacement) store.write(replacement)
    }
}
