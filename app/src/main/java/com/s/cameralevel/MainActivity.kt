package com.s.cameralevel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var viewFinder: PreviewView

    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var sensorManager: SensorManager
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private val VALUE_DRIFT = 0.05f

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = this.applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics("0")
    }


    companion object {

        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
//        supportActionBar?.setDisplayShowTitleEnabled(false)
//        supportActionBar?.hide()

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)




        // Request camera permissions
        if (allPermissionsGranted()) {

            viewFinder.post{


                displayId = viewFinder.display.displayId
                startCamera()



            }

        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()

        //viewFinder.scaleType = PreviewView.ScaleType.FIT_START

        Log.d(TAG, Build.MODEL.toString())


        if (Build.MODEL.equals("Android SDK built for x86")){
            // rotate camera 180°
            viewFinder.rotation = 180.0f;
        }

        Log.d(TAG, characteristics[SENSOR_ORIENTATION].toString())


        // sensor

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager



    }


    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        val rotation = viewFinder.display.rotation

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()


            // Select back camera as a default
            val cameraSelector =
                CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()


            // Preview
            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview
                )


                preview.setSurfaceProvider(viewFinder.surfaceProvider)

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))


    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
        }

        updateOrientationAngles()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Do something here if sensor accuracy changes.
        // You must implement this callback in your code.
    }

    override fun onResume() {
        super.onResume()

        // Get updates from the accelerometer and magnetometer at a constant rate.
        // To make batch operations more efficient and reduce power consumption,
        // provide support for delaying updates to the application.
        //
        // In this example, the sensor reporting delay is small enough such that
        // the application receives an update before the system checks the sensor
        // readings again.
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL,
                SensorManager.SENSOR_DELAY_UI
            )
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
            sensorManager.registerListener(
                this,
                magneticField,
                SensorManager.SENSOR_DELAY_NORMAL,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    override fun onPause() {
        super.onPause()

        // Don't receive any more updates from either sensor.
        sensorManager.unregisterListener(this)
    }

    fun updateOrientationAngles() {
        // Update rotation matrix, which is needed to update orientation angles.
        SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            accelerometerReading,
            magnetometerReading
        )

        // "rotationMatrix" now has up-to-date information.

        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        // "orientationAngles" now has up-to-date information.


        val orientation0 = findViewById<TextView>(R.id.orientation0)
        val orientation1 = findViewById<TextView>(R.id.orientation1)
        val orientation2 = findViewById<TextView>(R.id.orientation2)

        val mSpotTop =  findViewById<ImageView>(R.id.spot_top);
        val mSpotBottom = findViewById<ImageView>(R.id.spot_bottom);
        val mSpotLeft =  findViewById<ImageView>(R.id.spot_left);
        val mSpotRight = findViewById<ImageView>(R.id.spot_right);

//        orientation0.text = ((Math.toDegrees(orientationAngles[0].toDouble() + 360) % 360 * 10)
//            .roundToInt().toFloat() / 10).toString()
//        orientation1.text = ((Math.toDegrees(orientationAngles[1].toDouble() + 360) % 360 * 10)
//            .roundToInt().toFloat() / 10).toString()
//        orientation2.text = ((Math.toDegrees(orientationAngles[2].toDouble() + 360) % 360 * 10)
//            .roundToInt().toFloat() / 10).toString()


        // Pull out the individual values from the array.
        // Pull out the individual values from the array.
        var azimuth: Float = orientationAngles[0]
        var pitch: Float = orientationAngles[1]
        var roll: Float = orientationAngles[2]

        if (abs(pitch) < VALUE_DRIFT) {
            pitch = 0.0f;
        }
        if (abs(roll) < VALUE_DRIFT) {
            roll = 0.0f;
        }

        mSpotTop.alpha = 0f;
        mSpotBottom.alpha = 0f;
        mSpotLeft.alpha = 0f;
        mSpotRight.alpha = 0f;

        if (pitch > 0) {
            mSpotBottom.alpha = pitch
            mSpotTop.alpha = pitch
        } else {
            mSpotTop.alpha = (abs(pitch) / (Math.PI / 2)).toFloat()
            mSpotBottom.alpha = (abs(pitch) / (Math.PI / 2) ).toFloat()
        }

        mSpotLeft.alpha = (1 - (abs(roll) / (Math.PI / 2))).toFloat()
        mSpotRight.alpha = (1 - (abs(roll) / (Math.PI / 2))).toFloat()


//        if (roll > 0) {
//            mSpotLeft.alpha = roll
//        } else {
//            mSpotRight.alpha = abs(roll)
//        }


        orientation0.text = roundIt(orientationAngles[0]).toString()
        orientation1.text = roundIt(orientationAngles[1]).toString()
        orientation2.text = roundIt(orientationAngles[2]).toString()




        val rotation0 = findViewById<TextView>(R.id.rotation0)
        val rotation1 = findViewById<TextView>(R.id.rotation1)
        val rotation2 = findViewById<TextView>(R.id.rotation2)
        val rotation3 = findViewById<TextView>(R.id.rotation3)
        val rotation4 = findViewById<TextView>(R.id.rotation4)
        val rotation5 = findViewById<TextView>(R.id.rotation5)
        val rotation6 = findViewById<TextView>(R.id.rotation6)
        val rotation7 = findViewById<TextView>(R.id.rotation7)
        val rotation8 = findViewById<TextView>(R.id.rotation8)

        rotation0.text = roundIt(rotationMatrix[0]).toString()
        rotation1.text = roundIt(rotationMatrix[1]).toString()
        rotation2.text = roundIt(rotationMatrix[2]).toString()
        rotation3.text = roundIt(rotationMatrix[3]).toString()
        rotation4.text = roundIt(rotationMatrix[4]).toString()
        rotation5.text = roundIt(rotationMatrix[5]).toString()
        rotation6.text = roundIt(rotationMatrix[6]).toString()
        rotation7.text = roundIt(rotationMatrix[7]).toString()
        rotation8.text = roundIt(rotationMatrix[8]).toString()

//        Log.d(TAG, orientationAngles[0].toString())


    }


    private fun roundIt(num: Float): Float{
        val new = ((num * 100).roundToInt().toFloat() / 100)
        return new
    }




}