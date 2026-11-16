package io.github.elemirus1996.einsatzspur.kern

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.elemirus1996.einsatzspur.R
import io.github.elemirus1996.einsatzspur.ui.MainActivity

/**
 * Erinnerung an die Packliste als lokale Mitteilung (kein Push, kein Server). Ungenauer Wecker reicht; der Zeitpunkt
 * wird gemerkt, damit er nach einem Neustart des Geräts wieder gestellt wird.
 */
object PacklisteErinnerung {
    private const val KANAL = "packliste"
    private const val NUMMER = 4711
    private const val ZEIT = "packliste.naechste"

    fun planen(context: Context, zeitMs: Long?) {
        val wecker = context.getSystemService(AlarmManager::class.java)
        val ausloeser = ausloeser(context)
        wecker.cancel(ausloeser)
        val prefs = context.getSharedPreferences("einstellungen", Context.MODE_PRIVATE)
        prefs.edit().putString(ZEIT, zeitMs?.toString()).apply()
        if (zeitMs != null) wecker.set(AlarmManager.RTC_WAKEUP, zeitMs, ausloeser)
    }

    /** Nach dem Neustart des Geräts: gemerkten Zeitpunkt wieder stellen (verpasst = gleich erinnern) */
    internal fun wiederherstellen(context: Context) {
        val zeit = context.getSharedPreferences("einstellungen", Context.MODE_PRIVATE).getString(ZEIT, null)?.toLongOrNull() ?: return
        planen(context, maxOf(zeit, System.currentTimeMillis() + 60_000))
    }

    internal fun zeigen(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(KANAL, "Packliste", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Erinnerung, die Packliste für den Einsatz durchzugehen"
        })
        val oeffnen = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(NUMMER, NotificationCompat.Builder(context, KANAL)
            .setSmallIcon(R.drawable.ic_mitteilung)
            .setContentTitle("Packliste")
            .setContentText("Zeit, die Packliste für den Einsatz durchzugehen.")
            .setAutoCancel(true)
            .setContentIntent(oeffnen)
            .build())
        context.getSharedPreferences("einstellungen", Context.MODE_PRIVATE).edit().remove(ZEIT).apply()
    }

    private fun ausloeser(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, PacklisteEmpfaenger::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Wecker der Packliste und Neustart des Geräts */
class PacklisteEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) PacklisteErinnerung.wiederherstellen(context)
        else PacklisteErinnerung.zeigen(context)
    }
}
