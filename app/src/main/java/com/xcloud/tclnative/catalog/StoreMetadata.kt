package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.i18n.UiText

import com.xcloud.tclnative.signaling.GameTitle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StoreGameDetails(
    val publisher: String?,
    val description: String?,
    val rating: String?,
    val screenshots: List<String>,
    val releaseYear: Int? = null,
    val contentNotices: List<StoreContentNotice> = emptyList(),
    val interactiveNotices: List<StoreContentNotice> = emptyList()
)

enum class StoreContentNotice {
    VIOLENCE, EXTREME_VIOLENCE, SEXUAL_CONTENT, DRUGS, LEGAL_DRUGS, USER_INTERACTION, IN_GAME_PURCHASES;

    val label get() = when (this) {
        VIOLENCE -> UiText.text("Violência")
        EXTREME_VIOLENCE -> UiText.text("Violência extrema")
        SEXUAL_CONTENT -> UiText.text("Conteúdo sexual")
        DRUGS -> UiText.text("Drogas")
        LEGAL_DRUGS -> UiText.text("Drogas lícitas")
        USER_INTERACTION -> UiText.text("Usuários interagem")
        IN_GAME_PURCHASES -> UiText.text("Compras no jogo")
    }

    companion object {
        internal fun fromCode(value: String) = when (value) {
            "DJCTQ:Vio" -> VIOLENCE
            "DJCTQ:ExtVio" -> EXTREME_VIOLENCE
            "DJCTQ:SexCon" -> SEXUAL_CONTENT
            "DJCTQ:Dru" -> DRUGS
            "DJCTQ:LegDru" -> LEGAL_DRUGS
            "DJCTQ:UseInt" -> USER_INTERACTION
            "DJCTQ:InGamPur" -> IN_GAME_PURCHASES
            else -> null
        }
    }
}

/** Handles Store v7 arrays and the older Game Pass object map without changing cloud launch IDs. */
object StoreMetadata {
    fun gameDetails(data: JSONObject): StoreGameDetails {
        val local = localized(data)
        val images = local?.optJSONArray("Images")
        val screenshots = (0 until (images?.length() ?: 0)).asSequence()
            .mapNotNull { images?.optJSONObject(it) }
            .filter { it.optString("ImagePurpose").equals("Screenshot", true) }
            .mapNotNull { CatalogService.imageUrl(it.text("Uri")) }
            .distinct().take(4).toList()
        val markets = data.optJSONArray("MarketProperties")
        val brazil = (0 until (markets?.length() ?: 0)).asSequence().mapNotNull { markets?.optJSONObject(it) }
            .firstOrNull { market ->
                val codes = market.optJSONArray("Markets")
                (0 until (codes?.length() ?: 0)).any { codes?.optString(it).equals("BR", true) }
            }
        val ratings = brazil?.optJSONArray("ContentRatings")
        val rating = (0 until (ratings?.length() ?: 0)).asSequence().mapNotNull { ratings?.optJSONObject(it) }
            .firstOrNull { it.optString("RatingSystem").equals("DJCTQ", true) && it.text("RatingId") != null }
        fun notices(field: String): List<StoreContentNotice> {
            val values = rating?.optJSONArray(field) ?: return emptyList()
            return (0 until minOf(values.length(), 30)).mapNotNull { StoreContentNotice.fromCode(values.optString(it)) }.distinct()
        }
        val year = brazil?.text("OriginalReleaseDate")?.substringBefore('-')?.toIntOrNull()?.takeIf { it in 1970..2200 }
        return StoreGameDetails(
            local?.text("PublisherName") ?: data.text("PublisherName") ?: data.optJSONObject("Properties")?.text("PublisherName"),
            local?.let { it.text("ProductDescription") ?: it.text("ShortDescription") }?.take(12_000),
            rating?.text("RatingId")?.substringAfterLast(':')?.takeIf { it in setOf("L", "10", "12", "14", "16", "18") },
            screenshots, year, notices("RatingDescriptors"), notices("InteractiveElements")
        )
    }

