# Vision Voice (Android)

MVP de app Android que:
1. Abre la camara dentro de la app.
2. Captura una foto.
3. Genera una descripcion usando ML Kit GenAI Image Description.
4. Lee la descripcion con TextToSpeech.

## Requisitos

- Android Studio reciente.
- JDK 17.
- Dispositivo Android real recomendado (API 26+).
- Para GenAI Image Description, el dispositivo debe soportar AICore/Gemini Nano.

## Como correr

1. Abre este proyecto en Android Studio.
2. Espera el sync de Gradle.
3. Ejecuta la app en un dispositivo compatible.
4. Da permiso de camara.
5. Presiona `Tomar Foto y Describir`.

## Dependencias principales

- CameraX para preview y captura.
- `com.google.mlkit:genai-image-description:1.0.0-beta1`.
- TextToSpeech nativo de Android.

## Notas

- Si el modelo no esta en el dispositivo, la app intenta descargarlo.
- Si el dispositivo no soporta la feature, la app muestra un mensaje y no crashea.
- La descripcion suele generarse en ingles (segun soporte actual del modelo).

