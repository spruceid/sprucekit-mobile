import Foundation

func generateCSV(heading: String, rows: String, filename: String) -> URL? {
    var fileURL: URL!

    // file rows
    let stringData = heading + rows

    do {
        let path = try FileManager.default.url(for: .documentDirectory,
                                               in: .allDomainsMask,
                                               appropriateFor: nil,
                                               create: false)

        fileURL = path.appendingPathComponent(filename)

        // append string data to file
        try stringData.write(to: fileURL, atomically: true , encoding: .utf8)
        return fileURL
    } catch {
        print("error generating csv file")
    }

    return nil
}
