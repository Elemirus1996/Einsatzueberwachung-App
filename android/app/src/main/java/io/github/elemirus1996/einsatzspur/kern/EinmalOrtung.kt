package io.github.elemirus1996.einsatzspur.kern

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Genaue Position für „Punkt setzen“ (APP_API.md Abschnitt 13, wie iOS `EinmalOrtung`): nur auf Knopfdruck, eine einzelne
 * GPS-Ortung, unabhängig von der laufenden Ortung des Teams (auch in der Pause mit Grob-Ortung).
 */
class EinmalOrtung(private val context: Context) {
    data class Ergebnis(val lat: Double, val lng: Double, val genauigkeit: Double?, val zeitMs: Long)

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    val freigegeben: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** `null`: keine Freigabe oder keine Position innerhalb von 30 s */
    @SuppressLint("MissingPermission")
    suspend fun position(jetzt: Long = System.currentTimeMillis()): Ergebnis? {
        if (!freigegeben) return null
        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.takeIf { jetzt - it.time <= MAX_ALTER_MS }?.let { return ergebnis(it) }
        val ort = withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine<Location?> { weiter ->
                val hoerer = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (weiter.isActive) weiter.resume(location)
                    }
                    @Deprecated("Ältere Android-Versionen")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                    override fun onProviderDisabled(provider: String) {
                        manager.removeUpdates(this)
                        if (weiter.isActive) weiter.resume(null)
                    }
                    override fun onProviderEnabled(provider: String) = Unit
                }
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, hoerer, Looper.getMainLooper())
                weiter.invokeOnCancellation { manager.removeUpdates(hoerer) }
            }
        }
        return ort?.let(::ergebnis)
    }

    private fun ergebnis(ort: Location) =
        Ergebnis(ort.latitude, ort.longitude, if (ort.hasAccuracy()) ort.accuracy.toDouble() else null, ort.time)

    companion object {
        /** Höchstens so alt darf die Position sein */
        const val MAX_ALTER_MS = 60_000L
    }
}
