package com.toer15.queststeam.display

import android.content.Context
import android.net.ConnectivityManager
import android.system.OsConstants
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * The network as Wine needs to see it. Android blocks the usual interface lookup (netlink), so
 * Winlator's runtime reads the interfaces from <rootfs>/tmp/ifaddrs and the host name from
 * /etc/hosts (format from Winlator's NetworkInfoUpdateComponent). Needed by Steam and online games.
 */
object NetworkInfo {
    fun write(ctx: Context, rootfs: File) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val props = cm.activeNetwork?.let { cm.getLinkProperties(it) }
        val lines = mutableListOf<String>()
        var ipv4: String? = null
        props?.linkAddresses?.forEach { la ->
            val a = la.address
            val v6 = a is Inet6Address
            if (a !is Inet4Address && !v6) return@forEach
            if (a is Inet4Address && ipv4 == null) ipv4 = a.hostAddress
            val family = if (v6) OsConstants.AF_INET6 else OsConstants.AF_INET
            val scope = if (v6) (a as Inet6Address).scopeId else 0
            val flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            lines += "eth0,$flags,$family,$scope,${a.hostAddress?.substringBefore('%')},${netmask(la.prefixLength)}"
        }
        if (lines.isEmpty()) lines += "eth0,0,${OsConstants.AF_INET},0,0,0"
        File(rootfs, "tmp").mkdirs()
        File(rootfs, "tmp/ifaddrs").writeText(lines.joinToString("\n"))
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/hosts").writeText("${ipv4 ?: "127.0.0.1"}\tlocalhost\n")
    }

    private fun netmask(prefix: Int) = when (prefix) {
        8 -> "255.0.0.0"
        16 -> "255.255.0.0"
        24 -> "255.255.255.0"
        32 -> "255.255.255.255"
        64 -> "ffff:ffff:ffff:ffff::"
        else -> ""
    }
}
