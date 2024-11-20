package com.cihadseker.ibanrecognition

import android.Manifest
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import com.google.mlkit.nl.entityextraction.IbanEntity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d("Permission", "Permission granted!")
            } else {
                Toast.makeText(this, "Permission denied!", Toast.LENGTH_SHORT).show()
            }
        }

    private val pickImageResultLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, it)
                processImage(bitmap)
            }
        }

    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var preview: Preview
    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var cameraSelector: CameraSelector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IbanRecognitionApp()
        }

        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    @Composable
    fun IbanRecognitionApp() {
        var imageBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            imageBitmap?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = "Selected Image", modifier = Modifier.fillMaxWidth())
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = {
                    previewViewRef?.let { previewView ->
                        startCamera(previewView)
                    }
                }) {
                    Text(text = "Kamerayı Aç")
                }
                Button(onClick = {
                    pickImageResultLauncher.launch("image/*")
                }) {
                    Text(text = "Galeriden Seç")
                }
            }

            Spacer(modifier = Modifier.height(64.dp))

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                factory = { context ->
                    PreviewView(context).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                },
                update = { previewView ->
                    previewViewRef = previewView
                }
            )
        }

    }

    private fun startCamera(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            preview = Preview.Builder().build()
            imageAnalysis = ImageAnalysis.Builder().build()

            imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                processImageProxy(imageProxy)
            }

            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

                preview.surfaceProvider = previewView.surfaceProvider

            } catch (e: Exception) {
                Log.e("Camera", "Kamera başlatılamadı: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        mediaImage?.let {
            val image = InputImage.fromMediaImage(it, imageProxy.imageInfo.rotationDegrees)

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val extractedText = visionText.text
                    Log.d("MLKit", "Metin: $extractedText")
                    processTextForIban(extractedText)
                }
                .addOnFailureListener { e ->
                    Log.e("MLKit", "Metin tanıma başarısız: ${e.message}")
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    private fun processImage(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val extractedText = visionText.text
                Log.d("MLKit", "Metin: $extractedText")
                processTextForIban(extractedText)
            }
            .addOnFailureListener { e ->
                Log.e("MLKit", "Metin tanıma başarısız: ${e.message}")
            }
    }

    private fun processTextForIban(text: String) {
        val extractor = EntityExtraction.getClient(
            EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build()
        )

        extractor.downloadModelIfNeeded()
            .addOnSuccessListener {
                val params = EntityExtractionParams.Builder(text).build()
                extractor.annotate(params)
                    .addOnSuccessListener { entityAnnotations ->
                        entityAnnotations.forEach { annotation ->
                            annotation.entities.filterIsInstance<IbanEntity>().forEach {
                                Log.d("MLKit2", "Bulunan IBAN: ${it.iban}")
                                Log.d("MLKit2", "Ülke Kodu: ${it.ibanCountryCode}")
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("MLKit3", "IBAN çıkarımı başarısız: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                Log.e("MLKit3", "Model indirme başarısız: ${e.message}")
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider.unbindAll()
    }
}