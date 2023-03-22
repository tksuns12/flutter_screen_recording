package com.isvisoft.flutter_screen_recording

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.io.IOException

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding


class FlutterScreenRecordingPlugin : FlutterPlugin, PluginRegistry.ActivityResultListener,
    ActivityAware {

    private var mScreenDensity: Int = 0
    var mMediaRecorder: MediaRecorder? = null
    private var mProjectionManager: MediaProjectionManager? = null
    var mMediaProjection: MediaProjection? = null
    private var mMediaProjectionCallback: MediaProjectionCallback? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mDisplayWidth: Int = 1280
    private var mDisplayHeight: Int = 800
    private var videoName: String? = ""
    private var mFileName: String? = ""
    private var recordAudio: Boolean? = false
    private val SCREEN_RECORD_REQUEST_CODE = 333

    private lateinit var _result: MethodChannel.Result
    private lateinit var context: Context
    private var activity: Activity? = null


    @RequiresApi(Build.VERSION_CODES.R)
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        val channel = MethodChannel(binding.binaryMessenger, "flutter_screen_recording")
        channel.setMethodCallHandler { call, result ->
            run {

                if (call.method == "startRecordScreen") {
                    try {
                        _result = result
                        ForegroundService.startService(
                            context,
                            "Your screen is being recorded"
                        )
                        mProjectionManager =
                            context.applicationContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager?

                        val metrics = DisplayMetrics()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            mMediaRecorder = MediaRecorder(context.applicationContext)
                        } else {
                            @Suppress("DEPRECATION")
                            mMediaRecorder = MediaRecorder()
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val display = activity!!.display
                            display?.getRealMetrics(metrics)
                        } else {
                            val defaultDisplay = context.applicationContext.display
                            defaultDisplay?.getMetrics(metrics)
                        }
                        mScreenDensity = metrics.densityDpi
                        calculateResolution(metrics)
                        videoName = call.argument<String?>("name")
                        recordAudio = call.argument<Boolean?>("audio")

                        startRecordScreen()
                    } catch (e: Exception) {
                        println("Error onMethodCall startRecordScreen")
                        println(e.message)
                        result.success(false)
                    }
                } else if (call.method == "stopRecordScreen") {
                    try {
                        ForegroundService.stopService(context)
                        if (mMediaRecorder != null) {
                            stopRecordScreen()
                            result.success(mFileName)
                        } else {
                            result.success("")
                        }
                    } catch (e: Exception) {
                        result.success("")
                    }
                } else if (call.method == "pauseRecordScreen") {
                    try {
                        pauseRecordScreen()
                        result.success(true)
                    } catch (e: Exception) {
                        println("Error onMethodCall pauseRecordScreen")
                        println(e.message)
                        result.success(false)
                    }
                } else if (call.method == "resumeRecordScreen") {
                    try {
                        resumeRecordScreen()
                        result.success(true)
                    } catch (e: Exception) {
                        println("Error onMethodCall resumeRecordScreen")
                        println(e.message)
                        result.success(false)
                    }
                } else {
                    result.notImplemented()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun resumeRecordScreen() {
        mMediaRecorder!!.resume()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun pauseRecordScreen() {
        mMediaRecorder!!.pause()
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        ForegroundService.stopService(context)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == SCREEN_RECORD_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                mMediaProjectionCallback = MediaProjectionCallback()
                mMediaProjection = mProjectionManager?.getMediaProjection(resultCode, data!!)
                mMediaProjection?.registerCallback(mMediaProjectionCallback, null)
                mVirtualDisplay = createVirtualDisplay()
                _result.success(true)
                return true
            } else {
                ForegroundService.stopService(context)
                _result.success(false)
            }
        }
        return false
    }


    private fun calculateResolution(metrics: DisplayMetrics) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            mDisplayHeight = metrics.heightPixels
            mDisplayWidth = metrics.widthPixels
        } else {
            var maxRes = 1280.0
            if (metrics.scaledDensity >= 3.0f) {
                maxRes = 1920.0
            }
            if (metrics.widthPixels > metrics.heightPixels) {
                val rate = metrics.widthPixels / maxRes
                mDisplayWidth = maxRes.toInt()
                mDisplayHeight = (metrics.heightPixels / rate).toInt()
            } else {
                val rate = metrics.heightPixels / maxRes
                mDisplayHeight = maxRes.toInt()
                mDisplayWidth = (metrics.widthPixels / rate).toInt()
            }
        }
        println("Scaled Density")
        println(metrics.scaledDensity)
        println("Original Resolution ")
        println(metrics.widthPixels.toString() + " x " + metrics.heightPixels)
        println("Calculate Resolution ")
        println("$mDisplayWidth x $mDisplayHeight")
    }

    private fun startRecordScreen() {
        try {
            try {
                mFileName = context.externalCacheDir?.absolutePath
                mFileName += "/$videoName.mp4"
            } catch (e: IOException) {
                println("Error creating name")
                return
            }
            mMediaRecorder?.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            if (recordAudio!!) {
                mMediaRecorder?.setAudioSource(MediaRecorder.AudioSource.MIC)
                mMediaRecorder?.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                mMediaRecorder?.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            } else {
                mMediaRecorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            }
            mMediaRecorder?.setOutputFile(mFileName)
            mMediaRecorder?.setVideoSize(mDisplayWidth, mDisplayHeight)
            mMediaRecorder?.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mMediaRecorder?.setVideoEncodingBitRate(5 * mDisplayWidth * mDisplayHeight)
            mMediaRecorder?.setVideoFrameRate(30)

            mMediaRecorder?.prepare()
            mMediaRecorder?.start()
        } catch (e: IOException) {
            Log.d("--INIT-RECORDER", e.message + "")
            println("Error startRecordScreen")
            println(e.message)
        }
        val permissionIntent = mProjectionManager?.createScreenCaptureIntent()
        ActivityCompat.startActivityForResult(
            activity!!,
            permissionIntent!!,
            SCREEN_RECORD_REQUEST_CODE,
            null
        )
    }

    private fun stopRecordScreen() {
        try {
            println("stopRecordScreen")
            mMediaRecorder?.stop()
            mMediaRecorder?.reset()
            println("stopRecordScreen success")

        } catch (e: Exception) {
            Log.d("--INIT-RECORDER", e.message + "")
            println("stopRecordScreen error")
            println(e.message)

        } finally {
            stopScreenSharing()
        }
    }

    private fun createVirtualDisplay(): VirtualDisplay? {
        return mMediaProjection?.createVirtualDisplay(
            "MainActivity", mDisplayWidth, mDisplayHeight, mScreenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mMediaRecorder?.surface, null, null
        )
    }

    private fun stopScreenSharing() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay?.release()
            if (mMediaProjection != null) {
                mMediaProjection?.unregisterCallback(mMediaProjectionCallback)
                mMediaProjection?.stop()
                mMediaProjection = null
            }
            Log.d("TAG", "MediaProjection Stopped")
        }
    }

    inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            mMediaRecorder?.reset()
            mMediaProjection = null
            stopScreenSharing()
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        binding.addActivityResultListener(this)
        this.activity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        ForegroundService.stopService(context)
        this.activity = null

    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        binding.addActivityResultListener(this)
        this.activity = binding.activity
        binding.removeActivityResultListener(this)
    }

    override fun onDetachedFromActivity() {
        this.activity = null
        ForegroundService.stopService(context)
    }

}