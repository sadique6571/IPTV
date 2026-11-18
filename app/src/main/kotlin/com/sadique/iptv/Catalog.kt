package com.sadique.iptv

internal data class Channel(val name: String, val group: String, val url: String, val web: Boolean = false)

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
    Channel("Tamasha", "Cricket", ""),
    Channel("DD Sports", "Cricket", ""),
    Channel("ICC.tv", "Cricket", ""),
)
