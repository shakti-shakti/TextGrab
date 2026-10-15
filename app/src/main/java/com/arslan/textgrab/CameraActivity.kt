package com.arslan.textgrab

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.arslan.textgrab.databinding.ActivityCameraBinding
import java.io.File

class CameraActivity : AppCompatActivity() {

    companion object {
        fun photoFile(context: Context) = File(context.cacheDir, "camera/photo.jpg")
    }

    private lateinit var binding: ActivityCameraBinding
    private var controller: LifecycleCameraController? = null
    private var laserAnimator: ValueAnimator? = null
    private var currentZoom = 1.0f

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                // Hand picked image directly to MainActivity
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
                finish()
            }
        }

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            SystemBarStyle.dark(Color.TRANSPARENT),
            SystemBarStyle.dark(Color.TRANSPARENT)
        )
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.controls) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.btnClose.setOnClickListener { finish() }
        binding.btnShutter.setOnClickListener {
            binding.btnShutter.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            takePhoto()
        }

        binding.btnTorch.isVisible =
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        binding.btnTorch.addOnCheckedChangeListener { _, checked ->
            controller?.enableTorch(checked)
        }

        // Grid toggle
        binding.btnGrid.setOnClickListener {
            binding.gridOverlay.isVisible = !binding.gridOverlay.isVisible
            binding.btnGrid.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        // Zoom 1x / 2x
        binding.btnZoom1x.setOnClickListener {
            setZoom(1.0f)
        }
        binding.btnZoom2x.setOnClickListener {
            setZoom(2.0f)
        }

        // Gallery quick-pick
        binding.btnCameraGallery.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        binding.btnCameraInfo.setOnClickListener {
            Toast.makeText(this, R.string.camera_guide_hint, Toast.LENGTH_SHORT).show()
        }

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startCamera()
        else if (savedInstanceState == null) requestPermission.launch(Manifest.permission.CAMERA)

        startLaserAnimation()
    }

    private fun startLaserAnimation() {
        binding.reticleContainer.post {
            val height = binding.reticleContainer.height.toFloat()
            if (height <= 0f) return@post
            laserAnimator?.cancel()
            laserAnimator = ValueAnimator.ofFloat(0f, height - 10f).apply {
                duration = 2400L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    binding.scannerLaser.translationY = anim.animatedValue as Float
                }
                start()
            }
        }
    }

    private fun setZoom(zoom: Float) {
        currentZoom = zoom
        controller?.setZoomRatio(zoom)
        binding.btnZoom1x.setTextColor(if (zoom == 1.0f) Color.parseColor("#00E5FF") else Color.WHITE)
        binding.btnZoom2x.setTextColor(if (zoom == 2.0f) Color.parseColor("#00E5FF") else Color.WHITE)
        binding.btnZoom1x.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onDestroy() {
        laserAnimator?.cancel()
        laserAnimator = null
        super.onDestroy()
    }

    private fun startCamera() {
        val controller = LifecycleCameraController(this).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
        controller.initializationFuture.addListener({
            runCatching {
                if (!controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    controller.cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                }
            }
        }, ContextCompat.getMainExecutor(this))
        binding.preview.controller = controller
        controller.bindToLifecycle(this)
        this.controller = controller
    }

    private fun takePhoto() {
        val controller = controller ?: return
        val file = photoFile(this)
        file.parentFile?.mkdirs()
        binding.btnShutter.isEnabled = false
        binding.btnShutter.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
            binding.btnShutter.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
        }.start()

        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (callingActivity != null) {
                        setResult(RESULT_OK)
                    } else {
                        startActivity(
                            Intent(this@CameraActivity, MainActivity::class.java)
                                .setAction(MainActivity.ACTION_CAMERA_PHOTO)
                        )
                    }
                    finish()
                }

                override fun onError(e: ImageCaptureException) {
                    android.util.Log.e("TextGrab", "Photo capture failed", e)
                    file.delete()
                    binding.btnShutter.isEnabled = true
                    Toast.makeText(
                        this@CameraActivity, R.string.camera_failed, Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }
}
