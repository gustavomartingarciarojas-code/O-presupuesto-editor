package ar.gustavo.pruebaproxy

import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Servidor mínimo en el celular: ofrece el proxy dentro de la red Wi-Fi.
 *  GET /info  → datos del proxy (texto JSON)
 *  GET /proxy → el archivo de video
 */
class Servidor(
    private val archivo: () -> File?,
    private val info: () -> String,
    private val aviso: (String) -> Unit
) {
    companion object { const val PUERTO = 8765 }

    @Volatile private var socket: ServerSocket? = null

    val activo: Boolean get() = socket?.isClosed == false

    fun iniciar() {
        if (activo) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(PUERTO))
        socket = s
        Thread {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                Thread { atender(c) }.start()
            }
        }.start()
    }

    fun detener() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    private fun atender(c: Socket) {
        try {
            c.soTimeout = 30_000
            c.sendBufferSize = 1 shl 20
            val entrada = BufferedInputStream(c.getInputStream())
            val pedido = leerLinea(entrada)
            while (true) { val l = leerLinea(entrada); if (l.isEmpty()) break }
            val ruta = pedido.split(" ").getOrNull(1) ?: "/"
            val salida = c.getOutputStream()
            when {
                ruta.startsWith("/info") -> {
                    val cuerpo = info().toByteArray(Charsets.UTF_8)
                    cabecera(salida, "200 OK", "application/json; charset=utf-8", cuerpo.size.toLong())
                    salida.write(cuerpo)
                }
                ruta.startsWith("/proxy") -> {
                    val f = archivo()
                    if (f == null || !f.exists()) {
                        val cuerpo = "Todavía no hay proxy.".toByteArray(Charsets.UTF_8)
                        cabecera(salida, "404 Not Found", "text/plain; charset=utf-8", cuerpo.size.toLong())
                        salida.write(cuerpo)
                    } else {
                        val total = f.length()
                        cabecera(salida, "200 OK", "video/mp4", total)
                        aviso("Enviando el proxy a la tablet… 0 %")
                        var enviados = 0L
                        var ultimo = -1
                        f.inputStream().use { inp ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                salida.write(buf, 0, n)
                                enviados += n
                                val p = (enviados * 100 / total).toInt()
                                if (p != ultimo) { ultimo = p; aviso("Enviando el proxy a la tablet… $p %") }
                            }
                        }
                        aviso("Proxy enviado a la tablet (${Datos.mb(total)}).")
                    }
                }
                else -> {
                    val cuerpo = "Prueba Proxy".toByteArray(Charsets.UTF_8)
                    cabecera(salida, "200 OK", "text/plain; charset=utf-8", cuerpo.size.toLong())
                    salida.write(cuerpo)
                }
            }
            salida.flush()
        } catch (e: Exception) {
            aviso("Se cortó el envío: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { c.close() } catch (_: Exception) { }
        }
    }

    private fun cabecera(o: OutputStream, estado: String, tipo: String, largo: Long) {
        o.write(("HTTP/1.1 $estado\r\nContent-Type: $tipo\r\nContent-Length: $largo\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
    }

    private fun leerLinea(i: BufferedInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) break
        }
        return sb.toString()
    }
}
