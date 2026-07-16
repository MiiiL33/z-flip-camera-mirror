# FlipMirror

Visor de cámara propio con filtros para la pantalla exterior (cover screen) del Samsung Galaxy Z Flip.

**Estado actual:** Sprint 0: fundaciones y spikes de riesgo.

**Repo remoto:** [`github.com/MiiiL33/z-flip-camera-mirror`](https://github.com/MiiiL33/z-flip-camera-mirror).

---

## Qué es

FlipMirror es una app Android que muestra su propio visor de cámara (con pipeline de filtros en tiempo real) directamente en la cover screen del Z Flip, usando las cámaras traseras como si fueran de "selfie" con el teléfono cerrado. Desde ahí se captura foto/video y se comparte a Instagram.

Un punto importante para entender el alcance del proyecto: **FlipMirror no es un espejo de la preview de Instagram**. No refleja lo que Instagram está grabando en la pantalla principal; es un visor propio, construido con CameraX, que corre en la pantalla exterior y cuyo resultado (foto/video ya filtrado) se comparte después a IG mediante un intent. Esto es así porque **la cámara en Android es de acceso exclusivo**: mientras una app (por ejemplo Instagram) tiene el sensor abierto, ninguna otra app puede abrirlo simultáneamente. Un modo "espejo real" de la preview de otra app es una hipótesis separada (Opción C / Spike 3) que se evalúa de forma acotada y no es el producto base.

## Requisitos

- Android Studio (versión reciente, canal estable).
- JDK 17 (el mismo que usa el proyecto vía `compileOptions`/`kotlinOptions`).
- Dispositivos de prueba físicos disponibles: **Z Flip 7** (cover 4.1", 1048×948, ~1.1:1, 120 Hz, One UI 8) y **Z Flip 5** (cover 3.4", 720×748, ~0.96:1, 60 Hz, One UI 8.5/Android 16). El emulador no reproduce el display exterior real ni sus restricciones de One UI. No hay Fold físico en el equipo.
- Habilitar la app en la cover screen en el propio dispositivo, siempre vía **Good Lock → módulo MultiStar** ("I ♡ Galaxy Foldable" → Launcher Widget). El toggle de **Ajustes → Funciones avanzadas → Labs** existe en ambos dispositivos, pero **solo admite una lista curada de apps** (YouTube, WhatsApp, Maps, etc.) y no sirve para habilitar una app arbitraria como esta.

## Cómo compilar

Abrir la carpeta raíz del proyecto en Android Studio y dejar que sincronice Gradle, o desde línea de comandos:

```bash
./gradlew assembleDebug
```

El APK de debug queda en `app/build/outputs/apk/debug/`.

## Estructura del repo

```
app/                                    módulo Android (Kotlin + CameraX + Jetpack WindowManager)
.github/                                workflows de CI y plantillas de issues/PR
README.md, CHANGELOG.md, LICENSE        documentación pública del repositorio
```

## Convenciones

- **Flujo de ramas**: ver sección [Ramas](#ramas): `main` y `dev` protegidas, ramas de feature cortas contra `dev`, PR con al menos 1 review antes de mergear.
- **CI en cada PR** (GitHub Actions): build, lint (ktlint), unit tests.
- **Estilo de código**: ktlint aplicado vía el plugin de Gradle; no mergear con el lint en rojo.
- **Versionado**: SemVer, con un tag por cierre de sprint (`v0.1.0` = Sprint 1, `v0.2.0` = Sprint 2, etc.). Ver `CHANGELOG.md`.

## Ramas

- `main`: producción.
- `dev`: pruebas e integración.

Ambas ramas están protegidas: no se admite push directo, solo se entra vía Pull Request aprobado. El trabajo de cada feature se hace en ramas cortas creadas contra `dev`.

## Licencia

Este proyecto está publicado bajo la [GNU General Public License v3.0 (GPLv3)](./LICENSE). Cualquier fork o redistribución debe conservar el aviso de copyright, y los trabajos derivados deben permanecer bajo GPLv3.
