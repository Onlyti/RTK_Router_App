package com.ailab.rtkrouter.gnss

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/** NMEA-0183 helpers: build a GGA for NTRIP upload, parse fix quality from receiver GGA. */
object Nmea {

    /** XOR checksum over the bytes between '$' and '*'. */
    fun checksum(body: String): String {
        var c = 0
        for (ch in body) c = c xor ch.code
        return String.format(Locale.US, "%02X", c and 0xFF)
    }

    /**
     * Build a $GPGGA sentence from a fix. [utcSecOfDay] is seconds since UTC midnight.
     * Quality is reported as 1 (autonomous) which is sufficient for a VRS position seed.
     */
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

    /** GGA fix-quality field: 0 invalid, 1 GPS, 2 DGPS, 4 RTK fixed, 5 RTK float. */
    fun parseGgaQuality(sentence: String): Int? {
        val s = sentence.trim()
        if (!s.startsWith("\$") || s.length < 6) return null
        val type = s.substring(1, 6)
        if (!type.endsWith("GGA")) return null
        val fields = s.substringBefore('*').split(',')
        return fields.getOrNull(6)?.toIntOrNull()
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
