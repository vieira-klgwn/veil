import CoreGraphics
import CoreVideo
import Foundation
import VeilPrivacy

/// Bridge to the Kotlin Multiplatform privacy engine shared with Android.
///
/// Both entry points hand the engine a pointer to pixels that are already
/// BGRA in memory, which reads back as an `0xAARRGGBB` integer — exactly the
/// packing `PixelImage` uses — so no colour conversion happens on either side
/// and nothing is copied out of the capture pipeline.
enum PrivacyEngine {

    /// Anonymizes the faces of a locked `kCVPixelFormatType_32BGRA` buffer in
    /// place. Used for every recorded video frame.
    @discardableResult
    static func protect(
        pixelBuffer: CVPixelBuffer,
        faces: [FaceRegion],
        effect: PrivacyEffect,
        strength: PrivacyStrength
    ) -> ProtectionOutcome? {
        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, []) }
        guard let base = CVPixelBufferGetBaseAddress(pixelBuffer) else { return nil }
        return PixelBufferPrivacy.shared.protect(
            baseAddress: Int64(Int(bitPattern: base)),
            width: Int32(CVPixelBufferGetWidth(pixelBuffer)),
            height: Int32(CVPixelBufferGetHeight(pixelBuffer)),
            bytesPerRow: Int32(CVPixelBufferGetBytesPerRow(pixelBuffer)),
            faces: faces,
            effect: effect,
            strength: strength
        )
    }

    /// Anonymizes a still image. Returns the protected image and what the
    /// engine's own audit made of it.
    static func protect(
        image: CGImage,
        faces: [FaceRegion],
        effect: PrivacyEffect,
        strength: PrivacyStrength
    ) -> (image: CGImage, outcome: ProtectionOutcome)? {
        let width = image.width
        let height = image.height
        let bytesPerRow = width * 4
        guard
            let context = CGContext(
                data: nil,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: bytesPerRow,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue
                    | CGBitmapInfo.byteOrder32Little.rawValue
            ),
            let data = context.data
        else { return nil }

        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        let outcome = PixelBufferPrivacy.shared.protect(
            baseAddress: Int64(Int(bitPattern: data)),
            width: Int32(width),
            height: Int32(height),
            bytesPerRow: Int32(bytesPerRow),
            faces: faces,
            effect: effect,
            strength: strength
        )
        guard let output = context.makeImage() else { return nil }
        return (output, outcome)
    }
}
