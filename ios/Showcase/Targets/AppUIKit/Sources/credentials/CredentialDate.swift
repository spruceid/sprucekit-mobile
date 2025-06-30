import SwiftUI

struct CredentialDate: View {
    let dateString: String
    let parsedDate: String

    let dateTimeFormatter = {
        let dtFormatter = DateFormatter()
        dtFormatter.dateStyle = .medium
        dtFormatter.timeStyle = .short
        dtFormatter.locale = Locale(identifier: "en_US_POSIX")
        dtFormatter.timeZone = TimeZone(secondsFromGMT: 0)
        return dtFormatter
    }()

    let dateOnlyFormatter = {
        let dtFormatter = DateFormatter()
        dtFormatter.dateStyle = .medium
        dtFormatter.timeStyle = .none
        dtFormatter.locale = Locale(identifier: "en_US_POSIX")
        dtFormatter.timeZone = TimeZone(secondsFromGMT: 0)
        return dtFormatter
    }()

    let genericDateOnlyFormatter = {
        let dtFormatter = DateFormatter()
        dtFormatter.locale = Locale(identifier: "en_US_POSIX")  // set locale to reliable US_POSIX
        dtFormatter.dateFormat = "yyyy-MM-dd"
        dtFormatter.timeZone = TimeZone(secondsFromGMT: 0)
        return dtFormatter
    }()

    let genericDateTimeFormatter = {
        let dtFormatter = DateFormatter()
        dtFormatter.locale = Locale(identifier: "en_US_POSIX")  // set locale to reliable US_POSIX
        dtFormatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ssZ"
        return dtFormatter
    }()

    init(dateString: String) {
        self.dateString = dateString
        if let iso8681Date = ISO8601DateFormatter().date(
            from:
                dateString
                .replacingOccurrences(
                    of: "\\.\\d+", with: "", options: .regularExpression)
        ) {
            self.parsedDate = dateTimeFormatter.string(from: iso8681Date)
        } else if let date = genericDateTimeFormatter.date(from: dateString) {
            self.parsedDate = dateTimeFormatter.string(from: date)
        } else if let date = genericDateOnlyFormatter.date(from: dateString) {
            self.parsedDate = dateOnlyFormatter.string(from: date)
        } else if let timestamp = Double(dateString) {
            let date = Date(timeIntervalSince1970: timestamp);
            self.parsedDate = dateOnlyFormatter.string(from: date)
        } else {
            print("no... \(dateString)")
            self.parsedDate = dateString
        }
    }

    var body: some View {
        Text(parsedDate)
    }
}
