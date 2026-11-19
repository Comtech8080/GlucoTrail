import XCTest
import UIKit
import ImageIO
import UniformTypeIdentifiers
@testable import GlucoTrail

final class PhotoPreparationTests: XCTestCase {
    func testPhotoIsDownsampledAndReencodedWithoutSourceMetadata() throws {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: 3200, height: 2000), format: format).image { context in
            UIColor.blue.setFill(); context.fill(CGRect(x: 0, y: 0, width: 3200, height: 2000))
        }
        let tagged = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(tagged as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, try XCTUnwrap(image.cgImage), [kCGImagePropertyGPSDictionary:
            [kCGImagePropertyGPSLatitude: 42.0, kCGImagePropertyGPSLatitudeRef: "N",
             kCGImagePropertyGPSLongitude: 79.0, kCGImagePropertyGPSLongitudeRef: "W"]] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        let taggedSource = try XCTUnwrap(CGImageSourceCreateWithData(tagged as CFData, nil))
        let taggedProperties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(taggedSource, 0, nil) as? [String: Any])
        XCTAssertNotNil(taggedProperties[kCGImagePropertyGPSDictionary as String])
        let data = try JournalController.optimizedPhoto(tagged as Data)
        let source = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
        let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any])
        XCTAssertLessThanOrEqual(try XCTUnwrap(properties[kCGImagePropertyPixelWidth as String] as? Int), 1600)
        XCTAssertLessThanOrEqual(try XCTUnwrap(properties[kCGImagePropertyPixelHeight as String] as? Int), 1600)
        XCTAssertNil(properties[kCGImagePropertyGPSDictionary as String])
        XCTAssertLessThanOrEqual(data.count, 2_000_000)
    }
    func testInvalidOrOversizedSourceIsRejected() {
        XCTAssertThrowsError(try JournalController.optimizedPhoto(Data("bad image".utf8)))
        XCTAssertThrowsError(try JournalController.optimizedPhoto(Data(repeating: 0, count: 25_000_001)))
    }
}
