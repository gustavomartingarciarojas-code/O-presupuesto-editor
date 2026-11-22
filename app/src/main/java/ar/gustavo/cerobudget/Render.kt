package ar.gustavo.cerobudget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.math.roundToInt

/** Arma los trabajos de Media3 Transformer: el proxy liviano y el video final en 4K. */
object Render {

    private class VelocidadFija(private val v: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = v
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    /** Un texto encima del video, visible solo entre su inicio y su fin. */
    private class RotuloOverlay(private val bmp: Bitmap, private val t: Texto) : BitmapOverlay() {
        private val desde = t.ini * 1000
        private val hasta = t.fin * 1000
        private val ax = (t.x * 2f - 1f).coerceIn(-1f, 1f)
        private val ay = (1f - t.y * 2f).coerceIn(-1f, 1f)
        private val visible = OverlaySettings.Builder().setBackgroundFrameAnchor(ax, ay).setScale(t.ax, t.ay).build()
        private val oculto = OverlaySettings.Builder().setBackgroundFrameAnchor(ax, ay).setAlphaScale(0f).build()
        override fun getBitmap(presentationTimeUs: Long): Bitmap = bmp
        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            if (presentationTimeUs !in desde..hasta) return oculto
            if (t.kf.isEmpty()) return visible
            val k = t.pos(presentationTimeUs / 1000)
            return OverlaySettings.Builder()
                .setBackgroundFrameAnchor((k.x * 2f - 1f).coerceIn(-1f, 1f), (1f - k.y * 2f).coerceIn(-1f, 1f))
                .setScale(k.escX, k.escY)
                .build()
        }
    }

    /** Zoom y encuadre de cada tramo, con sus keyframes, sobre el video ya armado. */
    private class ZoomEncuadre(private val ed: Edicion) : MatrixTransformation {
        private val m = Matrix()
        override fun getMatrix(presentationTimeUs: Long): Matrix {
            m.reset()
            if (ed.segmentos.isEmpty()) return m
            val (i, dentro) = ed.ubicar(presentationTimeUs / 1000)
            val k = ed.segmentos[i].encuadre(dentro)
            val lim = Interp.limite(k.zoom)
            val px = k.px.coerceIn(-lim, lim); val py = k.py.coerceIn(-lim, lim)
            m.setTranslate(-2f * px, 2f * py)
            m.postScale(k.zoom, k.zoom)
            return m
        }
    }

