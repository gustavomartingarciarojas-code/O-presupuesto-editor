package ar.gustavo.pruebaproxy

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Busca la versión "prueba-proxy" en GitHub y la instala encima de la actual. */
object Actualizador {
    const val ACCION = "ar.gustavo.pruebaproxy.INSTALAR"
    private const val API = "https://api.github.com/repos/gustavomartingarciarojas-code/O-presupuesto-editor/releases/tags/prueba-proxy"

    class Info(val version: Int, val url: String)

    fun consultar(): Info? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        try {
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(txt)
            val v = Regex("versionCode=(\\d+)").find(j.optString("body", ""))?.groupValues?.get(1)?.toInt() ?: return null
            val assets = j.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) return Info(v, a.getString("browser_download_url"))
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    fun versionInstalada(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    /** Baja la APK directo a una sesión de instalación de Android y pide confirmar. */
    fun instalar(ctx: Context, url: String, avance: (Double) -> Unit) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        val sesion = pi.openSession(id)
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            val total = c.contentLengthLong
            sesion.openWrite("app.apk", 0, if (total > 0) total else -1).use { out ->
                c.inputStream.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var leidos = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        leidos += n
                        if (total > 0) avance(leidos.toDouble() / total)
                    }
                }
                sesion.fsync(out)
            }
            c.disconnect()
            val intent = Intent(ctx, MainActivity::class.java).setAction(ACCION).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val p = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            sesion.commit(p.intentSender)
        } catch (e: Exception) {
            sesion.abandon()
            throw e
        } finally {
            sesion.close()
        }
    }
}
