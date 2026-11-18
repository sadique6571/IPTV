package com.sadique.iptv

internal data class Channel(val name: String, val group: String, val url: String, val web: Boolean = false, val country: String = "")

internal val builtInChannels = listOf(
    Channel("Geo News", "Pakistan", "t3fvgmDDmdc", web = true),
    Channel("Dawn News", "Pakistan", "3Mo88hzFewk", web = true),
    Channel("Aaj News", "Pakistan", "9lLVNq4lWVk", web = true),
    Channel("City 42", "Pakistan", "OR9fZViwnyQ", web = true),
    Channel("Express News", "Pakistan", "KNvaWHgOBMw", web = true),
    Channel("ABP News", "India", "https://d2l4ar6y3mrs4k.cloudfront.net/live-streaming/abpnews-livetv/master.m3u8"),
    Channel("ABP Ananda", "India", "https://d2l4ar6y3mrs4k.cloudfront.net/live-streaming/ananda-livetv/master.m3u8"),
    Channel("India TV", "India", "https://pl-indiatvnews.akamaized.net/out/v1/db79179b608641ceaa5a4d0dd0dca8da/index.m3u8"),
    Channel("NDTV India", "India", "https://ndtvindiaelemarchana.akamaized.net/hls/live/2003679/ndtvindia/master.m3u8"),
    Channel("NDTV 24x7", "India", "https://ndtv24x7elemarchana.akamaized.net/hls/live/2003678/ndtv24x7/master.m3u8"),
    Channel("Har Pal Geo • Khuda Aur Mohabbat S2, Ep 1", "Drama", "V9CCPpF5dE4", web = true),
    Channel("HUM TV • Gulbahar, Ep 1", "Drama", "uCBJymYg8Fk", web = true),
    Channel("ARY Digital • Ae Dil, Ep 1", "Drama", "XaAnkBFzGJ4", web = true),
    Channel("Green TV • Intekhab, Ep 1", "Drama", "dbL9xd6wvhE", web = true),
    Channel("HUM TV Live", "Drama", "https://g4wlkwx8l23a-hls-live.5centscdn.com/HUM/271ddf829afeece44d8732757fba1a66.sdp/playlist_dvr.m3u8", country = "PK"),
    Channel("DD Sports", "Cricket", "https://d3qs3d2rkhfqrt.cloudfront.net/out/v1/b17adfe543354fdd8d189b110617cddd/index.m3u8", country = "IN"),
    Channel("Cricket Gold", "Cricket", "https://streams2.sofast.tv/ptnr-yupptv/title-cricketgold/v1/master/611d79b11b77e2f571934fd80ca1413453772ac7/b2048bb8-1686-4432-aa50-647245383e0c/manifest.m3u8"),
)


internal data class CatalogSource(val name: String, val url: String, val country: String = "")

private const val IPTV_ORG = "https://iptv-org.github.io/iptv"
private const val FREE_TV = "https://raw.githubusercontent.com/Free-TV/IPTV/master"

internal val catalogSources = listOf(
    CatalogSource("IPTV-org · Pakistan", "$IPTV_ORG/countries/pk.m3u", "PK"),
    CatalogSource("IPTV-org · India", "$IPTV_ORG/countries/in.m3u", "IN"),
    CatalogSource("IPTV-org · Sports", "$IPTV_ORG/categories/sports.m3u"),
    CatalogSource("IPTV-org · Entertainment", "$IPTV_ORG/categories/entertainment.m3u"),
    CatalogSource("IPTV-org · All countries", "$IPTV_ORG/index.m3u"),
    CatalogSource("Free-TV · India", "$FREE_TV/playlists/playlist_india.m3u8", "IN"),
    CatalogSource("Free-TV · All countries", "$FREE_TV/playlist.m3u8"),
)
