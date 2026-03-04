package com.example.visionvoice

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.example.visionvoice.databinding.ActivityMainBinding
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.common.MlKit
import com.google.mlkit.genai.imagedescription.ImageDescriber
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions
import com.google.mlkit.genai.imagedescription.ImageDescription
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var imageCapture: ImageCapture
    private var imageDescriber: ImageDescriber? = null
    private var imageLabeler: ImageLabeler? = null
    private var objectDetector: ObjectDetector? = null
    private lateinit var cameraExecutor: ExecutorService
    private var isDescribing = false
    private var lastStatusText: String? = null
    private var genAiInitError: String? = null
    private var localLabelerInitError: String? = null
    private var cameraReady = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                updateStatus("Se necesita permiso de camara para continuar.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureSystemInsets()

        runCatching { MlKit.initialize(applicationContext) }
            .onFailure { Log.e("VisionVoice", "MlKit.initialize failed", it) }

        cameraExecutor = Executors.newSingleThreadExecutor()
        imageLabeler = tryCreateLocalLabeler()
        objectDetector = runCatching {
            val odOptions = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
            ObjectDetection.getClient(odOptions)
        }.getOrElse {
            Log.e("VisionVoice", "ObjectDetector init failed", it)
            null
        }

        imageDescriber = runCatching {
            ImageDescription.getClient(
                ImageDescriberOptions.builder(this).build(),
            )
        }.getOrElse {
            genAiInitError = it.message
            null
        }

        tts = TextToSpeech(this, this)
        setModeAuto()
        runIntroAnimations()

        binding.captureButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            capturePhoto()
        }
        binding.captureButton.isEnabled = true
        updateStatus("Iniciando camara...")

        if (hasCameraPermission()) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val preferredResult = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.ERROR
            ttsReady = when (preferredResult) {
                TextToSpeech.LANG_MISSING_DATA,
                TextToSpeech.LANG_NOT_SUPPORTED,
                TextToSpeech.ERROR -> {
                    val fallbackResult = tts?.setLanguage(Locale.US) ?: TextToSpeech.ERROR
                    fallbackResult != TextToSpeech.LANG_MISSING_DATA &&
                        fallbackResult != TextToSpeech.LANG_NOT_SUPPORTED &&
                        fallbackResult != TextToSpeech.ERROR
                }

                else -> true
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder()
                        .build()
                        .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                    cameraReady = true
                    updateStatus("Camara lista. Toma una foto.")
                } catch (e: Exception) {
                    cameraReady = false
                    updateStatus("Error iniciando camara: ${e.message}")
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun capturePhoto() {
        if (!cameraReady || !::imageCapture.isInitialized) {
            updateStatus("La camara aun no esta lista.")
            return
        }
        if (isDescribing) {
            updateStatus("Espera a que termine la descripcion actual.")
            return
        }

        val photoFile = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        setBusyState(true, "Capturando foto...")
        updateStatus("Capturando foto...")
        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    runOnUiThread {
                        handleCapturedImage(photoFile)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        setBusyState(false)
                        updateStatus("No se pudo capturar la foto: ${exception.message}")
                    }
                }
            },
        )
    }

    private fun handleCapturedImage(photoFile: File) {
        val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
        if (bitmap == null) {
            updateStatus("No se pudo leer la foto capturada.")
            return
        }

        binding.capturedImage.visibility = View.VISIBLE
        binding.capturedImage.setImageBitmap(bitmap)
        animateCapturedImageIn()
        describeImage(bitmap)
    }

    private fun describeImage(bitmap: Bitmap) {
        isDescribing = true
        setModeAuto()
        setBusyState(true, "Preparando descripcion...")
        updateStatus("Preparando descripcion de imagen...")

        val describer = imageDescriber
        if (describer == null) {
            val reason = genAiInitError?.let {
                "GenAI no disponible ($it). Usando reconocimiento local..."
            } ?: "GenAI no disponible. Usando reconocimiento local..."
            runLabelFallback(bitmap, reason)
            return
        }

        val statusFuture = describer.checkFeatureStatus()
        statusFuture.addListener(
            {
                try {
                    when (statusFuture.get()) {
                        FeatureStatus.AVAILABLE -> {
                            setModeGenAi()
                            setBusyState(true, "Generando descripcion con GenAI...")
                            runDescription(describer, bitmap)
                        }

                        FeatureStatus.DOWNLOADABLE -> {
                            setModeGenAi()
                            setBusyState(true, "Descargando modelo GenAI...")
                            updateStatus("Descargando modelo de descripcion...")
                            describer.downloadFeature(
                                object : DownloadCallback {
                                    override fun onDownloadStarted(bytesToDownload: Long) {
                                        setBusyState(true, "Descarga iniciada...")
                                    }

                                    override fun onDownloadProgress(totalBytesDownloaded: Long) {
                                        setBusyState(true, "Descargando modelo...")
                                    }

                                    override fun onDownloadCompleted() {
                                        setBusyState(true, "Modelo listo, generando descripcion...")
                                        updateStatus("Modelo descargado. Generando descripcion...")
                                        runDescription(describer, bitmap)
                                    }

                                    override fun onDownloadFailed(exception: GenAiException) {
                                        runLabelFallback(
                                            bitmap,
                                            "GenAI no disponible (${exception.message}). Usando reconocimiento local...",
                                        )
                                    }
                                },
                            )
                        }

                        FeatureStatus.DOWNLOADING -> {
                            // If another download is in progress, an inference attempt can proceed once ready.
                            setModeGenAi()
                            setBusyState(true, "Esperando descarga en curso...")
                            updateStatus("El modelo aun se esta descargando. Reintentando...")
                            runDescription(describer, bitmap)
                        }

                        else -> {
                            runLabelFallback(
                                bitmap,
                                "Descripcion avanzada no disponible. Usando reconocimiento local...",
                            )
                        }
                    }
                } catch (e: Exception) {
                    runLabelFallback(
                        bitmap,
                        "No se pudo iniciar GenAI (${e.message}). Usando reconocimiento local...",
                    )
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun runDescription(describer: ImageDescriber, bitmap: Bitmap) {
        val request = ImageDescriptionRequest.builder(bitmap).build()
        val descriptionFuture = describer.runInference(request)

        descriptionFuture.addListener(
            {
                try {
                    val generated = descriptionFuture.get().description.trim()
                    if (generated.isNotEmpty()) {
                        setModeGenAi()
                        updateStatus(generated)
                        speak(generated)
                        isDescribing = false
                        setBusyState(false)
                    } else {
                        runLabelFallback(
                            bitmap,
                            "No se genero descripcion avanzada. Usando reconocimiento local...",
                        )
                    }
                } catch (e: Exception) {
                    runLabelFallback(
                        bitmap,
                        "No se pudo usar GenAI (${e.cause?.message ?: e.message}). Usando reconocimiento local...",
                    )
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun runLabelFallback(bitmap: Bitmap, reason: String) {
        val labeler = imageLabeler ?: tryCreateLocalLabeler()
        if (labeler == null) {
            val details = localLabelerInitError ?: "sin detalle"
            updateStatus("No se pudo iniciar ML Kit local: $details")
            isDescribing = false
            setBusyState(false)
            return
        }

        setModeLocal()
        setBusyState(true, "Analizando con reconocimiento local...")
        updateStatus(reason)
        val image = InputImage.fromBitmap(bitmap, 0)

        val labelTask = labeler.process(image)
        val detector = objectDetector
        if (detector != null) {
            val odTask = detector.process(image)
            Tasks.whenAllComplete(labelTask, odTask)
                .addOnSuccessListener {
                    val labels = if (labelTask.isSuccessful) {
                        labelTask.result ?: emptyList()
                    } else {
                        emptyList()
                    }
                    val objects = if (odTask.isSuccessful) {
                        odTask.result ?: emptyList()
                    } else {
                        emptyList()
                    }
                    if (labels.isEmpty() && !labelTask.isSuccessful) {
                        updateStatus(
                            "No se pudo describir la imagen: ${labelTask.exception?.message ?: "error desconocido"}",
                        )
                        isDescribing = false
                        setBusyState(false)
                        return@addOnSuccessListener
                    }
                    val odCategories = objects
                        .flatMap { obj -> obj.labels.map { it.text.lowercase(Locale.getDefault()) } }
                        .toSet()
                    Log.d("VisionVoice", "Labels: ${labels.map { "${it.text}(${it.confidence})" }}")
                    Log.d("VisionVoice", "OD categories: $odCategories")
                    val spoken = buildFallbackDescription(bitmap, labels, odCategories)
                    updateStatus(spoken)
                    speak(spoken)
                    isDescribing = false
                    setBusyState(false)
                }
        } else {
            labelTask
                .addOnSuccessListener { labels ->
                    Log.d("VisionVoice", "Labels: ${labels.map { "${it.text}(${it.confidence})" }}")
                    val spoken = buildFallbackDescription(bitmap, labels)
                    updateStatus(spoken)
                    speak(spoken)
                    isDescribing = false
                    setBusyState(false)
                }
                .addOnFailureListener { error ->
                    updateStatus("No se pudo describir la imagen: ${error.message ?: error.javaClass.simpleName}")
                    isDescribing = false
                    setBusyState(false)
                }
        }
    }

    private fun tryCreateLocalLabeler(): ImageLabeler? {
        val created = runCatching {
            ImageLabeling.getClient(
                ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(0.30f)
                    .build(),
            )
        }.getOrElse {
            localLabelerInitError = "${it.javaClass.simpleName}: ${it.message ?: "sin detalle"}"
            Log.e("VisionVoice", "Local labeler init failed", it)
            null
        }

        if (created != null) {
            localLabelerInitError = null
            imageLabeler = created
        }
        return created
    }

    private fun buildFallbackDescription(
        bitmap: Bitmap,
        labels: List<com.google.mlkit.vision.label.ImageLabel>,
        odCategories: Set<String> = emptySet(),
    ): String {
        if (labels.isEmpty() && odCategories.isEmpty()) {
            return "No pude reconocer objetos claros en la imagen."
        }

        val topLabels = labels
            .sortedByDescending { it.confidence }
            .take(5)

        Log.d("VisionVoice", "=== RAW LABELS ===")
        labels.forEach { Log.d("VisionVoice", "  '${it.text}' conf=${it.confidence}") }
        Log.d("VisionVoice", "OD categories: $odCategories")

        val parts = mutableListOf<String>()

        if (topLabels.isNotEmpty()) {
            val best = topLabels.first()
            val intro = when {
                best.confidence >= 0.85f -> "Veo"
                best.confidence >= 0.70f -> "Parece ser"
                else -> "Podria ser"
            }
            parts.add("$intro ${best.text}")

            val others = topLabels.drop(1).take(3).map { it.text }
            if (others.isNotEmpty()) {
                parts.add("Tambien detecto ${others.joinToString(", ")}")
            }
        }

        if (odCategories.isNotEmpty()) {
            val translated = odCategories.map { translateOdCategory(it) }
            parts.add("Categoria: ${translated.joinToString(", ")}")
        }

        val colorHint = detectDominantColorName(bitmap)
        if (colorHint != null) {
            parts.add("Color predominante: $colorHint")
        }

        return parts.joinToString(". ") + "."
    }

    private fun translateOdCategory(category: String): String {
        val lower = category.lowercase(Locale.getDefault())
        return when {
            "home" in lower -> "articulo del hogar"
            "food" in lower -> "comida"
            "fashion" in lower -> "articulo de moda"
            "plant" in lower -> "planta"
            "place" in lower -> "lugar"
            else -> category
        }
    }

    private fun detectDominantColorName(bitmap: Bitmap): String? {
        val sample = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0

        for (x in 0 until sample.width) {
            for (y in 0 until sample.height) {
                val pixel = sample.getPixel(x, y)
                if (Color.alpha(pixel) < 120) continue
                sumR += Color.red(pixel)
                sumG += Color.green(pixel)
                sumB += Color.blue(pixel)
                count++
            }
        }
        sample.recycle()
        if (count == 0) return null

        val r = (sumR / count).toInt()
        val g = (sumG / count).toInt()
        val b = (sumB / count).toInt()
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)

        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]

        if (v < 0.16f) return "negro"
        if (v > 0.92f && s < 0.12f) return "blanco"
        if (s < 0.18f) {
            return when {
                v < 0.35f -> "gris oscuro"
                v < 0.70f -> "gris"
                else -> "gris claro"
            }
        }

        return when {
            h < 20f || h >= 345f -> "rojo"
            h < 48f -> "naranja"
            h < 70f -> "amarillo"
            h < 165f -> "verde"
            h < 255f -> "azul"
            h < 295f -> "violeta"
            else -> "rosado"
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "description-${System.currentTimeMillis()}")
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateStatus(text: String) {
        runOnUiThread {
            if (text == lastStatusText) return@runOnUiThread
            lastStatusText = text

            if (!binding.statusText.isLaidOut) {
                binding.statusText.text = text
                return@runOnUiThread
            }

            binding.statusText.animate()
                .alpha(0f)
                .setDuration(120)
                .withEndAction {
                    binding.statusText.text = text
                    binding.statusText.animate()
                        .alpha(1f)
                        .setDuration(180)
                        .start()
                }
                .start()
        }
    }

    private fun setBusyState(busy: Boolean, processingMessage: String? = null) {
        runOnUiThread {
            binding.captureButton.isEnabled = !busy
            binding.captureButton.text = if (busy) {
                getString(R.string.capture_button_processing)
            } else {
                getString(R.string.capture_button)
            }

            if (busy) {
                binding.processingRow.visibility = View.VISIBLE
                binding.processingText.text = processingMessage ?: getString(R.string.processing_generic)
            } else {
                binding.processingRow.visibility = View.GONE
            }
        }
    }

    private fun setModeAuto() {
        runOnUiThread {
            binding.modeTag.text = getString(R.string.mode_auto)
            binding.modeTag.setBackgroundResource(R.drawable.bg_mode_auto)
        }
    }

    private fun setModeGenAi() {
        runOnUiThread {
            binding.modeTag.text = getString(R.string.mode_genai)
            binding.modeTag.setBackgroundResource(R.drawable.bg_mode_genai)
        }
    }

    private fun setModeLocal() {
        runOnUiThread {
            binding.modeTag.text = getString(R.string.mode_local)
            binding.modeTag.setBackgroundResource(R.drawable.bg_mode_local)
        }
    }

    private fun animateCapturedImageIn() {
        binding.capturedImage.apply {
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(260)
                .setInterpolator(OvershootInterpolator(0.55f))
                .start()
        }
    }

    private fun runIntroAnimations() {
        binding.headerContainer.apply {
            alpha = 0f
            translationY = -28f
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(500)
                .setStartDelay(120)
                .start()
        }

        binding.bottomPanel.apply {
            alpha = 0f
            translationY = 52f
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(500)
                .setStartDelay(180)
                .setInterpolator(OvershootInterpolator(0.8f))
                .start()
        }
    }

    private fun configureSystemInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.headerContainer.updatePadding(top = insets.top + dp(8))

            binding.bottomPanel.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = insets.bottom + dp(12)
            }

            binding.capturedImage.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = insets.bottom + dp(20)
            }

            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.rootContainer)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        imageDescriber?.close()
        imageLabeler?.close()
        objectDetector?.close()
        tts?.stop()
        tts?.shutdown()
    }

    companion object
}
