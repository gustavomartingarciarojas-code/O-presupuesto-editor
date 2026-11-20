package ar.gustavo.cerobudget

import kotlin.math.min

/** Reducción de ruido con red neuronal (RNNoise). Trabaja a 48 kHz en cuadros de 480 muestras. */
object Rnn {
    init { System.loadLibrary("rnnoise_jni") }

    @JvmStatic external fun crear(): Long
    @JvmStatic external fun liberar(h: Long)
    @JvmStatic external fun procesar(h: Long, datos: FloatArray, cuadros: Int)

    /** Limpia x (48 kHz) en el lugar. RNNoise devuelve el audio 480 muestras tarde: se compensa. */
    fun limpiar(x: FloatArray, avance: (Double) -> Unit) {
        val n = x.size
        val cuadros = (n + 479) / 480 + 1
        val bloque = 100
        val buf = FloatArray(bloque * 480)
        val h = crear()
        try {
            var f = 0
            while (f < cuadros) {
                val cnt = min(bloque, cuadros - f)
                for (k in 0 until cnt * 480) { val i = f * 480 + k; buf[k] = if (i < n) x[i] * 32768f else 0f }
                procesar(h, buf, cnt)
                for (k in 0 until cnt * 480) { val o = f * 480 + k - 480; if (o in 0 until n) x[o] = buf[k] / 32768f }
                f += cnt
                if ((f / bloque) % 20 == 0) avance(f.toDouble() / cuadros)
            }
        } finally {
            liberar(h)
        }
    }
}
