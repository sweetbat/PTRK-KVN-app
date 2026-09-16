package org.olcbox.app.vpn

/**
 * Soft RoscomVPN / HAPP-inspired rules using MetaCubeX GeoSite tags only.
 * Do NOT swap geox-url to Roscom CDN — that hung setup/ping and bloated storage.
 *
 * Source intent: https://raw.githubusercontent.com/hydraponique/roscomvpn-routing/.../HAPP/DEFAULT.JSON
 * (DNS from that JSON is not applied.)
 */
object RoscomVpnRouting {
    /** block → proxy → direct (then subscription rules / MATCH). */
    fun forceRulesForGroup(group: String): List<String> {
        val g = group.trim().ifBlank { "GLOBAL" }
        return buildList {
            add("GEOSITE,category-ads,REJECT")
            add("GEOSITE,github,$g")
            add("GEOSITE,youtube,$g")
            add("GEOSITE,telegram,$g")
            add("GEOSITE,google,$g")
            add("GEOSITE,private,DIRECT")
            add("GEOSITE,category-ru,DIRECT")
            add("GEOSITE,microsoft,DIRECT")
            add("GEOSITE,apple,DIRECT")
            add("GEOSITE,steam,DIRECT")
            add("GEOIP,private,DIRECT,no-resolve")
            add("GEOIP,RU,DIRECT")
        }
    }
}
