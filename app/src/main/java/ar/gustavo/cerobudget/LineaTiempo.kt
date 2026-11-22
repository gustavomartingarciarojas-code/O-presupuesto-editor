package ar.gustavo.cerobudget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Línea de tiempo como la de CapCut: el cabezal (línea blanca) queda fijo en el centro y lo que se mueve es la línea.
 * Arriba la regla, después la pista de video (los tramos), debajo una pista por cada texto o sticker que se pisa
 * en el tiempo, y abajo la música. Los keyframes se ven como diamantes sobre la pista del elemento.
 *
 * Gestos: arrastrar a los costados = mover el cabezal · pellizcar = acercar o alejar la línea ·
 * tocar = elegir · manijas del sticker elegido = cambiar dónde empieza y termina ·
 * mantener apretado el sticker elegido y arrastrar = moverlo en el tiempo · tocar un diamante = ir a ese keyframe.
 */
@SuppressLint("ViewConstructor")
class LineaTiempo(ctx: Context, private val ui: Ui) : View(ctx) {

    /** Lo que la línea necesita saber y avisar. Lo completa la pantalla del editor. */
    interface Oyente {
        fun edicion(): Edicion
        fun tiempo(): Long
        fun tramoElegido(): Int
        fun capaElegida(): String?          // id del texto o sticker, o MUSICA
        fun nombreClip(id: String): String
        fun buscar(t: Long, final: Boolean)
        fun elegirTramo(i: Int)
        fun elegirCapa(id: String)          // id del texto o sticker, o MUSICA
        fun elegirNada()
        fun cambiarCapa(id: String, ini: Long, fin: Long, mover: Boolean, final: Boolean)
    }

    companion object {
        const val MUSICA = "__musica__"
        private val COLOR_STICKER = Color.parseColor("#E3B800")
        private val COLOR_TEXTO = Color.parseColor("#B07BE0")
        private val COLOR_MUSICA = Color.parseColor("#2E8B57")
        private const val MAX_PISTAS = 5
    }

    var oyente: Oyente? = null

    /** Píxeles por milisegundo (lo cambia el pellizco). */
    var pxPorMs = 0.03f

    // ---------------------------------------------------------------- medidas
    private val hRegla = ui.dp(18).toFloat()
    private val hVideo = ui.dp(50).toFloat()
    private val hPista = ui.dp(30).toFloat()
    private val hMusica = ui.dp(24).toFloat()
    private val sep = ui.dp(5).toFloat()
    private val radio = ui.dp(6).toFloat()
    private val manija = ui.dp(16).toFloat()
    private val lado = ui.dp(6).toFloat()      // medio lado del diamante

