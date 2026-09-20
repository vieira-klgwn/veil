import Foundation
import Photos
import UIKit

/// Saves protected media to the photo library. Only the protected result is
/// ever written; the original frame never reaches storage.
enum MediaLibrary {

    private static let albumName = "Veil"

    static func savePhoto(_ image: UIImage) async throws {
        try await save {
            PHAssetChangeRequest.creationRequestForAsset(from: image)
        }
    }

    static func saveVideo(at url: URL) async throws {
        try await save {
            PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
        }
    }

    private static func save(
        _ makeRequest: @escaping () -> PHAssetChangeRequest?
    ) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw VeilError.captureFailed }
        let album = try await album()
        try await PHPhotoLibrary.shared().performChanges {
            guard let request = makeRequest() else { return }
            if let album, let placeholder = request.placeholderForCreatedAsset,
               let albumRequest = PHAssetCollectionChangeRequest(for: album) {
                albumRequest.addAssets([placeholder] as NSArray)
            }
        }
    }

    /// The "Veil" album, created on first save, so protected media is as easy
    /// to find as `Pictures/Veil` is on Android.
    private static func album() async throws -> PHAssetCollection? {
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "title = %@", albumName)
        let existing = PHAssetCollection.fetchAssetCollections(
            with: .album,
            subtype: .any,
            options: options
        )
        if let found = existing.firstObject { return found }

        var identifier: String?
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCollectionChangeRequest
                .creationRequestForAssetCollection(withTitle: albumName)
            identifier = request.placeholderForCreatedAssetCollection.localIdentifier
        }
        guard let identifier else { return nil }
        return PHAssetCollection.fetchAssetCollections(
            withLocalIdentifiers: [identifier],
            options: nil
        ).firstObject
    }
}
