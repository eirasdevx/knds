# KNDS: emulador de DS para Android

KNDS es una app Android escrita en Kotlin. Por dentro usa **[melonDS](https://melonds.kuribo64.net/)** como núcleo de emulación (C++), unido a la app a través de JNI.

> **Licencia: GPLv3.** melonDS es GPLv3, así que KNDS también lo es. Si distribuyes la app (por ejemplo, el APK), tienes que ofrecer el código fuente con la misma licencia. El texto completo está en `LICENSE`.

## Estructura

```
external/melonDS/          Código de melonDS (commit en COMMIT), sin la interfaz Qt
app/src/main/cpp/
  CMakeLists.txt           Compila el núcleo de melonDS y libknds.so
  knds_jni.cpp             Puente JNI: crear la consola, frames, pantallas, entrada, audio, partidas y estados
  platform.cpp             Implementación de melonDS::Platform: archivos, hilos, guardado, micrófono, dlopen
app/src/main/java/com/knds/app/
  NdsCore.kt               Funciones nativas y la clase Nds (Kotlin puro)
  Emulator.kt              Hilo de emulación: frames, dibujo de las dos pantallas y audio a 48 kHz
  ScreenLayout.kt          Posición de las pantallas (vertical u horizontal, se pueden intercambiar)
  GamepadView.kt           Consola dibujada, controles táctiles, barra de accesos y pantalla táctil inferior
  EmulatorActivity.kt      Menú de pausa, estados guardados y mando físico
  MainActivity.kt          Inicio: biblioteca, guardados y ajustes
  AppDialog.kt             Diálogos propios (los mismos que KGBA)
  CoverStore.kt            Nombres oficiales y carátulas (No-Intro de libretro)
  RomStore.kt              Copia de la ROM (también desde .zip), partidas y estados comprimidos
```

## Compilar

1. Abre la carpeta `KNDS` con **Android Studio**. Tiene que tener instalados el **NDK 27.2.12479018** y **CMake 3.22.1**: Tools ▸ SDK Manager ▸ SDK Tools. Si te ofrece instalarlos al abrir el proyecto, acepta.
2. Conecta el móvil y dale a ejecutar. La primera compilación tarda unos minutos porque compila melonDS entero.

Recomendación: instala la variante **release** (`./gradlew :app:installRelease`). Ya va firmada con la clave de depuración. El núcleo C++ se compila siempre optimizado, pero la parte Kotlin también va más fluida en release.

Arquitecturas incluidas: `arm64-v8a` (móviles, con JIT) y `x86_64` (el emulador de Android Studio). El mínimo es Android 8.0, por las funciones de memoria compartida que necesita el JIT.

## Uso

KNDS es de la misma familia que KGBA: mismo menú, mismos colores y misma forma de trabajar.

- **Biblioteca**: elige tu carpeta de juegos (+ ROMs → «Elegir carpeta de juegos») y KNDS añade todos los `.nds`, `.dsi` y `.zip`, también de subcarpetas. Se vuelve a escanear al abrir la app. Nombres oficiales y carátulas de la base de datos No-Intro de libretro (se identifica la ROM por el código de cartucho de la cabecera y, si no basta, por su CRC). Lista o cuadrícula, favoritos, búsqueda y orden A–Z.
- **Guardados**: todos los estados de todos los juegos, con miniatura de las dos pantallas; «Cargar» abre el juego en ese momento.
- **Ajustes**: tema claro/oscuro, colores de la consola, carcasa, imagen nítida o suavizada, micrófono real, JIT, 3D en un hilo aparte, avance rápido, nombre de la consola y archivos de sistema.
- **Archivos de sistema (opcional)**: `bios9.bin` (4 KB), `bios7.bin` (16 KB) y `firmware.bin` de tu propia consola. Sin ellos se usan la BIOS libre de melonDS y un firmware generado, con arranque directo del juego.
- **En el juego**: en vertical, una consola negra mate de una pieza que flota sobre la carátula del juego desenfocada, con las dos pantallas enmarcadas y una ranura fina entre ellas (mantenla para soplar en el micrófono); en horizontal, pantallas en el centro y controles a los lados. Animación de arranque con sonido y la pantalla inferior se toca como con el lápiz.
- **Barra de accesos**: cápsula oscura con botones redondos (cargar, guardar, **menú** en un círculo blanco en el centro, intercambiar pantallas y avance rápido). Es la misma que usa KGBA.
- **Menú de pausa**: ranuras, trucos Action Replay, tapa, sonido, micrófono real, carcasa, controles táctiles, reasignar el mando, reiniciar.
- **Mando físico**: por posición (abajo = B, derecha = A, arriba = X, izquierda = Y; L1/R1 = L/R). Se puede reasignar cada botón.
- **Teclado**: WASD, L = A, K = B, I = X, J = Y, Q/E = L/R, Enter = Start, Espacio = Select.

## Qué se ha probado

El puente JNI y la capa Platform se compilaron en Linux (x86_64) y se ejecutaron desde la JVM con la ROM homebrew *Pong-NDS*:

- Arranque directo con la BIOS libre.
- Las dos pantallas.
- Entrada de botones y pantalla táctil.
- Audio a 48 kHz: genera las muestras esperadas.
- Estados guardados: al cargar un estado, la partida sigue exactamente igual.

La app compila con el NDK 27 y se ha instalado y abierto en un móvil Android arm64 real, con la biblioteca leyendo una carpeta de juegos.

**Pendiente de comprobar a fondo:** el JIT ARM64 juego a juego.

En x86_64, el JIT colgaba esa ROM homebrew (con el intérprete funcionaba bien). Por eso el JIT tiene un interruptor en la pantalla de inicio.

## Ideas para seguir

- Renderizador OpenGL ES de melonDS (3D a mayor resolución). El de melonDS está escrito para OpenGL de escritorio y hay que portarlo a GLES 3.
- Wifi local entre instancias.
