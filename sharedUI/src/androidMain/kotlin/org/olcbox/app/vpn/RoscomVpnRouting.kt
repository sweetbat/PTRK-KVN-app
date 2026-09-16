package org.olcbox.app.vpn

/**
 * RoscomVPN / HAPP default routing adapted for Mihomo (links + rule order).
 * Source: https://raw.githubusercontent.com/hydraponique/roscomvpn-routing/refs/heads/main/HAPP/DEFAULT.JSON
 *
 * DNS from that JSON is intentionally NOT applied — RKN TSPU hijacks public UDP DNS;
 * we keep RuSafeDns / DoH bootstrap instead.
 */
object RoscomVpnRouting {
    const val GEOIP_URL =
        "https://cdn.jsdelivr.net/gh/hydraponique/roscomvpn-geoip@202609160831/release/geoip.dat"
    const val GEOSITE_URL =
        "https://cdn.jsdelivr.net/gh/hydraponique/roscomvpn-geosite@202604152235/release/geosite.dat"

    /** block → proxy → direct (then subscription rules / MATCH). */
    fun forceRulesForGroup(group: String): List<String> {
        val g = group.trim().ifBlank { "GLOBAL" }
        return buildList {
            // Block
            add("GEOSITE,win-spy,REJECT")
            add("GEOSITE,torrent,REJECT")
            add("GEOSITE,category-ads,REJECT")
            // Always proxy (foreign / blocked apps)
            add("GEOSITE,google-play,$g")
            add("GEOSITE,github,$g")
            add("GEOSITE,twitch-ads,$g")
            add("GEOSITE,youtube,$g")
            add("GEOSITE,telegram,$g")
            // Domestic / games / office → DIRECT
            add("GEOSITE,private,DIRECT")
            add("GEOSITE,category-ru,DIRECT")
            add("GEOSITE,whitelist,DIRECT")
            add("GEOSITE,microsoft,DIRECT")
            add("GEOSITE,apple,DIRECT")
            add("GEOSITE,epicgames,DIRECT")
            add("GEOSITE,riot,DIRECT")
            add("GEOSITE,escapefromtarkov,DIRECT")
            add("GEOSITE,steam,DIRECT")
            add("GEOSITE,twitch,DIRECT")
            add("GEOSITE,pinterest,DIRECT")
            add("GEOSITE,faceit,DIRECT")
            add("GEOIP,private,DIRECT,no-resolve")
            add("GEOIP,direct,DIRECT,no-resolve")
        }
    }
}
