package org.olcbox.app.data.model

/**
 * Dual-engine tag. olcRTC locations use the existing Mobile/hev path;
 * Mihomo locations use libclash TUN.
 */
object TunnelEngine {
    const val OLCRTC = "olcrtc"
    const val MIHOMO = "mihomo"
}
