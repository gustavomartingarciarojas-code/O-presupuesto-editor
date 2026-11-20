package ar.gustavo.cerobudget

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
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

    // ---------- Reducción de ruido espectral ----------
    fun denoise(x: FloatArray, fs: Int, strength: String, progress: (Double) -> Unit) {
        val (over, floorG) = when (strength) {
            "suave" -> 1.0 to 0.32
            "fuerte" -> 2.2 to 0.08
            else -> 1.5 to 0.16
        }
        val n = if (fs >= 32000) 1024 else 512
        val hop = n / 4; val h = n / 2 + 1
        val fft = Fft(n)
        val w = DoubleArray(n) { sqrt(0.5 - 0.5 * cos(2 * PI * it / n)) }
        val re = DoubleArray(n); val im = DoubleArray(n)
        val len = x.size

        // 1) Perfil de ruido: el 10 % de los cuadros más silenciosos
        val energies = ArrayList<DoubleArray>()
        var t0 = 0
        while (t0 + n <= len) {
            var e = 0.0
            for (i in 0 until n) { val v = x[t0 + i] * w[i]; e += v * v }
            if (e > 1e-9) energies.add(doubleArrayOf(e, t0.toDouble()))
            t0 += n
        }
        if (energies.size < 4) return
        val sorted = energies.map { it[0] }.sorted()
        val thr = sorted[max(0, (sorted.size * 0.1).toInt() - 1)]
        val quiet = energies.filter { it[0] <= thr }
        val stride = max(1, quiet.size / 1500)
        val noise = DoubleArray(h); var nq = 0
        var q = 0
        while (q < quiet.size) {
            val t = quiet[q][1].toInt()
            for (i in 0 until n) { re[i] = x[t + i] * w[i]; im[i] = 0.0 }
            fft.run(re, im, false)
            for (b in 0 until h) noise[b] += re[b] * re[b] + im[b] * im[b]
            nq++; q += stride
        }
        for (b in 0 until h) noise[b] = max(noise[b] / nq, 1e-12) * over

        // 2) Filtrado (Wiener con SNR a priori "decision-directed")
        val acc = DoubleArray(n); val g = DoubleArray(h); val gs = DoubleArray(h); val prevS = DoubleArray(h)
        val beta = 0.96
        val total = (len + n) / hop + 1
        var frame = 0
        var t = -(n - hop)
        while (t < len) {
            for (i in 0 until n) { val j = t + i; re[i] = (if (j in 0 until len) x[j].toDouble() else 0.0) * w[i]; im[i] = 0.0 }
            fft.run(re, im, false)
            for (b in 0 until h) {
                val p = re[b] * re[b] + im[b] * im[b]
                val post = p / noise[b]
                val xi = beta * prevS[b] / noise[b] + (1 - beta) * max(post - 1, 0.0)
                g[b] = max(xi / (1 + xi), floorG)
            }
            for (b in 0 until h) {
                val gg = if (b == 0 || b == h - 1) g[b] else 0.25 * g[b - 1] + 0.5 * g[b] + 0.25 * g[b + 1]
                gs[b] = gg
                prevS[b] = gg * gg * (re[b] * re[b] + im[b] * im[b])
            }
            for (b in 0 until h) {
                re[b] *= gs[b]; im[b] *= gs[b]
                if (b > 0 && b < h - 1) { re[n - b] *= gs[b]; im[n - b] *= gs[b] }
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

    /** Cadena completa. Devuelve el volumen final en LUFS. */
    fun process(x: FloatArray, fs: Int, strength: String, progress: (String, Double) -> Unit): Double {
        val f = fs.toDouble()
        biquad(x, hpf(80.0, 0.707, f)); biquad(x, hpf(80.0, 0.707, f))
        biquad(x, peak(250.0, 1.0, -2.0, f))
        denoise(x, fs, strength) { progress("ruido", it) }
        progress("eq", 0.0)
        biquad(x, peak(3200.0, 0.9, 3.0, f))
        biquad(x, hshelf(9000.0, 0.707, 1.5, f))
        var l = lufs(x, fs); gain(x, min(30.0, -20 - l))
        compress(x, fs, -24.0, 2.5)
        l = lufs(x, fs); gain(x, min(20.0, -14 - l))
        limit(x, fs, -1.5)
        return lufs(x, fs)
    }

}
