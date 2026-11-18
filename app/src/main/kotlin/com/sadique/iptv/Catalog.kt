package com.sadique.iptv

internal data class Channel(
    val name: String,
    val group: String,
    val url: String,
    val web: Boolean = false,
    val country: String = "",
    val languages: Set<String> = emptySet(),
    val logo: String = "",
) {
    /** Stable identity for favorites / recent / last-channel lookups. */
    val key: String get() = name + "\u0000" + url
}
