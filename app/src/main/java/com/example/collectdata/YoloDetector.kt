package com.example.collectdata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel


data class DetectionResult(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF
)

class YoloDetector(
    private val context: Context,
    private val modelPath: String = "yolo26n_float32.tflite",   // 你的模型文件名
    private val labelPath: String = "labels.txt",
    private val inputSize: Int = 640,                    // YOLO 默认输入尺寸
    private val confThreshold: Float = 0.25f,
    private val iouThreshold: Float = 0.5f
) {
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()

    init {
        loadModel()
        loadLabels()
    }

    private fun loadModel() {
        val assetFd = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(assetFd.fileDescriptor)
        val startOffset = assetFd.startOffset
        val declaredLength = assetFd.declaredLength
        val modelBuffer = inputStream.channel.map(
            java.nio.channels.FileChannel.MapMode.READ_ONLY,
            startOffset,
            declaredLength
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

    // 核心推理函数，传入一帧 Bitmap，返回检测结果列表
    fun detect(bitmap: Bitmap): List<DetectionResult> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputBuffer = bitmapToByteBuffer(resized)

        // 输出 shape: [1, 300, 6]
        val output = Array(1) { Array(300) { FloatArray(6) } }
        interpreter?.run(inputBuffer, output)

        return parseOutput(output[0], bitmap.width.toFloat(), bitmap.height.toFloat())
    }

    private fun parseOutput(
        output: Array<FloatArray>,
        origW: Float,
        origH: Float
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()

        for (row in output) {
            // row = [x1, y1, x2, y2, confidence, class_id]
            val x1   = row[0]
            val y1   = row[1]
            val x2   = row[2]
            val y2   = row[3]
            val conf = row[4]
            val cls  = row[5].toInt()

            if (conf > 0.05f) {
                android.util.Log.d("YOLO_RAW", "conf=${"%.3f".format(conf)}, cls=$cls, box=[${row[0]},${row[1]},${row[2]},${row[3]}]")
            }
            if (conf < confThreshold) continue


            // 坐标已经是归一化
            val scaleX = origW
            val scaleY = origH

            results.add(
                DetectionResult(
                    label = labels.getOrElse(cls) { "unknown" },
                    confidence = conf,
                    boundingBox = RectF(
                        x1 * scaleX, y1 * scaleY,
                        x2 * scaleX, y2 * scaleY
                    )
                )
            )
        }
        return results  // 模型输出已经做过 NMS，不需要再做
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            buffer.putFloat((pixel and 0xFF) / 255.0f)           // B
        }
        return buffer
    }



    fun close() {
        interpreter?.close()
    }
}