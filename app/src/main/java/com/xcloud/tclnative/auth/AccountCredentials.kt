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
        fun advance(): Long {
            rotation = Mutex()
            return ++generation
        }
    }

    private companion object {
        val states = WeakHashMap<Any, State>()
    }

    private val saved = store as? SavedAccountStore
    private val state = synchronized(states) { states.getOrPut(store.coordinationKey) { State() } }

    fun generation() = synchronized(state) { state.generation }

    fun snapshot(): Snapshot = synchronized(state) { Snapshot(state.generation, store.read()) }

    fun requireCurrent(generation: Long?) = synchronized(state) {
        if (generation != null && generation != state.generation) throw AuthenticationRequiredException()
    }

    fun replace(expectedGeneration: Long?, token: (OAuthToken?) -> OAuthToken): Snapshot = synchronized(state) {
        requireCurrent(expectedGeneration)
        val replacement = token(store.read())
        store.write(replacement)
        Snapshot(state.advance(), replacement)
    }

    fun clear(expectedGeneration: Long? = null): Boolean = synchronized(state) {
        if (expectedGeneration != null && expectedGeneration != state.generation) return@synchronized false
        store.clear()
        state.advance()
        true
    }

    fun accounts(): List<SavedXboxAccount> = synchronized(state) { saved?.accounts().orEmpty() }

    suspend fun addAccount(generation: Long, token: OAuthToken): Unit = rotate(generation) { current ->
        synchronized(state) {
            requireCurrent(current.generation)
            if (saved != null) saved.addAndActivate(token) else store.write(token)
            state.advance()
        }
    }

    suspend fun activateAccount(id: String): Boolean = rotate(null) { current ->
        synchronized(state) {
            requireCurrent(current.generation)
            if (saved?.activate(id) != true) false else {
                state.advance()
                true
            }
        }
    }

    fun bindAccount(generation: Long?, hash: String, xuid: String?, gamertag: String? = null) = synchronized(state) {
        requireCurrent(generation)
        saved?.bindIdentity(hash, xuid, gamertag)
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