    /** Volumen que cambia en el tiempo (keyframes). Cuenta las muestras desde el inicio del tramo. */
    private class Envolvente(private val ganancia: (Long) -> Float, private val desdeMs: Long = 0) : BaseAudioProcessor() {
        private var cuadros = 0L
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
                throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            return inputAudioFormat
        }
        override fun queueInput(inputBuffer: ByteBuffer) {
            val f = inputAudioFormat
            val bpf = f.bytesPerFrame
            val n = inputBuffer.remaining() / bpf
            val out = replaceOutputBuffer(n * bpf)
            val flotante = f.encoding == C.ENCODING_PCM_FLOAT
            var g = 1f
            for (i in 0 until n) {
                if ((cuadros + i) % 256 == 0L || i == 0) g = ganancia(desdeMs + (cuadros + i) * 1000 / f.sampleRate)
                for (c in 0 until f.channelCount) {
                    if (flotante) out.putFloat(inputBuffer.float * g)
                    else out.putShort((inputBuffer.short * g).toInt().coerceIn(-32768, 32767).toShort())
                }
            }
            cuadros += n
            out.flip()
        }
        override fun onFlush() { cuadros = 0 }
        override fun onReset() { cuadros = 0 }
    }

    private fun duracionMs(f: File): Long {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(f.path)
            var d = 0L
            for (i in 0 until ex.trackCount) {
                val fm = ex.getTrackFormat(i)
                if (fm.containsKey(MediaFormat.KEY_DURATION)) d = maxOf(d, fm.getLong(MediaFormat.KEY_DURATION) / 1000)
            }
            d
        } catch (_: Exception) { 0L } finally { ex.release() }
    }

    /** Máscara de un tramo, visible solo mientras dura ese tramo. Está dibujada a la mitad y se agranda al doble. */
    private class MascaraOverlay(private val bmp: Bitmap, desdeMs: Long, hastaMs: Long) : BitmapOverlay() {
        private val desde = desdeMs * 1000
        private val hasta = hastaMs * 1000
        private val visible = OverlaySettings.Builder().setScale(2f, 2f).build()
        private val oculto = OverlaySettings.Builder().setScale(2f, 2f).setAlphaScale(0f).build()
        override fun getBitmap(presentationTimeUs: Long): Bitmap = bmp
        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
            if (presentationTimeUs in desde until hasta) visible else oculto
    }

    /** Máscara con pista propia en la línea de tiempo. Dibujada a la mitad y agrandada al doble; si tiene keyframes, se rehace según el momento. */
    private class PistaMascara(private val m: Texto, private val w: Int, private val h: Int) : BitmapOverlay() {
        private val desde = m.ini * 1000
        private val hasta = m.fin * 1000
        private val visible = OverlaySettings.Builder().setScale(2f, 2f).build()
        private val oculto = OverlaySettings.Builder().setScale(2f, 2f).setAlphaScale(0f).build()
        private val fija: Bitmap? = if (m.kf.isEmpty()) Rotulo.mascaraEn(m, m.ini, w, h) else null
        private var clave = ""
        private var ultima: Bitmap? = null
        private val vacia = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            fija?.let { return it }
            val k = m.pos(presentationTimeUs / 1000)
            val c = "${(k.x * 500).toInt()}|${(k.y * 500).toInt()}|${(k.esc * 500).toInt()}"
            if (c != clave || ultima == null) { ultima = Rotulo.mascaraEn(m, presentationTimeUs / 1000, w, h); clave = c }
            return ultima ?: vacia
        }
        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
            if (presentationTimeUs in desde until hasta) visible else oculto
    }

    /** Fundidos a negro o a blanco en los cortes que tienen transición. */
    private class Fundidos(private val ed: Edicion) : RgbMatrix {
        private val m = FloatArray(16)
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            val (f, color) = ed.fundido(presentationTimeUs / 1000)
            java.util.Arrays.fill(m, 0f)
            m[0] = f; m[5] = f; m[10] = f; m[15] = 1f
            if (color == "blanco") { val o = 1f - f; m[12] = o; m[13] = o; m[14] = o }
            return m
        }
    }

    private fun volumen(v: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(v))
        putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(v))
    }

    // ------------------------------------------------------------------ proxy

    /** Medida del proxy: el lado corto a 720, manteniendo la proporción (números pares). */
    private fun medidaProxy(w: Int, h: Int): Pair<Int, Int> {
        val corto = minOf(w, h)
        if (corto <= 720) return (w / 2 * 2) to (h / 2 * 2)
        val k = 720.0 / corto
        return (((w * k) / 2).roundToInt() * 2) to (((h * k) / 2).roundToInt() * 2)
    }

    fun proxy(ctx: Context, clip: Clip, listener: Transformer.Listener): Pair<Transformer, Composition> {
        val (w, h) = medidaProxy(clip.ancho, clip.alto)
        val efectos = mutableListOf<Effect>(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))
        if (clip.fps == 0f || clip.fps > 31f) efectos.add(FrameDropEffect.createDefaultFrameDropEffect(30f))
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(clip.uri))).setEffects(Effects(emptyList(), efectos)).build()
        val comp = Composition.Builder(EditedMediaItemSequence.Builder(item).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        val codificador = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(2_500_000).setiFrameIntervalSeconds(1f).build())
            .setEnableFallback(true)
            .build()
        val t = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(codificador)
            .addListener(listener)
            .build()
        return t to comp
    }

    // ------------------------------------------------------------------ video final

    fun final(ctx: Context, clips: Map<String, Clip>, ed: Edicion, musica: File?, imagenes: (String) -> File?, listener: Transformer.Listener): Pair<Transformer, Composition> {
        val primero = clips[ed.segmentos.first().clip] ?: throw IllegalStateException("Falta el primer clip.")
        val (w, h) = Edicion.medida(ed.formato, primero.ancho, primero.alto)
        // Siempre entero, igual que en la vista previa: si el formato no coincide, quedan franjas negras (el zoom las tapa)
        val layout = Presentation.LAYOUT_SCALE_TO_FIT

        val items = ed.segmentos.map { s ->
            val clip = clips[s.clip] ?: throw IllegalStateException("Hay un tramo de un video que ya no está en el celular.")
            val mi = MediaItem.Builder()
                .setUri(Uri.parse(clip.uri))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.ini).setEndPositionMs(s.fin).build()
                )
                .build()
            val audio = mutableListOf<AudioProcessor>()
            val video = mutableListOf<Effect>()
            if (s.vel != 1f) {
                val par = Effects.createExperimentalSpeedChangingEffect(VelocidadFija(s.vel))
                audio += par.first
                video += par.second
            }
            if (s.kf.isNotEmpty()) audio += Envolvente({ ms -> s.encuadre(ms).vol })
            else if (s.vol != 1f) audio += volumen(s.vol)
            video += Presentation.createForWidthAndHeight(w, h, layout)
            EditedMediaItem.Builder(mi).setEffects(Effects(audio, video)).build()
        }

        val secuencias = mutableListOf(EditedMediaItemSequence.Builder(items).build())
        if (musica != null && musica.exists()) {
            val durMus = if (ed.kfMusica.isNotEmpty()) duracionMs(musica) else 0L
            if (ed.kfMusica.isNotEmpty() && durMus > 500) {
                // Con keyframes: la música se repite a mano, cada vuelta sabe en qué momento de la línea de tiempo empieza
                val total = ed.total
                val vueltas = mutableListOf<EditedMediaItem>()
                var pos = 0L
                while (pos < total && vueltas.size < 300) {
                    val largo = minOf(durMus, total - pos)
                    val desde = pos
                    val mi = MediaItem.Builder().setUri(Uri.fromFile(musica))
                        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(largo).build())
                        .build()
                    vueltas += EditedMediaItem.Builder(mi).setRemoveVideo(true)
                        .setEffects(Effects(listOf(Envolvente({ ms -> ed.volMusicaEn(ms) }, desde)), emptyList()))
                        .build()
                    pos += largo
                }
                if (vueltas.isNotEmpty()) secuencias += EditedMediaItemSequence.Builder(vueltas).build()
            } else {
                val m = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(musica)))
                    .setRemoveVideo(true)
                    .setEffects(Effects(listOf(volumen(ed.volMusica)), emptyList()))
                    .build()
                secuencias += EditedMediaItemSequence.Builder(m).setIsLooping(true).build()
            }
        }
        // Efectos sobre todo el video ya armado: textos y fundidos (con los tiempos de la línea de tiempo)
        val generales = mutableListOf<Effect>()
        if (ed.segmentos.any { it.animado }) generales += ZoomEncuadre(ed)
        val capas = mutableListOf<TextureOverlay>()
        // Primero las máscaras de los tramos (debajo), después textos y stickers (encima)
        val mascaras = mutableMapOf<String, Bitmap>()
        var acc = 0L
        for (s in ed.segmentos) {
            if (s.mascara.isNotEmpty()) {
                val clave = "${s.mascara}|${s.mTam}|${s.mSuave}|${s.mX}|${s.mY}|${s.mAng}"
                val bmp = mascaras[clave] ?: Rotulo.mascara(s.mascara, s.mTam, s.mSuave, w / 2, h / 2, s.mX, s.mY, s.mAng)?.also { mascaras[clave] = it }
                if (bmp != null) capas += MascaraOverlay(bmp, acc, acc + s.dura)
            }
            acc += s.dura
        }
        // Máscaras en pista propia (se mantienen sobre todos los tramos que cubren)
        ed.textos.filter { it.esMascara }.forEach { m -> capas += PistaMascara(m, w / 2, h / 2) }
        ed.textos.filter { !it.esMascara }.forEach { t -> Rotulo.bitmap(ctx, t, w, h, imagenes)?.let { capas += RotuloOverlay(it, t) } }
        if (capas.isNotEmpty()) generales += OverlayEffect(capas)
        if (ed.segmentos.any { it.trans != "corte" }) generales += Fundidos(ed)

        val comp = Composition.Builder(secuencias)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .experimentalSetForceAudioTrack(true)
            .setEffects(Effects(emptyList(), generales))
            .build()

        val bitrate = if (primero.fps > 31f) 45_000_000 else 30_000_000
        val codificador = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .setEnableFallback(true)
            .build()
        val t = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(codificador)
            .addListener(listener)
            .build()
        return t to comp
    }
}
