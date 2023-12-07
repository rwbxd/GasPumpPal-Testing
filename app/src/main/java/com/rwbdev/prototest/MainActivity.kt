package com.rwbdev.prototest

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.protobuf.Timestamp
import com.rwbdev.prototest.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


private const val USER_PREFERENCES_NAME = "fillup"
private const val DATA_STORE_FILE_NAME = "fillups.pb"
private const val SORT_ORDER_KEY = "sort_order"

private val Context.fillupsStore: DataStore<Fillups> by dataStore(
    fileName = DATA_STORE_FILE_NAME,
    serializer = FillupSerializer
)

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var fillupAdapter: FillupAdapter
    private lateinit var fillupsProto: Fillups.Builder

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private var cameraTextbox: EditText? = null

    private lateinit var mGraphicOverlay: GraphicOverlay;

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fillupAdapter = FillupAdapter()

        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        val fillupList = findViewById<RecyclerView>(R.id.rvFillupList)
        runBlocking{
            loadProtos(fillupAdapter)
        }
        fillupList.adapter = fillupAdapter
        fillupList.layoutManager = LinearLayoutManager(this)

        ItemTouchHelper(
            object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ): Int {
                    return makeMovementFlags(
                        0,
                        ItemTouchHelper.START
                    );
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    return false
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val pos = viewHolder.adapterPosition

                    AlertDialog.Builder(viewHolder.itemView.context)
                        .setMessage("Do you want to delete this fillup?")
                        .setPositiveButton("Yes") { _, _ ->
                            fillupAdapter.removeFillup(pos)
                            fillupsProto.removeFillup(fillupsProto.fillupCount - 1 - pos)
                            runBlocking {
                                fillupsStore.updateData { fillupsProto.build() }
                            }
                        }
                        .setNegativeButton("No") {_, _ ->
                            fillupAdapter.notifyItemChanged(pos)
                        }
                        .create()
                        .show()
                }
            }
        ).attachToRecyclerView(fillupList)

        val submitButton = findViewById<Button>(R.id.btnSubmit)
        val cameraButton = findViewById<Button>(R.id.btnCamera)
        val captureButton = findViewById<Button>(R.id.btnCapture)
        val backButton = findViewById<Button>(R.id.btnBack)
        val miles = findViewById<EditText>(R.id.etMiles)
        val cost = findViewById<EditText>(R.id.etCost)
        val gallons = findViewById<EditText>(R.id.etGallons)
        mGraphicOverlay = findViewById(R.id.graphic_overlay);

        val cameraPreview = findViewById<PreviewView>(R.id.viewFinder)

        submitButton.setOnClickListener{
            val instant = Instant.now()
            val proto = Fillup.newBuilder()
                .setMiles(miles.text.toString().toIntOrNull()?:0)
                .setCost(cost.text.toString().toFloatOrNull()?:0f)
                .setGallons(gallons.text.toString().toFloatOrNull()?:0f)
                .setTime(Timestamp.newBuilder()
                    .setSeconds(instant.epochSecond)
                    .setNanos(instant.nano)
                    .build()
                ).build()
            fillupAdapter.addFillup(proto)
            fillupsProto.addFillup(proto)
            runBlocking {
                fillupsStore.updateData { fillupsProto.build() }
            }
            miles.text.clear()
            cost.text.clear()
            gallons.text.clear()
        }

        fun cameraListenerFn() {
            if (allPermissionsGranted()) {
                startCamera()
                showCameraButtons()
                makeToast("Camera started")
            } else {
                requestPermissions()
            }
        }

        cameraButton.setOnClickListener{
            hideOverlayViews()
            cameraListenerFn()
        }

        captureButton.setOnClickListener{
            makeToast("click")
            takePhoto()
        }

        backButton.setOnClickListener{
            stopCamera()
            hideCameraButtons()
        }

        val odoCamera = findViewById<Button>(R.id.btnOdometerCamera)
        odoCamera.setOnClickListener {
            cameraListenerFn()
            cameraTextbox = miles
        }
        val costCamera = findViewById<Button>(R.id.btnCostCamera)
        costCamera.setOnClickListener {
            cameraListenerFn()
            cameraTextbox = cost
        }
        val gallonsCamera = findViewById<Button>(R.id.btnGallonsCamera)
        gallonsCamera.setOnClickListener {
            cameraListenerFn()
            cameraTextbox = gallons
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun makeToast(message: String) {
        Toast.makeText(
            baseContext,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    fun loadProtos(adapter: FillupAdapter) {
        lifecycleScope.launch {
            var fillups = fillupsStore.data.firstOrNull()
            if (fillups != null) {
                val fillups = fillupsStore.data.first()
                for (fillup in fillups.fillupList) {
                    adapter.addFillup(fillup)
                }
                fillupsProto = fillups.toBuilder()
            } else {
                fillupsProto = Fillups.newBuilder()
            }
        }
    }

    private fun processTextRecognitionResult(texts: Text) {
        showOverlayViews()
        val blocks: List<Text.TextBlock> = texts.getTextBlocks()
        if (blocks.size == 0) {
            makeToast("No text found")
            return
        }
        mGraphicOverlay.clear()
        for (block in blocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    mGraphicOverlay.add(
                        TextGraphic(
                            mGraphicOverlay,
                            element,
                        )
                    )
                }
            }
        }
    }

    private fun hideOverlayViews() {
        var iv: ImageView = findViewById(R.id.ocrView);
        var tg: GraphicOverlay = findViewById(R.id.graphic_overlay);
        iv.visibility = View.GONE
//        tg.visibility = View.GONE
    }

    private fun showOverlayViews() {
        var iv: ImageView = findViewById(R.id.ocrView);
        var tg: GraphicOverlay = findViewById(R.id.graphic_overlay);
        iv.visibility = View.VISIBLE
//        tg.visibility = View.VISIBLE
    }


    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    runTextRecognition(imageProxyToBitmap(image))
                    cameraTextbox?.setTextKeepState("123")
                    super.onCaptureSuccess(image)
                    stopCamera()
                    hideCameraButtons()
                }

                override fun onError(exception: ImageCaptureException) {
                    super.onError(exception)
                }
            }
        )
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val planeProxy = image.planes[0]
        val buffer: ByteBuffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun runTextRecognition(img: Bitmap) {
        var iv: ImageView = findViewById(R.id.ocrView)
        var image: InputImage = InputImage.fromBitmap(img, 0)
        var recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(image)
            .addOnSuccessListener { texts ->
                iv.setImageBitmap(img)
                processTextRecognitionResult(texts);
                Log.d(TAG, texts.text)
            }
            .addOnFailureListener { e -> // Task failed with an exception
                e.printStackTrace()
            }
    }


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()


            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
        cameraProvider.unbindAll()
    }

    private fun showCameraButtons() {
        val cameraButton = findViewById<Button>(R.id.btnCamera)
        val captureButton = findViewById<Button>(R.id.btnCapture)
        val backButton = findViewById<Button>(R.id.btnBack)
        val cameraPreview = findViewById<PreviewView>(R.id.viewFinder)
        cameraPreview.visibility = View.VISIBLE
        backButton.visibility = View.VISIBLE
        captureButton.visibility = View.VISIBLE
        cameraButton.visibility = View.INVISIBLE
    }

    private fun hideCameraButtons() {
        val cameraButton = findViewById<Button>(R.id.btnCamera)
        val captureButton = findViewById<Button>(R.id.btnCapture)
        val backButton = findViewById<Button>(R.id.btnBack)
        val cameraPreview = findViewById<PreviewView>(R.id.viewFinder)
        cameraPreview.visibility = View.GONE
        backButton.visibility = View.INVISIBLE
        captureButton.visibility = View.INVISIBLE
        cameraButton.visibility = View.VISIBLE
    }

    private fun requestPermissions() {}

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        runBlocking{
            fillupsStore.updateData {
                fillupsProto.build()
            }
        }
    }

    companion object {
        private const val TAG = "Will's Super Cool Camera"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(
                    baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                startCamera()
            }
        }
}