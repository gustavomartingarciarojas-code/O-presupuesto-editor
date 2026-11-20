package ar.gustavo.cerobudget

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Saca el audio del video, lo mejora y arma el mismo video con el audio nuevo (la imagen se copia sin tocar). */
class Procesador(private val ctx: Context) {

    class Resultado(val uri: Uri, val nombre: String, val lufs: Double, val antes: Double, val despues: Double)

    private class Mono(val x: FloatArray, val rate: Int, val firstPtsUs: Long)

    private class Codificado(
        val formato: MediaFormat,
        val datos: List<ByteArray>,
        val pts: LongArray
    )

    private val MESES = arrayOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

    fun nombreSalida(original: String): String {
        val base = original.substringBeforeLast('.').trim().replace(Regex("\\s+"), "-").ifEmpty { "video" }
        val c = Calendar.getInstance()
        val dia = c.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        return "$base-audio-mejorado-$dia-${MESES[c.get(Calendar.MONTH)]}-${c.get(Calendar.YEAR)}.mp4"
    }

    fun procesar(src: Uri, nombreOriginal: String, intensidad: String, avance: (Double, String) -> Unit): Resultado {
        // 1) Pistas
        val ex0 = MediaExtractor()
        ex0.setDataSource(ctx, src, null)
        var aIdx = -1; var vIdx = -1
        var aFmt: MediaFormat? = null; var vFmt: MediaFormat? = null
        for (i in 0 until ex0.trackCount) {
            val f = ex0.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (aIdx < 0 && m.startsWith("audio/")) { aIdx = i; aFmt = f }
            if (vIdx < 0 && m.startsWith("video/")) { vIdx = i; vFmt = f }
        }
        ex0.release()
        if (aFmt == null) throw IllegalStateException("El video no tiene audio.")
        if (vFmt == null) throw IllegalStateException("El archivo no tiene imagen de video.")
        val durUs = when {
            vFmt.containsKey(MediaFormat.KEY_DURATION) -> vFmt.getLong(MediaFormat.KEY_DURATION)
            aFmt.containsKey(MediaFormat.KEY_DURATION) -> aFmt.getLong(MediaFormat.KEY_DURATION)
            else -> 0L
        }

        // 2) Leer el audio
        avance(0.0, "Leyendo el audio…")
        val mono = decodificar(src, aIdx, aFmt, durUs) { p -> avance(p * 0.30, "Leyendo el audio… ${(p * 100).toInt()} %") }
        // Todo se procesa a 48 kHz (lo necesita la limpieza con red neuronal)
        val x = if (mono.rate == 48000) mono.x else remuestrear(mono.x, mono.rate, 48000)
        val antes = Dsp.spread(Dsp.envelope(x, 900))

        // 3) Mejorar
        val lufs = Dsp.process(x, 48000, intensidad) { etapa, p ->
            when (etapa) {
                "rnn" -> avance(0.30 + p * 0.20, "Sacando el ruido… ${(p * 100).toInt()} %")
                "eco" -> avance(0.50 + p * 0.25, "Sacando el eco de la sala… ${(p * 100).toInt()} %")
                else -> avance(0.76, "Ecualizando y ajustando el volumen…")
            }
        }
        val despues = Dsp.spread(Dsp.envelope(x, 900))

        // 4) Codificar en AAC
        val cod = codificar(x, 48000) { p -> avance(0.78 + p * 0.10, "Codificando el audio nuevo… ${(p * 100).toInt()} %") }

        // 5) Armar el video nuevo
        val nombre = nombreSalida(nombreOriginal)
        val uri = armar(src, vIdx, vFmt, cod, mono.firstPtsUs, durUs, nombre) { p ->
            avance(0.88 + p * 0.12, "Armando el video… ${(p * 100).toInt()} %")
        }
        return Resultado(uri, nombre, lufs, antes, despues)
    }

