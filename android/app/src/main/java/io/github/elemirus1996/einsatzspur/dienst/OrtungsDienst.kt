package io.github.elemirus1996.einsatzspur.dienst

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.elemirus1996.einsatzspur.EinsatzSpurApp
import io.github.elemirus1996.einsatzspur.R
import io.github.elemirus1996.einsatzspur.kern.TeamOrtung
import io.github.elemirus1996.einsatzspur.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Vordergrunddienst Typ `location` mit dauerhafter Benachrichtigung. Hält die Ortung im Hintergrund am Leben,
 * solange ein Team angemeldet ist (auch in der Pause, dann nur Grob-Ortung). Endet bei `stop`.
 */
class OrtungsDienst : Service() {
    private val bereich = MainScope()
    private var beobachtung: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        kanalAnlegen()
        val typ = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NUMMER, benachrichtigung(null), typ)
        } catch (e: Exception) {
            // Ohne Berechtigung oder aus dem Hintergrund gestartet: nicht laufen
            stopSelf()
            return START_NOT_STICKY
        }
        if (beobachtung == null) {
            val modell = (application as EinsatzSpurApp).modell
            beobachtung = bereich.launch {
                modell.team.collectLatest { team ->
                    if (team == null) {
                        beenden()
                        return@collectLatest
                    }
                    team.zustand.collect { zustand ->
                        if (zustand == TeamOrtung.Zustand.BEENDET) beenden()
                        else getSystemService(NotificationManager::class.java).notify(NUMMER, benachrichtigung(zustand))
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        bereich.cancel()
        super.onDestroy()
    }

    private fun beenden() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun benachrichtigung(zustand: TeamOrtung.Zustand?): Notification {
        val text = when (zustand) {
            TeamOrtung.Zustand.ORTET -> "Ortung läuft"
            TeamOrtung.Zustand.PAUSIERT -> "Pausiert – wartet auf neue Suche"
            else -> "Verbinde mit dem Server …"
        }
        val oeffnen = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, KANAL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("EinsatzSpur")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(oeffnen)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun kanalAnlegen() {
        val kanal = NotificationChannel(KANAL, "Ortung", NotificationManager.IMPORTANCE_LOW)
        kanal.description = "Zeigt, ob die Ortung für das Team läuft"
        getSystemService(NotificationManager::class.java).createNotificationChannel(kanal)
    }

    companion object {
        private const val KANAL = "ortung"
        private const val NUMMER = 1
    }
}
