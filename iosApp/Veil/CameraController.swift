import AVFoundation
import Combine
import CoreVideo
import Foundation
import UIKit
import VeilPrivacy

/// A face the viewfinder is currently tracking.
struct LiveFace: Identifiable, Equatable {
    let id: Int32
    let region: FaceRegion
    let keptVisible: Bool

    static func == (lhs: LiveFace, rhs: LiveFace) -> Bool {
        lhs.id == rhs.id
            && lhs.keptVisible == rhs.keptVisible
            && lhs.region.centerX == rhs.region.centerX
            && lhs.region.centerY == rhs.region.centerY
            && lhs.region.radiusX == rhs.region.radiusX
            && lhs.region.radiusY == rhs.region.radiusY
    }
}

/// A captured photo waiting for review.
struct ProtectedPhoto {
    let original: CGImage
    let faces: [TrackedRegion]
    let keptVisible: Set<Int32>
    let image: UIImage
    let facesProtected: Int
}

@MainActor
final class CameraController: NSObject, ObservableObject {

    @Published private(set) var liveFaces: [LiveFace] = []
    @Published private(set) var frameSize: CGSize = .zero
    @Published private(set) var isRecording = false
    @Published private(set) var recordedSeconds = 0
    @Published private(set) var photo: ProtectedPhoto?
    @Published private(set) var message: String?
    @Published private(set) var isBusy = false
    @Published var effect: PrivacyEffect = .blur {
        didSet {
            processor.setEffect(effect)
            reprocessPhoto()
        }
    }
    @Published var strength: PrivacyStrength = .balanced {
        didSet {
            processor.setStrength(strength)
            reprocessPhoto()
        }
    }

    let session = AVCaptureSession()

    private let videoOutput = AVCaptureVideoDataOutput()
    private let photoOutput = AVCapturePhotoOutput()
    private let queue = DispatchQueue(label: "app.veil.capture")
    private nonisolated let processor = FrameProcessor()

    /// Faces the user tapped to keep visible in the viewfinder. Ids come from
    /// the shared tracker: they follow a position between frames and mean
    /// nothing once the session ends.
    private var liveKeepVisible: Set<Int32> = []

    private let photoDetector = FaceDetector()
    private var photoTracker = FaceTracker(reachFactor: 0.9)
    private var photoKeepVisible: Set<Int32> = []
    private var photoContinuation: CheckedContinuation<CGImage, Error>?
    private var recordedVideoURL: URL?

