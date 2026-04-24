package com.example.collectdata

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    // ── 权限 ──────────────────────────────────────────────────────────────────
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    // ── UI ────────────────────────────────────────────────────────────────────
    private lateinit var tvAccTal: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnRecord: Button
    private lateinit var overlayView: DetectionOverlayView

    // ── 传感器 ────────────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var currentAcc = FloatArray(3)
    private var currentGyro = FloatArray(3)

    // ── 业务参数 ──────────────────────────────────────────────────────────────
    private var videoFps = 30
    private var threshold = 3.0f
    private var imuFrequencyHz = 50
    private var sampleIntervalMs: Long = 20L
    private var lastImuSaveTime = 0L

    private var isRecording = false
    private var lastTriggerTime = 0L
    private val COOL_DOWN_MS = 3000L

    // ── CameraX ───────────────────────────────────────────────────────────────
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    // 帧缓冲保留60帧（约2秒@30fps），供通道A取前2秒画面
    private val frameBuffer = Collections.synchronizedList(mutableListOf<Bitmap>())

    // ── 最新帧（用于推理完成后叠加到当前预览） ──────────────────────────────
    // 用 volatile 保证线程可见性，始终持有最新一帧的尺寸
    @Volatile private var latestFrameWidth = 1
    @Volatile private var latestFrameHeight = 1

    // ── 文件流 & 位置 ─────────────────────────────────────────────────────────
    private var imuFileWriter: BufferedWriter? = null
    private var currentFileGpsWriter: BufferedWriter? = null
    private var lastLocationText: String = "0.0,0.0"
    private val imuBuffer = Collections.synchronizedList(mutableListOf<String>())

    // ── YOLO ──────────────────────────────────────────────────────────────────
    private lateinit var detector: YoloDetector

    // 通道B（实时视频流检测）参数
    private var lastRealtimeDetectMs = 0L
    private val REALTIME_INTERVAL_MS = 200L   // 【修改】500ms → 200ms，响应更快
    private var lastChannelBSaveMs = 0L
    private val CHANNEL_B_COOLDOWN_MS = 5000L
    private val isDetecting = AtomicBoolean(false)

    // ══════════════════════════════════════════════════════════════════════════
    // onCreate
    // ══════════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvAccTal    = findViewById(R.id.tvAccTal)
        tvStatus    = findViewById(R.id.tvStatus)
        btnRecord   = findViewById(R.id.btnRecord)
        overlayView = findViewById(R.id.overlayView)

        val infoPanel = findViewById<android.widget.LinearLayout>(R.id.infoPanel)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor   = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor    = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        detector = YoloDetector(this)

        if (allPermissionsGranted()) {
            startCamera()
            startLocationUpdates()
        } else {
            requestPermissions()
        }

        btnRecord.setOnClickListener { captureVideo() }

        tvStatus.setOnClickListener {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try {
                val best = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (best != null) {
                    lastLocationText = "${best.latitude},${best.longitude}"
                    tvStatus.text = "手动获取成功: $lastLocationText"
                } else {
                    tvStatus.text = "缓存为空，请去室外"
                }
            } catch (e: SecurityException) {
                tvStatus.text = "权限被拒绝"
            }
        }

        infoPanel.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 定位
    // ══════════════════════════════════════════════════════════════════════════
    private fun startLocationUpdates() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLocationText = "${location.latitude},${location.longitude}"
                if (isRecording) {
                    try {
                        currentFileGpsWriter?.write("$lastLocationText\n")
                        currentFileGpsWriter?.flush()
                    } catch (e: Exception) {
                        Log.e("GPS_WRITE", "写入失败: ${e.message}")
                    }
                }
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,     1000L, 0f, listener)
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener)
            }
        } catch (e: Exception) {
            Log.e("GPS_ERROR", "启动失败: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 相机
    // ══════════════════════════════════════════════════════════════════════════
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()

            videoCapture = VideoCapture.Builder(recorder)
                .setTargetFrameRate(android.util.Range(videoFps, videoFps))
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalyzer.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                // 【修改】toRotatedBitmap() 放到 close() 之前，旋转后宽高才是正确的
                val bitmap = imageProxy.toRotatedBitmap()
                imageProxy.close()

                // 【修改】旋转后宽高已互换，用 bitmap.width/height 而不是 imageProxy.width/height
                val w = bitmap.width
                val h = bitmap.height

                // 始终更新最新帧尺寸，供推理完成后的UI更新使用
                latestFrameWidth  = w
                latestFrameHeight = h

                // 存入帧缓冲
                val safeCopy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                frameBuffer.add(safeCopy)
                if (frameBuffer.size > 60) frameBuffer.removeAt(0)

                // ── 通道B：录制中实时推理 ──────────────────────────────────
                val now = System.currentTimeMillis()
                if (isRecording && now - lastRealtimeDetectMs > REALTIME_INTERVAL_MS
                    && isDetecting.compareAndSet(false, true)) {

                    lastRealtimeDetectMs = now
                    // 推理帧独立拷贝
                    val inferBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                    val imuNow = imuBuffer.lastOrNull() ?: ""
                    val gpsNow = lastLocationText

                    Thread {
                        try {
                            val results = detector.detect(inferBitmap)
                            val filtered = results
                                .filter { it.confidence > 0.35f }
                                .groupBy { it.label }
                                .map { (_, list) -> list.maxByOrNull { it.confidence }!! }
                                .sortedByDescending { it.confidence }
                                .take(3)

                            runOnUiThread {
                                overlayView.detections  = filtered
                                // 【修改】用最新帧尺寸，不是推理帧尺寸，避免框位置滞后
                                overlayView.frameWidth  = latestFrameWidth
                                overlayView.frameHeight = latestFrameHeight
                                overlayView.invalidate()
                            }

                            if (filtered.isNotEmpty() &&
                                System.currentTimeMillis() - lastChannelBSaveMs > CHANNEL_B_COOLDOWN_MS) {
                                lastChannelBSaveMs = System.currentTimeMillis()
                                saveChannelBEvent(filtered, imuNow, gpsNow, inferBitmap)
                                Log.d("YOLO_B", "通道B保存: ${filtered.map { "${it.label}(${"%.2f".format(it.confidence)})" }}")
                            }
                        } catch (e: Exception) {
                            Log.e("YOLO_B", "通道B推理失败: ${e.message}")
                        } finally {
                            isDetecting.set(false)
                        }
                    }.start()
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, videoCapture, imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e("CameraX", "绑定失败", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 录制控制
    // ══════════════════════════════════════════════════════════════════════════
    @SuppressLint("MissingPermission")
    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return
        val curRecording = recording

        if (curRecording != null) {
            curRecording.stop()
            recording = null
            return
        }

        val ts      = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val roadDir = File(getExternalFilesDir(null), "RoadDataCapture")
        if (!roadDir.exists()) roadDir.mkdirs()

        val videoFile         = File(roadDir, "Video_$ts.mp4")
        val fileOutputOptions = FileOutputOptions.Builder(videoFile).build()

        recording = videoCapture.output
            .prepareRecording(this, fileOutputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        imuFileWriter = File(roadDir, "IMU_$ts.txt").bufferedWriter()
                        imuFileWriter?.write("AccX,AccY,AccZ,GyroX,GyroY,GyroZ\n")
                        currentFileGpsWriter = File(roadDir, "GPS_$ts.txt").bufferedWriter()
                        currentFileGpsWriter?.write("Latitude,Longitude\n")
                        btnRecord.text = "停止"
                        isRecording    = true
                    }
                    is VideoRecordEvent.Finalize -> {
                        try {
                            imuFileWriter?.close()
                            currentFileGpsWriter?.close()
                        } catch (e: Exception) {
                            Log.e("CLOSE_ERROR", e.message ?: "")
                        }
                        imuFileWriter        = null
                        currentFileGpsWriter = null
                        btnRecord.text       = "录制"
                        isRecording          = false

                        runOnUiThread {
                            overlayView.detections = emptyList()
                            overlayView.invalidate()
                        }

                        if (!recordEvent.hasError()) {
                            Toast.makeText(baseContext, "视频与数据已统一保存", Toast.LENGTH_SHORT).show()
                        } else {
                            Log.e("VIDEO_ERROR", "录制出错: ${recordEvent.error}")
                        }
                    }
                }
            }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 通道A事件保存（IMU颠簸触发）
    // ══════════════════════════════════════════════════════════════════════════
    private fun saveEventData(
        detections:  List<DetectionResult> = emptyList(),
        imuSnapshot: List<String>          = imuBuffer.toList(),
        gpsSnapshot: String                = lastLocationText
    ) {
        val ts       = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val eventDir = File(getExternalFilesDir(null), "RoadDataCapture/Event_$ts")
        if (!eventDir.exists()) eventDir.mkdirs()

        File(eventDir, "event_imu.txt").bufferedWriter().use { out ->
            imuSnapshot.forEach { out.write("$it\n") }
        }

        // 【修改】只保存最近8帧而不是全部60帧，大幅减少IO时间
        val frames = synchronized(frameBuffer) {
            frameBuffer.takeLast(8).map { it.copy(it.config ?: Bitmap.Config.ARGB_8888, false) }
        }
        frames.forEachIndexed { i, bmp ->
            FileOutputStream(File(eventDir, "frame_$i.jpg")).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        }

        File(eventDir, "event_location.txt").writeText(gpsSnapshot)

        val gpsFile = File(getExternalFilesDir(null), "RoadDataCapture/eventgps.txt")
        FileWriter(gpsFile, true).use {
            it.write("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}, $gpsSnapshot\n")
        }

        File(eventDir, "yolo_result.txt").bufferedWriter().use { out ->
            out.write("触发时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}\n")
            out.write("触发位置: $gpsSnapshot\n")
            out.write("触发方式: IMU颠簸触发\n")
            out.write("---\n")
            if (detections.isEmpty()) {
                out.write("未检测到病害\n")
            } else {
                detections.forEach { d ->
                    out.write("病害类型: ${d.label}\n")
                    out.write("置信度: ${"%.2f".format(d.confidence)}\n")
                    out.write("位置框: [${d.boundingBox.left.toInt()},${d.boundingBox.top.toInt()}," +
                            "${d.boundingBox.right.toInt()},${d.boundingBox.bottom.toInt()}]\n")
                    out.write("IMU融合数据(预留): ${imuSnapshot.lastOrNull() ?: "无"}\n")
                    out.write("---\n")
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 通道B事件保存（视觉实时触发）
    // ══════════════════════════════════════════════════════════════════════════
    private fun saveChannelBEvent(
        detections: List<DetectionResult>,
        imuData:    String,
        gpsData:    String,
        frame:      Bitmap
    ) {
        val ts       = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val eventDir = File(getExternalFilesDir(null), "RoadDataCapture/SideEvent_$ts")
        if (!eventDir.exists()) eventDir.mkdirs()

        FileOutputStream(File(eventDir, "frame_0.jpg")).use { out ->
            frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }

        File(eventDir, "event_location.txt").writeText(gpsData)

        val gpsFile = File(getExternalFilesDir(null), "RoadDataCapture/eventgps.txt")
        FileWriter(gpsFile, true).use {
            it.write("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}, $gpsData [视觉触发]\n")
        }

        File(eventDir, "yolo_result.txt").bufferedWriter().use { out ->
            out.write("触发时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}\n")
            out.write("触发位置: $gpsData\n")
            out.write("触发方式: 视觉实时检测\n")
            out.write("当前IMU: $imuData\n")
            out.write("---\n")
            detections.forEach { d ->
                out.write("病害类型: ${d.label}\n")
                out.write("置信度: ${"%.2f".format(d.confidence)}\n")
                out.write("---\n")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 传感器回调
    // ══════════════════════════════════════════════════════════════════════════
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastImuSaveTime < sampleIntervalMs) return
        lastImuSaveTime = currentTime

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            currentAcc = event.values.clone()
        } else if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            currentGyro = event.values.clone()
        }

        val line = "${currentAcc[0]},${currentAcc[1]},${currentAcc[2]}," +
                "${currentGyro[0]},${currentGyro[1]},${currentGyro[2]}"
        imuBuffer.add(line)
        if (imuBuffer.size > 180) imuBuffer.removeAt(0)

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val accTal = abs(currentAcc[1] - 9.8f) + abs(currentAcc[2])
            tvAccTal.text = String.format("实时 acc_tal: %.2f", accTal)

            if (accTal > threshold) {
                tvAccTal.setTextColor(getColor(android.R.color.holo_red_dark))

                // ── 通道A：IMU颠簸触发 ────────────────────────────────────
                if (isRecording) {
                    val now = System.currentTimeMillis()
                    if (now - lastTriggerTime > COOL_DOWN_MS
                        && isDetecting.compareAndSet(false, true)) {
                        lastTriggerTime = now
                        val frames = synchronized(frameBuffer) {
                            frameBuffer.map { it.copy(it.config ?: Bitmap.Config.ARGB_8888, false) }
                        }
                        val imuSnapshot = imuBuffer.toList()
                        val gpsSnapshot = lastLocationText

                        Thread {
                            try {
                                val step = maxOf(1, frames.size / 8)
                                val sampled = frames.filterIndexed { i, _ -> i % step == 0 }
                                val allDetect = sampled.flatMap { bmp ->
                                    try { detector.detect(bmp) } catch (e: Exception) { emptyList() }
                                }
                                val best = allDetect
                                    .groupBy { it.label }
                                    .map { (_, list) -> list.maxByOrNull { it.confidence }!! }
                                    .sortedByDescending { it.confidence }
                                    .take(5)
                                saveEventData(best, imuSnapshot, gpsSnapshot)
                                Log.d("YOLO_A", "通道A完成，检测到 ${best.size} 个病害")
                            } catch (e: Exception) {
                                Log.e("YOLO_A", "通道A异常: ${e.message}")
                                saveEventData(emptyList(), imuSnapshot, gpsSnapshot)
                            } finally {
                                isDetecting.set(false)
                            }
                        }.start()
                    }
                }
            } else {
                tvAccTal.setTextColor(getColor(android.R.color.white))
            }
        }

        if (isRecording) {
            try {
                imuFileWriter?.write("$line\n")
            } catch (e: Exception) {
                Log.e("IMU_WRITE", "写入失败: ${e.message}")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 工具函数
    // ══════════════════════════════════════════════════════════════════════════
    /**
     * 将 ImageProxy 转为正确方向的 Bitmap。
     *
     * 关键修复：逐行拷贝 Y/U/V plane，跳过行间 padding，
     * 避免 buffer.remaining() 包含 padding 字节导致图像损坏。
     * 旋转固定 90°（Redmi K30 后置竖屏）。
     */
    private fun ImageProxy.toRotatedBitmap(): Bitmap {
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yRowStride    = yPlane.rowStride
        val uvRowStride   = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride  // 通常为 2（interleaved）

        val w = width
        val h = height
        val nv21 = ByteArray(w * h * 3 / 2)

        // 逐行拷贝 Y plane，跳过行尾 padding
        val yBuf = yPlane.buffer
        for (row in 0 until h) {
            yBuf.position(row * yRowStride)
            yBuf.get(nv21, row * w, w)
        }

        // 逐像素拷贝 UV plane（NV21：V在前U在后，交错排列）
        val vBuf = vPlane.buffer
        val uBuf = uPlane.buffer
        var uvIndex = w * h
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val pos = row * uvRowStride + col * uvPixelStride
                nv21[uvIndex++] = vBuf.get(pos)
                nv21[uvIndex++] = uBuf.get(pos)
            }
        }

        val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, w, h), 95, out)
        val decoded = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())

        // 旋转 90°，旋转后宽高互换（原 w×h → 旋转后 h×w）
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && allPermissionsGranted()) {
            startCamera()
            startLocationUpdates()
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        threshold        = prefs.getFloat("threshold", 3.0f)
        imuFrequencyHz   = prefs.getInt("frequency", 50)
        sampleIntervalMs = 1000L / imuFrequencyHz

        val newFps = prefs.getInt("video_fps", 30)
        if (newFps != videoFps) {
            videoFps = newFps
            startCamera()
        }

        findViewById<TextView>(R.id.tvConfig).text =
            "阈值: $threshold m/s² | 采样率: ${imuFrequencyHz}Hz"

        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        gyroSensor?.let  { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        detector.close()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}