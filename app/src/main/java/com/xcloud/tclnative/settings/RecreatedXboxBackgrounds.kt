package com.xcloud.tclnative.settings

/** Original app animations inspired by documented Xbox designs; never native console packages. */
enum class RecreationFamily { CORE, ANNIVERSARY, NEBULA, VAPOR, STARMAP, OCEAN, SKULL, EMBER, TACTICAL, SNOW, MAGIC, ENERGY, FIREFLIES, RIBBONS, PARTICLES, DUALITY, PIXELS, COMMUNITY }

enum class ArtAnimationEffect(private val source: String) {
    AUTO("Automático"), PARTICLES("Partículas e luz"), MIST("Névoa e brilho"), WATER("Água e ondas"), FIRE("Fogo e faíscas"), FLUID("Deformação localizada");
    val label get()=com.xcloud.tclnative.i18n.UiText.text(source)
}

data class RecreatedBackground(val id: String,val name: String,val family: RecreationFamily,
                               val dark: Int,val primary: Int,val light: Int,val source: String) {
    fun wallpaper(artUrl: String?=null)=SeriesWallpaper(id,name,artUrl=artUrl,category=WallpaperCategory.RECREATIONS)
}

object RecreatedXboxBackgrounds {
    private const val ART_PREFIX="recreation-art:"
    fun animatedArtwork(original: SeriesWallpaper,effect: ArtAnimationEffect=ArtAnimationEffect.AUTO): SeriesWallpaper? {
        val source=original.artUrl?.let { OfficialXboxWallpapers.entry(original.name,it,original.previewUrl) } ?: return null
        if(!original.official || source.id!=original.id) return null
        return SeriesWallpaper(ART_PREFIX+source.id.removePrefix("official:")+":"+effect.name,source.name,
            artUrl=source.artUrl,category=WallpaperCategory.RECREATIONS,previewUrl=source.previewUrl)
    }
    fun originalArtwork(value: SeriesWallpaper): SeriesWallpaper? {
        if(!value.id.startsWith(ART_PREFIX)) return null
        val original=value.artUrl?.let { OfficialXboxWallpapers.entry(value.name,it,value.previewUrl) } ?: return null
        val key=value.id.removePrefix(ART_PREFIX).substringBefore(':')
        return original.takeIf { it.id=="official:$key" }
    }
    fun artworkEffect(value: SeriesWallpaper)=value.id.substringAfterLast(':').let { key -> ArtAnimationEffect.entries.firstOrNull { it.name==key } }
    fun artworkDesign(value: SeriesWallpaper): RecreatedBackground? {
        val original=originalArtwork(value) ?: return null
        val effect=artworkEffect(value) ?: return null
        val n=original.name.lowercase()
        val family=when(effect) {
            ArtAnimationEffect.PARTICLES -> RecreationFamily.PARTICLES
            ArtAnimationEffect.MIST -> RecreationFamily.NEBULA
            ArtAnimationEffect.WATER -> RecreationFamily.OCEAN
            ArtAnimationEffect.FIRE -> RecreationFamily.EMBER
            ArtAnimationEffect.FLUID -> RecreationFamily.VAPOR
            ArtAnimationEffect.AUTO -> when {
                "sea of thieves" in n -> RecreationFamily.OCEAN
                "diablo" in n || "doom" in n -> RecreationFamily.EMBER
                "starfield" in n -> RecreationFamily.STARMAP
                "witcher" in n -> RecreationFamily.SNOW
                "battlefield" in n || "call of duty" in n -> RecreationFamily.TACTICAL
                else -> RecreationFamily.PARTICLES
            }
        }
        val (primary,light)=when(family) {
            RecreationFamily.EMBER -> 0xffa93d16.toInt() to 0xffffc47a.toInt()
            RecreationFamily.OCEAN -> 0xff237b95.toInt() to 0xffb0e8ee.toInt()
            RecreationFamily.NEBULA,RecreationFamily.VAPOR -> 0xff646aa0.toInt() to 0xffd1d9f5.toInt()
            else -> 0xff5b7978.toInt() to 0xffdeefeb.toInt()
        }
        return RecreatedBackground(value.id,original.name,family,0xff061012.toInt(),primary,light,OfficialXboxWallpapers.SOURCE)
    }
    val entries: List<RecreatedBackground> = listOf(
        RecreatedBackground("recreation:indie-selects-rbita-pixel","Indie Selects — Órbita pixel",RecreationFamily.PIXELS,0xff0e1231.toInt(),0xff7159a1.toInt(),0xfff5c778.toInt(),"https://news.xbox.com/en-us/2026/01/28/indie-selects-anniversary/"),
        RecreatedBackground("recreation:indie-selects-constela-o","Indie Selects — Constelação",RecreationFamily.PIXELS,0xff0e1231.toInt(),0xff7159a1.toInt(),0xfff5c778.toInt(),"https://news.xbox.com/en-us/2026/01/28/indie-selects-anniversary/"),
        RecreatedBackground("recreation:indie-selects-festival","Indie Selects — Festival",RecreationFamily.PIXELS,0xff0e1231.toInt(),0xff7159a1.toInt(),0xfff5c778.toInt(),"https://news.xbox.com/en-us/2026/01/28/indie-selects-anniversary/"),
        RecreatedBackground("recreation:microsoft-50th-anniversary","Microsoft 50th Anniversary",RecreationFamily.COMMUNITY,0xff101026.toInt(),0xff6c66a2.toInt(),0xffe2c09a.toInt(),"https://news.xbox.com/en-us/2025/04/02/microsoft-50th-anniversary-dynamic-background-profile-theme/"),
        RecreatedBackground("recreation:x25-special-edition","X25 Special Edition",RecreationFamily.ANNIVERSARY,0xff020c05.toInt(),0xff176320.toInt(),0xffa2f33f.toInt(),"https://news.xbox.com/en-us/2026/11/12/xbox-insiders-january-2027-console-features-1/"),
        RecreatedBackground("recreation:diablo-iv-lord-of-hatred","Diablo IV: Lord of Hatred",RecreationFamily.EMBER,0xff190504.toInt(),0xff7d2310.toInt(),0xffffa63e.toInt(),"https://news.xbox.com/en-us/2026/04/30/april-xbox-update-2026/"),
        RecreatedBackground("recreation:invincible-vs","Invincible VS",RecreationFamily.ENERGY,0xff131c30.toInt(),0xffcbad28.toInt(),0xff6cabd5.toInt(),"https://news.xbox.com/en-us/2026/04/30/april-xbox-update-2026/"),
        RecreatedBackground("recreation:kiln","Kiln",RecreationFamily.FIREFLIES,0xff071b12.toInt(),0xff337c53.toInt(),0xffe6db8b.toInt(),"https://news.xbox.com/en-us/2026/04/30/april-xbox-update-2026/"),
        RecreatedBackground("recreation:overwatch","Overwatch",RecreationFamily.ENERGY,0xff081827.toInt(),0xff387981.toInt(),0xff8ce3bd.toInt(),"https://news.xbox.com/en-us/2026/04/30/april-xbox-update-2026/"),
        RecreatedBackground("recreation:crimson-desert","Crimson Desert",RecreationFamily.MAGIC,0xff20140b.toInt(),0xff8a6d3c.toInt(),0xffddc68b.toInt(),"https://news.xbox.com/en-us/2026/03/25/march-2026-xbox-update/"),
        RecreatedBackground("recreation:sea-of-thieves","Sea of Thieves",RecreationFamily.OCEAN,0xff030e18.toInt(),0xff156976.toInt(),0xff8cd4ba.toInt(),"https://news.xbox.com/en-us/2026/03/25/march-2026-xbox-update/"),
        RecreatedBackground("recreation:towerborne","Towerborne",RecreationFamily.MAGIC,0xff071522.toInt(),0xff267882.toInt(),0xffb4e7a3.toInt(),"https://news.xbox.com/en-us/2026/03/25/march-2026-xbox-update/"),
        RecreatedBackground("recreation:battlefield-6","Battlefield 6",RecreationFamily.TACTICAL,0xff111515.toInt(),0xff5f5b37.toInt(),0xfff2a953.toInt(),"https://news.xbox.com/en-us/2025/10/30/xbox-october-update-rog-xbox-ally-available-now/"),
        RecreatedBackground("recreation:keeper","Keeper",RecreationFamily.MAGIC,0xff071a13.toInt(),0xff285c50.toInt(),0xffefcd83.toInt(),"https://news.xbox.com/en-us/2025/10/30/xbox-october-update-rog-xbox-ally-available-now/"),
        RecreatedBackground("recreation:the-elder-scrolls-online","The Elder Scrolls Online",RecreationFamily.MAGIC,0xff081b27.toInt(),0xff597a9a.toInt(),0xffe6c690.toInt(),"https://news.xbox.com/en-us/2025/10/30/xbox-october-update-rog-xbox-ally-available-now/"),
        RecreatedBackground("recreation:the-outer-worlds-2","The Outer Worlds 2",RecreationFamily.MAGIC,0xff1c0d28.toInt(),0xff72428e.toInt(),0xff70e4db.toInt(),"https://news.xbox.com/en-us/2025/10/30/xbox-october-update-rog-xbox-ally-available-now/"),
        RecreatedBackground("recreation:call-of-duty-black-ops-7-vault-edition","Call of Duty: Black Ops 7 — Vault Edition",RecreationFamily.TACTICAL,0xff111515.toInt(),0xff5f5b37.toInt(),0xfff2a953.toInt(),"https://news.xbox.com/en-us/2025/09/29/xbox-september-update-2025/"),
        RecreatedBackground("recreation:duckside","Duckside",RecreationFamily.FIREFLIES,0xff051a24.toInt(),0xff1f6f80.toInt(),0xffd3d377.toInt(),"https://news.xbox.com/en-us/2025/09/29/xbox-september-update-2025/"),
        RecreatedBackground("recreation:gears-of-war-reloaded","Gears of War: Reloaded",RecreationFamily.EMBER,0xff160f0c.toInt(),0xff734839.toInt(),0xffe4aa66.toInt(),"https://news.xbox.com/en-us/2025/08/28/xbox-august-update-cross-device-play-history-controller-updates/"),
        RecreatedBackground("recreation:grounded-2","Grounded 2",RecreationFamily.FIREFLIES,0xff071b12.toInt(),0xff337c53.toInt(),0xffe6db8b.toInt(),"https://news.xbox.com/en-us/2025/08/28/xbox-august-update-cross-device-play-history-controller-updates/"),
        RecreatedBackground("recreation:madden-nfl-26","Madden NFL 26",RecreationFamily.ENERGY,0xff081b18.toInt(),0xff2e7258.toInt(),0xffa8ecd0.toInt(),"https://news.xbox.com/en-us/2025/08/28/xbox-august-update-cross-device-play-history-controller-updates/"),
        RecreatedBackground("recreation:ninja-gaiden-4","Ninja Gaiden 4",RecreationFamily.ENERGY,0xff160808.toInt(),0xff751c22.toInt(),0xffc3ddc1.toInt(),"https://news.xbox.com/en-us/2025/08/28/xbox-august-update-cross-device-play-history-controller-updates/"),
        RecreatedBackground("recreation:international-day-of-the-world-s-indigenous-peoples-2025","International Day of the World’s Indigenous Peoples 2025",RecreationFamily.COMMUNITY,0xff101026.toInt(),0xff6c66a2.toInt(),0xffe2c09a.toInt(),"https://news.xbox.com/en-us/2025/08/08/international-day-worlds-indigenous-peoples-2025-xbox/"),
        RecreatedBackground("recreation:hundred-bullets","Hundred Bullets",RecreationFamily.ENERGY,0xff111922.toInt(),0xff8d592c.toInt(),0xfff5ba58.toInt(),"https://news.xbox.com/en-us/2025/06/23/xbox-june-update-copilot-for-gaming-aggregated-gaming-library/"),
        RecreatedBackground("recreation:warhammer-40-000-boltgun","Warhammer 40,000: Boltgun",RecreationFamily.EMBER,0xff160f0c.toInt(),0xff734839.toInt(),0xffe4aa66.toInt(),"https://news.xbox.com/en-us/2025/06/23/xbox-june-update-copilot-for-gaming-aggregated-gaming-library/"),
        RecreatedBackground("recreation:the-witcher-3-wild-hunt","The Witcher 3: Wild Hunt",RecreationFamily.SNOW,0xff0b1420.toInt(),0xff56758a.toInt(),0xffd5e9ee.toInt(),"https://news.xbox.com/en-us/2025/06/23/xbox-june-update-copilot-for-gaming-aggregated-gaming-library/"),
        RecreatedBackground("recreation:pride-2025","Pride 2025",RecreationFamily.COMMUNITY,0xff160b29.toInt(),0xffb34b96.toInt(),0xff6bdab1.toInt(),"https://news.xbox.com/en-us/2025/06/23/xbox-june-update-copilot-for-gaming-aggregated-gaming-library/"),
        RecreatedBackground("recreation:warhammer-40-000-space-marine-2","Warhammer 40,000: Space Marine 2",RecreationFamily.EMBER,0xff160f0c.toInt(),0xff734839.toInt(),0xffe4aa66.toInt(),"https://news.xbox.com/en-us/2025/05/22/warhammer-skulls-xbox-recap-2025/"),
        RecreatedBackground("recreation:warhammer-40-000-rogue-trader","Warhammer 40,000: Rogue Trader",RecreationFamily.EMBER,0xff160f0c.toInt(),0xff734839.toInt(),0xffe4aa66.toInt(),"https://news.xbox.com/en-us/2025/05/22/warhammer-skulls-xbox-recap-2025/"),
        RecreatedBackground("recreation:doom-the-dark-ages","DOOM: The Dark Ages",RecreationFamily.EMBER,0xff190504.toInt(),0xff7d2310.toInt(),0xffffa63e.toInt(),"https://news.xbox.com/en-us/2025/05/21/xbox-may-update-retro-classics-pc-gaming-updates/"),
        RecreatedBackground("recreation:metaphor-refantazio","Metaphor: ReFantazio",RecreationFamily.MAGIC,0xff151728.toInt(),0xff4c6c8d.toInt(),0xffeee2c0.toInt(),"https://news.xbox.com/en-us/2025/05/21/xbox-may-update-retro-classics-pc-gaming-updates/"),
        RecreatedBackground("recreation:asian-and-pacific-islander-heritage-month-2025","Asian and Pacific Islander Heritage Month 2025",RecreationFamily.COMMUNITY,0xff101026.toInt(),0xff6c66a2.toInt(),0xffe2c09a.toInt(),"https://news.xbox.com/en-us/2025/05/05/xbox-asian-pacific-islander-heritage-month-2025/"),
        RecreatedBackground("recreation:monster-hunter-wilds","Monster Hunter Wilds",RecreationFamily.MAGIC,0xff0a0b24.toInt(),0xff4f4577.toInt(),0xffd9ae7e.toInt(),"https://news.xbox.com/en-us/2025/03/26/xbox-march-update-in-game-benefits-free-to-play-cloud-gaming/"),
        RecreatedBackground("recreation:sea-of-thieves-megalodon","Sea of Thieves: Megalodon",RecreationFamily.OCEAN,0xff020d1c.toInt(),0xff134264.toInt(),0xff50bdd3.toInt(),"https://news.xbox.com/en-us/2025/03/26/xbox-march-update-in-game-benefits-free-to-play-cloud-gaming/"),
        RecreatedBackground("recreation:sea-of-thieves-sunset","Sea of Thieves: Sunset",RecreationFamily.OCEAN,0xff220c24.toInt(),0xffb34b39.toInt(),0xffffc57b.toInt(),"https://news.xbox.com/en-us/2025/03/26/xbox-march-update-in-game-benefits-free-to-play-cloud-gaming/"),
        RecreatedBackground("recreation:split-fiction","Split Fiction",RecreationFamily.DUALITY,0xff140b21.toInt(),0xffa54535.toInt(),0xff3cbbe3.toInt(),"https://news.xbox.com/en-us/2025/03/26/xbox-march-update-in-game-benefits-free-to-play-cloud-gaming/"),
        RecreatedBackground("recreation:wwe-2k25","WWE 2K25",RecreationFamily.ENERGY,0xff1d070c.toInt(),0xff95263d.toInt(),0xfff4d3c8.toInt(),"https://news.xbox.com/en-us/2025/03/26/xbox-march-update-in-game-benefits-free-to-play-cloud-gaming/"),
        RecreatedBackground("recreation:international-women-s-day-2025","International Women’s Day 2025",RecreationFamily.COMMUNITY,0xff101026.toInt(),0xff6c66a2.toInt(),0xffe2c09a.toInt(),"https://news.xbox.com/en-us/2025/03/07/xbox-international-womens-day-roberta-williams-interview/"),
        RecreatedBackground("recreation:black-history-month-2025","Black History Month 2025",RecreationFamily.COMMUNITY,0xff180e12.toInt(),0xffba703f.toInt(),0xffd8c074.toInt(),"https://news.xbox.com/en-us/2025/02/03/black-history-month-2025/"),
        RecreatedBackground("recreation:mental-wellness-awareness-2024","Mental Wellness Awareness 2024",RecreationFamily.COMMUNITY,0xff101026.toInt(),0xff6c66a2.toInt(),0xffe2c09a.toInt(),"https://news.xbox.com/en-us/2024/12/16/xbox-mental-wellness-2024/"),
        RecreatedBackground("recreation:international-day-of-the-world-s-indigenous-peoples-2024","International Day of the World’s Indigenous Peoples 2024",RecreationFamily.COMMUNITY,0xff101026.toInt(),0xff6c66a2.toInt(),0xffe2c09a.toInt(),"https://news.xbox.com/en-us/2024/08/09/xbox-international-day-worlds-indigenous-peoples-2024/"),
        RecreatedBackground("recreation:pride-2024","Pride 2024",RecreationFamily.COMMUNITY,0xff160b29.toInt(),0xffb34b96.toInt(),0xff6bdab1.toInt(),"https://news.xbox.com/en-us/2024/05/31/xbox-pride-2024/"),
        RecreatedBackground("recreation:xbox-360-blades","Xbox 360 Blades",RecreationFamily.RIBBONS,0xff1a2413.toInt(),0xff677c3b.toInt(),0xffd0e5a4.toInt(),"https://news.xbox.com/en-us/2024/05/14/xbox-360-price-reductions-available-now/"),
        RecreatedBackground("recreation:fire-vapor","Fire Vapor",RecreationFamily.VAPOR,0xff1b0804.toInt(),0xffb33516.toInt(),0xffffc449.toInt(),"https://news.xbox.com/en-us/2024/05/07/introducing-xbox-wireless-controller-fire-vapor-special-edition/"),
        RecreatedBackground("recreation:asian-and-pacific-islander-heritage-month-2024","Asian and Pacific Islander Heritage Month 2024",RecreationFamily.COMMUNITY,0xff101026.toInt(),0xff6c66a2.toInt(),0xffe2c09a.toInt(),"https://news.xbox.com/en-us/2024/05/01/xbox-empowers-asian-and-pacific-islander-communities-2024/"),
        RecreatedBackground("recreation:women-s-history-month-2024-amika-cooper","Women’s History Month 2024 — Amika Cooper",RecreationFamily.COMMUNITY,0xff101026.toInt(),0xff6c66a2.toInt(),0xffe2c09a.toInt(),"https://news.xbox.com/en-us/2024/03/01/xbox-womens-history-month-2024/"),
        RecreatedBackground("recreation:black-history-month-2024-dominique-ramsey","Black History Month 2024 — Dominique Ramsey",RecreationFamily.COMMUNITY,0xff180e12.toInt(),0xffba703f.toInt(),0xffd8c074.toInt(),"https://news.xbox.com/en-us/2024/02/01/xbox-and-gameheads-amplifying-black-voices-in-gaming/"),
        RecreatedBackground("recreation:stormcloud-vapor","Stormcloud Vapor",RecreationFamily.VAPOR,0xff061123.toInt(),0xff244a90.toInt(),0xff65b8f7.toInt(),"https://news.xbox.com/en-us/2023/08/01/stormcloud-vapor-special-edition-xbox-wireless-controller/"),
        RecreatedBackground("recreation:starfield-limited-edition","Starfield Limited Edition",RecreationFamily.STARMAP,0xff070e1e.toInt(),0xff375275.toInt(),0xffd3dbd8.toInt(),"https://news.xbox.com/en-us/2023/06/12/starfield-limited-edition-wireless-controller-and-headset/"),
        RecreatedBackground("recreation:destiny-2","Destiny 2",RecreationFamily.MAGIC,0xff0a0b24.toInt(),0xff4f4577.toInt(),0xffd9ae7e.toInt(),"https://news.xbox.com/en-us/2023/02/28/destiny-2-lightfall/"),
        RecreatedBackground("recreation:stellar-shift","Stellar Shift",RecreationFamily.NEBULA,0xff080b24.toInt(),0xff4a388b.toInt(),0xff78c4ff.toInt(),"https://news.xbox.com/en-us/2023/02/07/stellar-shift-special-edition-wireless-controller-xbox/"),
        RecreatedBackground("recreation:xbox-game-pass","Xbox Game Pass",RecreationFamily.STARMAP,0xff031210.toInt(),0xff176449.toInt(),0xff9ae0ae.toInt(),"https://news.xbox.com/en-us/2021/07/23/xbox-insider-release-notes-alpha-skip-ahead-2109-210721-2200/"),
        RecreatedBackground("recreation:motes","Motes",RecreationFamily.PARTICLES,0xff071410.toInt(),0xff236748.toInt(),0xff9fe5a9.toInt(),"https://news.xbox.com/en-us/2021/05/11/may-2021-xbox-update/"),
        RecreatedBackground("recreation:xbox-25th-anniversary","Xbox 25th Anniversary",RecreationFamily.ANNIVERSARY,0xff020c05.toInt(),0xff176320.toInt(),0xffa2f33f.toInt(),"https://news.xbox.com/en-us/2026/08/24/xbox-anniversary-x25-fanfest-gear-community-designs/"),
        RecreatedBackground("recreation:xbox-25-anos-part-culas","Xbox 25 anos — Partículas",RecreationFamily.ANNIVERSARY,0xff020c05.toInt(),0xff176320.toInt(),0xffa2f33f.toInt(),"https://support.xbox.com/en-US/help/account-profile/customize-xbox-home/change-xbox-one-background"),
        RecreatedBackground("recreation:n-cleo-original","Núcleo Original",RecreationFamily.CORE,0xff041604.toInt(),0xff27870b.toInt(),0xffb8ff12.toInt(),"https://support.xbox.com/en-US/help/account-profile/customize-xbox-home/change-xbox-one-background"),
        RecreatedBackground("recreation:sea-of-thieves-lone-survivors","Sea of Thieves: Lone Survivors",RecreationFamily.OCEAN,0xff030e18.toInt(),0xff156976.toInt(),0xff8cd4ba.toInt(),"https://support.xbox.com/en-US/help/account-profile/customize-xbox-home/change-xbox-one-background"),
        RecreatedBackground("recreation:sea-of-thieves-reaper-s-mark","Sea of Thieves: Reaper’s Mark",RecreationFamily.SKULL,0xff010e0d.toInt(),0xff11695c.toInt(),0xff5ce0ba.toInt(),"https://support.xbox.com/en-US/help/account-profile/customize-xbox-home/change-xbox-one-background")
    )
    private val byId=entries.associateBy { it.id }
    fun find(id: String)=byId[id]
    @Volatile private var cachedArtwork: List<SeriesWallpaper>?=null
    fun wallpapers(context: android.content.Context): List<SeriesWallpaper> = cachedArtwork ?: synchronized(this) {
        cachedArtwork ?: wallpapers(OfficialXboxWallpapers.read(context)).also { cachedArtwork=it }
    }
    // Identity pairs from the shipped public catalog. No substring/title-family fallback:
    // another edition, season or anniversary artwork is not an interchangeable base image.
    internal val artworkIds=mapOf(
        "recreation:diablo-iv-lord-of-hatred" to "official:945043ba3955e5c79d3d2682", // Diablo IV: Lord of Hatred
        "recreation:invincible-vs" to "official:83c24bb7e946ce6001f4e31b", // Invincible VS
        "recreation:kiln" to "official:0c65094384ca8184b5ae85cb", // Kiln
        "recreation:overwatch" to "official:c5e488e7b391d5007971644b", // Overwatch
        "recreation:sea-of-thieves" to "official:45f5d6410cd0a296199d396f", // Sea of Thieves
        "recreation:towerborne" to "official:9d7dba73ab33e953a906bed0", // Towerborne
        "recreation:keeper" to "official:4a5a365586684ef36fa23ab2", // Keeper
        "recreation:the-elder-scrolls-online" to "official:30ae84a1c7259a8fed2e082f", // The Elder Scrolls Online
        "recreation:the-outer-worlds-2" to "official:bb2a8680c5a237bc91826a80", // The Outer Worlds 2
        "recreation:call-of-duty-black-ops-7-vault-edition" to "official:c72345110bf57ad43ab04822", // Call of Duty®: Black Ops 7 - Vault Edition
        "recreation:gears-of-war-reloaded" to "official:1373d4e585cd71e4e10479bc", // Gears of War: Reloaded
        "recreation:grounded-2" to "official:5becb954f5cf843ebde5271f", // Grounded 2
        "recreation:ninja-gaiden-4" to "official:13d3cbf46229fc515ae6517f", // NINJA GAIDEN 4
        "recreation:international-day-of-the-world-s-indigenous-peoples-2025" to "official:c470876a1df515f7adaa69eb", // International Day of the World's Indigenous Peoples 2025
        "recreation:the-witcher-3-wild-hunt" to "official:4dbde8c38032112e398b564e", // The Witcher 3 - Wildhunt
        "recreation:pride-2025" to "official:8f0ae49eabc1fedd6e5f4c44", // Pride Month 2025
        "recreation:doom-the-dark-ages" to "official:661d08d977c2d286421284a2", // DOOM: The Dark Ages
        "recreation:metaphor-refantazio" to "official:480ff007d735ebc3d1e83fdc", // Metaphor: ReFantazio
        "recreation:asian-and-pacific-islander-heritage-month-2025" to "official:06f19398df0db01dc0a5b345", // Asian and Pacific Island Heritage Month 2025
        "recreation:monster-hunter-wilds" to "official:6b6d3b43ca7a523ee338cff3", // Monster Hunter Wilds
        "recreation:international-women-s-day-2025" to "official:352eda8307fc75febe7502c4", // International Women's Day 2025
        "recreation:pride-2024" to "official:de406c1eebdd9a36e12aef6a", // Pride 2024
        "recreation:asian-and-pacific-islander-heritage-month-2024" to "official:eaf0362ecc709dee26f8e75d", // Asian and Pacific Islander Heritage Month 2024
        "recreation:destiny-2" to "official:8957bab897c3dcfcdc2b0c0d", // Destiny 2
    )
    internal fun restoredArtwork(id: String,url: String?): String? {
        val expected=artworkIds[id] ?: return null
        return url?.takeIf { OfficialXboxWallpapers.entry("Artwork",it)?.id==expected }
    }
    internal fun wallpapers(items: List<SeriesWallpaper>): List<SeriesWallpaper> {
        val official=items.filter { it.official }.associateBy { it.id }
        return entries.map { entry -> entry.wallpaper(artworkIds[entry.id]?.let { official[it]?.artUrl }) }
    }
}
