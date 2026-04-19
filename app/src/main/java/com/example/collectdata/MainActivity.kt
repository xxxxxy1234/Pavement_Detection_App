package com.example.collectdata

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
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
import android.provider.MediaStore
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
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    // 1. 权限数组 (核心：必须包含这四项)
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    // UI 控件
    private lateinit var tvAccTal: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnRecord: Button

    // 传感器相关
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var currentAcc = FloatArray(3)
    private var currentGyro = FloatArray(3)

    // 业务参数
    private var videoFps = 30
    private var threshold = 3.0f
    private var imuFrequencyHz = 50
    private var sampleIntervalMs: Long = 20L
    private var lastImuSaveTime = 0L

    private var isRecording = false
    private var lastTriggerTime = 0L
    private val COOL_DOWN_MS = 3000L

    // CameraX 相关
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val frameBuffer = Collections.synchronizedList(mutableListOf<Bitmap>())

    // 文件流与位置相关
    private var imuFileWriter: BufferedWriter? = null
    private var currentFileGpsWriter: BufferedWriter? = null
    private var lastLocationText: String = "0.0,0.0"
    private val imuBuffer = Collections.synchronizedList(mutableListOf<String>())


    private lateinit var detector: YoloDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 绑定 UI (保持原样)
        tvAccTal = findViewById(R.id.tvAccTal)
        tvStatus = findViewById(R.id.tvStatus)
        btnRecord = findViewById(R.id.btnRecord)

        // 绑定顶部面板作为设置入口
        val infoPanel = findViewById<android.widget.LinearLayout>(R.id.infoPanel)

        // 2. 初始化传感器 (保持原样)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)


        detector = YoloDetector(this)

        // 3. 核心：检查权限 (保持原样)
        if (allPermissionsGranted()) {
            startCamera()
            startLocationUpdates()
        } else {
            requestPermissions()
        }

        // 4. 录制按钮逻辑 (保持原样)
        btnRecord.setOnClickListener {
            captureVideo()
        }

        // 5. 保留你原有的：点击 tvStatus 手动获取 GPS 缓存
        tvStatus.setOnClickListener {
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            try {
                val lastGps = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                val lastNetwork = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                val bestLocation = lastGps ?: lastNetwork
                if (bestLocation != null) {
                    lastLocationText = "${bestLocation.latitude},${bestLocation.longitude}"
                    tvStatus.text = "手动获取成功: $lastLocationText"
                } else {
                    tvStatus.text = "缓存为空，请去室外"
                }
            } catch (e: SecurityException) {
                tvStatus.text = "权限被拒绝"
            }
        }

        // 6. 【核心新增】点击顶部半透明面板进入设置页面
        infoPanel.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun startLocationUpdates() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLocationText = "${location.latitude},${location.longitude}"
                Log.d("GPS_DEBUG", "坐标更新: $lastLocationText")

                if (isRecording) {
                    try {
                        currentFileGpsWriter?.write("$lastLocationText\n")
                        currentFileGpsWriter?.flush()
                    } catch (e: Exception) {
                        Log.e("GPS_WRITE", "写入失败: ${e.message}")
                    }
                }
            }
            override fun onProviderEnabled(provider: String) { Log.d("GPS_DEBUG", "提供者开启: $provider") }
            override fun onProviderDisabled(provider: String) { Log.d("GPS_DEBUG", "提供者关闭: $provider") }
        }

        try {
            // 同时请求 GPS 和网络定位
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, locationListener)
                Log.d("GPS_DEBUG", "定位监听已成功启动")
            }
        } catch (e: Exception) {
            Log.e("GPS_ERROR", "启动失败: ${e.message}")
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 1. 预览配置
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)
            }

            // 2. 录制器配置
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()

            //使用 Builder 模式来设置帧率

            videoCapture = VideoCapture.Builder(recorder)
                .setTargetFrameRate(android.util.Range(videoFps, videoFps))
                .build()


            // 3. 图像分析器（保留你原有的存图逻辑）
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalyzer.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                val bitmap = imageProxy.toBitmap()
                frameBuffer.add(bitmap)
                if (frameBuffer.size > 10) frameBuffer.removeAt(0)
                imageProxy.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                // 绑定所有 UseCase：预览、视频录制、图像分析
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    videoCapture,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e("CameraX", "绑定失败", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return
        val curRecording = recording

        // 1. 如果正在录制，点击则停止
        if (curRecording != null) {
            curRecording.stop()
            recording = null
            return
        }

        // 2. 统一准备私有目录路径
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val roadDir = File(getExternalFilesDir(null), "RoadDataCapture")
        if (!roadDir.exists()) roadDir.mkdirs()

        // 3. 创建视频文件对象（直接指向私有目录下的 .mp4）
        val videoFile = File(roadDir, "Video_$ts.mp4")

        // 4. 【关键修改】使用 FileOutputOptions 替代原来的 MediaStore 逻辑
        val fileOutputOptions = FileOutputOptions.Builder(videoFile).build()

        // 5. 启动录制
        recording = videoCapture.output
            .prepareRecording(this, fileOutputOptions) // 使用上面定义的私有文件配置
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        // 初始化 IMU 文件流（路径保持一致）
                        imuFileWriter = File(roadDir, "IMU_$ts.txt").bufferedWriter()
                        imuFileWriter?.write("AccX,AccY,AccZ,GyroX,GyroY,GyroZ\n")

                        // 初始化 GPS 文件流
                        currentFileGpsWriter = File(roadDir, "GPS_$ts.txt").bufferedWriter()
                        currentFileGpsWriter?.write("Latitude,Longitude\n")

                        btnRecord.text = "停止"
                        isRecording = true
                    }
                    is VideoRecordEvent.Finalize -> {
                        try {
                            imuFileWriter?.close()
                            currentFileGpsWriter?.close()
                        } catch (e: Exception) {
                            Log.e("CLOSE_ERROR", e.message ?: "")
                        }
                        imuFileWriter = null
                        currentFileGpsWriter = null

                        btnRecord.text = "录制"
                        isRecording = false

                        if (!recordEvent.hasError()) {
                            Toast.makeText(baseContext, "视频与数据已统一保存", Toast.LENGTH_SHORT).show()
                        } else {
                            Log.e("VIDEO_ERROR", "录制出错: ${recordEvent.error}")
                        }
                    }
                }
            }
    }

    private fun saveEventData() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val eventDir = File(getExternalFilesDir(null), "RoadDataCapture/Event_$ts")
        if (!eventDir.exists()) eventDir.mkdirs()

        // 保存 IMU 片段
        File(eventDir, "event_imu.txt").bufferedWriter().use { out ->
            imuBuffer.forEach { out.write("$it\n") }
        }

        // 保存 4 张图片
        val lastFrames = frameBuffer.takeLast(4)
        lastFrames.forEachIndexed { i, bmp ->
            val out = FileOutputStream(File(eventDir, "frame_$i.jpg"))
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.close()
        }

        // 记录事件坐标
        val gpsFile = File(getExternalFilesDir(null), "RoadDataCapture/eventgps.txt")
        FileWriter(gpsFile, true).use { it.write("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}, $lastLocationText\n") }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return


        val currentTime = System.currentTimeMillis()
        if (currentTime - lastImuSaveTime < sampleIntervalMs) return // 还没到频率间隔，跳过
        lastImuSaveTime = currentTime

        // 1. 分别更新加速度和陀螺仪的最新数值
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            currentAcc = event.values.clone()
        } else if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            currentGyro = event.values.clone()
        }

        // 2. 构造 IMU 数据行并维护缓冲区
        val line = "${currentAcc[0]},${currentAcc[1]},${currentAcc[2]},${currentGyro[0]},${currentGyro[1]},${currentGyro[2]}"
        imuBuffer.add(line)
        if (imuBuffer.size > 180) imuBuffer.removeAt(0)

        // 3. 核心逻辑：处理加速度变化和 UI 颜色切换
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val accTal = abs(currentAcc[1] - 9.8f) + abs(currentAcc[2])
            android.util.Log.d("ACC_DEBUG", "accTal=%.2f threshold=$threshold isRecording=$isRecording".format(accTal))
            tvAccTal.text = String.format("实时 acc_tal: %.2f", accTal)

            // --- 修复红字逻辑的核心 ---
            if (accTal > threshold) {
                // 超过门限，变红
                tvAccTal.setTextColor(getColor(android.R.color.holo_red_dark))

                // 触发事件保存（仅在录制中且过了冷却时间）
                if (isRecording) {
                    val now = System.currentTimeMillis()
                    if (now - lastTriggerTime > COOL_DOWN_MS) {
                        lastTriggerTime = now
                        android.util.Log.d("YOLO", "准备触发推理，lastFrame=${frameBuffer.lastOrNull() != null}")
                        saveEventData()
                        val latestFrame = frameBuffer.lastOrNull()
                        if (latestFrame != null) {
                            Thread {
                                try {
                                    android.util.Log.d("YOLO", "开始推理...")
                                    val detections = detector.detect(latestFrame)
                                    android.util.Log.d("YOLO", "推理完成，检测到 ${detections.size} 个目标")
                                    detections.forEach {
                                        android.util.Log.d("YOLO", "检测到: ${it.label}, 置信度: ${"%.2f".format(it.confidence)}")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("YOLO", "推理异常: ${e.message}")
                                }
                            }.start()
                        } else {
                            android.util.Log.w("YOLO", "frameBuffer 为空，无法推理")
                        }
                    }
                }
            } else {
                // 【关键补充】数值恢复正常，变回白色
                tvAccTal.setTextColor(getColor(android.R.color.white))
            }
        }

        // 4. 如果正在录制视频，同步写入全局 IMU 文件
        if (isRecording) {
            try {
                imuFileWriter?.write(line + "\n")
            } catch (e: Exception) {
                Log.e("IMU_WRITE", "写入失败: ${e.message}")
            }
        }
    }
    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val nv21 = ByteArray(yBuffer.remaining() + uBuffer.remaining() + vBuffer.remaining())
        yBuffer.get(nv21, 0, yBuffer.remaining())
        vBuffer.get(nv21, yBuffer.remaining(), vBuffer.remaining())
        uBuffer.get(nv21, yBuffer.remaining() + vBuffer.remaining(), uBuffer.remaining())
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 100, out)
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && allPermissionsGranted()) {
            startCamera()
            startLocationUpdates()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从“仓库”读取最新设置
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        threshold = prefs.getFloat("threshold", 3.0f)
        imuFrequencyHz = prefs.getInt("frequency", 50)
        sampleIntervalMs = 1000L / imuFrequencyHz

        // 检查视频帧率是否被修改
        val newFps = prefs.getInt("video_fps", 30)
        if (newFps != videoFps) {
            videoFps = newFps
            startCamera() // 帧率变了，重新启动相机绑定
        }

        // 同步更新 UI 上的参数文字
        findViewById<TextView>(R.id.tvConfig).text = "阈值: $threshold m/s² | 采样率: ${imuFrequencyHz}Hz"

        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    override fun onPause() { super.onPause(); sensorManager.unregisterListener(this) }


    override fun onDestroy() {
        super.onDestroy()
        // 释放 YOLO 模型占用的内存（对应方案第④步）
        detector.close()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}