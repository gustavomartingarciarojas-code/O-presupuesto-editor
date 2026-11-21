# Prueba Proxy (rama `prueba-proxy`)

App de prueba para decidir si el editor **0 Presupuesto Editor** puede trabajar con proxy en la Redmi Pad 2.

- **Celular (Motorola):** elige un video crudo (4K 60), arma una copia liviana 720p 30 fps (H.264, cuadro clave cada 1 s) con Media3 Transformer y la ofrece por Wi-Fi (puerto 8765).
- **Tablet (Redmi Pad 2):** baja el proxy, lo reproduce en una línea de tiempo y mide la fluidez: cuadros perdidos al reproducir, tiempo de salto rápido y de salto exacto.

La APK se compila sola con GitHub Actions en cada cambio de esta rama y queda en la versión **prueba-proxy** (Releases), archivo `Prueba-Proxy.apk`. No reemplaza a 0 Presupuesto Editor: es otra app.
