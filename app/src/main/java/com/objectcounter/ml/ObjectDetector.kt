package com.objectcounter.ml

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

data class Detection(
    val label: String,
    val confidence: Float,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

/**
 * Counts objects by detecting dominant background color and treating everything else as objects
 * Uses HSV color space with adaptive background detection
 */
class ObjectDetector {
    
    companion object {
        private const val MIN_AREA = 500
        private const val COLOR_TOLERANCE = 25 // How different from background to be considered object
        
        init {
            OpenCVLoader.initLocal()
        }
    }

    fun detect(imageProxy: ImageProxy): List<Detection> {
        val bitmap = imageProxyToBitmap(imageProxy)
        return detectBitmap(bitmap)
    }
    
    fun detectBitmap(bitmap: Bitmap): List<Detection> {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        
        // Convert to HSV color space
        val hsv = Mat()
        Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_RGB2HSV)
        
        // Blur to reduce noise before analysis
        Imgproc.GaussianBlur(hsv, hsv, Size(7.0, 7.0), 0.0)
        
        // Find dominant (background) color using histogram
        val bgColor = findDominantColor(hsv)
        
        // Create mask: pixels that are NOT similar to background = objects
        val binary = Mat()
        createNonBackgroundMask(hsv, bgColor, binary)
        
        hsv.release()
        
        // Moderate closing to fill internal gaps but not merge touching objects
        val bigCloseKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, bigCloseKernel, Point(-1.0, -1.0), 1)
        
        // Opening to remove noise - helps separate lightly touching objects
        val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, openKernel, Point(-1.0, -1.0), 1)
        
        // Distance transform
        val dist = Mat()
        Imgproc.distanceTransform(binary, dist, Imgproc.DIST_L2, 5)
        
        // Normalize distance
        Core.normalize(dist, dist, 0.0, 255.0, Core.NORM_MINMAX)
        dist.convertTo(dist, CvType.CV_8U)
        
        // Find local maxima - SMALLER kernel to detect peaks in touching objects
        val dilated = Mat()
        val localMaxKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0))
        Imgproc.dilate(dist, dilated, localMaxKernel)
        
        // Peaks are where dist == dilated
        val peaks = Mat()
        Core.compare(dist, dilated, peaks, Core.CMP_EQ)
        
        // LOWER threshold to catch more peaks (touching objects have lower distance values)
        val distThresh = Mat()
        Imgproc.threshold(dist, distThresh, 20.0, 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_and(peaks, distThresh, peaks)
        
        // Smaller dilation to avoid merging nearby peaks
        val peakDilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.dilate(peaks, peaks, peakDilateKernel)
        
        // Label the peaks as markers
        val markers = Mat()
        Imgproc.connectedComponents(peaks, markers)
        Core.add(markers, Scalar(1.0), markers)
        
        // Sure background
        val sureBg = Mat()
        val bgKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.dilate(binary, sureBg, bgKernel, Point(-1.0, -1.0), 3)
        
        // Unknown = sureBg - peaks
        val unknown = Mat()
        Core.subtract(sureBg, peaks, unknown)
        
        // Mark unknown as 0
        for (i in 0 until unknown.rows()) {
            for (j in 0 until unknown.cols()) {
                if (unknown.get(i, j)[0] > 0) {
                    markers.put(i, j, 0.0)
                }
            }
        }
        
        // Watershed
        markers.convertTo(markers, CvType.CV_32S)
        val bgr = Mat()
        Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_RGBA2BGR)
        Imgproc.watershed(bgr, markers)
        
        // Extract bounding boxes
        val labelBounds = mutableMapOf<Int, Rect>()
        for (i in 0 until markers.rows()) {
            for (j in 0 until markers.cols()) {
                val label = markers.get(i, j)[0].toInt()
                if (label > 1) {
                    val r = labelBounds[label]
                    if (r == null) {
                        labelBounds[label] = Rect(j, i, 1, 1)
                    } else {
                        labelBounds[label] = Rect(
                            minOf(r.x, j), minOf(r.y, i),
                            maxOf(r.x + r.width, j + 1) - minOf(r.x, j),
                            maxOf(r.y + r.height, i + 1) - minOf(r.y, i)
                        )
                    }
                }
            }
        }
        
        // Cleanup
        binary.release()
        bigCloseKernel.release()
        openKernel.release()
        dist.release()
        dilated.release()
        localMaxKernel.release()
        peaks.release()
        distThresh.release()
        peakDilateKernel.release()
        markers.release()
        sureBg.release()
        bgKernel.release()
        unknown.release()
        bgr.release()
        mat.release()
        
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        
        // Filter by min area and convert to detections
        val allDetections = labelBounds.values
            .filter { it.width * it.height >= MIN_AREA }
            .map { Detection("object", 1f, it.x / w, it.y / h, it.width / w, it.height / h) }
        
        if (allDetections.isEmpty()) return emptyList()
        
        // Find most common (mode) box area using histogram binning
        val areas = allDetections.map { (it.width * w * it.height * h).toInt() }
        val modeArea = findModeArea(areas)
        
        val minAllowed = modeArea * 0.7  // 30% smaller
        val maxAllowed = modeArea * 1.3  // 30% bigger
        
        val finalDetections = mutableListOf<Detection>()
        
        for (det in allDetections) {
            val area = (det.width * w * det.height * h).toDouble()
            when {
                area in minAllowed..maxAllowed -> {
                    // Normal size - keep as is
                    finalDetections.add(det)
                }
                area > maxAllowed -> {
                    // Oversized - estimate count based on area ratio
                    val estimatedCount = (area / modeArea).toInt().coerceAtLeast(2)
                    repeat(estimatedCount) {
                        finalDetections.add(det)
                    }
                }
                // Too small - discard (likely noise)
            }
        }
        
        return finalDetections
    }
    
    // Find dominant color (background) using histogram analysis
    private fun findDominantColor(hsv: Mat): DoubleArray {
        // Sample pixels to build color histogram (for performance)
        val hBins = IntArray(18)  // 18 bins for hue (0-180)
        val sBins = IntArray(8)   // 8 bins for saturation
        val vBins = IntArray(8)   // 8 bins for value
        
        val step = 4 // Sample every 4th pixel
        for (y in 0 until hsv.rows() step step) {
            for (x in 0 until hsv.cols() step step) {
                val pixel = hsv.get(y, x)
                val h = pixel[0].toInt()
                val s = pixel[1].toInt()
                val v = pixel[2].toInt()
                
                hBins[(h / 10).coerceIn(0, 17)]++
                sBins[(s / 32).coerceIn(0, 7)]++
                vBins[(v / 32).coerceIn(0, 7)]++
            }
        }
        
        // Find most common bin for each channel
        val dominantH = hBins.indices.maxByOrNull { hBins[it] }!! * 10 + 5
        val dominantS = sBins.indices.maxByOrNull { sBins[it] }!! * 32 + 16
        val dominantV = vBins.indices.maxByOrNull { vBins[it] }!! * 32 + 16
        
        return doubleArrayOf(dominantH.toDouble(), dominantS.toDouble(), dominantV.toDouble())
    }
    
    // Create binary mask where pixels different from background are white
    private fun createNonBackgroundMask(hsv: Mat, bgColor: DoubleArray, output: Mat) {
        val bgH = bgColor[0]
        val bgS = bgColor[1]
        val bgV = bgColor[2]
        
        // Define range around background color
        // For low saturation backgrounds (white/gray), use saturation-based detection
        // For colored backgrounds, use hue-based detection
        val isNeutralBg = bgS < 50
        
        if (isNeutralBg) {
            // Neutral background - objects are anything with saturation
            val lowerBound = Scalar(0.0, 40.0, 30.0)
            val upperBound = Scalar(180.0, 255.0, 255.0)
            Core.inRange(hsv, lowerBound, upperBound, output)
        } else {
            // Colored background - exclude that hue range
            val hTolerance = 15.0
            val sTolerance = 50.0
            val vTolerance = 50.0
            
            // Create mask of background color
            val bgMask = Mat()
            val lowerBg = Scalar(
                (bgH - hTolerance).coerceAtLeast(0.0),
                (bgS - sTolerance).coerceAtLeast(0.0),
                (bgV - vTolerance).coerceAtLeast(0.0)
            )
            val upperBg = Scalar(
                (bgH + hTolerance).coerceAtMost(180.0),
                (bgS + sTolerance).coerceAtMost(255.0),
                (bgV + vTolerance).coerceAtMost(255.0)
            )
            Core.inRange(hsv, lowerBg, upperBg, bgMask)
            
            // Invert - objects are NOT background
            Core.bitwise_not(bgMask, output)
            bgMask.release()
        }
    }
    
    // Find the most common area using histogram binning
    private fun findModeArea(areas: List<Int>): Double {
        if (areas.isEmpty()) return MIN_AREA.toDouble()
        if (areas.size == 1) return areas[0].toDouble()
        
        // Create bins (10% width each)
        val sorted = areas.sorted()
        val minArea = sorted.first()
        val maxArea = sorted.last()
        val range = maxArea - minArea
        
        if (range == 0) return minArea.toDouble()
        
        // Use 10 bins
        val binCount = 10
        val binWidth = range / binCount + 1
        val bins = IntArray(binCount)
        
        for (area in areas) {
            val binIndex = ((area - minArea) / binWidth).coerceIn(0, binCount - 1)
            bins[binIndex]++
        }
        
        // Find bin with most items
        val modeBinIndex = bins.indices.maxByOrNull { bins[it] } ?: 0
        
        // Return center of that bin
        return minArea + (modeBinIndex + 0.5) * binWidth
    }
    
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21,
            imageProxy.width, imageProxy.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height), 80, out)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())

        val rotation = imageProxy.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else bitmap
    }

    fun close() {}
}
