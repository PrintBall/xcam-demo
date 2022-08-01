package com.printball.demo.camerax.fragments

import com.printball.demo.camerax.R
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import coil.fetch.VideoFrameUriFetcher
import coil.load
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import com.printball.demo.camerax.analyzer.LuminosityAnalyzer
import com.printball.demo.camerax.databinding.FragmentCameraBinding
import com.printball.demo.camerax.enums.CameraTimer
import com.printball.demo.camerax.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutionException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.properties.Delegates

class CameraFragment : BaseFragment<FragmentCameraBinding>(R.layout.fragment_camera) {

    private val displayManager by lazy { requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }

    private val prefs by lazy { SharedPrefsManager.newInstance(requireContext()) }

    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null

    override val binding: FragmentCameraBinding by lazy { FragmentCameraBinding.inflate(layoutInflater) }

    private var displayId = -1

    private var camera:Camera? = null
    private var mCameraInfo:CameraInfo? = null
    private var mCameraControl:CameraControl? = null

    //选择镜头
    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA
    private var hdrCameraSelector: CameraSelector? = null

    //选择闪光灯模式（开启、自动、关闭）
    private var flashMode by Delegates.observable(FLASH_MODE_OFF) { _, _, new ->
        binding.btnFlash.setImageResource(
            when (new) {
                FLASH_MODE_ON -> R.drawable.ic_flash_on
                FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
                else -> R.drawable.ic_flash_off
            }
        )
    }

    //设置参照线
    private var hasGrid = false

    //设置HDR
    private var hasHdr = false

    //设置计时器
    private var selectedTimer = CameraTimer.OFF

    companion object {
        private const val TAG = "X-CAMERA"

        const val KEY_FLASH = "sPrefFlashCamera"
        const val KEY_GRID = "sPrefGridCamera"
        const val KEY_HDR = "sPrefHDR"

        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }

