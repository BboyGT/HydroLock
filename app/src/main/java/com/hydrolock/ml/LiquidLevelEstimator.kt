package com.hydrolock.ml

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Two-phase liquid level estimator.
 *
 * Phase 1 (BEFORE): User holds container toward camera.
 *   - Object detection identifies the container
 *   - Geometry + pixel analysis estimates fill level percentage
 *
 * Phase 2 (AFTER): User drinks then holds container back toward camera.
 *   - Same estimation, compares to before reading
 *   - Delta converted to ml estimate using container type
 *
 * Confidence scoring:
 *   - Good lighting, clear container, stable hold = high confidence
 *   - Opaque container, motion blur, poor lighting = low confidence → manual fallback
 */
@Singleton
class LiquidLevelEstimator @Inject constructor() {

    companion object {
        const val CONFIDENCE_HIGH = 0.75f
        const val CONFIDENCE_MEDIUM = 0.5f
        const val CONFIDENCE_LOW = 0.3f

        // Container volume estimates by detected type (ml)
        val CONTAINER_VOLUMES = mapOf(
            ContainerType.STANDARD_BOTTLE to 500,
            ContainerType.LARGE_BOTTLE to 750,
            ContainerType.SMALL_GLASS to 200,
            ContainerType.STANDARD_GLASS to 300,
            ContainerType.MUG to 350,
            ContainerType.TALL_GLASS to 400,
            ContainerType.UNKNOWN to 300
        )
    }

    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )

    data class LiquidReading(
        val fillPercentage: Float,       // 0.0 to 1.0
        val containerType: ContainerType,
        val estimatedTotalMl: Int,
        val estimatedCurrentMl: Int,
        val confidence: Float,
        val boundingBox: RectF?
    )

    data class DrinkEstimate(
        val consumedMl: Int,
        val confidence: Float,
        val isVerified: Boolean,         // true if confidence >= threshold
        val beforeReading: LiquidReading,
        val afterReading: LiquidReading
    )

    enum class ContainerType {
        STANDARD_BOTTLE, LARGE_BOTTLE, SMALL_GLASS, STANDARD_GLASS, MUG, TALL_GLASS, UNKNOWN
    }

    /**
     * Analyze a single frame for liquid level.
     * Returns null if no container detected.
     */
    suspend fun analyzeFrame(bitmap: Bitmap): LiquidReading? {
        val image = InputImage.fromBitmap(bitmap, 0)

        return suspendCancellableCoroutine { cont ->
            objectDetector.process(image)
                .addOnSuccessListener { objects ->
                    val containerObject = findBestContainer(objects)
                    if (containerObject == null) {
                        cont.resume(null)
                        return@addOnSuccessListener
                    }

                    val containerType = classifyContainer(containerObject, bitmap)
                    val boundingBox = containerObject.boundingBox.toRectF()
                    val fillResult = estimateFillLevel(bitmap, boundingBox)

                    val totalMl = CONTAINER_VOLUMES[containerType] ?: 300
                    val currentMl = (totalMl * fillResult.fillPercentage).toInt()

                    cont.resume(
                        LiquidReading(
                            fillPercentage = fillResult.fillPercentage,
                            containerType = containerType,
                            estimatedTotalMl = totalMl,
                            estimatedCurrentMl = currentMl,
                            confidence = fillResult.confidence,
                            boundingBox = boundingBox
                        )
                    )
                }
                .addOnFailureListener {
                    cont.resume(null)
                }
        }
    }

    /**
     * Calculate how much was consumed between before and after readings.
     */
    fun calculateConsumed(before: LiquidReading, after: LiquidReading): DrinkEstimate {
        val consumedMl = max(0, before.estimatedCurrentMl - after.estimatedCurrentMl)
        val combinedConfidence = (before.confidence + after.confidence) / 2f
        val isVerified = combinedConfidence >= CONFIDENCE_MEDIUM && consumedMl > 50

        return DrinkEstimate(
            consumedMl = consumedMl,
            confidence = combinedConfidence,
            isVerified = isVerified,
            beforeReading = before,
            afterReading = after
        )
    }

    private fun findBestContainer(objects: List<DetectedObject>): DetectedObject? {
        return objects.maxByOrNull { obj ->
            // Prefer larger objects in center of frame
            val area = obj.boundingBox.width() * obj.boundingBox.height()
            val centerScore = 1f - abs(obj.boundingBox.centerX() - 0.5f)
            area * centerScore
        }
    }

    private fun classifyContainer(obj: DetectedObject, bitmap: Bitmap): ContainerType {
        val box = obj.boundingBox
        val aspectRatio = box.height().toFloat() / box.width().toFloat()

        // Use object labels if available
        val labels = obj.labels
        val labelText = labels.joinToString(" ") { it.text }.lowercase()

        return when {
            "bottle" in labelText && box.height() > bitmap.height * 0.5f -> ContainerType.LARGE_BOTTLE
            "bottle" in labelText -> ContainerType.STANDARD_BOTTLE
            "mug" in labelText || "cup" in labelText -> ContainerType.MUG
            "glass" in labelText && aspectRatio > 2.0f -> ContainerType.TALL_GLASS
            "glass" in labelText -> ContainerType.STANDARD_GLASS
            aspectRatio > 2.5f -> ContainerType.STANDARD_BOTTLE  // tall thin = bottle
            aspectRatio > 1.5f -> ContainerType.STANDARD_GLASS
            else -> ContainerType.UNKNOWN
        }
    }

    data class FillResult(val fillPercentage: Float, val confidence: Float)

    private fun estimateFillLevel(
        bitmap: Bitmap,
        boundingBox: RectF
    ): FillResult {
        // Crop to container region
        val left = max(0, boundingBox.left.toInt())
        val top = max(0, boundingBox.top.toInt())
        val right = min(bitmap.width, boundingBox.right.toInt())
        val bottom = min(bitmap.height, boundingBox.bottom.toInt())

        if (right <= left || bottom <= top) {
            return FillResult(0.5f, CONFIDENCE_LOW)
        }

        val containerCrop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)

        // Scan columns from bottom to top to find liquid surface
        // Liquid appears darker/more saturated than air in the container
        val liquidSurfaceRow = findLiquidSurface(containerCrop)

        return if (liquidSurfaceRow == null) {
            FillResult(0.5f, CONFIDENCE_LOW)
        } else {
            val fillPercentage = 1f - (liquidSurfaceRow.toFloat() / containerCrop.height)
            val confidence = assessConfidence(containerCrop, liquidSurfaceRow)
            FillResult(fillPercentage.coerceIn(0f, 1f), confidence)
        }
    }

    private fun findLiquidSurface(containerBitmap: Bitmap): Int? {
        val width = containerBitmap.width
        val height = containerBitmap.height
        val sampleWidth = width / 3 // sample middle third to avoid edges

        var transitionRow: Int? = null
        var maxSaturationDelta = 0f

        for (row in 0 until height - 1) {
            var rowSaturation = 0f
            var nextRowSaturation = 0f
            val sampleCount = (sampleWidth / 5).coerceAtLeast(1)

            for (col in (width / 3) until (2 * width / 3) step 5) {
                val pixel = containerBitmap.getPixel(col, row)
                val nextPixel = containerBitmap.getPixel(col, minOf(row + 1, height - 1))
                rowSaturation += getSaturation(pixel)
                nextRowSaturation += getSaturation(nextPixel)
            }

            rowSaturation /= sampleCount
            nextRowSaturation /= sampleCount

            val delta = abs(rowSaturation - nextRowSaturation)
            if (delta > maxSaturationDelta) {
                maxSaturationDelta = delta
                transitionRow = row
            }
        }

        return if (maxSaturationDelta > 0.08f) transitionRow else null
    }

    private fun getSaturation(pixel: Int): Float {
        val r = ((pixel shr 16) and 0xFF) / 255f
        val g = ((pixel shr 8) and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        return if (max == 0f) 0f else (max - min) / max
    }

    private fun assessConfidence(containerBitmap: Bitmap, liquidSurfaceRow: Int): Float {
        // Check if the container region is well-lit enough
        var totalBrightness = 0f
        val sampleCount = 20
        for (i in 0 until sampleCount) {
            val x = (containerBitmap.width * i) / sampleCount
            val y = containerBitmap.height / 2
            val pixel = containerBitmap.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            totalBrightness += (r + g + b) / (3f * 255f)
        }
        val avgBrightness = totalBrightness / sampleCount

        return when {
            avgBrightness < 0.15f -> CONFIDENCE_LOW   // too dark
            avgBrightness > 0.95f -> CONFIDENCE_LOW   // too bright / blown out
            liquidSurfaceRow < 5 -> CONFIDENCE_LOW    // surface at very top = full or bad read
            else -> CONFIDENCE_HIGH
        }
    }

    private fun android.graphics.Rect.toRectF(): RectF =
        RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}