    fun galleryProduct(data: JSONObject): GalleryProduct? {
        val id = (data.text("ProductId") ?: data.text("StoreId"))?.let(GamePassCatalog::productKey) ?: return null
        val local = localized(data)
        return GalleryProduct(id, local?.text("ProductTitle") ?: data.text("ProductTitle"),
            artwork(local, listOf("Poster", "BoxArt", "BrandedKeyArt", "Portrait")) ?: direct(data, "Image_Poster"),
            artwork(local, listOf("SquareTile", "Tile", "BoxArt")) ?: direct(data, "Image_Tile"),
            artwork(local, listOf("SuperHeroArt", "TitledHeroArt", "Hero", "Screenshot")) ?: direct(data, "Image_Hero"), optimized(data),
            data.optJSONObject("XCloudOfferings")?.let { offerings ->
                val key=offerings.keys().asSequence().firstOrNull { it.equals("XGPUWEB",true) } ?: return@let false
                val programs=offerings.optJSONObject(key)?.optJSONArray("Programs") ?: return@let null
                if((0 until programs.length()).any { programs.opt(it) !is String }) return@let null
                programs.length()>0
            }, (data.opt("XCloudTitleId") as? String)?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")) },
            englishName(data), genres(data))
    }
    private fun JSONObject.text(key: String) = optString(key).takeIf { it.isNotBlank() && it != "null" }
    fun products(response: JSONObject): List<JSONObject> = when (val data = response.opt("Products")) {
        is JSONArray -> (0 until data.length()).mapNotNull { data.optJSONObject(it) }
        is JSONObject -> data.keys().asSequence().mapNotNull { key -> data.optJSONObject(key)?.let {
            if (it.text("ProductId") == null && it.text("StoreId") == null) JSONObject(it.toString()).put("StoreId", key) else it
        } }.toList()
        else -> emptyList()
    }
    private fun direct(data: JSONObject, key: String) = CatalogService.imageUrl(
        data.optJSONObject(key)?.text("URL") ?: data.text(key))
    private fun localized(data: JSONObject): JSONObject? {
        val list = data.optJSONArray("LocalizedProperties") ?: return null
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .firstOrNull { it.optString("Language").equals("pt-BR", true) } ?: list.optJSONObject(0)
    }
    private fun englishName(data: JSONObject): String? {
        val list = data.optJSONArray("LocalizedProperties")
        val english = (0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it) }
            .filter { it.optString("Language").substringBefore('-').equals("en", true) }
        return english.firstOrNull { it.optString("Language").equals("en-US", true) }?.text("ProductTitle")
            ?: english.firstNotNullOfOrNull { it.text("ProductTitle") }
            ?: data.text("EnglishProductTitle")
    }
    private fun artwork(localized: JSONObject?, purposes: List<String>): String? {
        val list = localized?.optJSONArray("Images") ?: return null
        val images = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        for (purpose in purposes) {
            val found = images.filter { it.optString("ImagePurpose").equals(purpose, true) }
                .sortedByDescending { it.optInt("Height") }.firstNotNullOfOrNull { CatalogService.imageUrl(it.text("Uri")) }
            if (found != null) return found
        }
        return null
    }
    private fun strings(value: Any?): List<String> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { (value.opt(it) as? String)?.lowercase() }
        is String -> listOf(value.lowercase())
        else -> emptyList()
    }
    private fun genres(data: JSONObject): Set<CatalogGenre> {
        val properties = data.optJSONObject("Properties")
        return listOf(properties?.opt("Category"), properties?.opt("Categories"),
            data.opt("Category"), data.opt("Categories"), data.opt("Genres"))
            .flatMap(::strings).mapNotNull(CatalogGenre::fromStore).toSet()
    }
    fun optimized(data: JSONObject): Boolean {
        val supported = setOf("consolegen9", "xboxseriesx", "xboxseriess", "xboxseriesxs")
        // Compatibility with Series alone is backward compatibility, not a Series edition badge.
        return data.optBoolean("SeriesOptimized", false) || strings(data.optJSONObject("Properties")
            ?.opt("XboxConsoleGenOptimized")).any { it.replace(Regex("[^a-z0-9]"), "") in supported }
    }
    fun enrich(game: GameTitle, data: JSONObject): GameTitle {
        val local = localized(data)
        return game.copy(
            productId = data.text("ProductId") ?: data.text("StoreId") ?: game.productId,
            name = local?.text("ProductTitle") ?: data.text("ProductTitle") ?: game.name,
            englishName = englishName(data) ?: game.englishName,
            posterUrl = artwork(local, listOf("Poster", "BoxArt", "BrandedKeyArt", "Portrait"))
                ?: direct(data, "Image_Poster") ?: game.posterUrl,
            tileUrl = artwork(local, listOf("SquareTile", "Tile", "BoxArt")) ?: direct(data, "Image_Tile") ?: game.tileUrl,
            heroUrl = artwork(local, listOf("SuperHeroArt", "TitledHeroArt", "Hero", "Screenshot"))
                ?: direct(data, "Image_Hero") ?: game.heroUrl,
            seriesOptimized = optimized(data) || game.seriesOptimized,
            genres = genres(data).ifEmpty { game.genres })
    }
    fun merge(games: List<GameTitle>, response: JSONObject): List<GameTitle> {
        val data = products(response)
        val byProduct = data.mapNotNull { value -> (value.text("ProductId") ?: value.text("StoreId"))?.let { it to value } }.toMap()
        val byCloud = data.mapNotNull { value -> value.text("XCloudTitleId")?.let { it to value } }.toMap()
        return games.map { game -> (byProduct[game.productId] ?: byCloud[game.titleId])?.let { enrich(game, it) } ?: game }
    }
}

