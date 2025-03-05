import Foundation

import SpruceIDMobileSdkRs

public class Oid4vciSyncHttpClient: SyncHttpClient {
    public init() {}

    public func httpClient(request: HttpRequest) throws -> HttpResponse {
        guard let url = URL(string: request.url) else {
            throw HttpClientError.Other(error: "failed to construct URL")
        }

        let session = URLSession.shared
        var req = URLRequest(url: url,
                             cachePolicy: .useProtocolCachePolicy,
                             timeoutInterval: 60)
        req.httpMethod = request.method
        req.httpBody = request.body
        req.allHTTPHeaderFields = request.headers

        // Semaphore used to wait for the callback to complete
        let semaphore = DispatchSemaphore(value: 0)

        var data: Data?
        var response: URLResponse?
        var error: Error?

        let dataTask = session.dataTask(with: req) {
            data = $0
            response = $1
            error = $2

            // Signaling from inside the callback will let the outside function
            // know that `data`, `response` and `error` are ready to be read.
            semaphore.signal()
        }
        // Initiate execution of the http request
        dataTask.resume()

        // Blocking wait for the callback to signal back
        _ = semaphore.wait(timeout: .distantFuture)

        if let error {
            throw HttpClientError.Other(error: "failed to execute request: \(error)")
        }

        guard let response = response as? HTTPURLResponse else {
            throw HttpClientError.Other(error: "failed to parse response")
        }

        guard let data = data else {
            throw HttpClientError.Other(error: "failed to parse response data")
        }

        guard let statusCode = UInt16(exactly: response.statusCode) else {
            throw HttpClientError.Other(error: "failed to parse response status code")
        }

        let headers = try response.allHeaderFields.map({ (key, value) in
            guard let key = key as? String else {
                throw HttpClientError.HeaderParse
            }

            guard let value = value as? String else {
                throw HttpClientError.HeaderParse
            }

            return (key, value)
        })

        return HttpResponse(
            statusCode: statusCode,
            headers: Dictionary(uniqueKeysWithValues: headers),
            body: data)
    }
}

public class Oid4vciAsyncHttpClient: AsyncHttpClient {
    public init() {}

    public func httpClient(request: HttpRequest) async throws -> HttpResponse {
        guard let url = URL(string: request.url) else {
            throw HttpClientError.Other(error: "failed to construct URL")
        }

        let session = URLSession.shared
        var req = URLRequest(url: url,
                             cachePolicy: .useProtocolCachePolicy,
                             timeoutInterval: 60)
        req.httpMethod = request.method
        req.httpBody = request.body
        req.allHTTPHeaderFields = request.headers

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: req)
        } catch {
            throw HttpClientError.Other(error: "failed to execute request: \(error)")
        }

        guard let response = response as? HTTPURLResponse else {
            throw HttpClientError.Other(error: "failed to parse response")
        }

        guard let statusCode = UInt16(exactly: response.statusCode) else {
            throw HttpClientError.Other(error: "failed to parse response status code")
        }

        let headers = try response.allHeaderFields.map({ (key, value) in
            guard let key = key as? String else {
                throw HttpClientError.HeaderParse
            }

            guard let value = value as? String else {
                throw HttpClientError.HeaderParse
            }

            return (key, value)
        })

        return HttpResponse(
            statusCode: statusCode,
            headers: Dictionary(uniqueKeysWithValues: headers),
            body: data)
    }
}
