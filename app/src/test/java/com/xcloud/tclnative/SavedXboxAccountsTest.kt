package com.xcloud.tclnative

import android.content.Context
import com.xcloud.tclnative.auth.AccountCredentials
import com.xcloud.tclnative.auth.OAuthToken
import com.xcloud.tclnative.auth.PreferencesTokenStore
import com.xcloud.tclnative.auth.XalAuthenticator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SavedXboxAccountsTest {
    private fun store() = PreferencesTokenStore(RuntimeEnvironment.getApplication())
    private val primary = OAuthToken("t=primary-demo", "primary-refresh", 1_000, XalAuthenticator.XCLOUD_CLIENT_ID)
    private val secondary = OAuthToken("t=other-demo", "other-refresh", 2_000, XalAuthenticator.XCLOUD_CLIENT_ID)

    @Test fun legacyLoginAndItsRotatedCredentialsSurviveAddingSwitchingAndSignOut() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcloud_native_auth", Context.MODE_PRIVATE).edit()
            .putString("rps_ticket", primary.ticket).putString("refresh_token", primary.refreshToken)
            .putString("client_id", primary.clientId).putLong("expires_at", 1_000).commit()
        val credentials = AccountCredentials(store())
        val legacy = credentials.snapshot()
        assertEquals(primary, legacy.token)
        credentials.bindAccount(legacy.generation, "hash-one", "1", "Jogador um")
        credentials.addAccount(legacy.generation, secondary)
        credentials.bindAccount(credentials.snapshot().generation, "hash-two", "2", "Jogador dois")
        val reopened = AccountCredentials(store())
        val primaryId = reopened.accounts().single { it.name == "Jogador um" }.id
        val secondaryId = reopened.accounts().single { it.name == "Jogador dois" }.id
        assertEquals(secondary, reopened.snapshot().token)

        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val rotated = secondary.copy(refreshToken = "rotated-refresh")
        val rotation = async {
            reopened.rotate(reopened.snapshot().generation) { snapshot ->
                started.complete(Unit)
                finish.await()
                reopened.saveRotation(snapshot, rotated)
            }
        }
        started.await()
        val switching = async { reopened.activateAccount(primaryId) }
        yield()
        assertFalse(switching.isCompleted)
        finish.complete(Unit)
        rotation.await()
        assertTrue(switching.await())
        assertEquals(primary, reopened.snapshot().token)
        assertTrue(reopened.activateAccount(secondaryId))
        assertEquals(rotated, reopened.snapshot().token)

        reopened.clear()
        assertNull(reopened.snapshot().token)
        assertEquals(listOf("Jogador um"), reopened.accounts().map { it.name })
        assertTrue(reopened.activateAccount(primaryId))
        assertEquals(primary, reopened.snapshot().token)
    }

    @Test fun authenticatingTheSameXboxIdentityReplacesItsLoginWithoutDuplicatingTheAccount() = runBlocking {
        val credentials = AccountCredentials(store())
        credentials.addAccount(credentials.snapshot().generation, primary)
        credentials.bindAccount(credentials.snapshot().generation, "old-hash", "1", "Jogador um")
        credentials.addAccount(credentials.snapshot().generation, secondary)
        credentials.bindAccount(credentials.snapshot().generation, "new-hash", "1")
        assertEquals(secondary, credentials.snapshot().token)
        assertEquals("Jogador um", credentials.accounts().single().name)
    }
}
