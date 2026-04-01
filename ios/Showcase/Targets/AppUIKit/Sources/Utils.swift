import CryptoKit
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import Foundation
import SwiftUI
import Network

// modifier
struct HideViewModifier: ViewModifier {
    let isHidden: Bool
    @ViewBuilder func body(content: Content) -> some View {
        if isHidden {
            EmptyView()
        } else {
            content
        }
    }
}

extension View {
    func hide(if isHiddden: Bool) -> some View {
        ModifiedContent(content: self,
                        modifier: HideViewModifier(isHidden: isHiddden)
        )
    }
}

extension RequestedField: Hashable, Equatable {
    public static func ==(lhs: RequestedField, rhs: RequestedField) -> Bool {
        return ObjectIdentifier(lhs) == ObjectIdentifier(rhs)
    }

    public func hash(into hasher: inout Hasher) {
         hasher.combine(ObjectIdentifier(self))
    }
}

struct iOSCheckboxToggleStyle: ToggleStyle {
    let enabled: Bool

    init(enabled: Bool = true) {
        self.enabled = enabled
    }

    func makeBody(configuration: Configuration) -> some View {
        Button(action: {
            configuration.isOn.toggle()
        }, label: {
            HStack {
                if configuration.isOn {
                    ZStack {
                        RoundedRectangle(cornerRadius: 2)
                            .stroke(Color("ColorBlue600"), lineWidth: 1)
                            .background(Color("ColorBlue600"))
                            .frame(width: 20, height: 20)
                            .opacity(enabled ? 1 : 0.5)
                        Image(systemName: "checkmark")
                            .foregroundColor(.white)
                    }
                } else {
                    RoundedRectangle(cornerRadius: 2)
                        .stroke(Color("ColorStone300"), lineWidth: 1)
                        .frame(width: 20, height: 20)
                }
                configuration.label
            }
        })
    }
}

extension Optional {
    enum Error: Swift.Error {
        case unexpectedNil
    }

    func unwrap() throws -> Wrapped {
        if let self { return self } else { throw Error.unexpectedNil }
    }
}

func generateQRCode(from data: Data) -> UIImage {
    let context = CIContext()
    let filter = CIFilter.qrCodeGenerator()
    filter.message = data
    if let outputImage = filter.outputImage {
        if let cgimg = context.createCGImage(outputImage, from: outputImage.extent) {
            return UIImage(cgImage: cgimg)
        }
    }
    return UIImage(systemName: "xmark.circle") ?? UIImage()
}

func checkInternetConnection() -> Bool {
    let monitor = NWPathMonitor()
    let queue = DispatchQueue.global(qos: .background)
    var isConnected = false

    let semaphore = DispatchSemaphore(value: 0)

    monitor.pathUpdateHandler = { path in
        isConnected = (path.status == .satisfied)
        semaphore.signal()
        monitor.cancel()
    }

    monitor.start(queue: queue)
    semaphore.wait()

    return isConnected
}

func generateTxtFile(content: String, filename: String) -> URL? {
    var fileURL: URL!
    do {
        let path = try FileManager.default.url(for: .documentDirectory,
                                               in: .allDomainsMask,
                                               appropriateFor: nil,
                                               create: false)

        fileURL = path.appendingPathComponent(filename)

        // append content to file
        try content.write(to: fileURL, atomically: true, encoding: .utf8)
        return fileURL
    } catch {
        print("error generating .txt file")
    }
    return nil
}

func convertDictToJSONString(dict: [String: GenericJSON]) -> String? {
    let encoder = JSONEncoder()
    encoder.outputFormatting = .prettyPrinted

    do {
        let jsonData = try encoder.encode(dict)
        return String(data: jsonData, encoding: .utf8)
    } catch {
        print("Error encoding JSON: \(error)")
        return nil
    }
}

func prettyPrintedJSONString(from jsonString: String) -> String? {
    guard let jsonData = jsonString.data(using: .utf8) else {
        print("Invalid JSON string")
        return nil
    }

    guard let jsonObject = try? JSONSerialization.jsonObject(with: jsonData, options: []) else {
        print("Invalid JSON format")
        return nil
    }

    guard let prettyData = try? JSONSerialization.data(withJSONObject: jsonObject, options: .prettyPrinted) else {
        print("Failed to pretty print JSON")
        return nil
    }

    return String(data: prettyData, encoding: .utf8)
}

extension Sequence {
    func asyncMap<T>(_ transform: @escaping (Element) async throws -> T) async rethrows -> [T] {
        var results = [T]()
        for element in self {
            let result = try await transform(element)
            results.append(result)
        }
        return results
    }
}

extension Sequence {
    func asyncForEach(
        _ operation: (Element) async throws -> Void
    ) async rethrows {
        for element in self {
            try await operation(element)
        }
    }
}

let trustedDids: [String] = []

func convertToGenericJSON(map: [String: [String: MDocItem]]) -> GenericJSON {
    var jsonObject: [String: GenericJSON] = [:]

    for (key, value) in map {
        jsonObject[key] = mapToGenericJSON(value)
    }

    return .object(jsonObject)
}

func mapToGenericJSON(_ map: [String: MDocItem]) -> GenericJSON {
    var jsonObject: [String: GenericJSON] = [:]

    for (key, value) in map {
        jsonObject[key] = convertMDocItemToGenericJSON(value)
    }

    return .object(jsonObject)
}

func convertMDocItemToGenericJSON(_ item: MDocItem) -> GenericJSON {
    switch item {
    case .text(let value):
        return .string(value)
    case .bool(let value):
        return .bool(value)
    case .integer(let value):
        return .number(Double(value))
    case .itemMap(let value):
        return mapToGenericJSON(value)
    case .array(let value):
        return .array(value.map { convertMDocItemToGenericJSON($0) })
    }
}
