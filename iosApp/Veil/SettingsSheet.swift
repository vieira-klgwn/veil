import SwiftUI
import VeilPrivacy

struct SettingsSheet: View {
    @ObservedObject var camera: CameraController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Effect") {
                    Picker("Effect", selection: $camera.effect) {
                        Text("Blur").tag(PrivacyEffect.blur)
                        Text("Pixelate").tag(PrivacyEffect.pixelate)
                        Text("Mask").tag(PrivacyEffect.mask)
                    }
                    .pickerStyle(.segmented)
                }
                Section("Strength") {
                    Picker("Strength", selection: $camera.strength) {
                        Text("Balanced").tag(PrivacyStrength.balanced)
                        Text("Maximum").tag(PrivacyStrength.maximum)
                    }
                    .pickerStyle(.segmented)
                    Text("Maximum widens the protected area and strengthens the effect. Everything outside a face is left untouched either way.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Section("Privacy") {
                    Label("Faces are found on this device", systemImage: "iphone")
                    Label("Nothing is uploaded, no identity is recognised", systemImage: "wifi.slash")
                    Label("Only the protected copy is ever saved", systemImage: "lock.fill")
                }
            }
            .navigationTitle("Veil")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
