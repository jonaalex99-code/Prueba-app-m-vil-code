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

## Panel táctil

La pestaña **Panel táctil** funciona como el touchpad de un mouse para moverte por los menús:

| Gesto | Acción en la TV |
| --- | --- |
| Deslizar el dedo | Flechas (una por cada tramo recorrido: arrastre largo = varios pasos) |
| Tocar | OK |
| Mantener presionado | Atrás |

> Nota: por infrarrojo **no existe un cursor libre** tipo mouse; eso solo lo hacen controles
> Bluetooth/WiFi (como el Magic Remote de LG). Por eso el panel traduce tus gestos a flechas y OK,
> que funcionan en cualquier TV.

También hay pestañas con **cruceta** (flechas + OK) y **teclado numérico**, más Volumen, Canal,
Silencio, Entrada, Inicio, Menú y Atrás.

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
