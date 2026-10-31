package io.github.elemirus1996.einsatzspur.kern

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.elemirus1996.einsatzspur.R
import io.github.elemirus1996.einsatzspur.ui.MainActivity

/** Lokale Mitteilungen (kein Push), nur wenn die App nicht im Vordergrund ist. Tippen öffnet die Team-Ansicht. */
class LokaleMitteilungen(private val context: Context, private val imVordergrund: () -> Boolean) {
    private var nummer = 100

    /** `stumm`: Ton dieser Art ist in den Einstellungen aus */
    fun senden(meldung: LageMeldung, stumm: Boolean = false) {
        if (imVordergrund()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(KANAL, "Lage", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Suche gestartet oder beendet, neues Gebiet, Mitteilungen der Einsatzleitung"
        })
        val oeffnen = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(nummer++, NotificationCompat.Builder(context, KANAL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(meldung.titel)
            .apply { meldung.text?.let { setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) } }
            .setAutoCancel(true)
            .setSilent(stumm)
            .setContentIntent(oeffnen)
            .build())
    }

    companion object {
        private const val KANAL = "lage"
    }
}
