package expo.modules.forge

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.media.MediaExtractor
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import java.io.File
import java.io.FileOutputStream
import android.net.Uri
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

class ReactNativeForgeModule : Module() {
    override fun definition() = ModuleDefinition {
        Name("ReactNativeForge")

        Events("onProgress")

        AsyncFunction("getAllFrames") { inputPath: String, outputFolder: String, duration: Double, size: Map<String, Double>? ->
            processFrames(inputPath, outputFolder, size, duration)
        }

        AsyncFunction("getFrame") { inputPath: String, outputPath: String, timestamp: Double, quality: Double?, size: Map<String, Double>? ->
            extractFrame(inputPath, outputPath, timestamp, quality ?: 15.0, size)
        }

        AsyncFunction("getDuration") { inputPath: String ->
            getDuration(inputPath)
        }

        AsyncFunction("padToRatio") { inputPath: String, outputPath: String, targetRatio: Double ->
            padVideo(inputPath, outputPath, targetRatio)
        }
    }

    private fun processFrames(inputPath: String, outputFolder: String, size: Map<String, Double>?, duration: Double): List<String> {
        val retriever = MediaMetadataRetriever()
        val framePaths = mutableListOf<String>()
        val cleanOutputPath = outputFolder.removePrefix("file://")
        val outputDir = File(cleanOutputPath).apply { mkdirs() }

        retriever.setDataSource(appContext.reactContext!!, Uri.parse(inputPath))
        val frame = retriever.getFrameAtTime(0) // Get first frame to determine dimensions
        val width = size?.get("width")?.toInt() ?: frame?.width ?: 1280
        val height = size?.get("height")?.toInt() ?: frame?.height ?: 720
        frame?.recycle()

        (0 until duration.toInt()).forEach { second ->
            retriever.getFrameAtTime(second * 1_000_000L)?.let { frame ->
                val scaledFrame = if (size != null) {
                    Bitmap.createScaledBitmap(frame, width, height, true)
                } else frame
                
                val outputFile = File(outputDir, "frame_$second.jpg")
                FileOutputStream(outputFile).use { scaledFrame.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                framePaths.add("file://${outputFile.absolutePath}")
                
                if (frame !== scaledFrame) frame.recycle()
                scaledFrame.recycle()
                
                val progress = (second + 1) / duration.toDouble()
                sendEvent("onProgress", mapOf("progress" to progress))
                
            }
        }
        retriever.release()
        return framePaths
    }

    private fun extractFrame(inputPath: String, outputPath: String, timestamp: Double, quality: Double, size: Map<String, Double>?): String {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(appContext.reactContext!!, Uri.parse(inputPath))
        val frame = retriever.getFrameAtTime((timestamp * 1_000_000).toLong()) ?: throw Exception("Failed to extract frame")

        val scaledFrame = size?.let {
            Bitmap.createScaledBitmap(frame, it["width"]?.toInt() ?: frame.width, it["height"]?.toInt() ?: frame.height, true)
        } ?: frame

        val cleanOutputPath = outputPath.removePrefix("file://")
        val outputFile = File(cleanOutputPath).apply { parentFile?.mkdirs() }
        FileOutputStream(outputFile).use { scaledFrame.compress(Bitmap.CompressFormat.JPEG, quality.toInt(), it) }

        if (frame !== scaledFrame) frame.recycle()
        scaledFrame.recycle()
        retriever.release()
        return "file://${outputFile.absolutePath}"
    }

    private fun getDuration(inputPath: String): Long {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(appContext.reactContext!!, Uri.parse(inputPath))
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000) ?: 0L
        retriever.release()
        return duration
    }

    private fun padVideo(inputPath: String, outputPath: String, targetRatio: Double): Map<String, Any> {
        val inputUri = Uri.parse(inputPath)
        val outputUri = Uri.parse(outputPath)
    
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(appContext.reactContext!!, inputUri)
        
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
        retriever.release()
    
        val currentRatio = width.toDouble() / height.toDouble()
        
        val (finalWidth, finalHeight) = calculatePaddedDimensions(width, height, targetRatio)
        
        val extractor = MediaExtractor()
        extractor.setDataSource(appContext.reactContext!!, inputUri, null)
        
        val videoTrackIndex = (0 until extractor.trackCount).firstOrNull { 
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true 
        } ?: throw Exception("ReactNativeForge: No video track found")
        
        val format = extractor.getTrackFormat(videoTrackIndex)
        val mimeType = format.getString(MediaFormat.KEY_MIME) ?: throw Exception("No mime type found")

        val encoderFormat = MediaFormat.createVideoFormat(mimeType, finalWidth.toInt(), finalHeight.toInt())

        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, 
            format.getInteger(MediaFormat.KEY_BIT_RATE, 2000000))  // Default 2Mbps
        encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 
            format.getInteger(MediaFormat.KEY_FRAME_RATE, 30))     // Default 30fps
        encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 
            format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)) // Default 1 second
        encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, 
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        
        try {
            val encoder = MediaCodec.createEncoderByType(mimeType)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            
            // Create input surface with OpenGL ES
            val surface = encoder.createInputSurface()
            val eglContext = EGL14.eglGetCurrentContext()
            
            // Calculate padding offsets
            val xOffset = ((finalWidth - width) / 2).toInt()
            val yOffset = ((finalHeight - height) / 2).toInt()
            
            // Setup OpenGL ES rendering
            val surfaceTexture = SurfaceTexture(0)
            surfaceTexture.setDefaultBufferSize(finalWidth.toInt(), finalHeight.toInt())
            
            
            encoder.start()
            // Process frames...
            encoder.stop()
            encoder.release()
        } catch (e: Exception) {
            throw Exception("ReactNativeForge: Failed to configure video encoder: ${e.message}")
        }
        
        return mapOf(
            "outputPath" to outputUri.toString(),
            "ratio" to (finalWidth / finalHeight)
        )
    }

    private fun calculatePaddedDimensions(width: Int, height: Int, targetRatio: Double): Pair<Double, Double> {
        val currentRatio = width.toDouble() / height.toDouble()
        
        return if (currentRatio < targetRatio) {
            // Add horizontal padding
            val newWidth = height * targetRatio
            Pair(newWidth, height.toDouble())
        } else {
            // Add vertical padding
            val newHeight = width / targetRatio
            Pair(width.toDouble(), newHeight)
        }
    }
}
