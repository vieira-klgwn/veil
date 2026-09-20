import SwiftUI
import VeilPrivacy

/// The protected photo, before it is saved anywhere. Tapping a face brings it
/// back into the picture; tapping it again protects it.
struct ReviewScreen: View {

    @ObservedObject var camera: CameraController
    let photo: ProtectedPhoto
    @Binding var showShare: Bool

    var body: some View {
        VStack(spacing: 0) {
            GeometryReader { geometry in
                let mapping = FrameMapping(
                    frame: CGSize(width: photo.original.width, height: photo.original.height),
                    view: geometry.size,
                    fill: false
                )
                ZStack {
                    Image(uiImage: photo.image)
                        .resizable()
                        .scaledToFit()
                    FaceOverlay(
                        faces: photo.faces.map {
                            ($0.id, $0.region, photo.keptVisible.contains($0.id))
                        },
                        mapping: mapping
                    )
                    Color.clear
                        .contentShape(Rectangle())
                        .onTapGesture { location in
                            let point = mapping.framePoint(location)
                            camera.toggleReviewFace(atX: point.x, y: point.y)
                        }
                }
            }

            VStack(spacing: 12) {
                Text("\(photo.facesProtected) of \(photo.faces.count) face\(photo.faces.count == 1 ? "" : "s") protected · tap a face to keep it visible")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                Picker("Effect", selection: $camera.effect) {
                    Text("Blur").tag(PrivacyEffect.blur)
                    Text("Pixelate").tag(PrivacyEffect.pixelate)
                    Text("Mask").tag(PrivacyEffect.mask)
                }
                .pickerStyle(.segmented)

                HStack(spacing: 12) {
                    Button("Retake") { camera.discardPhoto() }
                        .buttonStyle(.bordered)
                    Button("Share") { showShare = true }
                        .buttonStyle(.bordered)
                    Button("Save") { camera.savePhoto() }
                        .buttonStyle(.borderedProminent)
                }
            }
            .padding()
        }
        .foregroundStyle(.white)
    }
}
