package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.signaling.GameTitle
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** Immutable inputs captured on Main; all catalog-sized work runs on the catalog worker. */
internal data class CatalogInput(
    val games: List<GameTitle>,
    val galleries: Map<OfficialGalleryKind, OfficialGallery?>,
    val bindings: Map<String, GameTitle>,
    val legacyProducts: Set<String>?,
    val legacyTitles: Set<String>,
    val tab: LibraryTab,
    val favorites: Set<String>,
    val added: List<String>?,
    val recent: List<String>?,
    val query: String,
    val ownershipFilter: OwnershipFilter=OwnershipFilter.ALL,
    val ownership: Map<String,Ownership> = emptyMap(),
    val limit: Int? = null,
    val genre: CatalogGenre? = null
)

internal data class CatalogPresentation(
    val cards: List<CatalogCard>,
    val positions: Map<String, Int>,
    val subscription: String?,
    val gamePassProducts: Set<String>?,
    val purchaseProducts: Set<String>,
    val genres: Set<CatalogGenre> = emptySet(),
    val alphabetPositions: Map<Char, Int> = emptyMap()
) {
    companion object {
        fun build(input: CatalogInput): CatalogPresentation = CatalogPresentationEngine().build(input)
    }
}

/** One account/view worker owns this index. Tab/search/license changes reuse the immutable artwork joins. */
internal class CatalogPresentationEngine {
    private var data: IndexedCatalog?=null
    fun build(input: CatalogInput): CatalogPresentation {
        val catalog=data?.takeIf { it.matches(input) } ?: IndexedCatalog(input).also { data=it }
        val search=CatalogQuery(input.query)
        fun ownership(card: CatalogCard) = input.ownership[card.ownershipProductId()] ?: Ownership.UNKNOWN
        val eligible=catalog.select(input).asSequence().filter { card ->
            search.matches(card) && input.ownershipFilter.accepts(ownership(card)) &&
                (!input.tab.hasBrowseTools || input.genre == null || input.genre in card.genres)
        }
        val matching = if (input.tab == LibraryTab.PURCHASED) {
            val groups = eligible.groupBy(::ownership)
            Ownership.entries.asSequence().flatMap { status ->
                groups[status].orEmpty().asSequence().map {
                    if (status == Ownership.OWNED) it.copy(purchased = true) else it
                }
            }
        } else eligible
        val visible=(input.limit?.let(matching::take) ?: matching).toList()
        val alphabet = if (input.tab.hasBrowseTools && input.limit == null) sortedMapOf<Char, Int>().apply {
            val initials = catalog.initials.getValue(input.tab)
            visible.forEachIndexed { index, card ->
                val initial = initials.getValue(card.key)
                if (initial !in this) put(initial, index)
            }
        } else emptyMap()
        return CatalogPresentation(visible,visible.withIndex().associate { it.value.key to it.index },catalog.subscription,
            catalog.products,catalog.purchaseProducts,catalog.genres[input.tab].orEmpty(),alphabet)
    }
    private class IndexedCatalog(private val source: CatalogInput) {
        val products=if(source.galleries.containsKey(OfficialGalleryKind.GAME_PASS))
            source.galleries[OfficialGalleryKind.GAME_PASS]?.products?.map { it.productId }?.toSet()
            else source.legacyProducts?.map(GamePassCatalog::productKey)?.toSet()
        private val membership=products ?: emptySet()
        private val titles=source.legacyTitles.map(GamePassCatalog::productKey).toSet()
        private val games=source.games.map { game ->
            val member=GamePassCatalog.contains(game,membership,titles)
            if(game.gamePass==member) game else game.copy(gamePass=member)
        }
        val subscription=XboxProfile.subscription(source.games.flatMap { it.userSubscriptions+it.userPrograms })
        private val index=CloudTitleIndex(games)
        private val galleries=source.galleries.mapNotNull { (kind,gallery) -> gallery?.let {
            val cards = OfficialGalleryCards.matching(it,index,source.bindings,membership,"")
            kind to if (kind == OfficialGalleryKind.PURCHASED) sortByEnglishTitle(cards) else cards.toList()
        } }.toMap()
        val purchaseProducts = galleries[OfficialGalleryKind.PURCHASED].orEmpty()
            .mapNotNull(CatalogCard::ownershipProductId).toSet()
        private val official=galleries.values.flatten().distinctBy { it.key }
        private val byProduct=official.associateBy { it.product!!.productId }
        private val byTitle=official.filter { it.game!=null }.groupBy { GamePassCatalog.productKey(it.game!!.titleId) }
        private val native=games.associate { game ->
            val direct=game.productId?.let { byProduct[GamePassCatalog.productKey(it)] }
                ?.takeIf { it.game?.titleId?.equals(game.titleId,true)==true }
            val alias=direct ?: byTitle[GamePassCatalog.productKey(game.titleId)]?.singleOrNull()
            game.titleId to CatalogCard(game,alias?.product,alias?.gamePass ?: game.gamePass,nativeIdentity=true)
        }
        private val allCards = run {
            val selected=GameLibrary.select(games,LibraryTab.ALL,emptySet(),emptyMap(),products?.toList(),null).map { native.getValue(it.titleId) }
            val represented=selected.mapNotNull { it.product?.productId }.toSet()
            sortForBrowse(selected + official.filter { it.product!!.productId !in represented })
        }
        private val browseCards = mapOf(
            LibraryTab.ALL to allCards,
            LibraryTab.PURCHASED to galleries[OfficialGalleryKind.PURCHASED].orEmpty(),
            LibraryTab.GAME_PASS to sortForBrowse(galleries[OfficialGalleryKind.GAME_PASS]
                ?: games.filter { GamePassCatalog.contains(it, membership, titles) }.map { native.getValue(it.titleId) })
        )
        val initials = browseCards.mapValues { (tab, cards) ->
            cards.associate { card ->
                val title = if (tab == LibraryTab.PURCHASED) card.englishSortName else card.name
                card.key to initial(normalizedTitle(title))
            }
        }
        val genres = browseCards.mapValues { (_, cards) -> cards.flatMap { it.genres }.toSet() }

