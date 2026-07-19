# Changelog

Todos los cambios notables de este proyecto se documentarán en este archivo.

El formato está basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/),
y este proyecto se adhiere a [Semantic Versioning](https://semver.org/lang/es/).

## [Unreleased]

### Added

- Visor de producto base del Sprint 1 (`com.flipmirror.app.viewfinder`): Activity `ViewfinderActivity` con preview de CameraX bindeada al lifecycle, pensada para la cover screen del Z Flip con el teléfono cerrado (showWhenLocked/turnScreenOn), layout a pantalla completa adaptado a la cover casi cuadrada, permiso de cámara en runtime y estados de error básicos. Observa las FoldingFeature de Jetpack WindowManager y rebindea la cámara al cambiar de postura o de configuración para no quedar en negro ni crashear. Se extrajo la clasificación de postura a una clase pura testeable (`FoldPosture`). El visor pasa a ser el launcher principal; las activities de spikes quedan exported para QA por adb. Fuera de alcance de este PR (PRs siguientes del Sprint 1): cambio de lente wide/ultrawide, captura de foto y toggle de encuadre 1:1 / 9:16.
- Scaffold del módulo Android (Kotlin + CameraX + Jetpack WindowManager) para el visor de cámara propio en la cover screen del Z Flip.
- CI con GitHub Actions para build, lint (ktlint) y unit tests en cada PR.
- Plantillas de issues y de Pull Request en `.github/`.
- Gradle Wrapper fijado en la versión 8.11.1.
- Licencia GPLv3 (`LICENSE`) para el repositorio.
