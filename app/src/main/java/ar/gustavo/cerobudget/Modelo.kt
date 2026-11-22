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

/** Un tramo de la línea de tiempo: una parte de un clip, con su velocidad y volumen. */
data class Segmento(
    val clip: String,
    val ini: Long,      // ms dentro del clip original
    val fin: Long,
    val vel: Float = 1f,
    val vol: Float = 1f, // 1 = 100 %
    val trans: String = "corte" // transición al entrar en este tramo: corte · negro · blanco
) {
    /** Lo que dura en la línea de tiempo (la velocidad lo acorta o lo alarga). */
    val dura: Long get() = ((fin - ini) / vel).toLong()

    fun json(): JSONObject = JSONObject().apply {
        put("clip", clip); put("ini", ini); put("fin", fin); put("vel", vel.toDouble()); put("vol", vol.toDouble())
        put("trans", trans)
    }

    companion object {
        fun de(j: JSONObject) = Segmento(
            j.getString("clip"), j.getLong("ini"), j.getLong("fin"),
            j.optDouble("vel", 1.0).toFloat(), j.optDouble("vol", 1.0).toFloat(),
            j.optString("trans", "corte")
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
    val estilo: String = "contorno" // contorno · caja · simple
) {
    fun json(): JSONObject = JSONObject().apply {
        put("id", id); put("texto", texto); put("ini", ini); put("fin", fin)
        put("x", x.toDouble()); put("y", y.toDouble()); put("tam", tam.toDouble())
        put("fuente", fuente); put("color", color); put("estilo", estilo)
    }

    companion object {
        fun de(j: JSONObject) = Texto(
            j.getString("id"), j.optString("texto", ""), j.optLong("ini"), j.optLong("fin"),
            j.optDouble("x", 0.5).toFloat(), j.optDouble("y", 0.8).toFloat(), j.optDouble("tam", 0.07).toFloat(),
            j.optString("fuente", "Poppins-Bold"), j.optString("color", "#FFFFFF"), j.optString("estilo", "contorno")
        )
    }
}

/** Toda la edición. Es lo único que viaja de la tablet al celular para exportar (pesa unos KB). */
data class Edicion(
    val segmentos: List<Segmento> = emptyList(),
    val formato: String = "original",   // original · 9:16 · 16:9 · 1:1 · 4:5
    val musica: String = "",            // nombre del archivo de música en la tablet ("" = sin música)
    val volMusica: Float = 0.3f,
    val textos: List<Texto> = emptyList()
) {
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
    }

    companion object {
        fun de(j: JSONObject): Edicion {
            val a = j.optJSONArray("segmentos") ?: JSONArray()
            val tx = j.optJSONArray("textos") ?: JSONArray()
            return Edicion(
                List(a.length()) { Segmento.de(a.getJSONObject(it)) },
                j.optString("formato", "original"), j.optString("musica", ""),
                j.optDouble("volMusica", 0.3).toFloat(),
                List(tx.length()) { Texto.de(tx.getJSONObject(it)) }
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