    private fun decodificar(src: Uri, idx: Int, fmt: MediaFormat, durUs: Long, avance: (Double) -> Unit): Mono {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, src, null)
        ex.selectTrack(idx)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val dec = MediaCodec.createDecoderByType(mime)
        dec.configure(fmt, null, null, 0)
        dec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var esFloat = false
        var buf = FloatArray(max(rate, ((durUs / 1e6 + 2) * rate).toInt()))
        var len = 0
        var firstPts = -1L
        val info = MediaCodec.BufferInfo()
        var inFin = false; var outFin = false
        var ultimo = 0L
        try {
            while (!outFin) {
                if (!inFin) {
                    val ii = dec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = dec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inFin = true
                        } else {
                            dec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = dec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    esFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    if (info.size > 0) {
                        val ob = dec.getOutputBuffer(oi)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val bb = ob.slice().order(ByteOrder.nativeOrder())
                        if (firstPts < 0) firstPts = info.presentationTimeUs
                        val frames = if (esFloat) info.size / 4 / ch else info.size / 2 / ch
                        if (len + frames > buf.size) buf = buf.copyOf(((len + frames) * 1.25).toInt())
                        if (esFloat) {
                            val fb = bb.asFloatBuffer()
                            for (f in 0 until frames) { var s = 0f; for (c in 0 until ch) s += fb.get(); buf[len + f] = s / ch }
                        } else {
                            val sb = bb.asShortBuffer()
                            for (f in 0 until frames) { var s = 0f; for (c in 0 until ch) s += sb.get() / 32768f; buf[len + f] = s / ch }
                        }
                        len += frames
                        if (durUs > 0 && info.presentationTimeUs - ultimo > 2_000_000) {
                            ultimo = info.presentationTimeUs; avance(min(1.0, info.presentationTimeUs.toDouble() / durUs))
                        }
                    }
                    dec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outFin = true
                }
            }
        } finally {
            dec.stop(); dec.release(); ex.release()
        }
        if (len == 0) throw IllegalStateException("No se pudo leer el audio del video.")
        return Mono(buf.copyOf(len), rate, max(0L, firstPts))
    }

    private fun remuestrear(x: FloatArray, desde: Int, hacia: Int): FloatArray {
        val n = (x.size.toLong() * hacia / desde).toInt()
        val y = FloatArray(n); val r = desde.toDouble() / hacia
        fun at(i: Int) = if (i in x.indices) x[i] else 0f
        for (i in 0 until n) {
            val pos = i * r; val k = floor(pos).toInt(); val f = (pos - k).toFloat()
            val p0 = at(k - 1); val p1 = at(k); val p2 = at(k + 1); val p3 = at(k + 2)
            y[i] = p1 + 0.5f * f * (p2 - p0 + f * (2 * p0 - 5 * p1 + 4 * p2 - p3 + f * (3 * (p1 - p2) + p3 - p0)))
        }
        return y
    }

    private fun codificar(xIn: FloatArray, rate: Int, avance: (Double) -> Unit): Codificado {
        val outRate = if (rate == 44100 || rate == 48000) rate else 48000
        val x = if (outRate != rate) remuestrear(xIn, rate, outRate) else xIn
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outRate, 2)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val datos = ArrayList<ByteArray>(); val pts = ArrayList<Long>()
        var formato: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var pos = 0; var inFin = false; var outFin = false; var paso = 0
        try {
            while (!outFin) {
                if (!inFin) {
                    val ii = enc.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = enc.getInputBuffer(ii)!!
                        ib.clear(); ib.order(ByteOrder.LITTLE_ENDIAN)
                        val n = min(min(ib.remaining() / 4, 4096), x.size - pos)
                        if (n <= 0) {
                            enc.queueInputBuffer(ii, 0, 0, pos * 1_000_000L / outRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inFin = true
                        } else {
                            for (i in 0 until n) {
                                val v = (x[pos + i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
                                ib.putShort(v); ib.putShort(v)
                            }
                            enc.queueInputBuffer(ii, 0, n * 4, pos * 1_000_000L / outRate, 0)
                            pos += n
                            if (++paso % 200 == 0) avance(pos.toDouble() / x.size)
                        }
                    }
                }
                val oi = enc.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    formato = enc.outputFormat
                } else if (oi >= 0) {
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0) {
                        val ob = enc.getOutputBuffer(oi)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val b = ByteArray(info.size); ob.get(b)
                        datos.add(b); pts.add(info.presentationTimeUs)
                    }
                    enc.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outFin = true
                }
            }
        } finally {
            enc.stop(); enc.release()
        }
        if (formato == null || datos.isEmpty()) throw IllegalStateException("No se pudo codificar el audio.")
        return Codificado(formato, datos, pts.toLongArray())
    }

    private fun armar(
        src: Uri, vIdx: Int, vFmt: MediaFormat, cod: Codificado, desfaseUs: Long, durUs: Long,
        nombre: String, avance: (Double) -> Unit
    ): Uri {
        val cr = ctx.contentResolver
        val valores = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, nombre)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/0-Presupuesto-Editor")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val destino = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), valores)
            ?: throw IllegalStateException("No se pudo crear el archivo de salida.")
        var ok = false
        try {
            cr.openFileDescriptor(destino, "rw").use { pfd ->
                if (pfd == null) throw IllegalStateException("No se pudo abrir el archivo de salida.")
                val mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                if (vFmt.containsKey(MediaFormat.KEY_ROTATION)) mux.setOrientationHint(vFmt.getInteger(MediaFormat.KEY_ROTATION))
                val vt = mux.addTrack(vFmt)
                val at = mux.addTrack(cod.formato)
                mux.start()
                val ex = MediaExtractor()
                ex.setDataSource(ctx, src, null)
                ex.selectTrack(vIdx)
                val maxIn = if (vFmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) vFmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                val vb = ByteBuffer.allocateDirect(max(maxIn, 16 * 1024 * 1024))
                var maxA = 0; for (d in cod.datos) if (d.size > maxA) maxA = d.size
                val ab = ByteBuffer.allocateDirect(max(maxA, 1024))
                val bi = MediaCodec.BufferInfo()
                var ai = 0
                val na = cod.datos.size
                fun escribirAudio(i: Int) {
                    ab.clear(); ab.put(cod.datos[i]); ab.flip()
                    bi.set(0, cod.datos[i].size, cod.pts[i] + desfaseUs, 0)
                    mux.writeSampleData(at, ab, bi)
                }
                var ultimo = 0L
                try {
                    while (true) {
                        vb.clear()
                        val n = ex.readSampleData(vb, 0)
                        if (n < 0) break
                        val t = ex.sampleTime
                        while (ai < na && cod.pts[ai] + desfaseUs <= t) escribirAudio(ai++)
                        val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        bi.set(0, n, t, flags)
                        mux.writeSampleData(vt, vb, bi)
                        ex.advance()
                        if (durUs > 0 && t - ultimo > 2_000_000) { ultimo = t; avance(min(1.0, t.toDouble() / durUs)) }
                    }
                    while (ai < na) escribirAudio(ai++)
                    mux.stop()
                } finally {
                    mux.release(); ex.release()
                }
            }
            valores.clear()
            valores.put(MediaStore.Video.Media.IS_PENDING, 0)
            cr.update(destino, valores, null, null)
            ok = true
            return destino
        } finally {
            if (!ok) try { cr.delete(destino, null, null) } catch (e: Exception) {}
        }
    }
}
