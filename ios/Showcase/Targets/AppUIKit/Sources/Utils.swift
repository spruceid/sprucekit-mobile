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

extension Color {
    init(hex: String) {
        var hexSanitized = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        hexSanitized = hexSanitized.replacingOccurrences(of: "#", with: "")

        var rgb: UInt64 = 0
        Scanner(string: hexSanitized).scanHexInt64(&rgb)

        let r = Double((rgb >> 16) & 0xFF) / 255.0
        let g = Double((rgb >> 8) & 0xFF) / 255.0
        let b = Double(rgb & 0xFF) / 255.0

        self.init(red: r, green: g, blue: b)
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

// MARK: - Credential Field Type and Formatting

enum CredentialFieldType {
    case text
    case date
    case image
}

func getCredentialFieldType(displayName: String, fieldValue: String = "") -> CredentialFieldType {
    let lowerDisplayName = displayName.lowercased()
    let lowerValue = fieldValue.lowercased()

    if lowerDisplayName.contains("image") && !lowerDisplayName.contains("date") {
        return .image
    }
    if lowerDisplayName.contains("portrait") && !lowerDisplayName.contains("date") {
        return .image
    }
    if lowerValue.contains("data:image") || (lowerValue.hasPrefix("http") && (lowerValue.contains(".jpg") || lowerValue.contains(".png") || lowerValue.contains(".jpeg"))) {
        return .image
    }

    if lowerDisplayName.contains("date") || lowerDisplayName.contains("from") || lowerDisplayName.contains("until") || lowerDisplayName.contains("expiry") {
        return .date
    }

    if fieldValue.range(of: #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z?$"#, options: .regularExpression) != nil {
        return .date
    }
    if fieldValue.range(of: #"^\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil {
        return .date
    }

    return .text
}

func formatCredentialFieldValue(fieldValue: String, fieldType: CredentialFieldType, fieldName: String = "", maxLength: Int = 17) -> String {
    switch fieldType {
    case .date:
        return formatDateValue(fieldValue) ?? fieldValue

    case .text:
        if let formattedDate = formatDateValue(fieldValue) {
            return formattedDate
        }

        if fieldName.lowercased() == "sex" {
            switch fieldValue {
            case "1": return "M"
            case "2": return "F"
            case "M", "m", "male": return "M"
            case "F", "f", "female": return "F"
            default: return fieldValue.uppercased()
            }
        }

        if fieldValue.lowercased() == "true" {
            return "True"
        }
        if fieldValue.lowercased() == "false" {
            return "False"
        }

        let titleCaseValue = fieldValue.split(separator: " ")
            .map { word -> String in
                guard !word.isEmpty else { return "" }
                let firstChar = word.prefix(1).uppercased()
                let rest = word.dropFirst()
                return firstChar + rest
            }
            .joined(separator: " ")

        if maxLength > 0 && titleCaseValue.count > maxLength + 3 {
            let index = titleCaseValue.index(titleCaseValue.startIndex, offsetBy: maxLength)
            return String(titleCaseValue[..<index]) + "..."
        }

        return titleCaseValue

    case .image:
        return fieldValue
    }
}

private func formatDateValue(_ fieldValue: String) -> String? {
    let dateFormatter = DateFormatter()
    let outputFormatter = DateFormatter()
    outputFormatter.dateFormat = "MMM dd, yyyy"

    if fieldValue.range(of: #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z?$"#, options: .regularExpression) != nil {
        if fieldValue.hasSuffix("Z") {
            dateFormatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
        } else {
            dateFormatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        }

        if let date = dateFormatter.date(from: fieldValue) {
            return outputFormatter.string(from: date)
        }
    }

    if fieldValue.range(of: #"^\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil {
        dateFormatter.dateFormat = "yyyy-MM-dd"
        if let date = dateFormatter.date(from: fieldValue) {
            return outputFormatter.string(from: date)
        }
    }

    return nil
}

struct RenderCredentialFieldValue: View {
    let fieldType: CredentialFieldType
    let rawFieldValue: String
    let formattedValue: String
    let displayName: String

    var body: some View {
        switch fieldType {
        case .image:
            if !rawFieldValue.isEmpty {
                CredentialImage(image: rawFieldValue)
                    .frame(width: 100, height: 100)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                    .overlay(
                        RoundedRectangle(cornerRadius: 4)
                            .stroke(Color.black.opacity(0.1), lineWidth: 1)
                    )
            }

        case .date, .text:
            Text(formattedValue)
                .font(.customFont(font: .inter, style: .regular, size: .h4))
                .foregroundColor(Color("ColorStone950"))
                .multilineTextAlignment(.trailing)
        }
    }
}

extension String {
    func getKeyReadable() -> String {
        if self == "un_distinguishing_sign" {
            return "country_code"
        }
        return self.replacingOccurrences(of: "_", with: " ")
    }

    func toTitle() -> String {
        if self == self.uppercased() || self.first?.isUppercase == true {
            return self
        }

        return self
            .split(separator: " ")
            .map { word in
                guard !word.isEmpty else { return "" }
                let firstChar = word.prefix(1).uppercased()
                let rest = word.dropFirst().lowercased()
                return firstChar + rest
            }
            .joined(separator: " ")
    }
}