    /**
     * 显示监听器
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        @SuppressLint("UnsafeExperimentalUsageError", "UnsafeOptInUsageError")
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                preview?.targetRotation = view.display.rotation
                imageCapture?.targetRotation = view.display.rotation
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        flashMode = prefs.getInt(KEY_FLASH, FLASH_MODE_OFF)
        hasGrid = prefs.getBoolean(KEY_GRID, false)
        hasHdr = prefs.getBoolean(KEY_HDR, false)
        initViews()

        displayManager.registerDisplayListener(displayListener, null)

        binding.run {
            viewFinder.addOnAttachStateChangeListener(object :
                View.OnAttachStateChangeListener {
                override fun onViewDetachedFromWindow(v: View) =
                    displayManager.registerDisplayListener(displayListener, null)

                override fun onViewAttachedToWindow(v: View) =
                    displayManager.unregisterDisplayListener(displayListener)
            })
            root.setOnClickListener{

            }
            btnTakePicture.setOnClickListener { takePicture() }
            btnGallery.setOnClickListener { openPreview() }
            btnSwitchCamera.setOnClickListener { toggleCamera() }
            btnTimer.setOnClickListener { selectTimer() }
            btnGrid.setOnClickListener { toggleGrid() }
            btnFlash.setOnClickListener { selectFlash() }
            btnHdr.setOnClickListener { toggleHdr() }
            btnTimerOff.setOnClickListener { closeTimerAndSelect(CameraTimer.OFF) }
            btnTimer3.setOnClickListener { closeTimerAndSelect(CameraTimer.S3) }
            btnTimer10.setOnClickListener { closeTimerAndSelect(CameraTimer.S10) }
            btnFlashOff.setOnClickListener { closeFlashAndSelect(FLASH_MODE_OFF) }
            btnFlashOn.setOnClickListener { closeFlashAndSelect(FLASH_MODE_ON) }
            btnFlashAuto.setOnClickListener { closeFlashAndSelect(FLASH_MODE_AUTO) }
            btnExposure.setOnClickListener { flExposure.visibility = View.VISIBLE }
            flExposure.setOnClickListener { flExposure.visibility = View.GONE }

            //左右滑动功能切换（录像、照相）
            val swipeGestures = SwipeGestureDetector().apply {
                setSwipeCallback(right = {
                    Navigation.findNavController(view).navigate(R.id.action_camera_to_video)
                })
            }

        }
    }

    //初始化
    private fun initViews() {
        binding.btnGrid.setImageResource(if (hasGrid) R.drawable.ic_grid_on else R.drawable.ic_grid_off)
        binding.groupGridLines.visibility = if (hasGrid) View.VISIBLE else View.GONE
        adjustInsets()
    }

    private fun adjustInsets() {
        activity?.window?.fitSystemWindows()
        binding.btnTakePicture.onWindowInsets { view, windowInsets ->
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                view.bottomMargin =
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            } else {
                view.endMargin = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).right
            }
        }
        binding.btnTimer.onWindowInsets { view, windowInsets ->
            view.topMargin = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top
        }
        binding.llTimerOptions.onWindowInsets { view, windowInsets ->
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                view.topPadding =
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            } else {
                view.startPadding =
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).left
            }
        }
        binding.llFlashOptions.onWindowInsets { view, windowInsets ->
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                view.topPadding =
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            } else {
                view.startPadding =
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).left
            }
        }
    }

    //切换摄像头
    @SuppressLint("RestrictedApi")
    fun toggleCamera() = binding.btnSwitchCamera.toggleButton(
        flag = lensFacing == CameraSelector.DEFAULT_BACK_CAMERA,
        rotationAngle = 180f,
        firstIcon = R.drawable.ic_outline_camera_rear,
        secondIcon = R.drawable.ic_outline_camera_front,
    ) {
        lensFacing = if (it) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        startCamera()
    }

    //打开预览
    private fun openPreview() {
        if (getMedia().isEmpty()) return
        view?.let { Navigation.findNavController(it).navigate(R.id.action_camera_to_preview) }
    }

    //选择计时器
    private fun selectTimer() = binding.llTimerOptions.circularReveal(binding.btnTimer)

    private fun closeTimerAndSelect(timer: CameraTimer) =
        binding.llTimerOptions.circularClose(binding.btnTimer) {
            selectedTimer = timer
            binding.btnTimer.setImageResource(
                when (timer) {
                    CameraTimer.S3 -> R.drawable.ic_timer_3
                    CameraTimer.S10 -> R.drawable.ic_timer_10
                    CameraTimer.OFF -> R.drawable.ic_timer_off
                }
            )
        }

    private fun selectFlash() = binding.llFlashOptions.circularReveal(binding.btnFlash)

    private fun closeFlashAndSelect(@FlashMode flash: Int) =
        binding.llFlashOptions.circularClose(binding.btnFlash) {
            flashMode = flash
            binding.btnFlash.setImageResource(
                when (flash) {
                    FLASH_MODE_ON -> R.drawable.ic_flash_on
                    FLASH_MODE_OFF -> R.drawable.ic_flash_off
                    else -> R.drawable.ic_flash_auto
                }
            )
            imageCapture?.flashMode = flashMode
            prefs.putInt(KEY_FLASH, flashMode)
        }

    private fun toggleGrid() {
        binding.btnGrid.toggleButton(
            flag = hasGrid,
            rotationAngle = 180f,
            firstIcon = R.drawable.ic_grid_off,
            secondIcon = R.drawable.ic_grid_on,
        ) { flag ->
            hasGrid = flag
            prefs.putBoolean(KEY_GRID, flag)
            binding.groupGridLines.visibility = if (flag) View.VISIBLE else View.GONE
        }
    }

    private fun toggleHdr() {
        binding.btnHdr.toggleButton(
            flag = hasHdr,
            rotationAngle = 360f,
            firstIcon = R.drawable.ic_hdr_off,
            secondIcon = R.drawable.ic_hdr_on,
        ) { flag ->
            hasHdr = flag
            prefs.putBoolean(KEY_HDR, flag)
            startCamera()
        }
    }

    override fun onPermissionGranted() {
        // Each time apps is coming to foreground the need permission check is being processed
        binding.viewFinder.let { vf ->
            vf.post {
                // Setting current display ID
                displayId = vf.display.displayId
                startCamera()
                lifecycleScope.launch(Dispatchers.IO) {
                    // Do on IO Dispatcher
                    setLastPictureThumbnail()
                }
            }
        }
    }

    private fun setLastPictureThumbnail() = binding.btnGallery.post {
        getMedia().firstOrNull() // check if there are any photos or videos in the app directory
            ?.let { setGalleryThumbnail(it.uri) } // preview the last one
            ?: binding.btnGallery.setImageResource(R.drawable.ic_no_picture) // or the default placeholder
    }

    private fun startCamera() {
        // This is the CameraX PreviewView where the camera will be rendered
        val viewFinder = binding.viewFinder

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
            } catch (e: InterruptedException) {
                Toast.makeText(requireContext(), "Error starting camera", Toast.LENGTH_SHORT).show()
                return@addListener
            } catch (e: ExecutionException) {
                Toast.makeText(requireContext(), "Error starting camera", Toast.LENGTH_SHORT).show()
                return@addListener
            }

            val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
            val aspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
            val rotation = viewFinder.display.rotation
            val localCameraProvider = cameraProvider?: throw IllegalStateException("Camera initialization failed.")

            preview = Preview.Builder()
                .setTargetAspectRatio(aspectRatio)
                .setTargetRotation(rotation)
                .build()

            imageCapture = Builder()
                .setCaptureMode(CAPTURE_MODE_MINIMIZE_LATENCY) //选择高速低画质
                .setFlashMode(flashMode)
                .setTargetAspectRatio(aspectRatio)
                .setTargetRotation(rotation)
                .build()

            checkForHdrExtensionAvailability()

            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(aspectRatio)
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)// 保证传入到图片分析的是最新帧
                .build()
                .also { setLuminosityAnalyzer(it) }

            // 解绑和绑定
            localCameraProvider.unbindAll()
            bindToLifecycle(localCameraProvider, viewFinder)

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    //弄HDR的，未测试，没设备也不确定这个能不能用，直接照着谷歌demo来的
    private fun checkForHdrExtensionAvailability() {
        val extensionsManagerFuture = ExtensionsManager.getInstanceAsync(
            requireContext(), cameraProvider ?: return,
        )
        extensionsManagerFuture.addListener(
            {
                val extensionsManager = extensionsManagerFuture.get() ?: return@addListener
                val cameraProvider = cameraProvider ?: return@addListener
                val isAvailable = extensionsManager.isExtensionAvailable(lensFacing, ExtensionMode.HDR)
                if (!isAvailable) {
                    binding.btnHdr.visibility = View.GONE
                } else if (hasHdr) {
                    binding.btnHdr.visibility = View.VISIBLE
                    hdrCameraSelector =
                        extensionsManager.getExtensionEnabledCameraSelector(lensFacing, ExtensionMode.HDR)
                }
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    private fun setLuminosityAnalyzer(imageAnalysis: ImageAnalysis) {
       val analyzerThread = HandlerThread("LuminosityAnalysis").apply { start() }
        imageAnalysis.setAnalyzer(
            ThreadExecutor(Handler(analyzerThread.looper)),
            LuminosityAnalyzer()
        )
    }

    private fun bindToLifecycle(localCameraProvider: ProcessCameraProvider, viewFinder: PreviewView) {
        try {
            val camera=localCameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                hdrCameraSelector ?: lensFacing,
                preview,
                imageCapture,
                imageAnalyzer,
            )
            mCameraInfo = camera?.cameraInfo
            mCameraControl = camera?.cameraControl
            camera.run {
                // 初始化
                cameraInfo.exposureState.run {
                    val lower = exposureCompensationRange.lower
                    val upper = exposureCompensationRange.upper

                    binding.sliderExposure.run {
                        valueFrom = lower.toFloat()
                        valueTo = upper.toFloat()
                        stepSize = 1f
                        value = exposureCompensationIndex.toFloat()

                        addOnChangeListener { _, value, _ ->
                            cameraControl.setExposureCompensationIndex(value.toInt())
                        }
                    }
                }
            }

            preview?.setSurfaceProvider(viewFinder.surfaceProvider)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind use cases", e)
        }
    }

    /**
     *  判断宽高比
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    @Suppress("NON_EXHAUSTIVE_WHEN")
    private fun takePicture() = lifecycleScope.launch(Dispatchers.Main) {
        // Show a timer based on user selection
        when (selectedTimer) {
            CameraTimer.S3 -> for (i in 3 downTo 1) {
                binding.tvCountDown.text = i.toString()
                delay(1000)
            }
            CameraTimer.S10 -> for (i in 10 downTo 1) {
                binding.tvCountDown.text = i.toString()
                delay(1000)
            }
        }
        binding.tvCountDown.text = ""
        captureImage()
    }

    //照相后的快门效果
    private fun shutter(){
        binding.root.postDelayed({
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                binding.root.foreground = ColorDrawable(0xff000000.toInt())
                binding.root.postDelayed({
                    binding.root.foreground=null
                },0L)
            }
        },0L)
    }

    private fun captureImage() {
        val localImageCapture = imageCapture ?: throw IllegalStateException("Camera initialization failed.")

        val metadata = Metadata().apply {
            //使用前置摄像头时水平反转
            isReversedHorizontal = lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA
        }
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
       val outputOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, System.currentTimeMillis())
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, outputDirectory)
            }

            val contentResolver = requireContext().contentResolver

            val contentUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            OutputFileOptions.Builder(contentResolver, contentUri, contentValues)
        } else {
            File(outputDirectory).mkdirs()
            val file = File(outputDirectory, "${System.currentTimeMillis()}.jpg")

            OutputFileOptions.Builder(file)
        }.setMetadata(metadata).build()

        localImageCapture.takePicture(
            outputOptions,
            requireContext().mainExecutor(),
            object : OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: OutputFileResults) {
                    outputFileResults.savedUri
                        ?.let { uri ->
                            setGalleryThumbnail(uri)
                            Log.d(TAG, "Photo saved in $uri")
                        }
                        ?: setLastPictureThumbnail()
                }

                override fun onError(exception: ImageCaptureException) {
                    val msg = "Photo capture failed: ${exception.message}"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    Log.e(TAG, msg)
                    exception.printStackTrace()
                }
            }
        )
        shutter()
    }

    private fun setGalleryThumbnail(savedUri: Uri?) = binding.btnGallery.load(savedUri) {
        placeholder(R.drawable.ic_no_picture)
        transformations(CircleCropTransformation())
        listener(object : ImageRequest.Listener {
            override fun onError(request: ImageRequest, throwable: Throwable) {
                super.onError(request, throwable)
                binding.btnGallery.load(savedUri) {
                    placeholder(R.drawable.ic_no_picture)
                    transformations(CircleCropTransformation())
                    fetcher(VideoFrameUriFetcher(requireContext()))
                }
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onBackPressed() = when {
        binding.llTimerOptions.visibility == View.VISIBLE -> binding.llTimerOptions.circularClose(binding.btnTimer)
        binding.llFlashOptions.visibility == View.VISIBLE -> binding.llFlashOptions.circularClose(binding.btnFlash)
        else -> requireActivity().finish()
    }

}
