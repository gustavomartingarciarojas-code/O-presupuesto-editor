package ar.gustavo.cerobudget

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Calendar
import java.util.Locale

/** Utilidades: datos de un video, formatos de tiempo y tamaño, direcciones de red. */
object Datos {

    val AR: Locale = Locale("es", "AR")
    private val MESES = arrayOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

    class Video(
        val nombre: String,
        val bytes: Long,
        val ancho: Int,          // ya girado como se ve en pantalla
        val alto: Int,
        val fps: Float,
        val mime: String,
        val duracionMs: Long,
        val hdr: Boolean
    )

    fun mb(bytes: Long): String =
        if (bytes >= 1_073_741_824L) String.format(AR, "%.2f GB", bytes / 1_073_741_824.0)
        else String.format(AR, "%,.0f MB", bytes / 1_048_576.0)

    fun duracion(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(AR, "%d h %02d min %02d s", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(AR, "%d min %02d s", s / 60, s % 60)
    }

    /** 01:23,4 */
    fun reloj(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val s = t / 1000
        return String.format(AR, "%02d:%02d,%d", s / 60, s % 60, (t % 1000) / 100)
    }

    /** Fecha con el formato de Gustavo: 22-noviembre-2026 */
    fun fecha(): String {
        val c = Calendar.getInstance()
        val dia = c.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        return "$dia-${MESES[c.get(Calendar.MONTH)]}-${c.get(Calendar.YEAR)}"
    }

    fun hora(): String {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0') + "-" + c.get(Calendar.MINUTE).toString().padStart(2, '0')
    }

    fun nombreDe(ctx: Context, uri: Uri): Pair<String, Long> {
        var nombre = "archivo"
        var bytes = 0L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val iN = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val iS = c.getColumnIndex(OpenableColumns.SIZE)
                    if (iN >= 0) nombre = c.getString(iN) ?: "archivo"
                    if (iS >= 0 && !c.isNull(iS)) bytes = c.getLong(iS)
                }
            }
        } catch (_: Exception) { }
        if (bytes <= 0 && uri.scheme == "file") bytes = java.io.File(uri.path ?: "").length()
        return nombre to bytes
    }

    fun leerVideo(ctx: Context, uri: Uri): Video {
        val (nombre, bytes) = nombreDe(ctx, uri)
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                var w = f.getInteger(MediaFormat.KEY_WIDTH)
                var h = f.getInteger(MediaFormat.KEY_HEIGHT)
                val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                val fps = leerFps(f)
                val dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
                val hdr = f.containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
                    f.getInteger(MediaFormat.KEY_COLOR_TRANSFER).let { it == MediaFormat.COLOR_TRANSFER_ST2084 || it == MediaFormat.COLOR_TRANSFER_HLG }
                return Video(nombre, bytes, w, h, fps, mime, dur, hdr)
            }
        } finally {
            ex.release()
        }
        throw IllegalStateException("El archivo no tiene pista de video.")
    }

    private fun leerFps(f: MediaFormat): Float {
        if (!f.containsKey(MediaFormat.KEY_FRAME_RATE)) return 0f
        return try { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() } catch (_: Exception) {
            try { f.getFloat(MediaFormat.KEY_FRAME_RATE) } catch (_: Exception) { 0f }
        }
    }

    /** Direcciones IPv4 del equipo en la red local (Wi-Fi o zona Wi-Fi). */
    fun ips(): List<String> {
        val res = mutableListOf<Pair<String, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ad in ni.inetAddresses) {
                    if (ad is Inet4Address && !ad.isLoopbackAddress) {
                        val h = ad.hostAddress ?: continue
                        res += ni.name to h
                    }
                }
            }
        } catch (_: Exception) { }
        return res.sortedBy { (n, _) ->
            when {
                n.startsWith("wlan") -> 0
                n.startsWith("ap") || n.startsWith("swlan") -> 1
                else -> 5
            }
        }.filter { !it.first.startsWith("rmnet") && !it.first.startsWith("ccmni") && !it.first.startsWith("dummy") }
            .map { it.second }
            .distinct()
    }
}
