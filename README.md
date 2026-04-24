# CollectData — 基于安卓APP的路面病害检测系统

> 大创项目。手机采集IMU + GPS + 视频，实时YOLO推理检测路面病害，双通道触发保存事件数据。

---

## 项目概览

| 项目 | 内容 |
|------|------|
| 平台 | Android（Kotlin），测试机 Xiaomi Redmi K30，Android 12，API 31 |
| 相机框架 | CameraX（Preview + VideoCapture + ImageAnalysis 三路绑定） |
| 推理框架 | TensorFlow Lite（TFLite Interpreter） |
| 模型 | YOLO26n nano，FP32精度，`.tflite` 格式 |
| 传感器 | 加速度计（TYPE_ACCELEROMETER）+ 陀螺仪（TYPE_GYROSCOPE） |
| 定位 | LocationManager，GPS_PROVIDER + NETWORK_PROVIDER 双源 |

---

## 模型规格

| 参数 | 值 |
|------|----|
| 模型文件 | `assets/yolo26n_float32.tflite` |
| 标签文件 | `assets/labels.txt` |
| 输入 Shape | `[1, 640, 640, 3]`，FP32，RGB，归一化 0~1 |
| 输出 Shape | `[1, 300, 6]`，已做 NMS |
| 输出格式 | 每行 `[x1, y1, x2, y2, conf, cls_id]`，坐标归一化 0~1（xyxy） |
| 推理线程数 | 4（CPU，`Interpreter.Options.numThreads = 4`） |

---

## 架构：双通道检测

### 通道 A — IMU 颠簸触发

```
加速度超阈值 → 冷却3秒去重 → 快照frameBuffer（最近60帧）
→ 后台线程采样8帧推理 → saveEventData() 保存到 Event_时间戳/
```

- 触发条件：`abs(accY - 9.8) + abs(accZ) > threshold`（默认 3.0 m/s²）
- 每次触发保存：最近 8 帧图像 + IMU 片段 + GPS + YOLO 结果文本

### 通道 B — 视觉实时检测

```
每200ms取最新帧 → 后台线程推理 → 更新DetectionOverlayView
→ 检测到病害且冷却5秒 → saveChannelBEvent() 保存到 SideEvent_时间戳/
```

- 实时显示检测框（叠加在预览画面上）
- 过滤规则：`conf > 0.35`，每类保留置信度最高的，最多显示3个

### 互斥保护

```kotlin
private val isDetecting = AtomicBoolean(false)
// 通道A和通道B共用，compareAndSet(false, true) 抢占，finally 释放
```

---

## 核心文件说明

```
app/src/main/
├── java/com/example/collectdata/
│   ├── MainActivity.kt          # 主逻辑：相机、传感器、双通道调度
│   ├── YoloDetector.kt          # TFLite推理封装
│   ├── DetectionOverlayView.kt  # 检测框叠加View
│   └── SettingsActivity.kt      # 阈值/频率/FPS设置
└── assets/
    ├── yolo26n_float32.tflite   # 模型文件
    └── labels.txt               # 类别标签，每行一个
```

---

## 关键实现细节

### 1. ImageProxy → Bitmap 转换（`toRotatedBitmap`）

**踩坑**：CameraX 的 `ImageProxy` 自带 `toBitmap()` 扩展函数，自定义时必须改名，否则被库函数覆盖，旋转代码不生效。

**踩坑**：直接用 `buffer.remaining()` 拷贝 YUV plane 会包含行间 padding 字节，图像颜色损坏，移动时尤为明显。

```kotlin
private fun ImageProxy.toRotatedBitmap(): Bitmap {
    val yPlane = planes[0]; val uPlane = planes[1]; val vPlane = planes[2]
    val yRowStride = yPlane.rowStride
    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride  // 关键：通常为2（interleaved）

    val nv21 = ByteArray(width * height * 3 / 2)

    // 逐行拷贝Y，跳过行尾padding
    val yBuf = yPlane.buffer
    for (row in 0 until height) {
        yBuf.position(row * yRowStride)
        yBuf.get(nv21, row * width, width)
    }

    // 逐像素拷贝UV（NV21格式：V在前U在后）
    val vBuf = vPlane.buffer; val uBuf = uPlane.buffer
    var uvIndex = width * height
    for (row in 0 until height / 2) {
        for (col in 0 until width / 2) {
            val pos = row * uvRowStride + col * uvPixelStride
            nv21[uvIndex++] = vBuf.get(pos)
            nv21[uvIndex++] = uBuf.get(pos)
        }
    }

    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, width, height), 95, out)
    val decoded = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())

    // 后置摄像头竖屏固定旋转90°
    val matrix = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}
```

### 2. 检测框坐标系对齐

**踩坑**：旋转后 bitmap 宽高互换（原 480×640 → 旋转后 640×480），但之前传给 `DetectionOverlayView` 的是 `imageProxy.width/height`（旋转前），导致检测框位置错乱。

```kotlin
// 错误：使用 imageProxy.width/height（旋转前）
// overlayView.frameWidth = imageProxy.width

// 正确：旋转后用 bitmap.width/height
@Volatile private var latestFrameWidth = 1
@Volatile private var latestFrameHeight = 1

// imageAnalyzer 里
val bitmap = imageProxy.toRotatedBitmap()
imageProxy.close()
latestFrameWidth = bitmap.width   // 旋转后的宽
latestFrameHeight = bitmap.height  // 旋转后的高

// 推理完成回调里
overlayView.frameWidth = latestFrameWidth
overlayView.frameHeight = latestFrameHeight
```

### 3. 推理性能优化

