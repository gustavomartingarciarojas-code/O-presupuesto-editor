package ar.gustavo.cerobudget

import org.json.JSONArray
import org.json.JSONObject

/** Un video crudo que está en el celular. */
class Clip(
    val id: String,
    val uri: String,          // solo lo usa el celular
    val nombre: String,
    val duracionMs: Long,
    val ancho: Int,
    val alto: Int,
    val fps: Float,
    val bytes: Long,
    var proxyBytes: Long = 0, // 0 = todavía no hay proxy
    var estado: String = "pendiente", // pendiente · armando · listo · error
    var avance: Int = 0,
    var error: String = ""
) {
    fun json(conUri: Boolean): JSONObject = JSONObject().apply {
        put("id", id); if (conUri) put("uri", uri)
        put("nombre", nombre); put("duracionMs", duracionMs)
        put("ancho", ancho); put("alto", alto); put("fps", fps.toDouble())
        put("bytes", bytes); put("proxyBytes", proxyBytes)
        put("estado", estado); put("avance", avance); put("error", error)
    }

    companion object {
        fun de(j: JSONObject) = Clip(
            j.getString("id"), j.optString("uri", ""), j.optString("nombre", "video"),
            j.optLong("duracionMs"), j.optInt("ancho"), j.optInt("alto"), j.optDouble("fps", 0.0).toFloat(),
            j.optLong("bytes"), j.optLong("proxyBytes"), j.optString("estado", "pendiente"),
            j.optInt("avance"), j.optString("error", "")
        )
    }
}

/** Keyframe de un tramo: zoom, encuadre (centro de la vista, fracción del cuadro) y volumen, en el ms t desde el inicio del tramo. */
data class Kf(val t: Long, val zoom: Float = 1f, val px: Float = 0f, val py: Float = 0f, val vol: Float = 1f) {
    fun json(): JSONObject = JSONObject().put("t", t).put("z", zoom.toDouble()).put("x", px.toDouble()).put("y", py.toDouble()).put("v", vol.toDouble())
    companion object {
        fun de(j: JSONObject) = Kf(j.getLong("t"), j.optDouble("z", 1.0).toFloat(), j.optDouble("x", 0.0).toFloat(), j.optDouble("y", 0.0).toFloat(), j.optDouble("v", 1.0).toFloat())
    }
}

/** Keyframe de un texto o sticker: posición y escala en el ms t de la línea de tiempo. */
data class KfT(val t: Long, val x: Float, val y: Float, val esc: Float = 1f) {
    fun json(): JSONObject = JSONObject().put("t", t).put("x", x.toDouble()).put("y", y.toDouble()).put("e", esc.toDouble())
    companion object {
        fun de(j: JSONObject) = KfT(j.getLong("t"), j.optDouble("x", 0.5).toFloat(), j.optDouble("y", 0.5).toFloat(), j.optDouble("e", 1.0).toFloat())
    }
}

/** Keyframe del volumen de la música, en el ms t de la línea de tiempo. */
data class KfM(val t: Long, val vol: Float) {
    fun json(): JSONObject = JSONObject().put("t", t).put("v", vol.toDouble())
    companion object { fun de(j: JSONObject) = KfM(j.getLong("t"), j.optDouble("v", 0.3).toFloat()) }
}

/** Interpolación lineal entre keyframes ordenados por tiempo. */
object Interp {
    fun <K> en(lista: List<K>, t: Long, tiempo: (K) -> Long, mezcla: (K, K, Float) -> K): K? {
        if (lista.isEmpty()) return null
        if (t <= tiempo(lista.first())) return lista.first()
        if (t >= tiempo(lista.last())) return lista.last()
        for (i in 0 until lista.size - 1) {
            val a = lista[i]; val b = lista[i + 1]
            if (t >= tiempo(a) && t <= tiempo(b)) {
                val d = (tiempo(b) - tiempo(a)).coerceAtLeast(1)
                return mezcla(a, b, (t - tiempo(a)).toFloat() / d)
            }
        }
        return lista.last()
    }
    fun f(a: Float, b: Float, u: Float) = a + (b - a) * u
    /** Cuánto se puede correr el encuadre con ese zoom sin que aparezcan bordes negros. */
    fun limite(zoom: Float) = ((1f - 1f / zoom.coerceAtLeast(1f)) / 2f).coerceAtLeast(0f)
}

private fun arr(a: JSONArray?) = a ?: JSONArray()