/** A partial response can fill gaps without erasing artwork from another request. */
internal fun GameTitle.withMetadataFallback(previous: GameTitle) = copy(
    name = if (name == titleId) previous.name else name,
    englishName = englishName ?: previous.englishName,
    productId = productId ?: previous.productId,
    posterUrl = posterUrl ?: previous.posterUrl,
    tileUrl = tileUrl ?: previous.tileUrl,
    heroUrl = heroUrl ?: previous.heroUrl,
    seriesOptimized = seriesOptimized || previous.seriesOptimized,
    genres = genres.ifEmpty { previous.genres }
)

/** Public art only. A cached product never adds a playable title or restores account entitlements. */
class CatalogCache(
    private val file: File,
    private val readText: (File) -> String = { it.readText() }
) {
    private data class Revision(val modified: Long, val bytes: Long)
    private class ArtIndex(products: List<JSONObject>) {
        private val byProduct = HashMap<String, GameTitle>()
        private val byTitle = HashMap<String, GameTitle>()

        init {
            for (data in products) {
                val title = data.optString("XCloudTitleId")
                val art = StoreMetadata.enrich(GameTitle(title, ""), data)
                art.productId?.let { byProduct[it] = art }
                if (title.isNotBlank() && title != "null") byTitle[title] = art
            }
        }

        fun merge(game: GameTitle): GameTitle {
            val art = byProduct[game.productId] ?: byTitle[game.titleId] ?: return game
            // Only public presentation fields are cached. Launchability comes from the live title.
            return game.copy(
                productId = art.productId ?: game.productId,
                name = art.name.ifBlank { game.name },
                englishName = art.englishName ?: game.englishName,
                posterUrl = art.posterUrl ?: game.posterUrl,
                tileUrl = art.tileUrl ?: game.tileUrl,
                heroUrl = art.heroUrl ?: game.heroUrl,
                seriesOptimized = art.seriesOptimized || game.seriesOptimized,
                genres = art.genres.ifEmpty { game.genres }
            )
        }
    }
    private var revision: Revision? = null
    private var artwork: ArtIndex? = null

    private fun index(): ArtIndex? {
        val current = Revision(file.lastModified(), file.length())
        if (revision == current) return artwork
        artwork = if (current.bytes in 1..MAX_BYTES) try {
            ArtIndex(StoreMetadata.products(JSONObject(readText(file))))
        } catch (_: Exception) {
            null
        } else null
        revision = current
        return artwork
    }

    /** A visual hint before authentication; callers must validate launch access with the live catalog. */
    @Synchronized fun artwork(titleId: String): GameTitle? = index()
        ?.merge(GameTitle(titleId,UiText.text("Seu jogo")))
        ?.takeIf { it.posterUrl!=null || it.tileUrl!=null || it.heroUrl!=null }

    @Synchronized fun restore(games: List<GameTitle>): List<GameTitle> = index()?.let { index ->
        games.map(index::merge)
    } ?: games

    @Synchronized fun save(games: List<GameTitle>) {
        var next: File? = null
        try {
            val products = JSONArray()
            games.zip(restore(games)) { fresh, cached -> fresh.withMetadataFallback(cached) }
                .filter { it.productId != null && (it.posterUrl != null || it.tileUrl != null || it.genres.isNotEmpty()) }.forEach { game ->
                products.put(JSONObject().put("StoreId", game.productId).put("XCloudTitleId", game.titleId)
                    .put("ProductTitle", game.name).put("EnglishProductTitle", game.englishName)
                    .put("Image_Poster", game.posterUrl).put("Image_Tile", game.tileUrl)
                    .put("Image_Hero", game.heroUrl).put("SeriesOptimized", game.seriesOptimized)
                    .put("Genres", JSONArray(game.genres.map { it.name })))
            }
            val directory = file.absoluteFile.parentFile ?: return
            directory.mkdirs()
            next = File.createTempFile("catalog-art-", ".tmp", directory)
            next.bufferedWriter().use { it.write(JSONObject().put("Products", products).toString()) }
            if (next.length() <= MAX_BYTES && next.renameTo(file)) {
                artwork = ArtIndex((0 until products.length()).map(products::getJSONObject))
                revision = Revision(file.lastModified(), file.length())
            }
        } catch (_: Exception) {
            // A partial cache write must not prevent browsing or replace the previous snapshot.
        } finally {
            next?.delete()
        }
    }

    private companion object { const val MAX_BYTES = 10 * 1024 * 1024 }
}
