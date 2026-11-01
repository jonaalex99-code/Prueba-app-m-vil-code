# Control Universal (Android, infrarrojo)

App para celulares con **emisor infrarrojo** (Xiaomi, Redmi, POCO, algunos Huawei/Honor, etc.)
que controla la TV **sin tener que buscar la marca**.

**Descargar:** [`ControlUniversal.apk`](ControlUniversal.apk) → ábrelo en el celular y acepta
"instalar apps de origen desconocido".

## Cómo se usa

1. Apunta el celular a la TV y toca el botón rojo **Encender**.
   La app prueba los códigos de encendido de ~300 modelos/marcas, uno tras otro (las marcas más comunes van primero).
2. En cuanto la TV prenda (o se apague), toca **«¡Ya prendió!»**.
3. Si quedan varias marcas posibles, la app sube el volumen un poquito y pregunta
   «¿Subió el volumen?» (1–3 preguntas de Sí/No). Al terminar baja el volumen otra vez.
4. Listo: todos los botones responden al instante. La configuración se guarda.

- **Mantener presionado Encender** → vuelve a buscar (por ejemplo, para otra TV).
- El botón de arriba a la derecha cambia el modo: *Universal*, *Detectar mi TV* o elegir la marca a mano (opcional).
- Sin detectar, cada botón se envía a las 12 familias de códigos más comunes (tarda ~1 s por toque).

## Proyectar en la TV con un toque

El botón azul **«Proyectar en la TV»** conecta la pantalla del celular a la TV:

1. Abre el menú de **Transmitir** del celular (en Xiaomi, el botón «Transmitir» del panel rápido).
2. Toca automáticamente el nombre de tu TV en la lista.
3. Si la TV no aparece en ~6 s (está apagada), la **enciende por infrarrojo** y sigue esperando hasta 45 s.

Configuración (una sola vez):
- Al tocar el botón, la app pide activar **Accesibilidad › Control Universal**. Solo se usa unos segundos
  después de tocar «Proyectar», para tocar el nombre de la TV. Si Android muestra «Configuración restringida»:
  *Info de la app › ⋮ › Permitir configuración restringida*.
- La primera vez toca tú el nombre de tu TV en la lista: la app lo recuerda y desde entonces se conecta sola.
- Para que la TV se encienda sola, primero detecta tu TV con el botón rojo (ver arriba).
- Mantén presionado el botón azul para cambiar la forma de proyectar (panel rápido, Ajustes o
  Google Home para TVs con Chromecast/Google TV), olvidar la TV o desactivar el encendido automático.

Límites (de Android y de las TVs, no de la app):
- Android no deja que una app inicie la proyección por sí sola; por eso se usa Accesibilidad para "tocar" el menú.
- Proyección directa (Miracast / Wi-Fi Direct): **no necesita red WiFi ni router**. Solo el WiFi del
  celular debe estar encendido (sin conectarse a nada); la app lo enciende/pide si está apagado.
- La TV debe soportar proyección: Miracast / Screen Mirroring / Smart View (Samsung, LG, Hisense, TCL, Sony…)
  o Chromecast integrado (Google TV / Android TV). Miracast se conecta directo, sin necesidad de WiFi;
  Chromecast necesita que TV y celular estén en la misma red WiFi.
- Ninguna app puede conectar la TV a tu red WiFi: eso se configura una vez en la TV.
- Algunas TVs (LG, Samsung) preguntan «¿Permitir conexión?» la primera vez: acepta con el control.

## Secciones (sin scroll)

La app ocupa una sola pantalla y se divide en tres secciones con una barra inferior animada:

- **Control**: Proyectar, Encender, Entrada, Silencio, Volumen, Canal, Inicio/Menú/Atrás y abajo una
  cruceta (mantener presionado repite) o el teclado numérico.
- **Mouse**: panel táctil grande, botones de clic, Atrás/Inicio, volumen y velocidad del cursor.
- **Teclado**: escribe y el texto aparece en la TV al instante (o activa «Enviar» para mandarlo todo junto).

## Bluetooth: mouse y teclado de verdad

Por Bluetooth el celular se presenta ante la TV como un **mouse + teclado inalámbrico** (perfil HID,
Android 9+). Así aparece un **cursor real** en la TV y puedes escribir en buscadores, YouTube, etc.

Emparejar (una sola vez): toca **Bluetooth** (arriba a la derecha) › *Emparejar una TV nueva* ›
*Hacer visible*. En la TV: *Ajustes › Control remoto y accesorios › Agregar accesorio* (o
*Ajustes › Bluetooth*) y elige tu celular. Después se reconecta solo al abrir la app.

| Gesto en el panel (con Bluetooth) | Acción |
| --- | --- |
| Mover un dedo | Mueve el cursor (con aceleración: lento = preciso, rápido = cruza la pantalla) |
| Tocar | Clic |
| Tocar con dos dedos | Clic derecho (en Android TV suele ser «Atrás») |
| Arrastrar con dos dedos / franja derecha | Desplazar (rueda) |
| Mantener presionado y mover | Arrastrar |

Con Bluetooth conectado, flechas, OK, Atrás, Inicio, Menú y volumen se envían por Bluetooth (no hace
falta apuntar). Encender, Entrada, Canales y números siguen por infrarrojo.

Sin Bluetooth, el panel funciona por infrarrojo: deslizar = flechas, tocar = OK, mantener = Atrás.

Requisitos y límites:
- La TV debe aceptar mouse/teclado Bluetooth: Android TV / Google TV (TCL, Hisense, Sony, Xiaomi…),
  Samsung y LG recientes. Roku y las TVs sin Bluetooth no lo aceptan.
- El celular necesita Android 9 o superior con el perfil HID activado (la mayoría lo tiene; si no,
  la app lo avisa).
- El teclado usa distribución en inglés: los acentos se envían sin tilde (á → a, ñ → n).

## Marcas incluidas

TCL (Roku TV y Android/Google TV), LG, Samsung, Sony, Hisense, Xiaomi, Toshiba, RCA, Insignia,
Philips, Panasonic, Sharp, Vizio, Hitachi, JVC, Sanyo, Emerson, Akai, BGH, Telefunken y más.
El barrido de encendido incluye además cientos de códigos extra de otras marcas.

## Compilar

```bash
./gradlew assembleRelease   # resultado: app/build/outputs/apk/release/app-release.apk
```

Requiere el Android SDK (API 34). La base de códigos (`app/src/main/assets/codes.json`) se genera con
`tools/gen.py` a partir de [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB) (CC0) y la
biblioteca universal `tv.ir` del firmware de Flipper Zero (GPL-3.0):

```bash
python3 tools/gen.py <Flipper-IRDB>/TVs <flipperzero-firmware>/.../infrared/assets/tv.ir app/src/main/assets/codes.json
```
