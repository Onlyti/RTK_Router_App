package com.ailab.rtkrouter.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone GNSS via the platform LocationManager (no Play Services). Used as the
 * position seed for NTRIP GGA upload (VRS) and nearest-mount selection.
 * Adapted from phone-sensor-stream sensors/LocationHub.kt.
 */
class LocationHub(private val context: Context) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var thread: HandlerThread? = null
    private val last = AtomicReference<Location?>(null)

    private val listener = LocationListener { loc -> last.set(loc) }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Most recent fix, or null if none yet. */
    fun lastLocation(): Location? = last.get()

    @SuppressLint("MissingPermission")
    fun start(minIntervalMs: Long = 1000L) {
        if (!hasPermission() || thread != null) return
        val t = HandlerThread("rtk-location").also { it.start() }
        thread = t
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        try {
            lm.getLastKnownLocation(provider)?.let { last.set(it) }
            lm.requestLocationUpdates(provider, minIntervalMs, 0f, listener, t.looper)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    fun stop() {
        lm.removeUpdates(listener)
        thread?.quitSafely()
        thread = null
    }
}
