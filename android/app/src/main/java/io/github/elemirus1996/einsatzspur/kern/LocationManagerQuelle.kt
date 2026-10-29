package io.github.elemirus1996.einsatzspur.kern

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Looper

/**
 * Ortung über den `LocationManager` des Systems, ohne Google Play Services.
 * FEIN: GPS, alle 2 s, ab 5 m Bewegung. GROB: nur Netzwerk-Ortung (Funkzellen/WLAN), kein GPS,
 * selten und erst ab 1 km; ihre Positionen werden verworfen, sie hält nur den Dienst wach.
 * Läuft im Vordergrunddienst [io.github.elemirus1996.einsatzspur.dienst.OrtungsDienst].
 */
class LocationManagerQuelle(private val context: Context) : OrtungsQuelle, LocationListener {
    override var beiPosition: ((Position) -> Unit)? = null

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var stufe: OrtungsStufe? = null

    @SuppressLint("MissingPermission")
    override fun starten(stufe: OrtungsStufe) {
        if (this.stufe == stufe) return
        manager.removeUpdates(this)
        this.stufe = stufe
        try {
            when (stufe) {
                OrtungsStufe.FEIN ->
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 5f, this, Looper.getMainLooper())
                OrtungsStufe.GROB -> {
                    val anbieter = if (manager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                        LocationManager.NETWORK_PROVIDER
                    } else {
                        LocationManager.PASSIVE_PROVIDER
                    }
                    manager.requestLocationUpdates(anbieter, 60_000L, 1_000f, this, Looper.getMainLooper())
                }
            }
        } catch (e: SecurityException) {
            // Ohne Standort-Berechtigung keine Ortung; die Anzeige fordert sie an
            this.stufe = null
        }
    }

    override fun beenden() {
        manager.removeUpdates(this)
        stufe = null
    }

    override fun onLocationChanged(ort: Location) {
        // Grob-Ortung dient nur dem Wachhalten
        if (stufe != OrtungsStufe.FEIN) return
        beiPosition?.invoke(
            Position(
                lat = ort.latitude,
                lng = ort.longitude,
                accuracy = if (ort.hasAccuracy()) ort.accuracy.toDouble() else null,
                timestamp = ort.time,
                battery = akku(),
            )
        )
    }

    // Ohne diese Überschreibungen stürzen ältere Android-Versionen beim Ein-/Ausschalten von GPS ab
    @Deprecated("Nur für API < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun akku(): Int? {
        val stand = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val wert = stand.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val skala = stand.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (wert >= 0 && skala > 0) (wert * 100 / skala) else null
    }
}
