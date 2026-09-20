import AVFoundation
import CoreVideo
import Foundation

/// Writes an H.264 MP4 from frames the caller has already anonymized.
///
/// The camera's own `AVCaptureMovieFileOutput` is deliberately unused: it
/// would write the untouched sensor frames straight to disk. Every frame here
/// arrives through `append`, after the privacy engine has run on it.
final class ProtectedVideoWriter: @unchecked Sendable {

    private let writer: AVAssetWriter
    private let input: AVAssetWriterInput
    private let adaptor: AVAssetWriterInputPixelBufferAdaptor
    private var started = false
    private var startTime: CMTime = .zero

    let url: URL
    private(set) var droppedFrames = 0

    init(width: Int, height: Int) throws {
        url = FileManager.default.temporaryDirectory
            .appendingPathComponent("veil-\(Int(Date().timeIntervalSince1970)).mp4")
        writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        input = AVAssetWriterInput(
            mediaType: .video,
            outputSettings: [
                AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: width,
                AVVideoHeightKey: height,
                AVVideoCompressionPropertiesKey: [
                    AVVideoAverageBitRateKey: max(2_000_000, width * height * 4),
                ],
            ]
        )
        input.expectsMediaDataInRealTime = true
        adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: input,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
            ]
        )
        guard writer.canAdd(input) else { throw VeilError.recordingUnavailable }
        writer.add(input)
    }

    /// Appends an already protected frame. Frames are dropped rather than
    /// stalling capture when the encoder cannot keep up; timestamps come from
    /// the camera, so dropping shortens the frame count, not the playback.
    func append(_ buffer: CVPixelBuffer, at time: CMTime) {
        if !started {
            guard writer.startWriting() else { return }
            writer.startSession(atSourceTime: time)
            startTime = time
            started = true
        }
        guard writer.status == .writing, input.isReadyForMoreMediaData else {
            droppedFrames += 1
            return
        }
        if !adaptor.append(buffer, withPresentationTime: time) {
            droppedFrames += 1
        }
    }

    /// Finishes the file. Returns nil when nothing was ever written, and
    /// deletes a partial file rather than leaving it behind.
    func finish() async -> URL? {
        guard started, writer.status == .writing else {
            cancel()
            return nil
        }
        input.markAsFinished()
        await writer.finishWriting()
        guard writer.status == .completed else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return url
    }

    func cancel() {
        if writer.status == .writing { writer.cancelWriting() }
        try? FileManager.default.removeItem(at: url)
    }
}

enum VeilError: Error {
    case recordingUnavailable
    case captureFailed
    case noProtectedResult
}
