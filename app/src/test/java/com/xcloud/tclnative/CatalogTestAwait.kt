package com.xcloud.tclnative

import android.os.Looper
import com.xcloud.tclnative.ui.dashboard.GameDashboardView
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertFalse
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

/** Drain Main while the real catalog worker finishes; no synchronous production fast path. */
internal fun GameDashboardView.awaitCatalog() {
    val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
    while(isCatalogUpdating && System.nanoTime()<deadline) {
        shadowOf(Looper.getMainLooper()).idleFor(16,TimeUnit.MILLISECONDS)
        Thread.sleep(1)
    }
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse("Catalog worker did not finish",isCatalogUpdating)
}

internal fun GameDashboardView.awaitFavoriteWrites() {
    val writer = ReflectionHelpers.getField<Mutex>(this, "favoriteWrites")
    val main = shadowOf(Looper.getMainLooper())
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    main.idle()
    while (writer.isLocked && System.nanoTime() < deadline) {
        main.idle()
        Thread.sleep(1)
    }
    assertFalse("Favorite writer did not finish", writer.isLocked)
    awaitCatalog()
}
