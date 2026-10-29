package io.github.elemirus1996.einsatzspur.kern

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Was auf diesem Gerät die Ortung im Einsatz verhindern kann, mit dem Weg in die passende Systemeinstellung.
 * Viele Hersteller beenden Vordergrunddienste trotz Benachrichtigung, solange die Akku-Optimierung aktiv ist.
 */
data class GeraeteHinweis(val titel: String, val text: String, val einstellung: Intent)

object GeraeteHinweise {

    fun pruefen(context: Context): List<GeraeteHinweis> = buildList {
        if (!erlaubt(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            add(
                GeraeteHinweis(
                    "Genauer Standort fehlt",
                    "Ohne „Genauer Standort“ kann die App deinen Laufweg nicht aufzeichnen. „Beim Verwenden der App“ genügt, „Immer“ ist nicht nötig.",
                    appEinstellungen(context),
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !erlaubt(context, Manifest.permission.POST_NOTIFICATIONS)) {
            add(
                GeraeteHinweis(
                    "Mitteilungen sind aus",
                    "Ohne Mitteilungen siehst du nicht, ob die Ortung läuft, und bekommst keine Hinweise der Einsatzleitung.",
                    appEinstellungen(context),
                )
            )
        }
        val strom = context.getSystemService(PowerManager::class.java)
        if (!strom.isIgnoringBatteryOptimizations(context.packageName)) {
            add(
                GeraeteHinweis(
                    "Akku-Optimierung ausschalten",
                    "Sonst kann Android die Ortung bei gesperrtem Handy beenden. In der Liste „EinsatzSpur“ auf „Nicht optimieren“ stellen." +
                        (herstellerHinweis(Build.MANUFACTURER)?.let { "\n\n$it" } ?: ""),
                    // Liste in den Systemeinstellungen; keine Direktanfrage (Google-Play-Richtlinien)
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            )
        }
    }

    /** Zusätzliche Einstellung je Hersteller, wo sie über die Android-Akku-Optimierung hinausgeht. */
    fun herstellerHinweis(hersteller: String?): String? = when (hersteller?.lowercase()?.trim()) {
        "samsung" ->
            "Samsung: Einstellungen → Akku → Hintergrundnutzungslimits → EinsatzSpur unter „Nie in Standby versetzte Apps“ eintragen."
        "xiaomi", "redmi", "poco" ->
            "Xiaomi: App-Info von EinsatzSpur → Akkusparmodus → „Keine Einschränkungen“, außerdem „Autostart“ einschalten."
        "huawei", "honor" ->
            "Huawei/Honor: Einstellungen → Akku → App-Start → EinsatzSpur auf „Manuell verwalten“ und alle drei Schalter an."
        "oppo", "oneplus", "realme" ->
            "OPPO/OnePlus/realme: App-Info von EinsatzSpur → Akkunutzung → „Hintergrundaktivität erlauben“ einschalten."
        "vivo", "iqoo" ->
            "vivo: Einstellungen → Akku → Hoher Hintergrundstromverbrauch → EinsatzSpur erlauben."
        "motorola", "google", "fairphone", "nothing", "sony" -> null
        else -> null
    }

    private fun erlaubt(context: Context, recht: String) =
        ContextCompat.checkSelfPermission(context, recht) == PackageManager.PERMISSION_GRANTED

    private fun appEinstellungen(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
}
