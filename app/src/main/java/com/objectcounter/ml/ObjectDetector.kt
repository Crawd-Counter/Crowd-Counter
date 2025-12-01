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
 * Uses distance transform + watershed to separate touching objects
 */
class ObjectDetector {
    
    companion object {
        private const val MIN_AREA = 200
        
        init {
            OpenCVLoader.initLocal()
        }
    }

    fun detect(imageProxy: ImageProxy): List<Detection> {
        val bitmap = imageProxyToBitmap(imageProxy)
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        
        // Grayscale + blur
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        
        // Otsu threshold - dark objects become white
        val binary = Mat()
        Imgproc.threshold(gray, binary, 0.0, 255.0, 
            Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        
        // Strong morphological opening to separate touching objects
        val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, openKernel, Point(-1.0, -1.0), 3)
        
        // Close small holes
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, closeKernel, Point(-1.0, -1.0), 2)
        
        // Distance transform
        val dist = Mat()
        Imgproc.distanceTransform(binary, dist, Imgproc.DIST_L2, 5)
        
        // Normalize distance
        Core.normalize(dist, dist, 0.0, 255.0, Core.NORM_MINMAX)
        dist.convertTo(dist, CvType.CV_8U)
        
        // Find local maxima (object centers) using dilation comparison
        val dilated = Mat()
        val localMaxKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
        Imgproc.dilate(dist, dilated, localMaxKernel)
        
        // Peaks are where dist == dilated and dist > threshold
        val peaks = Mat()
        Core.compare(dist, dilated, peaks, Core.CMP_EQ)
        
        // Also require minimum distance value (not background)
        val distThresh = Mat()
        Imgproc.threshold(dist, distThresh, 30.0, 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_and(peaks, distThresh, peaks)
        
        // Label the peaks as markers
        val markers = Mat()
        Imgproc.connectedComponents(peaks, markers)
        Core.add(markers, Scalar(1.0), markers) // Background = 1
        
        // Sure background
        val sureBg = Mat()
        Imgproc.dilate(binary, sureBg, closeKernel, Point(-1.0, -1.0), 3)
        
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
        openKernel.release()
        closeKernel.release()
        dist.release()
        dilated.release()
        localMaxKernel.release()
        peaks.release()
        distThresh.release()
        markers.release()
        sureBg.release()
        unknown.release()
        bgr.release()
        mat.release()
        
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        
        return labelBounds.values
            .filter { it.width * it.height >= MIN_AREA }
            .map { Detection("object", 1f, it.x / w, it.y / h, it.width / w, it.height / h) }
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