/** Un tramo de la línea de tiempo: una parte de un clip, con su velocidad y volumen. */
data class Segmento(
    val clip: String,
    val ini: Long,      // ms dentro del clip original
    val fin: Long,
    val vel: Float = 1f,
    val vol: Float = 1f, // 1 = 100 %
    val trans: String = "corte", // transición al entrar en este tramo: corte · negro · blanco
    val mascara: String = "",    // "" sin máscara · circulo · ovalo · rectangulo
    val mTam: Float = 0.7f,      // tamaño de la forma, fracción del cuadro
    val mSuave: Float = 0.15f,   // borde difuminado, 0 a 0,5
    val zoom: Float = 1f,        // zoom y encuadre fijos (cuando no hay keyframes)
    val px: Float = 0f,
    val py: Float = 0f,
    val kf: List<Kf> = emptyList()
) {
    /** Zoom, encuadre y volumen en el ms tRel desde el inicio del tramo (en tiempo de línea). */
    fun encuadre(tRel: Long): Kf = Interp.en(kf, tRel, { it.t }) { a, b, u ->
        Kf(tRel, Interp.f(a.zoom, b.zoom, u), Interp.f(a.px, b.px, u), Interp.f(a.py, b.py, u), Interp.f(a.vol, b.vol, u))
    } ?: Kf(tRel, zoom, px, py, vol)

    val animado: Boolean get() = kf.isNotEmpty() || zoom != 1f || px != 0f || py != 0f

    /** Lo que dura en la línea de tiempo (la velocidad lo acorta o lo alarga). */
    val dura: Long get() = ((fin - ini) / vel).toLong()

    fun json(): JSONObject = JSONObject().apply {
        put("clip", clip); put("ini", ini); put("fin", fin); put("vel", vel.toDouble()); put("vol", vol.toDouble())
        put("trans", trans); put("mascara", mascara); put("mTam", mTam.toDouble()); put("mSuave", mSuave.toDouble())
        put("zoom", zoom.toDouble()); put("px", px.toDouble()); put("py", py.toDouble())
        put("kf", JSONArray().apply { kf.forEach { put(it.json()) } })
    }

    companion object {
        fun de(j: JSONObject) = Segmento(
            j.getString("clip"), j.getLong("ini"), j.getLong("fin"),
            j.optDouble("vel", 1.0).toFloat(), j.optDouble("vol", 1.0).toFloat(),
            j.optString("trans", "corte"), j.optString("mascara", ""),
            j.optDouble("mTam", 0.7).toFloat(), j.optDouble("mSuave", 0.15).toFloat(),
            j.optDouble("zoom", 1.0).toFloat(), j.optDouble("px", 0.0).toFloat(), j.optDouble("py", 0.0).toFloat(),
            arr(j.optJSONArray("kf")).let { a -> List(a.length()) { Kf.de(a.getJSONObject(it)) } }
        )
    }
}

/** Un texto sobre el video, ubicado en la línea de tiempo (no en un tramo). */
data class Texto(
    val id: String,
    val texto: String,
    val ini: Long,          // ms de la línea de tiempo
    val fin: Long,
    val x: Float = 0.5f,    // centro del texto, 0 a 1 del ancho
    val y: Float = 0.8f,    // centro del texto, 0 a 1 del alto
    val tam: Float = 0.07f, // alto de la letra, fracción del alto del video
    val fuente: String = "Poppins-Bold",
    val color: String = "#FFFFFF",
    val estilo: String = "contorno", // contorno · caja · simple
    val tipo: String = "texto",      // texto · emoji · imagen (en imagen, "texto" es el nombre del archivo)
    val kf: List<KfT> = emptyList()
) {
    /** Posición y escala en el ms t de la línea de tiempo. */
    fun pos(t: Long): KfT = Interp.en(kf, t, { it.t }) { a, b, u ->
        KfT(t, Interp.f(a.x, b.x, u), Interp.f(a.y, b.y, u), Interp.f(a.esc, b.esc, u))
    } ?: KfT(t, x, y, 1f)

    fun json(): JSONObject = JSONObject().apply {
        put("id", id); put("texto", texto); put("ini", ini); put("fin", fin)
        put("x", x.toDouble()); put("y", y.toDouble()); put("tam", tam.toDouble())
        put("fuente", fuente); put("color", color); put("estilo", estilo); put("tipo", tipo)
        put("kf", JSONArray().apply { kf.forEach { put(it.json()) } })
    }

    companion object {
        fun de(j: JSONObject) = Texto(
            j.getString("id"), j.optString("texto", ""), j.optLong("ini"), j.optLong("fin"),
            j.optDouble("x", 0.5).toFloat(), j.optDouble("y", 0.8).toFloat(), j.optDouble("tam", 0.07).toFloat(),
            j.optString("fuente", "Poppins-Bold"), j.optString("color", "#FFFFFF"), j.optString("estilo", "contorno"),
            j.optString("tipo", "texto"),
            arr(j.optJSONArray("kf")).let { a -> List(a.length()) { KfT.de(a.getJSONObject(it)) } }
        )
    }
}