    // ---------------------------------------------------------------- pinturas
    private val pRelleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pTexto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ui.dp(12).toFloat(); color = Ui.tinta }
    private val pRegla = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ui.dp(10).toFloat(); color = Ui.gris }
    private val pCabezal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = ui.dp(2).toFloat() }
    private val rect = RectF()
    private val camino = Path()

    // ---------------------------------------------------------------- estado del dibujo
    /** Pista asignada a cada texto o sticker (0 = la primera debajo del video). */
    private var pistas: Map<String, Int> = emptyMap()
    private var cantPistas = 0

    // ---------------------------------------------------------------- gestos
    private var tVista: Long? = null            // mientras se arrastra, el tiempo lo manda el dedo
    private var modo = 0                        // 0 nada · 1 buscar · 2 manija inicio · 3 manija fin · 4 mover capa
    private var xIni = 0f
    private var yIni = 0f
    private var tAlTocar = 0L
    private var capaIni = 0L
    private var capaFin = 0L
    private var seMovio = false
    private var pellizcando = false
    private var apretadoLargo = false
    private val umbral = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val largo = Runnable { apretadoLargo = true; if (modo == 1 && !seMovio && tocaCapaElegida(xIni, yIni)) { modo = 4; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); invalidate() } }

    private val escala = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { pellizcando = true; removeCallbacks(largo); return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            pxPorMs = (pxPorMs * d.scaleFactor).coerceIn(0.0004f, 0.6f)
            invalidate(); return true
        }
    })

    /** Ruedita del mouse: acerca o aleja la línea (con el mouse no se puede pellizcar). */
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL).takeIf { it != 0f } ?: e.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (v != 0f) {
                pxPorMs = (pxPorMs * (if (v > 0) 1.25f else 0.8f)).coerceIn(0.0004f, 0.6f)
                invalidate()
                return true
            }
        }
        return super.onGenericMotionEvent(e)
    }

    // ================================================================ medidas de la vista

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val alto = altoNecesario()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(alto.toInt(), heightMeasureSpec))
    }

    private fun altoNecesario(): Float =
        hRegla + hVideo + sep + max(1, cantPistas) * (hPista + sep) + hMusica + sep

    /** Recalcula las pistas de textos y stickers; si cambia la cantidad, la vista cambia de alto. */
    fun refrescar() {
        val ed = oyente?.edicion() ?: return
        val fines = mutableListOf<Long>()
        val m = mutableMapOf<String, Int>()
        for (t in ed.textos.sortedWith(compareBy({ it.ini }, { it.id }))) {
            var p = fines.indexOfFirst { it <= t.ini }
            if (p < 0) { if (fines.size < MAX_PISTAS) { fines += t.fin; p = fines.lastIndex } else p = fines.lastIndex }
            else fines[p] = t.fin
            m[t.id] = p
        }
        pistas = m
        if (fines.size != cantPistas) { cantPistas = fines.size; requestLayout() }
        invalidate()
    }

    // ================================================================ posiciones

    private fun tCentro(): Long = tVista ?: (oyente?.tiempo() ?: 0L)
    private fun xDe(t: Long): Float = width / 2f + (t - tCentro()) * pxPorMs
    private fun yVideo() = hRegla
    private fun yPista(p: Int) = hRegla + hVideo + sep + p * (hPista + sep)
    private fun yMusica() = hRegla + hVideo + sep + max(1, cantPistas) * (hPista + sep)

    /** Tolerancia para decir que el cabezal "está sobre" un keyframe. */
    fun toleranciaMs(): Long = max(120L, (ui.dp(8) / pxPorMs).toLong())

    // ================================================================ dibujo

    override fun onDraw(c: Canvas) {
        val o = oyente ?: return
        val ed = o.edicion()
        val total = ed.total
        c.drawColor(Ui.fondo)
        dibujarRegla(c, total)

        // Pista de video
        val tramoSel = o.tramoElegido()
        val capaSel = o.capaElegida()
        var acc = 0L
        ed.segmentos.forEachIndexed { i, s ->
            val x0 = xDe(acc) + ui.dp(1); val x1 = xDe(acc + s.dura) - ui.dp(1)
            if (x1 > 0 && x0 < width) {
                rect.set(x0, yVideo() + ui.dp(3), x1, yVideo() + hVideo - ui.dp(3))
                pRelleno.color = Ui.azul
                c.drawRoundRect(rect, radio, radio, pRelleno)
                val elegido = i == tramoSel && capaSel == null
                pBorde.color = if (elegido) Color.WHITE else Ui.linea
                pBorde.strokeWidth = ui.dp(if (elegido) 3 else 1).toFloat()
                c.drawRoundRect(rect, radio, radio, pBorde)
                val etiqueta = o.nombreClip(s.clip).substringBeforeLast('.') +
                    (if (s.vel != 1f) "  ${s.vel}×".replace(".0×", "×").replace('.', ',') else "")
                textoEn(c, etiqueta, rect, Ui.tinta)
                val ini = acc
                if (s.kf.isNotEmpty()) s.kf.forEach { k -> diamante(c, xDe(ini + k.t), rect.bottom - lado - ui.dp(3), elegido) }
            }
            acc += s.dura
        }
        if (ed.segmentos.isEmpty()) {
            pTexto.color = Ui.gris
            c.drawText("Línea de tiempo vacía: agregá un video", ui.dp(12).toFloat(), yVideo() + hVideo / 2 + ui.dp(4), pTexto)
            pTexto.color = Ui.tinta
        }

        // Pistas de textos y stickers
        for (t in ed.textos) {
            val p = pistas[t.id] ?: continue
            val x0 = xDe(t.ini); val x1 = xDe(t.fin)
            if (x1 < 0 || x0 > width) continue
            val y = yPista(p)
            rect.set(x0, y, max(x1, x0 + ui.dp(4)), y + hPista)
            pRelleno.color = if (t.tipo == "texto") COLOR_TEXTO else COLOR_STICKER
            c.drawRoundRect(rect, radio, radio, pRelleno)
            val elegido = t.id == capaSel
            val etiqueta = when (t.tipo) { "imagen" -> "Imagen"; "emoji" -> t.texto; else -> t.texto.replace('\n', ' ') }
            if (elegido) { pRelleno.color = Color.argb(60, 255, 255, 255); c.drawRoundRect(rect, radio, radio, pRelleno) }
            textoEn(c, etiqueta, rect, Ui.fondo)
            if (elegido) dibujarManijas(c, rect)
            t.kf.forEach { k -> diamante(c, xDe(k.t), y + hPista / 2, elegido) }
        }

        // Música
        if (ed.musica.isNotEmpty() && total > 0) {
            val y = yMusica()
            rect.set(xDe(0), y, xDe(total), y + hMusica)
            pRelleno.color = COLOR_MUSICA
            c.drawRoundRect(rect, radio, radio, pRelleno)
            val elegido = capaSel == MUSICA
            if (elegido) { pBorde.color = Color.WHITE; pBorde.strokeWidth = ui.dp(2).toFloat(); c.drawRoundRect(rect, radio, radio, pBorde) }
            textoEn(c, "♪ " + ed.musica, rect, Ui.tinta)
            ed.kfMusica.forEach { k -> diamante(c, xDe(k.t), y + hMusica / 2, elegido) }
        }

        // Cabezal fijo en el centro
        val cx = width / 2f
        c.drawLine(cx, hRegla - ui.dp(4), cx, height.toFloat(), pCabezal)
        camino.reset()
        camino.moveTo(cx - ui.dp(6), hRegla - ui.dp(8)); camino.lineTo(cx + ui.dp(6), hRegla - ui.dp(8)); camino.lineTo(cx, hRegla - ui.dp(1)); camino.close()
        pRelleno.color = Color.WHITE
        c.drawPath(camino, pRelleno)
    }

    private fun dibujarRegla(c: Canvas, total: Long) {
        // Paso de las marcas según el acercamiento: que queden a unos 80 dp
        val pasos = longArrayOf(100, 250, 500, 1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000, 600000)
        val paso = pasos.firstOrNull { it * pxPorMs >= ui.dp(80) } ?: pasos.last()
        val tIzq = (tCentro() - (width / 2f) / pxPorMs).toLong().coerceAtLeast(0)
        val tDer = min(total, (tCentro() + (width / 2f) / pxPorMs).toLong())
        pBorde.color = Ui.linea; pBorde.strokeWidth = ui.dp(1).toFloat()
        var t = tIzq / paso * paso
        while (t <= tDer) {
            val x = xDe(t)
            c.drawLine(x, hRegla - ui.dp(5), x, hRegla, pBorde)
            c.drawText(Datos.reloj(t).let { if (paso >= 1000) it.substringBefore(',') else it }, x + ui.dp(3), hRegla - ui.dp(6), pRegla)
            t += paso
        }
    }

    private fun textoEn(c: Canvas, s: String, r: RectF, color: Int) {
        val izq = max(r.left, 0f) + ui.dp(8)
        val ancho = min(r.right, width.toFloat()) - izq - ui.dp(6)
        if (ancho < ui.dp(14)) return
        pTexto.color = color
        val corto = TextUtils.ellipsize(s, pTexto, ancho, TextUtils.TruncateAt.END).toString()
        c.drawText(corto, izq, r.centerY() + pTexto.textSize / 3, pTexto)
        pTexto.color = Ui.tinta
    }

    private val rIzq = RectF()
    private val rDer = RectF()

    private fun dibujarManijas(c: Canvas, r: RectF) {
        pBorde.color = Color.WHITE; pBorde.strokeWidth = ui.dp(2).toFloat()
        c.drawRoundRect(r, radio, radio, pBorde)
        pRelleno.color = Color.WHITE
        rIzq.set(r.left - manija, r.top, r.left, r.bottom)
        rDer.set(r.right, r.top, r.right + manija, r.bottom)
        c.drawRoundRect(rIzq, radio, radio, pRelleno)
        c.drawRoundRect(rDer, radio, radio, pRelleno)
        pBorde.color = Ui.fondo
        c.drawLine(rIzq.centerX(), rIzq.top + ui.dp(8), rIzq.centerX(), rIzq.bottom - ui.dp(8), pBorde)
        c.drawLine(rDer.centerX(), rDer.top + ui.dp(8), rDer.centerX(), rDer.bottom - ui.dp(8), pBorde)
    }

    /** Diamante de keyframe. Si el cabezal está encima, se pinta rojo (como en CapCut). */
    private fun diamante(c: Canvas, x: Float, y: Float, elegido: Boolean) {
        if (x < -lado || x > width + lado) return
        val sobre = abs(x - width / 2f) <= toleranciaMs() * pxPorMs
        camino.reset()
        camino.moveTo(x, y - lado); camino.lineTo(x + lado, y); camino.lineTo(x, y + lado); camino.lineTo(x - lado, y); camino.close()
        pRelleno.color = when { sobre -> Ui.rojo; elegido -> Color.WHITE; else -> Color.argb(200, 255, 255, 255) }
        c.drawPath(camino, pRelleno)
        pBorde.color = Ui.fondo; pBorde.strokeWidth = ui.dp(1).toFloat()
        c.drawPath(camino, pBorde)
    }

    // ================================================================ toques

    private fun capaEn(x: Float, y: Float): Texto? {
        val ed = oyente?.edicion() ?: return null
        for (t in ed.textos) {
            val p = pistas[t.id] ?: continue
            val yp = yPista(p)
            if (y in yp..(yp + hPista) && x in (xDe(t.ini) - ui.dp(4))..(xDe(t.fin) + ui.dp(4))) return t
        }
        return null
    }

    private fun capaElegida(): Texto? {
        val o = oyente ?: return null
        val id = o.capaElegida() ?: return null
        return o.edicion().textos.firstOrNull { it.id == id }
    }

    private fun tocaCapaElegida(x: Float, y: Float): Boolean {
        val t = capaElegida() ?: return false
        val p = pistas[t.id] ?: return false
        val yp = yPista(p)
        return y in yp..(yp + hPista) && x in xDe(t.ini)..xDe(t.fin)
    }

    /** ¿Tocó una manija del sticker elegido? 2 = inicio, 3 = fin, 0 = no. */
    private fun manijaEn(x: Float, y: Float): Int {
        val t = capaElegida() ?: return 0
        val p = pistas[t.id] ?: return 0
        val yp = yPista(p)
        if (y !in (yp - ui.dp(6))..(yp + hPista + ui.dp(6))) return 0
        val a = xDe(t.ini); val b = xDe(t.fin)
        if (x in (a - manija - ui.dp(8))..(a + ui.dp(6))) return 2
        if (x in (b - ui.dp(6))..(b + manija + ui.dp(8))) return 3
        return 0
    }

    /** Keyframe tocado del elemento elegido: devuelve su tiempo en la línea, o null. */
    private fun diamanteEn(x: Float, y: Float): Long? {
        val o = oyente ?: return null
        val ed = o.edicion()
        val margen = ui.dp(14)
        val capa = o.capaElegida()
        if (capa == MUSICA) {
            val ym = yMusica()
            if (y in (ym - margen)..(ym + hMusica + margen)) return ed.kfMusica.map { it.t }.minByOrNull { abs(xDe(it) - x) }?.takeIf { abs(xDe(it) - x) <= margen }
            return null
        }
        val t = capaElegida()
        if (t != null) {
            val yp = yPista(pistas[t.id] ?: return null)
            if (y in (yp - margen)..(yp + hPista + margen)) return t.kf.map { it.t }.minByOrNull { abs(xDe(it) - x) }?.takeIf { abs(xDe(it) - x) <= margen }
            return null
        }
        val i = o.tramoElegido()
        val s = ed.segmentos.getOrNull(i) ?: return null
        if (y !in yVideo()..(yVideo() + hVideo)) return null
        val ini = ed.inicio(i)
        return s.kf.map { ini + it.t }.minByOrNull { abs(xDe(it) - x) }?.takeIf { abs(xDe(it) - x) <= margen }
    }

    private fun tocar(x: Float, y: Float) {
        val o = oyente ?: return
        val ed = o.edicion()
        diamanteEn(x, y)?.let { o.buscar(it, true); invalidate(); return }
        capaEn(x, y)?.let { o.elegirCapa(it.id); refrescar(); return }
        if (y in yVideo()..(yVideo() + hVideo)) {
            var acc = 0L
            ed.segmentos.forEachIndexed { i, s ->
                if (x in xDe(acc)..xDe(acc + s.dura)) { o.elegirTramo(i); invalidate(); return }
                acc += s.dura
            }
        }
        val ym = yMusica()
        if (ed.musica.isNotEmpty() && y in ym..(ym + hMusica) && x in xDe(0)..xDe(ed.total)) { o.elegirCapa(MUSICA); invalidate(); return }
        o.elegirNada(); invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val o = oyente ?: return false
        escala.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                xIni = e.x; yIni = e.y; seMovio = false; pellizcando = false; apretadoLargo = false
                tAlTocar = o.tiempo()
                val m = manijaEn(e.x, e.y)
                val t = capaElegida()
                if (m != 0 && t != null) { modo = m; capaIni = t.ini; capaFin = t.fin }
                else {
                    modo = 1
                    if (t != null) { capaIni = t.ini; capaFin = t.fin }
                    postDelayed(largo, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> { removeCallbacks(largo); if (modo == 1 && tVista != null) { o.buscar(tVista!!, true); tVista = null }; modo = 0; return true }
            MotionEvent.ACTION_MOVE -> {
                if (pellizcando || e.pointerCount > 1) return true
                val dx = e.x - xIni
                if (!seMovio && abs(dx) < umbral && abs(e.y - yIni) < umbral) return true
                if (!seMovio) { seMovio = true; removeCallbacks(largo) }
                val dMs = (dx / pxPorMs).toLong()
                val total = o.edicion().total
                val t = capaElegida()
                when (modo) {
                    1 -> { val nt = (tAlTocar - dMs).coerceIn(0, total); tVista = nt; o.buscar(nt, false) }
                    2 -> if (t != null) { val ni = (capaIni + dMs).coerceIn(0, max(0L, capaFin - 300)); o.cambiarCapa(t.id, ni, capaFin, false, false) }
                    3 -> if (t != null) { val nf = (capaFin + dMs).coerceIn(capaIni + 300, max(total, capaIni + 300)); o.cambiarCapa(t.id, capaIni, nf, false, false) }
                    4 -> if (t != null) {
                        val d = (capaFin - capaIni)
                        val ni = (capaIni + dMs).coerceIn(0L, max(0L, total - d))
                        o.cambiarCapa(t.id, ni, ni + d, true, false)
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(largo)
                val t = capaElegida()
                when {
                    pellizcando -> { }
                    !seMovio && modo != 4 && e.actionMasked == MotionEvent.ACTION_UP && !apretadoLargo -> tocar(e.x, e.y)
                    modo == 1 && tVista != null -> o.buscar(tVista!!, true)
                    modo in 2..4 && t != null -> o.cambiarCapa(t.id, t.ini, t.fin, false, true)
                }
                tVista = null; modo = 0; pellizcando = false
                parent?.requestDisallowInterceptTouchEvent(false)
                refrescar()
                return true
            }
        }
        return true
    }
}
