package com.rwbdev.prototest

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
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
import com.canhub.cropper.CropImageView
import com.google.android.material.R.*
import com.google.android.material.R.color.material_dynamic_primary50
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
    private var cameraSummoner: View? = null;

    private lateinit var mGraphicOverlay: GraphicOverlay;

    private var rotationDegrees = 0;

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
                .setMiles(miles.text.toString().toFloatOrNull()?.toInt()?:0)
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

        cameraButton.setOnClickListener{
            hideOverlayViews()
            cameraListenerFn()
        }

        captureButton.setOnClickListener{
//            makeToast("click")
            takePhoto()
        }

        backButton.setOnClickListener{
            stopCamera()
            hideCaptureButtons()
            hideOverlayViews()
            findViewById<CropImageView>(R.id.cropImageView).visibility = View.INVISIBLE;
            hideCropButton()
        }

        val odoCamera = findViewById<Button>(R.id.btnOdometerCamera)
        odoCamera.setOnClickListener {specificCameraListenerFn(it, miles)}

        val costCamera = findViewById<Button>(R.id.btnCostCamera)
        costCamera.setOnClickListener {specificCameraListenerFn(it, cost)}

        val gallonsCamera = findViewById<Button>(R.id.btnGallonsCamera)
        gallonsCamera.setOnClickListener {specificCameraListenerFn(it, gallons)}

        cameraExecutor = Executors.newSingleThreadExecutor()

        val cropButton = findViewById<Button>(R.id.btnCrop)
        cropButton.setOnClickListener {
            var cmi : CropImageView = findViewById(R.id.cropImageView)
            val bitmap = cmi.getCroppedImage()
            if (bitmap != null) runTextRecognition(bitmap)
            cmi.visibility = View.INVISIBLE;
            hideCropButton()
        }

        val discardButton = findViewById<Button>(R.id.btnDiscard)
        discardButton.setOnClickListener {
            var cmi : CropImageView = findViewById(R.id.cropImageView)
            cmi.visibility = View.INVISIBLE;
            hideCropButton()
            hideOverlayViews()
        }
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

    // Utility Functions

    private fun makeToast(message: String) {
        Toast.makeText(
            baseContext,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    // Camera Functions

    private fun cameraListenerFn() {
        if (allPermissionsGranted()) {
            hideOverlayViews()
            startCamera()
            showCaptureButtons()
            makeToast("Camera started")
        } else {
            requestPermissions()
        }
    }

    private fun requestPermissions() {}

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
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
        findViewById<PreviewView>(R.id.viewFinder).visibility = View.GONE;
    }

    private fun resetCameraSummoner() {
        cameraSummoner?.setBackgroundColor(getThemeColor(com.google.android.material.R.attr.colorAccent))
        cameraSummoner = null;
    }

    private fun getThemeColor(attributeId: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attributeId, typedValue, true)
        return ContextCompat.getColor(this, typedValue.resourceId)
    }

    private fun takePhoto() {
        resetCameraSummoner()
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
                    rotationDegrees = image.imageInfo.rotationDegrees
                    var bm: Bitmap = imageProxyToBitmap(image)
                    startCrop(bm.rotate(rotationDegrees.toFloat()))
                    super.onCaptureSuccess(image)
                    stopCamera()
                    hideCaptureButtons()
                }

                override fun onError(exception: ImageCaptureException) {
                    super.onError(exception)
                }
            }
        )
    }

    private fun startCrop(img: Bitmap?) {
        var cmi : CropImageView = findViewById(R.id.cropImageView);
        cmi.visibility = View.VISIBLE;
        if (img == null) makeToast("bitmap was null :(")
        else {
            Log.d(TAG, "Setting crop thingy");
        }
        cmi.setImageBitmap(img);
        showCropButton()
    }

    fun Bitmap.rotate(degrees: Float): Bitmap =
        Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees) }, true)

    private fun specificCameraListenerFn(it: View?, miles: EditText?) {
        if (cameraSummoner == it) takePhoto()
        else {
            resetCameraSummoner()
            cameraListenerFn()
            cameraTextbox = miles
            cameraSummoner = it
            cameraSummoner?.setBackgroundColor(Color.parseColor("#9C27B0"))
        }
    }

    // File Functions

    private fun loadProtos(adapter: FillupAdapter) {
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

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val planeProxy = image.planes[0]
        val buffer: ByteBuffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // OCR Functions

    private fun processTextRecognitionResult(texts: Text) {
        // showOverlayViews()
        val blocks: List<Text.TextBlock> = texts.getTextBlocks()
        if (blocks.size == 0) {
            makeToast("No text found")
            return
        }
        mGraphicOverlay.clear()
        for (block in blocks) {
            for (line in block.lines) {
                var x = line.text.toFloatOrNull()
                if (x != null) {
                    cameraTextbox?.setTextKeepState(x.toString())
                    return
                }
            }
        }
    }

    private fun runTextRecognition(img: Bitmap) {
        var iv: ImageView = findViewById(R.id.ocrView)
        var image: InputImage = InputImage.fromBitmap(img, rotationDegrees)
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

    // Layout Functions

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

    private fun showCameraButton() { findViewById<Button>(R.id.btnCamera).visibility = View.VISIBLE }
    private fun hideCameraButton() { findViewById<Button>(R.id.btnCamera).visibility = View.INVISIBLE }

    private fun showCaptureButtons() {
        hideCameraButton()
        hideCropButton()
        findViewById<Button>(R.id.btnCapture).visibility = View.VISIBLE
        findViewById<Button>(R.id.btnBack).visibility = View.VISIBLE
        findViewById<PreviewView>(R.id.viewFinder).visibility = View.VISIBLE
    }

    private fun hideCaptureButtons() {
        resetCameraSummoner()
        findViewById<Button>(R.id.btnCapture).visibility = View.INVISIBLE
        findViewById<Button>(R.id.btnBack).visibility = View.INVISIBLE
        findViewById<PreviewView>(R.id.viewFinder).visibility = View.INVISIBLE
        showCameraButton()
    }

    private fun showCropButton() {
        findViewById<Button>(R.id.btnCrop).visibility = View.VISIBLE
        findViewById<Button>(R.id.btnDiscard).visibility = View.VISIBLE
        hideCameraButton()
        hideCaptureButtons()
    }
    private fun hideCropButton() {
        findViewById<Button>(R.id.btnCrop).visibility = View.INVISIBLE
        findViewById<Button>(R.id.btnDiscard).visibility = View.INVISIBLE
        showCameraButton()
    }

    // Other

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