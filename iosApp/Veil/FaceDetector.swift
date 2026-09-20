import CoreGraphics
import CoreVideo
import Foundation
import VeilPrivacy
import Vision

/// On-device face detection with Vision. Nothing leaves the phone: Vision runs
/// locally, and only face *rectangles* are requested — no landmarks, no face
/// print, no identity, nothing persisted.
final class FaceDetector {

    private let sequence = VNSequenceRequestHandler()

    /// Faces of a camera frame, in pixel coordinates of that frame.
    func faces(in pixelBuffer: CVPixelBuffer, orientation: CGImagePropertyOrientation) -> [FaceRegion] {
        let request = VNDetectFaceRectanglesRequest()
        do {
            try sequence.perform([request], on: pixelBuffer, orientation: orientation)
        } catch {
            return []
        }
        let size = CGSize(
            width: CVPixelBufferGetWidth(pixelBuffer),
            height: CVPixelBufferGetHeight(pixelBuffer)
        )
        return regions(from: request.results ?? [], imageSize: size)
    }

    /// Faces of a still image, in pixel coordinates of that image.
    func faces(in image: CGImage) -> [FaceRegion] {
        let request = VNDetectFaceRectanglesRequest()
        do {
            try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
        } catch {
            return []
        }
        let size = CGSize(width: image.width, height: image.height)
        return regions(from: request.results ?? [], imageSize: size)
    }

    /// Vision reports normalized rectangles whose origin is the bottom left;
    /// the engine works in top left pixel coordinates.
    private func regions(from observations: [VNFaceObservation], imageSize: CGSize) -> [FaceRegion] {
        let width = Float(imageSize.width)
        let height = Float(imageSize.height)
        let converted = observations.map { face -> FaceRegion in
            let box = face.boundingBox
            return FaceGeometry.shared.fromBoundingBox(
                left: Float(box.minX) * width,
                top: Float(1 - box.maxY) * height,
                right: Float(box.maxX) * width,
                bottom: Float(1 - box.minY) * height,
                rotationDegrees: 0
            )
        }
        return FaceRegions.shared.deduplicate(regions: converted)
    }
}
