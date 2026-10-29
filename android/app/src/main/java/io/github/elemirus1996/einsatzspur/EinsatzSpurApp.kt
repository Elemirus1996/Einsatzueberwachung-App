package io.github.elemirus1996.einsatzspur

import android.app.Application
import android.content.pm.ApplicationInfo
import io.github.elemirus1996.einsatzspur.kern.KartenNetz
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager

class EinsatzSpurApp : Application() {
    lateinit var modell: AppModell
        private set

    override fun onCreate() {
        super.onCreate()
        // Karte: ohne API-Schlüssel, nur über unseren Client mit Host-Freigabeliste und eigenem User-Agent
        MapLibre.getInstance(this)
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
        val debug = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        HttpRequestUtil.setOkHttpClient(KartenNetz.client(version, protokollieren = debug))
        // Kachel-Cache (Nutzungsregeln OSM: Kacheln nicht ständig neu laden)
        OfflineManager.getInstance(this).setMaximumAmbientCacheSize(KACHEL_CACHE_BYTES, null)
        modell = AppModell(this)
    }

    companion object {
        private const val KACHEL_CACHE_BYTES = 200L * 1024 * 1024
    }
}