/** Toda la edición. Es lo único que viaja de la tablet al celular para exportar (pesa unos KB). */
data class Edicion(
    val segmentos: List<Segmento> = emptyList(),
    val formato: String = "original",   // original · 9:16 · 16:9 · 1:1 · 4:5
    val musica: String = "",            // nombre del archivo de música en la tablet ("" = sin música)
    val volMusica: Float = 0.3f,
    val textos: List<Texto> = emptyList(),
    val kfMusica: List<KfM> = emptyList()
) {
    fun volMusicaEn(t: Long): Float = Interp.en(kfMusica, t, { it.t }) { a, b, u -> KfM(t, Interp.f(a.vol, b.vol, u)) }?.vol ?: volMusica

    val total: Long get() = segmentos.sumOf { it.dura }

    fun inicio(i: Int): Long = segmentos.take(i).sumOf { it.dura }

    /** Dado un momento de la línea de tiempo, devuelve qué segmento es y cuánto avanzó adentro (en tiempo de línea). */
    fun ubicar(t: Long): Pair<Int, Long> {
        var acc = 0L
        segmentos.forEachIndexed { i, s ->
            if (t < acc + s.dura || i == segmentos.lastIndex) return i to (t - acc).coerceIn(0, s.dura)
            acc += s.dura
        }
        return 0 to 0L
    }

    /**
     * Fundidos de las transiciones: devuelve cuánto se ve la imagen (1 = normal, 0 = todo negro o blanco)
     * y hacia qué color funde, en el momento t de la línea de tiempo.
     */
    fun fundido(t: Long): Pair<Float, String> {
        var acc = 0L
        var f = 1f
        var color = "negro"
        segmentos.forEachIndexed { i, s ->
            if (s.trans != "corte") {
                val d = kotlin.math.abs(t - acc)
                if (d < MEDIO_FUNDIDO) {
                    val v = d.toFloat() / MEDIO_FUNDIDO
                    if (v < f) { f = v; color = s.trans }
                }
            }
            acc += s.dura
        }
        return f to color
    }

    fun json(): JSONObject = JSONObject().apply {
        put("segmentos", JSONArray().apply { segmentos.forEach { put(it.json()) } })
        put("formato", formato); put("musica", musica); put("volMusica", volMusica.toDouble())
        put("textos", JSONArray().apply { textos.forEach { put(it.json()) } })
        put("kfMusica", JSONArray().apply { kfMusica.forEach { put(it.json()) } })
    }

    companion object {
        fun de(j: JSONObject): Edicion {
            val a = j.optJSONArray("segmentos") ?: JSONArray()
            val tx = j.optJSONArray("textos") ?: JSONArray()
            return Edicion(
                List(a.length()) { Segmento.de(a.getJSONObject(it)) },
                j.optString("formato", "original"), j.optString("musica", ""),
                j.optDouble("volMusica", 0.3).toFloat(),
                List(tx.length()) { Texto.de(tx.getJSONObject(it)) },
                arr(j.optJSONArray("kfMusica")).let { a -> List(a.length()) { KfM.de(a.getJSONObject(it)) } }
            )
        }

        /** Mitad de la duración de un fundido: se oscurece en 350 ms y se aclara en 350 ms. */
        const val MEDIO_FUNDIDO = 350L

        /** Medida final en 4K para cada formato (ancho × alto). */
        fun medida(formato: String, anchoOrig: Int, altoOrig: Int): Pair<Int, Int> = when (formato) {
            "9:16" -> 2160 to 3840
            "16:9" -> 3840 to 2160
            "1:1" -> 2160 to 2160
            "4:5" -> 2160 to 2700
            else -> {
                // Original: misma proporción que el primer clip, con el lado largo hasta 3840
                val largo = maxOf(anchoOrig, altoOrig)
                if (largo <= 3840 || largo == 0) (anchoOrig / 2 * 2).coerceAtLeast(2) to (altoOrig / 2 * 2).coerceAtLeast(2)
                else {
                    val k = 3840.0 / largo
                    ((anchoOrig * k).toInt() / 2 * 2) to ((altoOrig * k).toInt() / 2 * 2)
                }
            }
        }

        /** Proporción ancho / alto para la vista previa. */
        fun proporcion(formato: String, anchoOrig: Int, altoOrig: Int): Float {
            val (w, h) = medida(formato, anchoOrig, altoOrig)
            return if (h == 0) 16f / 9f else w.toFloat() / h
        }
    }
}
