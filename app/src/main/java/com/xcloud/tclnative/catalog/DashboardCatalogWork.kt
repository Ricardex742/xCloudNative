package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.signaling.GameTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns background catalog work and its publication rights for one dashboard at a time. */
internal class DashboardCatalogWork(private val scope: CoroutineScope, private val ui: CoroutineDispatcher = Dispatchers.Main) {
    private var job: Job? = null
    private var current: Load? = null

    fun start(load: suspend CoroutineScope.(Load) -> Unit) {
        cancel()
        val owner = Load()
        current = owner
        job = scope.launch { load(owner) }
    }

    fun cancel() {
        current = null
        job?.cancel()
        job = null
    }

    inner class Load {
        fun isCurrent(): Boolean = current === this

        private suspend fun publish(block: () -> Unit) = withContext(ui) {
            if (isCurrent()) block()
        }

        suspend fun enrich(
            catalog: CatalogService,
            games: List<GameTitle>,
            knownGames: () -> List<GameTitle>,
            onMetadata: (List<GameTitle>) -> Unit,
            onProgress: (CatalogService.Progress) -> Unit,
            onGallery: (OfficialGalleryKind, OfficialGallery?) -> Unit,
            onGalleryUpdate: (OfficialGallery) -> Unit,
            onAdded: (List<String>?) -> Unit
        ) = coroutineScope {
            val native = async {
                catalog.hydrate(games, onProgress = { progress -> publish { onProgress(progress) } }) {
                    hydrated -> publish { onMetadata(hydrated) }
                }
            }
            OfficialGalleryKind.entries.forEach { kind ->
                launch {
                    val gallery = try { catalog.officialGallery(kind) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    publish { onGallery(kind, gallery) }
                    if (gallery == null) return@launch
                    native.await()
                    val known = knownGames()
                    catalog.hydrateOfficialGallery(gallery,
                        productKeys(known.filter { it.name != it.titleId && (it.posterUrl != null || it.tileUrl != null) }),
                        productKeys(known.filter { it.englishName != null }),
                        productKeys(known.filter { it.genres.isNotEmpty() })) { hydrated ->
                        publish { onGalleryUpdate(hydrated) }
                    }
                }
            }
            launch {
                val added = catalog.gallery(CatalogService.RECENTLY_ADDED)
                publish { onAdded(added) }
            }
        }
    }

    private fun productKeys(games: List<GameTitle>) =
        games.mapNotNullTo(hashSetOf()) { it.productId?.let(GamePassCatalog::productKey) }
}
