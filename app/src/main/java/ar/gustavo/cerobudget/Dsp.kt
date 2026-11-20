package ar.gustavo.cerobudget

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Procesamiento de audio: filtros, reducción de ruido, compresor, volumen y limitador. */
object Dsp {

    // ---------- Filtros (biquad, fórmulas RBJ) ----------
    private fun norm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
        doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    fun hpf(f: Double, q: Double, fs: Double): DoubleArray {
        val w = 2 * PI * f / fs; val cs = cos(w); val al = sin(w) / (2 * q)
        return norm((1 + cs) / 2, -(1 + cs), (1 + cs) / 2, 1 + al, -2 * cs, 1 - al)
    }

    fun peak(f: Double, q: Double, g: Double, fs: Double): DoubleArray {
        val a = 10.0.pow(g / 40); val w = 2 * PI * f / fs; val cs = cos(w); val al = sin(w) / (2 * q)
        return norm(1 + al * a, -2 * cs, 1 - al * a, 1 + al / a, -2 * cs, 1 - al / a)
    }

    fun hshelf(f: Double, q: Double, g: Double, fs: Double): DoubleArray {
        val a = 10.0.pow(g / 40); val w = 2 * PI * f / fs; val cs = cos(w); val al = sin(w) / (2 * q)
        val s = 2 * sqrt(a) * al
        return norm(
            a * ((a + 1) + (a - 1) * cs + s), -2 * a * ((a - 1) + (a + 1) * cs), a * ((a + 1) + (a - 1) * cs - s),
            (a + 1) - (a - 1) * cs + s, 2 * ((a - 1) - (a + 1) * cs), (a + 1) - (a - 1) * cs - s
        )
    }

