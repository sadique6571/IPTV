package com.sadique.iptv

internal data class Channel(
    val name: String,
    val group: String,
    val url: String,
    val web: Boolean = false,
    val country: String = "",
)
