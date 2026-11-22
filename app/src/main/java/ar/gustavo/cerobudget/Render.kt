package ar.gustavo.cerobudget

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
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

    fun final(ctx: Context, clips: Map<String, Clip>, ed: Edicion, musica: File?, listener: Transformer.Listener): Pair<Transformer, Composition> {
        val primero = clips[ed.segmentos.first().clip] ?: throw IllegalStateException("Falta el primer clip.")
        val (w, h) = Edicion.medida(ed.formato, primero.ancho, primero.alto)
        val layout = if (ed.formato == "original") Presentation.LAYOUT_SCALE_TO_FIT else Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP

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
            if (s.vol != 1f) audio += volumen(s.vol)
            video += Presentation.createForWidthAndHeight(w, h, layout)
            EditedMediaItem.Builder(mi).setEffects(Effects(audio, video)).build()
        }

        val secuencias = mutableListOf(EditedMediaItemSequence.Builder(items).build())
        if (musica != null && musica.exists()) {
            val m = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(musica)))
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(volumen(ed.volMusica)), emptyList()))
                .build()
            secuencias += EditedMediaItemSequence.Builder(m).setIsLooping(true).build()
        }
        val comp = Composition.Builder(secuencias)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .experimentalSetForceAudioTrack(true)
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