**ByteBuffer 和输出数组复用**，避免每帧推理重新分配 4MB 内存：

```kotlin
// 类成员，init时分配一次
private val inputBuffer = ByteBuffer
    .allocateDirect(1 * 640 * 640 * 3 * 4)
    .apply { order(ByteOrder.nativeOrder()) }
private val outputArray = Array(1) { Array(300) { FloatArray(6) } }

// 每次推理
inputBuffer.clear()
// ... 填充像素 ...
inputBuffer.rewind()  // 必须在推理前rewind
```

### 4. parseOutput 注意事项

```kotlin
// 坐标是归一化xyxy（0~1），不是像素值，不是xywh
val x1 = (row[0] * origW).coerceIn(0f, origW)  // clamp防止负值越界
val y1 = (row[1] * origH).coerceIn(0f, origH)
val x2 = (row[2] * origW).coerceIn(0f, origW)
val y2 = (row[3] * origH).coerceIn(0f, origH)
if (x2 <= x1 || y2 <= y1) continue  // 过滤零面积框
```

---

## 性能数据（Redmi K30，CPU 4线程）

| 阶段 | 耗时 |
|------|------|
| 图像缩放 | 4~17ms |
| ByteBuffer 预处理 | 20~40ms |
| TFLite 推理 | 316~414ms |
| 输出解析 | 0~1ms |
| **总计** | **344~442ms** |

推理是主要瓶颈（~320ms），下一步优化方向见下节。

---

## 待优化 / 后续计划

### 推理速度优化（按优先级）

**方案1：开启 GPU Delegate**（预期推理降至 50~100ms）

```kotlin
// YoloDetector.kt loadModel() 里
import org.tensorflow.lite.gpu.GpuDelegate

val gpuDelegate = GpuDelegate()
interpreter = Interpreter(modelBuffer, Interpreter.Options().apply {
    addDelegate(gpuDelegate)
})
// 注意：需要在 build.gradle 添加依赖
// implementation 'org.tensorflow:tensorflow-lite-gpu:2.x.x'
// GPU Delegate 不支持所有算子，若报错降级回CPU
```

**方案2：缩小输入尺寸到 320×320**（推理降至约 80ms，精度会下降）

```kotlin
private val inputSize: Int = 320  // YoloDetector 里改这一行
// 需要重新导出对应输入尺寸的 .tflite 模型
```

**方案3：NNAPI Delegate**（部分高通芯片有效）

```kotlin
import org.tensorflow.lite.nnapi.NnApiDelegate
val nnApiDelegate = NnApiDelegate()
interpreter = Interpreter(modelBuffer, Interpreter.Options().apply {
    addDelegate(nnApiDelegate)
})
```

### 后续功能计划

- [ ] 服务器端管理平台（Web，地图显示病害位置 + 图片）
- [ ] APP 上传 GPS + 图像数据到服务器
- [ ] YOLO11 多传感器融合（IMU + 视觉联合决策）

---

## 数据保存结构

```
/sdcard/Android/data/com.example.collectdata/files/RoadDataCapture/
├── Video_20260424_085400.mp4       # 录像文件
├── IMU_20260424_085400.txt         # 全程IMU数据（AccX,AccY,AccZ,GyroX,GyroY,GyroZ）
├── GPS_20260424_085400.txt         # 全程GPS轨迹（Latitude,Longitude）
├── eventgps.txt                    # 所有事件GPS坐标索引（A+B通道追加写入）
├── Event_20260424_085412/          # 通道A事件（IMU触发）
│   ├── frame_0.jpg ~ frame_7.jpg   # 颠簸前最近8帧
│   ├── event_imu.txt               # 触发时刻IMU片段
│   ├── event_location.txt          # 触发时GPS坐标
│   └── yolo_result.txt             # 检测结果（类别/置信度/坐标框）
└── SideEvent_20260424_085430/      # 通道B事件（视觉触发）
    ├── frame_0.jpg                 # 触发时刻帧
    ├── event_location.txt
    └── yolo_result.txt
```

---

## 配置项（SettingsActivity）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| IMU阈值 | 3.0 m/s² | 触发通道A的加速度阈值 |
| 采样率 | 50 Hz | IMU采样频率 |
| 视频帧率 | 30 fps | 录像帧率 |

存储在 SharedPreferences（`config`），App Resume时重新读取。

---

## 已解决的主要Bug

| Bug | 现象 | 根因 | 解决方案 |
|-----|------|------|----------|
| 模型输出全为0 | conf极低，检测不到任何结果 | 图像送入模型前旋转了90°，模型看到侧置路面 | `toRotatedBitmap()` 末尾加 `Matrix.postRotate(90f)` |
| 旋转代码不生效 | debug图依然旋转 | 自定义扩展函数名 `toBitmap` 与CameraX库函数冲突，被覆盖 | 改名为 `toRotatedBitmap` |
| 图像颜色轻微损坏 | 移动时conf明显下降 | YUV plane 含行间padding，`buffer.remaining()` 多拷贝了无效字节 | 改为逐行按 `rowStride`/`pixelStride` 精确拷贝 |
| 检测框位置错乱 | 框和实际目标有偏移 | 传给overlayView的是旋转前的宽高（宽高互换了） | 改用 `@Volatile latestFrameWidth/Height` 存旋转后尺寸 |
| SIGSEGV崩溃 | App崩溃 | TFLite Interpreter非线程安全，通道A/B并发推理 | `synchronized(lock)` + `AtomicBoolean isDetecting` 互斥 |
| 通道A取帧错误 | 保存的不是颠簸时的画面 | `frameBuffer.take(4)` 取的是最旧的帧 | 改为 `frameBuffer.takeLast(N)` |