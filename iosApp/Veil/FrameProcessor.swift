import AVFoundation
import CoreVideo
import Foundation
import VeilPrivacy

/// Everything that happens to a camera frame, off the main actor.
///
/// Detection, anonymization and encoding all run on the capture queue, and the
/// only thing that leaves this class is the protected result: the face
/// overlay, and an mp4 whose every frame went through the privacy engine.
final class FrameProcessor: @unchecked Sendable {

    /// Called on the capture queue after each analyzed frame.
    var onFrame: ((_ faces: [LiveFace], _ frameSize: CGSize, _ recordedSeconds: Int) -> Void)?

    private let lock = NSLock()
    private var keepVisible: Set<Int32> = []
    private var recording = false
    private var effect: PrivacyEffect = .blur
    private var strength: PrivacyStrength = .balanced

    private let detector = FaceDetector()
    private let tracker = FaceTracker(reachFactor: 0.9)
    private var writer: ProtectedVideoWriter?
    private var recordingStart: CMTime?

    // MARK: - Settings, written from the main actor

    func setKeepVisible(_ ids: Set<Int32>) { lock.withLock { keepVisible = ids } }
    func setEffect(_ value: PrivacyEffect) { lock.withLock { effect = value } }
    func setStrength(_ value: PrivacyStrength) { lock.withLock { strength = value } }

    func startRecording() {
        lock.withLock {
            recording = true
            recordingStart = nil
        }
    }

    /// Stops recording and finishes the file. Returns nil when no frame was
    /// ever written; a partial file is deleted rather than left behind.
    func stopRecording() async -> URL? {
        let finished: ProtectedVideoWriter? = lock.withLock {
            recording = false
            let active = writer
            writer = nil
            recordingStart = nil
            return active
        }
        return await finished?.finish()
    }

    // MARK: - Frames

    func handle(frame buffer: CVPixelBuffer, at time: CMTime) {
        // Frames arrive upright because the capture connection is portrait.
        let tracked = tracker.update(regions: detector.faces(in: buffer, orientation: .up))
        let (keep, isRecording, currentEffect, currentStrength) = lock.withLock {
            (keepVisible, recording, effect, strength)
        }

        if isRecording {
            // Anonymized before the frame can reach the encoder.
            PrivacyEngine.protect(
                pixelBuffer: buffer,
                faces: tracked.filter { !keep.contains($0.id) }.map { $0.region },
                effect: currentEffect,
                strength: currentStrength
            )
            append(buffer, at: time)
        }

        let elapsed: Int = lock.withLock {
            guard let start = recordingStart else { return 0 }
            return max(0, Int(CMTimeGetSeconds(time) - CMTimeGetSeconds(start)))
        }
        onFrame?(
            tracked.map { LiveFace(id: $0.id, region: $0.region, keptVisible: keep.contains($0.id)) },
            CGSize(width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer)),
            elapsed
        )
    }

    private func append(_ buffer: CVPixelBuffer, at time: CMTime) {
        let active: ProtectedVideoWriter? = lock.withLock {
            if writer == nil {
                writer = try? ProtectedVideoWriter(
                    width: CVPixelBufferGetWidth(buffer),
                    height: CVPixelBufferGetHeight(buffer)
                )
                recordingStart = time
            }
            return writer
        }
        active?.append(buffer, at: time)
    }
}
