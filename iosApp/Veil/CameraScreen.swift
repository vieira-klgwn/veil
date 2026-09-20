import SwiftUI
import VeilPrivacy

struct CameraScreen: View {

    @StateObject private var camera = CameraController()
    @State private var showSettings = false
    @State private var showShare = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let photo = camera.photo {
                ReviewScreen(camera: camera, photo: photo, showShare: $showShare)
            } else {
                viewfinder
            }
        }
        .task { await camera.start() }
        .onDisappear { camera.stop() }
        .sheet(isPresented: $showSettings) { SettingsSheet(camera: camera) }
        .sheet(isPresented: $showShare) { ShareSheet(items: camera.shareItems) }
        .overlay(alignment: .bottom) { banner }
    }

    private var viewfinder: some View {
        GeometryReader { geometry in
            let mapping = FrameMapping(
                frame: camera.frameSize,
                view: geometry.size,
                fill: true
            )
            ZStack {
                CameraPreview(session: camera.session)
                    .ignoresSafeArea()

                FaceOverlay(
                    faces: camera.liveFaces.map { ($0.id, $0.region, $0.keptVisible) },
                    mapping: mapping
                )

                // A tap keeps the face under the finger visible, or protects
                // it again.
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture { location in
                        let point = mapping.framePoint(location)
                        camera.toggleLiveFace(atX: point.x, y: point.y)
                    }

                controls
            }
        }
    }

    private var controls: some View {
        VStack {
            HStack {
                Text(camera.isRecording
                     ? "Recording · \(camera.recordedSeconds)s"
                     : "\(camera.liveFaces.count) face\(camera.liveFaces.count == 1 ? "" : "s")")
                    .font(.footnote.weight(.semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(camera.isRecording ? Color.red : Color.black.opacity(0.55),
                                in: Capsule())
                Spacer()
                Button {
                    showSettings = true
                } label: {
                    Image(systemName: "slider.horizontal.3")
                        .padding(10)
                        .background(Color.black.opacity(0.55), in: Circle())
                }
            }
            .padding(.horizontal)

            Spacer()

            Text(camera.liveFaces.contains { $0.keptVisible }
                 ? "Tap a face to protect it again"
                 : "Tap a face to keep it visible")
                .font(.footnote)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Color.black.opacity(0.55), in: Capsule())

            HStack(spacing: 40) {
                Button {
                    camera.capturePhoto()
                } label: {
                    Circle()
                        .strokeBorder(.white, lineWidth: 4)
                        .frame(width: 72, height: 72)
                        .overlay(Circle().fill(.white).frame(width: 58, height: 58))
                }
                .disabled(camera.isBusy || camera.isRecording)

                Button {
                    camera.toggleRecording()
                } label: {
                    Circle()
                        .fill(Color.red)
                        .frame(width: 58, height: 58)
                        .overlay {
                            if camera.isRecording {
                                RoundedRectangle(cornerRadius: 4)
                                    .fill(.white)
                                    .frame(width: 22, height: 22)
                            }
                        }
                }
            }
            .padding(.bottom, 28)
        }
        .foregroundStyle(.white)
        .padding(.top, 8)
    }

    @ViewBuilder
    private var banner: some View {
        if let message = camera.message {
            HStack {
                Text(message)
                Spacer()
                if !camera.shareItems.isEmpty {
                    Button("Share") { showShare = true }
                }
                Button("Dismiss") { camera.consumeMessage() }
            }
            .font(.footnote)
            .padding()
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
            .padding()
            .task {
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                camera.consumeMessage()
            }
        }
    }
}
