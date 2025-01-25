import ExpoModulesCore
import AVFoundation
import UIKit

public class ReactNativeForgeModule: Module {
    private func calculatePaddedDimensions(width: CGFloat, height: CGFloat, targetRatio: Double) -> (CGFloat, CGFloat) {
        let currentRatio = width / height
        
        if currentRatio < targetRatio {
            // Add horizontal padding
            let newWidth = height * CGFloat(targetRatio)
            return (newWidth, height)
        } else {
            // Add vertical padding
            let newHeight = width / CGFloat(targetRatio)
            return (width, newHeight)
        }
    }

    public func definition() -> ModuleDefinition {
        Name("ReactNativeForge")

        Events("onProgress")

        AsyncFunction("getAllFrames") { [self] (inputPath: String, outputFolder: String, duration: Double, size: [String: Double]?) -> [String] in
            guard let inputURL = URL(string: inputPath),
                let outputFolderURL = URL(string: outputFolder) else {
                throw NSError(domain: "ReactNativeForge", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid paths"])
            }
            
            // Create output directory if it doesn't exist
            let fileManager = FileManager.default
            try? fileManager.createDirectory(at: outputFolderURL, withIntermediateDirectories: true)
            
            let asset = AVAsset(url: inputURL)
            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            
            generator.requestedTimeToleranceBefore = CMTime(value: 1, timescale: 2)
            generator.requestedTimeToleranceAfter = CMTime(value: 1, timescale: 2)
            
            generator.maximumSize = CGSize(width: 1280, height: 720)
            if let size = size,
                let width = size["width"],
                let height = size["height"] {
                generator.maximumSize = CGSize(width: min(width, 1280), height: min(height, 720))
            }
            
            var framePaths: [String] = []
            let batchSize = 10
            
            for batchStart in stride(from: 0, to: Int(duration), by: batchSize) {
                let batchEnd = min(batchStart + batchSize, Int(duration))
                autoreleasepool {
                    for second in batchStart..<batchEnd {
                        let time = CMTime(seconds: Double(second), preferredTimescale: 600)
                        do {
                            let image = try generator.copyCGImage(at: time, actualTime: nil)
                            let frameURL = outputFolderURL.appendingPathComponent("frame_\(second).jpg")
                            if let data = UIImage(cgImage: image).jpegData(compressionQuality: 0.7) {
                                try data.write(to: frameURL)
                                framePaths.append(frameURL.absoluteString)
                            }
                        } catch {
                            print("Failed to generate frame at \(second) seconds: \(error)")
                            continue
                        }
                        
                        let progress = Double(second + 1) / duration
                        self.sendEvent("onProgress", ["progress": progress])
                        
                    }
                }
            }
            
            return framePaths
        }

        AsyncFunction("getFrame") { (inputPath: String, outputPath: String, timestamp: Double, quality: Double?, size: [String: Double]?) -> String in
            guard let inputURL = URL(string: inputPath),
                let outputURL = URL(string: outputPath) else {
                throw NSError(domain: "ReactNativeForge", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid paths"])
            }
            
            let asset = AVAsset(url: inputURL)
            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            
            // Add tolerance for faster seeking
            generator.requestedTimeToleranceBefore = CMTime(value: 1, timescale: 2)
            generator.requestedTimeToleranceAfter = CMTime(value: 1, timescale: 2)
            
            if let size = size, let width = size["width"], let height = size["height"] {
                generator.maximumSize = CGSize(width: width, height: height)
            }
            
            let time = CMTime(seconds: timestamp, preferredTimescale: 600)
            let image = try generator.copyCGImage(at: time, actualTime: nil)
            let compressionQuality = quality.map { Float($0) / 100.0 } ?? 0.15
            
            if let data = UIImage(cgImage: image).jpegData(compressionQuality: CGFloat(compressionQuality)) {
                try data.write(to: outputURL)
                return outputURL.absoluteString
            }
            
            throw NSError(domain: "ReactNativeForge", code: -2, userInfo: [NSLocalizedDescriptionKey: "Failed to save image"])
        }

        AsyncFunction("padToRatio") { (inputPath: String, outputPath: String, targetRatio: Double) -> [String: Any] in
            guard let inputURL = URL(string: inputPath),
                let outputURL = URL(string: outputPath) else {
                throw NSError(domain: "ReactNativeForge", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid paths"])
            }
            
            let asset = AVAsset(url: inputURL)
            guard let videoTrack = try? await asset.loadTracks(withMediaType: .video).first else {
                throw NSError(domain: "ReactNativeForge", code: -2, userInfo: [NSLocalizedDescriptionKey: "No video track found"])
            }
            
            let naturalSize = try await videoTrack.load(.naturalSize)
            let originalRatio = naturalSize.width / naturalSize.height
            
            // Calculate new dimensions to achieve target ratio
            let (finalWidth, finalHeight) = calculatePaddedDimensions(
                width: naturalSize.width,
                height: naturalSize.height,
                targetRatio: targetRatio
            )
            
            let composition = AVMutableComposition()
            let compositionTrack = composition.addMutableTrack(
                withMediaType: .video,
                preferredTrackID: kCMPersistentTrackID_Invalid
            )
            
            // Insert video track
            try compositionTrack?.insertTimeRange(
                CMTimeRange(start: .zero, duration: asset.duration),
                of: videoTrack,
                at: .zero
            )
            
            // Setup video composition
            let videoComposition = AVMutableVideoComposition()
            videoComposition.renderSize = CGSize(width: finalWidth, height: finalHeight)
            videoComposition.frameDuration = CMTime(value: 1, timescale: 30)
            
            let xOffset = (finalWidth - naturalSize.width) / 2
            let yOffset = (finalHeight - naturalSize.height) / 2
            
            let instruction = AVMutableVideoCompositionInstruction()
            instruction.timeRange = CMTimeRange(start: .zero, duration: asset.duration)
            
            let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: compositionTrack!)
            
            // Apply transform to center the video
            var transform = CGAffineTransform(translationX: xOffset, y: yOffset)
            layerInstruction.setTransform(transform, at: .zero)
            
            instruction.layerInstructions = [layerInstruction]
            videoComposition.instructions = [instruction]
            
            let exporter = AVAssetExportSession(
                asset: composition,
                presetName: AVAssetExportPresetHighestQuality
            )
            
            exporter?.videoComposition = videoComposition
            exporter?.outputURL = outputURL
            exporter?.outputFileType = .mp4
            
            await exporter?.export()
            
            guard exporter?.status == .completed else {
                throw NSError(domain: "ReactNativeForge", code: -3, userInfo: [
                    NSLocalizedDescriptionKey: "Failed to export video: \(String(describing: exporter?.error))"
                ])
            }
            
            return [
                "outputPath": outputURL.absoluteString,
                "ratio": Double(finalWidth / finalHeight)
            ]
        }

        AsyncFunction("getDuration") { (inputPath: String) -> Int in
            guard let inputURL = URL(string: inputPath) else {
                throw NSError(domain: "ReactNativeForge", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid path"])
            }
            
            let asset = AVAsset(url: inputURL)
            let duration = try await asset.load(.duration)
            return Int(duration.seconds)
        }
    }
}