        fun matches(input: CatalogInput)=source.games===input.games && source.galleries==input.galleries &&
            source.bindings==input.bindings && source.legacyProducts==input.legacyProducts && source.legacyTitles==input.legacyTitles
        fun select(input: CatalogInput): List<CatalogCard> {
            browseCards[input.tab]?.let { return it }
            return GameLibrary.select(games,input.tab,input.favorites,emptyMap(),products?.toList(),input.added,"",titles,input.recent)
                .map { native.getValue(it.titleId) }
        }
        private fun normalizedTitle(title: String) = Normalizer.normalize(title.trim(), Normalizer.Form.NFD)
            .filterNot { it.category == CharCategory.NON_SPACING_MARK }.lowercase(Locale.ROOT)

        private fun initial(title: String) =
            title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'

        private fun sortForBrowse(cards: List<CatalogCard>): List<CatalogCard> = cards.map { card ->
            val name = normalizedTitle(card.name)
            Triple(initial(name), name, card)
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }

        private fun sortByEnglishTitle(cards: Sequence<CatalogCard>): List<CatalogCard> {
            val alphabet = Collator.getInstance(Locale.US).apply { strength = Collator.PRIMARY }
            return cards.map { card ->
                alphabet.getCollationKey(card.englishSortName) to card
            }.sortedWith(compareBy { (key, _) -> key })
                .map { (_, card) -> card }.toList()
        }
    }
}

/** Reorders replace a stable-ID snapshot once, avoiding quadratic move detection/dispatch. */
internal data class CatalogChanges(val replace: Boolean, val ranges: List<IntRange>) {
    companion object {
        fun between(before: List<CatalogCard>, next: List<CatalogCard>): CatalogChanges {
            if (before.size != next.size || before.indices.any { before[it].key != next[it].key })
                return CatalogChanges(true, emptyList())
            val ranges = mutableListOf<IntRange>()
            var start = -1
            for (index in next.indices) {
                if (before[index] != next[index]) {
                    if (start == -1) start = index
                } else if (start != -1) { ranges.add(start until index); start = -1 }
            }
            if (start != -1) ranges.add(start until next.size)
            // Bound RecyclerView notification work even for fragmented metadata batches.
            return CatalogChanges(false, if (ranges.size > 32) listOf(next.indices) else ranges)
        }
    }
}
