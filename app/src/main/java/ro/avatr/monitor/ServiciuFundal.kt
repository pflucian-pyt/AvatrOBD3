package ro.avatr.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Serviciu de prim-plan.
 *
 * Rostul lui: fara notificare permanenta, Android opreste procesul la prima
 * strangere de memorie, iar legatura cu adaptorul se rupe in mijlocul drumului.
 * Cu el, procesul are prioritate de aplicatie vizibila.
 *
 * ATENTIE, limita reala: serviciul tine procesul in viata, dar NU porneste
 * ceasurile din pagina. Cand WebView-ul nu e vizibil, Android incetineste
 * temporizatoarele din JavaScript, deci interogarea se rareste. De asta ecranul
 * e tinut aprins. Inregistrarea cu ecranul stins cere mutarea buclei de
 * interogare din HTML in Kotlin — pasul urmator, nu acesta.
 */
class ServiciuFundal : Service() {

    companion object {
        private const val CANAL = "avatr_monitor"
        private const val ID_NOTIF = 42
    }

    private var trezire: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        creeazaCanal()
        porneste()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        trezire = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AvatrMonitor:citire").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)   // cel mai lung drum plauzibil
        }
    }

    private fun creeazaCanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val c = NotificationChannel(CANAL, "Citire din masina", NotificationManager.IMPORTANCE_LOW)
        c.setShowBadge(false)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(c)
    }

    private fun porneste() {
        val deschide = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CANAL)
            .setContentTitle("Avatr Monitor")
            .setContentText("Citesc date din masina")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(deschide)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ID_NOTIF, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(ID_NOTIF, n)
        }
    }

    override fun onStartCommand(i: Intent?, steaguri: Int, id: Int): Int = START_STICKY

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        trezire?.let { if (it.isHeld) it.release() }
        trezire = null
        super.onDestroy()
    }
}