    fun biquad(x: FloatArray, c: DoubleArray) {
        val b0 = c[0]; val b1 = c[1]; val b2 = c[2]; val a1 = c[3]; val a2 = c[4]
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val xi = x[i].toDouble()
            val y = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi; y2 = y1; y1 = y
            x[i] = y.toFloat()
        }
    }

    fun gain(x: FloatArray, db: Double) {
        val g = 10.0.pow(db / 20).toFloat()
        for (i in x.indices) x[i] *= g
    }

    // ---------- Sonoridad integrada (LUFS, BS.1770, mono) ----------
    fun lufs(x: FloatArray, fs: Int): Double {
        val k1 = if (fs == 48000) doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585)
        else hshelf(1500.0, sqrt(0.5), 4.0, fs.toDouble())
        val k2 = if (fs == 48000) doubleArrayOf(1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621)
        else hpf(38.0, 0.5, fs.toDouble())
        val sub = (fs * 0.1).roundToInt()
        val subs = ArrayList<Double>()
        var s = 0.0; var cnt = 0
        var a0 = 0.0; var a1 = 0.0; var a2 = 0.0; var a3 = 0.0
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0
        for (i in x.indices) {
            val xi = x[i].toDouble()
            val y = k1[0] * xi + k1[1] * a0 + k1[2] * a1 - k1[3] * a2 - k1[4] * a3
            a1 = a0; a0 = xi; a3 = a2; a2 = y
            val z = k2[0] * y + k2[1] * b0 + k2[2] * b1 - k2[3] * b2 - k2[4] * b3
            b1 = b0; b0 = y; b3 = b2; b2 = z
            s += z * z
            if (++cnt == sub) { subs.add(s / sub); s = 0.0; cnt = 0 }
        }
        val blocks = ArrayList<Double>()
        for (j in 0..subs.size - 4) blocks.add((subs[j] + subs[j + 1] + subs[j + 2] + subs[j + 3]) / 4)
        fun l(z: Double) = -0.691 + 10 * log10(z)
        val g1 = blocks.filter { it > 0 && l(it) > -70 }
        if (g1.isEmpty()) return -70.0
        val rel = l(g1.average()) - 10
        val g2 = g1.filter { l(it) > rel }
        return if (g2.isEmpty()) l(g1.average()) else l(g2.average())
    }

    // ---------- FFT ----------
    private class Fft(val n: Int) {
        val rev = IntArray(n)
        val cs = DoubleArray(n / 2)
        val sn = DoubleArray(n / 2)
        init {
            val lg = Integer.numberOfTrailingZeros(n)
            for (i in 0 until n) {
                var r = 0
                for (b in 0 until lg) r = r or (((i shr b) and 1) shl (lg - 1 - b))
                rev[i] = r
            }
            for (i in 0 until n / 2) { cs[i] = cos(2 * PI * i / n); sn[i] = sin(2 * PI * i / n) }
        }
        fun run(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
            for (i in 0 until n) {
                val j = rev[i]
                if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            val sg = if (inverse) 1.0 else -1.0
            var size = 2
            while (size <= n) {
                val half = size shr 1; val step = n / size
                var i = 0
                while (i < n) {
                    var k = 0
                    for (j in 0 until half) {
                        val wr = cs[k]; val wi = sg * sn[k]; val a = i + j; val b = a + half
                        val tr = re[b] * wr - im[b] * wi; val ti = re[b] * wi + im[b] * wr
                        re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
                        k += step
                    }
                    i += size
                }
                size = size shl 1
            }
        }
    }

    // ---------- Intensidades ----------
    class Nivel(val over: Double, val piso: Double, val rev: Double, val puertaDb: Double, val rango: Double)

    fun nivel(clave: String) = when (clave) {
        "normal" -> Nivel(1.0, 0.25, 0.7, 32.0, 18.0)
        "fuerte" -> Nivel(1.5, 0.12, 1.1, 28.0, 30.0)
        else -> Nivel(2.0, 0.06, 1.6, 24.0, 45.0) // máxima
    }

    // ---------- Ruido residual + reverberación (eco de la sala) ----------
    // Interferencia = ruido de fondo + reverberación tardía estimada (método de Lebart):
    // la potencia de hace 50 ms, atenuada según un tiempo de reverberación de 0,6 s.
    fun dereverb(x: FloatArray, fs: Int, nv: Nivel, progress: (Double) -> Unit) {
        val n = 1024; val hop = 256; val h = n / 2 + 1
        val fft = Fft(n)
        val w = DoubleArray(n) { sqrt(0.5 - 0.5 * cos(2 * PI * it / n)) }
        val re = DoubleArray(n); val im = DoubleArray(n)
        val len = x.size

        // Perfil de ruido: el 10 % de los cuadros más silenciosos
        val noise = DoubleArray(h)
        val energies = ArrayList<DoubleArray>()
        var t0 = 0
        while (t0 + n <= len) {
            var e = 0.0
            for (i in 0 until n) { val v = x[t0 + i] * w[i]; e += v * v }
            if (e > 1e-12) energies.add(doubleArrayOf(e, t0.toDouble()))
            t0 += n
        }
        if (energies.size >= 4) {
            val sorted = energies.map { it[0] }.sorted()
            val thr = sorted[max(0, (sorted.size * 0.1).toInt() - 1)]
            val quiet = energies.filter { it[0] <= thr }
            val stride = max(1, quiet.size / 1500)
            var nq = 0; var q = 0
            while (q < quiet.size) {
                val t = quiet[q][1].toInt()
                for (i in 0 until n) { re[i] = x[t + i] * w[i]; im[i] = 0.0 }
                fft.run(re, im, false)
                for (b in 0 until h) noise[b] += re[b] * re[b] + im[b] * im[b]
                nq++; q += stride
            }
            for (b in 0 until h) noise[b] /= nq
        }
        for (b in 0 until h) noise[b] = max(noise[b], 1e-14) * nv.over

        val t60 = 0.6; val td = 0.05
        val nd = max(1, (td * fs / hop).roundToInt())
        val decay = exp(-2 * (3 * ln(10.0) / t60) * td) * nv.rev
        val hist = Array(nd + 1) { DoubleArray(h) }
        var hi = 0
        val sm = DoubleArray(h)
        val acc = DoubleArray(n); val g = DoubleArray(h); val prevS = DoubleArray(h)
        val beta = 0.97
        val total = (len + n) / hop + 1
        var frame = 0
        var t = -(n - hop)
        while (t < len) {
            for (i in 0 until n) { val j = t + i; re[i] = (if (j in 0 until len) x[j].toDouble() else 0.0) * w[i]; im[i] = 0.0 }
            fft.run(re, im, false)
            val old = hist[(hi + 1) % (nd + 1)]
            for (b in 0 until h) {
                val pw = re[b] * re[b] + im[b] * im[b]
                sm[b] = 0.6 * sm[b] + 0.4 * pw
                val inter = noise[b] + decay * old[b] + 1e-14
                val post = pw / inter
                val xi = beta * prevS[b] / inter + (1 - beta) * max(post - 1, 0.0)
                g[b] = max(xi / (1 + xi), nv.piso)
            }
            hi = (hi + 1) % (nd + 1)
            System.arraycopy(sm, 0, hist[hi], 0, h)
            for (b in 0 until h) {
                val gg = if (b > 1 && b < h - 2) (g[b - 2] + 2 * g[b - 1] + 3 * g[b] + 2 * g[b + 1] + g[b + 2]) / 9 else g[b]
                prevS[b] = gg * gg * (re[b] * re[b] + im[b] * im[b])
                re[b] *= gg; im[b] *= gg
                if (b > 0 && b < h - 1) { re[n - b] *= gg; im[n - b] *= gg }
            }
            fft.run(re, im, true)
            for (i in 0 until n) acc[i] += re[i] / n * w[i] * 0.5
            for (i in 0 until hop) { val j = t + i; if (j in 0 until len) x[j] = acc[i].toFloat() }
            System.arraycopy(acc, hop, acc, 0, n - hop)
            java.util.Arrays.fill(acc, n - hop, n, 0.0)
            if (++frame % 4000 == 0) progress(frame.toDouble() / total)
            t += hop
        }
    }

    // ---------- Puerta: baja lo que queda entre frases (colas de eco y ruido) ----------
    fun gate(x: FloatArray, fs: Int, nv: Nivel) {
        val n = x.size; val win = (0.01 * fs).roundToInt()
        val dbs = ArrayList<Double>(); var s = 0.0
        for (i in 0 until n) { s += x[i] * x[i]; if ((i + 1) % win == 0) { dbs.add(10 * log10(s / win + 1e-12)); s = 0.0 } }
        if (dbs.isEmpty()) return
        val sorted = dbs.sorted()
        val thr = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)] - nv.puertaDb
        val att = exp(-1 / (0.002 * fs)); val rel = exp(-1 / (0.12 * fs)); val hold = (0.08 * fs).roundToInt()
        val ea = exp(-1 / (0.005 * fs)); val er = exp(-1 / (0.03 * fs))
        var env = 0.0; var g = 1.0; var hcount = 0
        for (i in 0 until n) {
            val v = x[i].toDouble() * x[i]
            env = if (v > env) ea * env + (1 - ea) * v else er * env + (1 - er) * v
            val lv = 10 * log10(env + 1e-12)
            val target = if (lv >= thr) { hcount = hold; 1.0 }
                else if (hcount > 0) { hcount--; 1.0 }
                else 10.0.pow(-min(nv.rango, (thr - lv) * 3) / 20)
            g = if (target > g) att * g + (1 - att) * target else rel * g + (1 - rel) * target
            x[i] = (x[i] * g).toFloat()
        }
    }

    // ---------- Compresor ----------
    fun compress(x: FloatArray, fs: Int, thrDb: Double, ratio: Double) {
        val a = exp(-1 / (0.005 * fs)); val r = exp(-1 / (0.15 * fs)); var env = 0.0
        val k = 1 - 1 / ratio
        for (i in x.indices) {
            val d = abs(x[i].toDouble())
            env = if (d > env) a * env + (1 - a) * d else r * env + (1 - r) * d
            val over = 20 * log10(env + 1e-9) - thrDb
            if (over > 0) x[i] = (x[i] * 10.0.pow(-over * k / 20)).toFloat()
        }
    }

    // ---------- Limitador con anticipación de 5 ms ----------
    fun limit(x: FloatArray, fs: Int, ceilDb: Double) {
        val ceil = 10.0.pow(ceilDb / 20)
        val l = max(1, (fs * 0.005).roundToInt())
        val n = x.size
        val delay = FloatArray(l)
        val sz = l + 2
        val dq = IntArray(sz); val dv = DoubleArray(sz)
        var head = 0; var tail = 0; var g = 1.0
        val att = exp(-1.0 / (l / 4.0)); val rel = exp(-1 / (0.08 * fs))
        for (i in 0 until n + l) {
            val v = if (i < n) x[i] else 0f
            val av = abs(v.toDouble())
            while (tail > head && dv[(tail - 1) % sz] <= av) tail--
            dq[tail % sz] = i; dv[tail % sz] = av; tail++
            while (dq[head % sz] < i - l) head++
            val pk = dv[head % sz]
            val target = if (pk > ceil) ceil / pk else 1.0
            g = if (target < g) att * g + (1 - att) * target else rel * g + (1 - rel) * target
            val out = delay[i % l]; delay[i % l] = v
            val o = i - l
            if (o >= 0) {
                var y = out * g
                if (y > ceil) y = ceil else if (y < -ceil) y = -ceil
                x[o] = y.toFloat()
            }
        }
    }

    // ---------- Medición para mostrar ----------
    fun envelope(x: FloatArray, points: Int): DoubleArray {
        val out = DoubleArray(points); val step = x.size.toDouble() / points
        for (k in 0 until points) {
            val a = floor(k * step).toInt(); val b = max(a + 1, floor((k + 1) * step).toInt())
            var s = 0.0
            for (i in a until min(b, x.size)) s += x[i] * x[i]
            out[k] = sqrt(s / (b - a))
        }
        return out
    }

    /** Distancia entre la voz y el ruido de fondo, en dB. */
    fun spread(env: DoubleArray): Double {
        val db = env.map { 20 * log10(it + 1e-7) }.sorted()
        return db[(db.size * 0.95).toInt().coerceAtMost(db.size - 1)] - db[(db.size * 0.08).toInt()]
    }

    /** Cadena completa (x a 48 kHz). Devuelve el volumen final en LUFS. */
    fun process(x: FloatArray, fs: Int, clave: String, progress: (String, Double) -> Unit): Double {
        val f = fs.toDouble(); val nv = nivel(clave)
        biquad(x, hpf(80.0, 0.707, f)); biquad(x, hpf(80.0, 0.707, f))
        Rnn.limpiar(x) { progress("rnn", it) }
        dereverb(x, fs, nv) { progress("eco", it) }
        progress("eq", 0.0)
        biquad(x, peak(300.0, 1.0, -3.0, f))
        biquad(x, peak(3000.0, 0.9, 2.0, f))
        var l = lufs(x, fs); gain(x, min(30.0, -20 - l))
        gate(x, fs, nv)
        compress(x, fs, -22.0, 2.0)
        l = lufs(x, fs); gain(x, min(20.0, -14 - l))
        limit(x, fs, -1.5)
        return lufs(x, fs)
    }

}
