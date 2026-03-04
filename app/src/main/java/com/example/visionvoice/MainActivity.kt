package com.example.visionvoice

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        labeler.process(image)
            .addOnSuccessListener { labels ->
                val spoken = buildFallbackDescription(labels)
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

    private fun tryCreateLocalLabeler(): ImageLabeler? {
        val created = runCatching {
            ImageLabeling.getClient(
                ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(0.55f)
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
        labels: List<com.google.mlkit.vision.label.ImageLabel>,
    ): String {
        if (labels.isEmpty()) {
            return "No pude reconocer objetos claros en la imagen."
        }

        val ordered = labels.sortedByDescending { it.confidence }
        val candidates = ordered
            .take(5)
            .map { translateLabel(it.text) }
            .distinct()
            .take(3)

        if (candidates.isEmpty()) {
            return "No pude armar una descripcion clara."
        }

        val certainty = when (ordered.first().confidence) {
            in 0.85f..1.0f -> "Veo con bastante claridad"
            in 0.70f..0.8499f -> "Parece que hay"
            else -> "Podria haber"
        }

        val sceneContext = inferSceneContext(candidates)
        val objectsText = joinForSpeech(candidates)

        return if (sceneContext == null) {
            "$certainty $objectsText."
        } else {
            "$certainty $objectsText. Parece una escena $sceneContext."
        }
    }

    private fun joinForSpeech(items: List<String>): String {
        return when (items.size) {
            0 -> ""
            1 -> items[0]
            2 -> "${items[0]} y ${items[1]}"
            else -> "${items[0]}, ${items[1]} y ${items[2]}"
        }
    }

    private fun inferSceneContext(labels: List<String>): String? {
        val set = labels.map { it.lowercase(Locale.getDefault()) }.toSet()

        if (set.any { it in OUTDOOR_WORDS }) return "al aire libre"
        if (set.any { it in INDOOR_WORDS }) return "de interior"
        if (set.any { it in FOOD_WORDS }) return "de comida"
        if (set.any { it in ANIMAL_WORDS }) return "con animales"
        if (set.any { it in PEOPLE_WORDS }) return "con personas"
        if (set.any { it in TECH_WORDS }) return "con tecnologia"

        return null
    }

    private fun translateLabel(raw: String): String {
        val key = raw.trim().lowercase(Locale.getDefault())
        return LABEL_TRANSLATIONS[key] ?: key
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
        tts?.stop()
        tts?.shutdown()
    }

    companion object {
        private val OUTDOOR_WORDS = setOf(
            "cielo", "nube", "arbol", "arboles", "pasto", "planta",
            "montana", "montanas", "calle", "carretera", "parque", "playa",
            "sky", "cloud", "tree", "grass", "mountain", "street", "road", "beach",
        )

        private val INDOOR_WORDS = setOf(
            "silla", "mesa", "habitacion", "cocina", "sofa", "cama", "televisor",
            "chair", "table", "room", "kitchen", "sofa", "bed", "tv",
        )

        private val FOOD_WORDS = setOf(
            "comida", "plato", "postre", "fruta", "verdura", "pizza", "hamburguesa",
            "food", "dish", "dessert", "fruit", "vegetable", "pizza", "burger",
        )

        private val ANIMAL_WORDS = setOf(
            "animal", "perro", "gato", "pajaro", "caballo", "vaca",
            "animal", "dog", "cat", "bird", "horse", "cow",
        )

        private val PEOPLE_WORDS = setOf(
            "persona", "personas", "hombre", "mujer", "nino", "nina", "cara",
            "person", "people", "man", "woman", "boy", "girl", "face",
        )

        private val TECH_WORDS = setOf(
            "computadora", "telefono", "celular", "pantalla", "teclado", "mouse",
            "computer", "phone", "screen", "keyboard", "mouse",
        )

        private val LABEL_TRANSLATIONS = mapOf(
            "person" to "persona",
            "people" to "personas",
            "man" to "hombre",
            "woman" to "mujer",
            "boy" to "nino",
            "girl" to "nina",
            "face" to "cara",
            "dog" to "perro",
            "cat" to "gato",
            "bird" to "pajaro",
            "car" to "auto",
            "truck" to "camion",
            "bus" to "omnibus",
            "bicycle" to "bicicleta",
            "motorcycle" to "moto",
            "tree" to "arbol",
            "sky" to "cielo",
            "cloud" to "nube",
            "grass" to "pasto",
            "flower" to "flor",
            "plant" to "planta",
            "food" to "comida",
            "fruit" to "fruta",
            "vegetable" to "verdura",
            "pizza" to "pizza",
            "burger" to "hamburguesa",
            "drink" to "bebida",
            "water" to "agua",
            "table" to "mesa",
            "chair" to "silla",
            "sofa" to "sofa",
            "bed" to "cama",
            "room" to "habitacion",
            "kitchen" to "cocina",
            "computer" to "computadora",
            "laptop" to "laptop",
            "phone" to "telefono",
            "screen" to "pantalla",
            "book" to "libro",
            "text" to "texto",
        )
    }
}
