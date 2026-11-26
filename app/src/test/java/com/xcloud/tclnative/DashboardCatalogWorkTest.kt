package com.xcloud.tclnative

import com.xcloud.tclnative.catalog.DashboardCatalogWork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardCatalogWorkTest {
    @Test fun retiredDashboardCannotPublishALateResultAcrossReloadOrSignOut() = runTest {
        val work = DashboardCatalogWork(this, StandardTestDispatcher(testScheduler))
        val firstReply = CompletableDeferred<Unit>()
        val secondReply = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        val retired = mutableListOf<String>()
        work.start { old ->
            withContext(NonCancellable) {
                firstReply.await()
                if (old.isCurrent()) delivered += "old" else retired += "old"
            }
        }
        runCurrent()
        work.start { current ->
            delivered += "current"
            withContext(NonCancellable) {
                secondReply.await()
                if (current.isCurrent()) delivered += "after sign out" else retired += "signed out"
            }
        }
        runCurrent()
        firstReply.complete(Unit)
        runCurrent()
        work.cancel()
        secondReply.complete(Unit)
        runCurrent()
        assertEquals(listOf("current"), delivered)
        assertEquals(listOf("old", "signed out"), retired)
    }
}
