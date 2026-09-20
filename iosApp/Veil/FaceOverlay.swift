import CoreGraphics
import SwiftUI
import VeilPrivacy

/// Maps between pixels of a camera frame (or image) and points of the view
/// showing it. The viewfinder fills the view, so the frame is scaled up until
/// it covers and the overflow is centred; a reviewed photo is fitted instead.
struct FrameMapping {
    let frame: CGSize
    let view: CGSize
    let fill: Bool

    private var scale: CGFloat {
        guard frame.width > 0, frame.height > 0 else { return 1 }
        let x = view.width / frame.width
        let y = view.height / frame.height
        return fill ? max(x, y) : min(x, y)
    }

    private var origin: CGPoint {
        CGPoint(
            x: (view.width - frame.width * scale) / 2,
            y: (view.height - frame.height * scale) / 2
        )
    }

    func point(x: Float, y: Float) -> CGPoint {
        CGPoint(x: origin.x + CGFloat(x) * scale, y: origin.y + CGFloat(y) * scale)
    }

    func length(_ value: Float) -> CGFloat { CGFloat(value) * scale }

    /// A tap in the view, back in frame pixels.
    func framePoint(_ point: CGPoint) -> (x: Float, y: Float) {
        (
            Float((point.x - origin.x) / scale),
            Float((point.y - origin.y) / scale)
        )
    }
}

/// Marks the faces the app is tracking: a solid ring for faces that will be
/// protected, a dashed ring for the ones the user chose to keep visible.
struct FaceOverlay: View {
    let faces: [(id: Int32, region: FaceRegion, keptVisible: Bool)]
    let mapping: FrameMapping

    var body: some View {
        ZStack {
            ForEach(faces, id: \.id) { face in
                let center = mapping.point(x: face.region.centerX, y: face.region.centerY)
                let width = mapping.length(face.region.radiusX * 2)
                let height = mapping.length(face.region.radiusY * 2)
                Ellipse()
                    .strokeBorder(
                        face.keptVisible ? Color.yellow : Color.white.opacity(0.9),
                        style: StrokeStyle(
                            lineWidth: 2,
                            dash: face.keptVisible ? [6, 5] : []
                        )
                    )
                    .frame(width: width, height: height)
                    .position(center)
                if face.keptVisible {
                    Text("visible")
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.yellow, in: Capsule())
                        .foregroundStyle(.black)
                        .position(x: center.x, y: max(12, center.y - height / 2 - 12))
                }
            }
        }
        .allowsHitTesting(false)
    }
}
