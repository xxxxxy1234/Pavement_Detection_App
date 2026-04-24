package com.example.collectdata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DetectionResult(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF
)

class YoloDetector(
    private val context: Context,
    private val modelPath: String   = "yolo26n_float32.tflite",
    private val labelPath: String   = "labels.txt",
    private val inputSize: Int      = 640,
    private val confThreshold: Float = 0.25f,   // 推理过滤阈值（低一点，让更多候选进来）
    private val iouThreshold: Float  = 0.5f
) {
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private val lock = Any()

    // 【优化】ByteBuffer 复用，避免每次推理都重新分配内存
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(1 * inputSize * inputSize * 3 * 4)
        .apply { order(ByteOrder.nativeOrder()) }

    // 【优化】输出数组复用
    private val outputArray = Array(1) { Array(300) { FloatArray(6) } }

    init {
        loadModel()
        loadLabels()
        Log.d("YOLO_INIT", "加载了 ${labels.size} 个类别: $labels")
    }

    private fun loadModel() {
        val assetFd = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(assetFd.fileDescriptor)
        val modelBuffer = inputStream.channel.map(
            java.nio.channels.FileChannel.MapMode.READ_ONLY,
            assetFd.startOffset,
            assetFd.declaredLength
        )
        inputStream.close()
        assetFd.close()
        interpreter = Interpreter(modelBuffer, Interpreter.Options().apply {
            numThreads = 4
        })
    }

    private fun loadLabels() {
        labels = context.assets.open(labelPath)
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        return synchronized(lock) {
            val t0 = System.currentTimeMillis()

            // 确保输入是 ARGB_8888
            val argbBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
            else bitmap.copy(Bitmap.Config.ARGB_8888, false)

            // 缩放到模型输入尺寸，确保结果也是 ARGB_8888
            val resized = Bitmap.createScaledBitmap(argbBitmap, inputSize, inputSize, true).let {
                if (it.config == Bitmap.Config.ARGB_8888) it
                else it.copy(Bitmap.Config.ARGB_8888, false)
            }
            val t1 = System.currentTimeMillis()

            // 填充 ByteBuffer（复用，先 clear）
            fillInputBuffer(resized)
            val t2 = System.currentTimeMillis()

            // 推理（复用输出数组）
            val outputMap = hashMapOf<Int, Any>(0 to outputArray)
            interpreter?.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)
            val t3 = System.currentTimeMillis()

            val result = parseOutput(outputArray[0], bitmap.width.toFloat(), bitmap.height.toFloat())
            val t4 = System.currentTimeMillis()

            Log.d("YOLO_PERF", "缩放=${t1-t0}ms 预处理=${t2-t1}ms 推理=${t3-t2}ms 解析=${t4-t3}ms 总=${t4-t0}ms")

            result
        }
    }

    private fun fillInputBuffer(bitmap: Bitmap) {
        inputBuffer.clear()
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)  // R
            inputBuffer.putFloat(((pixel shr 8)  and 0xFF) / 255.0f)  // G
            inputBuffer.putFloat(( pixel          and 0xFF) / 255.0f)  // B
        }
        // 【重要】推理前 rewind，让 TFLite 从头读取
        inputBuffer.rewind()
    }

    private fun parseOutput(
        output: Array<FloatArray>,
        origW: Float,
        origH: Float
    ): List<DetectionResult> {
        // 诊断日志：打印置信度最高的3行（确认模型输出正常后可删除）
        val top3 = output.sortedByDescending { it[4] }.take(3)
        top3.forEachIndexed { i, row ->
            Log.d("YOLO_RAW", "top[$i]: x1=${row[0]} y1=${row[1]} x2=${row[2]} y2=${row[3]} conf=${row[4]} cls=${row[5]}")
        }

        val results = mutableListOf<DetectionResult>()
        for (row in output) {
            val conf = row[4]
            if (conf < confThreshold) continue

            val cls = row[5].toInt()

            // 坐标：归一化 xyxy → 原图像素坐标，clamp 到合法范围
            val x1 = (row[0] * origW).coerceIn(0f, origW)
            val y1 = (row[1] * origH).coerceIn(0f, origH)
            val x2 = (row[2] * origW).coerceIn(0f, origW)
            val y2 = (row[3] * origH).coerceIn(0f, origH)

            // 过滤无效框（宽或高为0）
            if (x2 <= x1 || y2 <= y1) continue

            results.add(
                DetectionResult(
                    label      = labels.getOrElse(cls) { "cls_$cls" },
                    confidence = conf,
                    boundingBox = RectF(x1, y1, x2, y2)
                )
            )
        }

        // 模型已做 NMS，无需再做
        return results
    }

    fun close() {
        synchronized(lock) {
            interpreter?.close()
            interpreter = null
        }
    }
}