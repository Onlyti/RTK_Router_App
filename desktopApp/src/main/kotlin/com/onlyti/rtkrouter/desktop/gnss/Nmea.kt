package com.onlyti.rtkrouter.desktop.gnss

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

object Nmea {
    fun checksum(body: String): String {
        var c = 0
        for (ch in body) c = c xor ch.code
        return String.format(Locale.US, "%02X", c and 0xFF)
    }

    fun buildGga(
        lat: Double,
        lon: Double,
        altMeters: Double,
        utcSecOfDay: Double,
        satellites: Int = 10,
    ): String {
        val hh = (utcSecOfDay / 3600).toInt()
        val mm = ((utcSecOfDay % 3600) / 60).toInt()
        val ss = utcSecOfDay % 60
        val time = String.format(Locale.US, "%02d%02d%05.2f", hh, mm, ss)
        val (latStr, ns) = degToDm(lat, isLat = true)
        val (lonStr, ew) = degToDm(lon, isLat = false)
        val body = String.format(
            Locale.US,
            "GPGGA,%s,%s,%s,%s,%s,1,%02d,1.0,%.1f,M,0.0,M,,",
            time, latStr, ns, lonStr, ew, satellites, altMeters,
        )
        return "\$$body*${checksum(body)}\r\n"
    }

    private fun degToDm(deg: Double, isLat: Boolean): Pair<String, Char> {
        val hemi = if (isLat) (if (deg >= 0) 'N' else 'S') else (if (deg >= 0) 'E' else 'W')
        val a = abs(deg)
        val d = floor(a).toInt()
        val minutes = (a - d) * 60.0
        return if (isLat) {
            String.format(Locale.US, "%02d%08.5f", d, minutes) to hemi
        } else {
            String.format(Locale.US, "%03d%08.5f", d, minutes) to hemi
        }
    }

    fun parseGgaQuality(sentence: String): Int? = parseGga(sentence)?.quality

    data class GgaFix(
        val quality: Int,
        val lat: Double,
        val lon: Double,
        val satellites: Int,
        val hdop: Double,
        val altMeters: Double,
    )

    fun parseGga(sentence: String): GgaFix? {
        val s = sentence.trim()
        if (!s.startsWith("\$") || s.length < 6) return null
        if (!s.substring(1, 6).endsWith("GGA")) return null
        val f = s.substringBefore('*').split(',')
        val q = f.getOrNull(6)?.toIntOrNull() ?: return null
        return GgaFix(
            quality = q,
            lat = dmToDeg(f.getOrNull(2), f.getOrNull(3)),
            lon = dmToDeg(f.getOrNull(4), f.getOrNull(5)),
            satellites = f.getOrNull(7)?.toIntOrNull() ?: 0,
            hdop = f.getOrNull(8)?.toDoubleOrNull() ?: Double.NaN,
            altMeters = f.getOrNull(9)?.toDoubleOrNull() ?: Double.NaN,
        )
    }

    private fun dmToDeg(dm: String?, hemi: String?): Double {
        val v = dm?.toDoubleOrNull() ?: return Double.NaN
        val deg = floor(v / 100.0)
        var dec = deg + (v - deg * 100.0) / 60.0
        if (hemi == "S" || hemi == "W") dec = -dec
        return dec
    }

    fun fixQualityLabel(q: Int): String = when (q) {
        0 -> "no fix"
        1 -> "GPS (single)"
        2 -> "DGPS"
        4 -> "RTK FIXED"
        5 -> "RTK float"
        else -> "q=$q"
    }
}
