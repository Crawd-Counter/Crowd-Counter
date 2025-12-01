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
 * Counts dark objects on light background
 * Uses morphological closing to fill internal gaps (like chickpea creases)
 * Then watershed to separate touching objects
 */
class ObjectDetector {
    
    companion object {
        private const val MIN_AREA = 500
        
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
        
        // Grayscale + stronger blur to smooth internal details
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(7.0, 7.0), 0.0)
        
        // Otsu threshold - dark objects become white
        val binary = Mat()
        Imgproc.threshold(gray, binary, 0.0, 255.0, 
            Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        
        // STRONG morphological closing FIRST to fill internal gaps (chickpea crease)
        val bigCloseKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, bigCloseKernel, Point(-1.0, -1.0), 2)
        
        // Then opening to remove noise and thin connections between objects
        val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, openKernel, Point(-1.0, -1.0), 2)
        
        // Distance transform
        val dist = Mat()
        Imgproc.distanceTransform(binary, dist, Imgproc.DIST_L2, 5)
        
        // Normalize distance
        Core.normalize(dist, dist, 0.0, 255.0, Core.NORM_MINMAX)
        dist.convertTo(dist, CvType.CV_8U)
        
        // Find local maxima with LARGER kernel to avoid multiple peaks per object
        val dilated = Mat()
        val localMaxKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(25.0, 25.0))
        Imgproc.dilate(dist, dilated, localMaxKernel)
        
        // Peaks are where dist == dilated
        val peaks = Mat()
        Core.compare(dist, dilated, peaks, Core.CMP_EQ)
        
        // Require higher minimum distance value
        val distThresh = Mat()
        Imgproc.threshold(dist, distThresh, 50.0, 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_and(peaks, distThresh, peaks)
        
        // Dilate peaks slightly to merge very close ones
        val peakDilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
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
        gray.release()
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
        
        // Filter and merge overlapping detections
        val detections = labelBounds.values
            .filter { it.width * it.height >= MIN_AREA }
            .map { Detection("object", 1f, it.x / w, it.y / h, it.width / w, it.height / h) }
        
        return mergeOverlapping(detections)
    }
    
    // Merge detections that overlap significantly
    private fun mergeOverlapping(detections: List<Detection>): List<Detection> {
        if (detections.size <= 1) return detections
        
        val merged = mutableListOf<Detection>()
        val used = BooleanArray(detections.size)
        
        for (i in detections.indices) {
            if (used[i]) continue
            
            var current = detections[i]
            used[i] = true
            
            // Find all overlapping detections and merge
            for (j in i + 1 until detections.size) {
                if (used[j]) continue
                if (iou(current, detections[j]) > 0.3f) {
                    current = merge(current, detections[j])
                    used[j] = true
                }
            }
            merged.add(current)
        }
        return merged
    }
    
    private fun merge(a: Detection, b: Detection): Detection {
        val x = minOf(a.x, b.x)
        val y = minOf(a.y, b.y)
        val right = maxOf(a.x + a.width, b.x + b.width)
        val bottom = maxOf(a.y + a.height, b.y + b.height)
        return Detection("object", 1f, x, y, right - x, bottom - y)
    }
    
    private fun iou(a: Detection, b: Detection): Float {
        val x1 = maxOf(a.x, b.x)
        val y1 = maxOf(a.y, b.y)
        val x2 = minOf(a.x + a.width, b.x + b.width)
        val y2 = minOf(a.y + a.height, b.y + b.height)
        val inter = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val union = a.width * a.height + b.width * b.height - inter
        return if (union > 0) inter / union else 0f
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
