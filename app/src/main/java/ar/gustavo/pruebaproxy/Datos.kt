package ar.gustavo.pruebaproxy

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

/** Datos del equipo y del video, para mostrar y para el informe. */
object Datos {

    val AR: Locale = Locale("es", "AR")

    class Video(
        val nombre: String,
        val bytes: Long,
        val ancho: Int,          // ya girado como se ve en pantalla
        val alto: Int,
        val fps: Float,
        val mime: String,
        val duracionMs: Long,
        val hdr: Boolean
    ) {
        fun codec(): String = when {
            mime.contains("hevc", true) -> "H.265 (HEVC)"
            mime.contains("avc", true) -> "H.264"
            mime.contains("av01", true) -> "AV1"
            mime.contains("vp9", true) -> "VP9"
            else -> mime
        }

        fun resumen(): String {
            val fpsTxt = if (fps > 0) String.format(AR, "%.0f fps", fps) else "fps ?"
            return "$nombre\n${ancho}×${alto} · $fpsTxt · ${codec()}${if (hdr) " · HDR" else ""}\n" +
                "${duracion(duracionMs)} · ${mb(bytes)}"
        }
    }

    fun mb(bytes: Long): String =
        if (bytes >= 1_073_741_824L) String.format(AR, "%.2f GB", bytes / 1_073_741_824.0)
        else String.format(AR, "%,.0f MB", bytes / 1_048_576.0)

    fun duracion(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(AR, "%d h %02d min %02d s", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(AR, "%d min %02d s", s / 60, s % 60)
    }

    fun reloj(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(AR, "%02d:%02d", s / 60, s % 60)
    }

    fun leerVideo(ctx: Context, uri: Uri): Video {
        var nombre = "video"
        var bytes = 0L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val iN = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val iS = c.getColumnIndex(OpenableColumns.SIZE)
                    if (iN >= 0) nombre = c.getString(iN) ?: "video"
                    if (iS >= 0 && !c.isNull(iS)) bytes = c.getLong(iS)
                }
            }
        } catch (_: Exception) { }
        if (bytes <= 0 && uri.scheme == "file") bytes = java.io.File(uri.path ?: "").length()

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

    fun ramTotalGb(ctx: Context): String {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return String.format(AR, "%.1f GB", mi.totalMem / 1_073_741_824.0)
    }

    fun procesador(): String =
        if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else Build.HARDWARE

    /** ¿Este equipo decodifica ese video? Busca decodificadores por hardware y por software. */
    fun decodifica(mime: String, w: Int, h: Int, fps: Double): String {
        var hw = false
        var sw = false
        for (ci in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (ci.isEncoder) continue
            if (ci.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val caps = try { ci.getCapabilitiesForType(mime).videoCapabilities } catch (_: Exception) { null } ?: continue
            val ok = try {
                caps.areSizeAndRateSupported(w, h, fps) || caps.areSizeAndRateSupported(h, w, fps)
            } catch (_: Exception) { false }
            if (ok) { if (ci.isHardwareAccelerated) hw = true else sw = true }
        }
        return when {
            hw -> "sí, por hardware"
            sw -> "solo por software (lento)"
            else -> "NO"
        }
    }

    fun equipo(ctx: Context): String {
        val b = StringBuilder()
        b.append("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n")
        b.append("Procesador: ${procesador()} · RAM: ${ramTotalGb(ctx)}\n")
        b.append("Decodifica 4K 60 fps H.265: ${decodifica(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160, 60.0)}\n")
        b.append("Decodifica 4K 60 fps H.264: ${decodifica(MediaFormat.MIMETYPE_VIDEO_AVC, 3840, 2160, 60.0)}\n")
        b.append("Decodifica 4K 30 fps H.265: ${decodifica(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160, 30.0)}\n")
        b.append("Decodifica 720p 30 fps H.264 (proxy): ${decodifica(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720, 30.0)}")
        return b.toString()
    }

    /** Direcciones IPv4 del equipo en la red local (Wi-Fi o zona Wi-Fi). */
    fun ips(): List<String> {
        val res = mutableListOf<Pair<String, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress) res += ni.name to (a.hostAddress ?: continue)
                }
            }
        } catch (_: Exception) { }
        // Primero el Wi-Fi (wlan), después la zona Wi-Fi (ap / swlan), al final el resto
        return res.sortedBy { (n, _) ->
            when {
                n.startsWith("wlan") -> 0
                n.startsWith("ap") || n.startsWith("swlan") -> 1
                n.startsWith("rmnet") || n.startsWith("ccmni") -> 9
                else -> 5
            }
        }.filter { !it.first.startsWith("rmnet") && !it.first.startsWith("ccmni") && !it.first.startsWith("dummy") }
            .map { it.second }
            .distinct()
    }
}