    override init() {
        super.init()
        processor.onFrame = { [weak self] faces, size, seconds in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.liveFaces = faces
                self.frameSize = size
                if self.isRecording { self.recordedSeconds = seconds }
            }
        }
    }

    // MARK: - Session

    func start() async {
        guard await AVCaptureDevice.requestAccess(for: .video) else {
            message = "Camera access is needed to protect faces."
            return
        }
        guard session.inputs.isEmpty else {
            queue.async { [session] in if !session.isRunning { session.startRunning() } }
            return
        }
        session.beginConfiguration()
        session.sessionPreset = .high
        if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
           let input = try? AVCaptureDeviceInput(device: device),
           session.canAddInput(input) {
            session.addInput(input)
        }
        videoOutput.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        ]
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(self, queue: queue)
        if session.canAddOutput(videoOutput) { session.addOutput(videoOutput) }
        if session.canAddOutput(photoOutput) { session.addOutput(photoOutput) }
        // Upright frames, so detection coordinates, the overlay and the
        // recorded file all agree with what the viewfinder shows.
        for output in [videoOutput as AVCaptureOutput, photoOutput] {
            if let connection = output.connection(with: .video),
               connection.isVideoOrientationSupported {
                connection.videoOrientation = .portrait
            }
        }
        session.commitConfiguration()
        queue.async { [session] in session.startRunning() }
    }

    func stop() {
        queue.async { [session] in if session.isRunning { session.stopRunning() } }
    }

    // MARK: - Keeping a face visible

    /// A tap in the viewfinder, in coordinates of the analyzed frame.
    func toggleLiveFace(atX x: Float, y: Float) {
        guard let hit = smallestFace(liveFaces.map { ($0.id, $0.region) }, x: x, y: y) else { return }
        if liveKeepVisible.contains(hit) {
            liveKeepVisible.remove(hit)
        } else {
            liveKeepVisible.insert(hit)
        }
        processor.setKeepVisible(liveKeepVisible)
        liveFaces = liveFaces.map {
            LiveFace(id: $0.id, region: $0.region, keptVisible: liveKeepVisible.contains($0.id))
        }
    }

    /// A tap on the photo under review, in image pixel coordinates.
    func toggleReviewFace(atX x: Float, y: Float) {
        guard let photo else { return }
        guard let hit = smallestFace(photo.faces.map { ($0.id, $0.region) }, x: x, y: y) else { return }
        if photoKeepVisible.contains(hit) {
            photoKeepVisible.remove(hit)
        } else {
            photoKeepVisible.insert(hit)
        }
        reprocessPhoto()
    }

    /// One tap can land inside two overlapping faces; the smaller one is the
    /// one the user aimed at.
    private func smallestFace(_ faces: [(Int32, FaceRegion)], x: Float, y: Float) -> Int32? {
        faces
            .filter { $0.1.contains(x: x, y: y, margin: 1.15) }
            .min { $0.1.area < $1.1.area }?
            .0
    }

    // MARK: - Photo

    func capturePhoto() {
        guard !isBusy, !isRecording else { return }
        isBusy = true
        Task {
            do {
                let image = try await takePhoto()
                photoTracker = FaceTracker(reachFactor: 0.9)
                photoKeepVisible = []
                let faces = photoTracker.update(regions: photoDetector.faces(in: image))
                photo = protect(image: image, faces: faces)
            } catch {
                message = "Capture failed."
            }
            isBusy = false
        }
    }

    private func reprocessPhoto() {
        guard let current = photo else { return }
        photo = protect(image: current.original, faces: current.faces)
    }

    private func protect(image: CGImage, faces: [TrackedRegion]) -> ProtectedPhoto? {
        let regions = faces.filter { !photoKeepVisible.contains($0.id) }.map { $0.region }
        guard let result = PrivacyEngine.protect(
            image: image,
            faces: regions,
            effect: effect,
            strength: strength
        ) else {
            message = "Could not protect this photo, so it was discarded."
            return nil
        }
        return ProtectedPhoto(
            original: image,
            faces: faces,
            keptVisible: photoKeepVisible,
            image: UIImage(cgImage: result.image),
            facesProtected: Int(result.outcome.facesProtected)
        )
    }

    private func takePhoto() async throws -> CGImage {
        try await withCheckedThrowingContinuation { continuation in
            photoContinuation = continuation
            photoOutput.capturePhoto(with: AVCapturePhotoSettings(), delegate: self)
        }
    }

    func discardPhoto() {
        photo = nil
        photoKeepVisible = []
    }

    func savePhoto() {
        guard let image = photo?.image else { return }
        Task {
            do {
                try await MediaLibrary.savePhoto(image)
                message = "Saved to Veil"
                discardPhoto()
            } catch {
                message = "Could not save to your photo library."
            }
        }
    }

    // MARK: - Video

    func toggleRecording() {
        if isRecording {
            isRecording = false
            Task {
                guard let url = await processor.stopRecording() else {
                    message = "Recording failed, nothing was saved."
                    return
                }
                recordedVideoURL = url
                do {
                    try await MediaLibrary.saveVideo(at: url)
                    message = "Saved protected video to Veil"
                } catch {
                    message = "Could not save to your photo library."
                }
            }
        } else {
            recordedVideoURL = nil
            recordedSeconds = 0
            isRecording = true
            processor.startRecording()
        }
    }

    var shareItems: [Any] {
        if let image = photo?.image { return [image] }
        if let url = recordedVideoURL { return [url] }
        return []
    }

    func consumeMessage() {
        message = nil
    }
}

extension CameraController: AVCaptureVideoDataOutputSampleBufferDelegate {
    nonisolated func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        processor.handle(frame: buffer, at: CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
    }
}

extension CameraController: AVCapturePhotoCaptureDelegate {
    nonisolated func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        let image = photo.cgImageRepresentation()
        Task { @MainActor in
            guard let continuation = self.photoContinuation else { return }
            self.photoContinuation = nil
            if let image {
                continuation.resume(returning: image)
            } else {
                continuation.resume(throwing: error ?? VeilError.captureFailed)
            }
        }
    }
}
