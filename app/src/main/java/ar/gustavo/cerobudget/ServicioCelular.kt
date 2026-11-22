package ar.gustavo.cerobudget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Servicio en primer plano del CELULAR: mantiene vivo a MotorCelular con una notificación fija.
 * Así el proxy, la conexión con la tablet y la exportación siguen aunque se use otra app o se apague la pantalla.
 */
class ServicioCelular : Service() {

    companion object {
        private const val CANAL = "trabajo"
        private const val ID = 7
        const val APAGAR = "ar.gustavo.cerobudget.APAGAR"

        fun arrancar(ctx: Context) {
            val i = Intent(ctx, ServicioCelular::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun apagar(ctx: Context) {
            ctx.stopService(Intent(ctx, ServicioCelular::class.java))
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val alCambiar: () -> Unit = { actualizarNotificacion() }
    private var ultimoTexto = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanal()
        val n = notificacion("Preparando…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
        // El procesador y el Wi-Fi siguen despiertos aunque se apague la pantalla
        try {
            wake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cerobudget:trabajo")?.apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { }
        try {
            @Suppress("DEPRECATION")
            wifi = getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cerobudget:wifi")?.apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { }
        MotorCelular.iniciar(this)
        MotorCelular.escuchar(alCambiar)
        actualizarNotificacion()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == APAGAR) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        MotorCelular.dejarDeEscuchar(alCambiar)
        MotorCelular.detener()
        try { wake?.release() } catch (_: Exception) { }
        try { wifi?.release() } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            val c = NotificationChannel(CANAL, "Trabajo del celular", NotificationManager.IMPORTANCE_LOW)
            c.setShowBadge(false)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(c)
        }
    }

    private fun notificacion(texto: String): Notification {
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, CelularActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val apagar = PendingIntent.getService(this, 1, Intent(this, ServicioCelular::class.java).setAction(APAGAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CANAL)
        return b.setContentTitle("0 Presupuesto Editor · celular")
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(null, "Apagar", apagar).build())
            .build()
    }

    private fun actualizarNotificacion() {
        val t = MotorCelular.resumen()
        if (t == ultimoTexto) return
        ultimoTexto = t
        try { getSystemService(NotificationManager::class.java)?.notify(ID, notificacion(t)) } catch (_: Exception) { }
    }
}
