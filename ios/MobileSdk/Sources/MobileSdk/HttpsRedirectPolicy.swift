import Foundation
import SpruceIDMobileSdkRs

/// Redirect rule for the native HTTP clients that carry the Rust layer's requests.
///
/// The Rust layer checks the first URL of every request, but `URLSession` follows redirects on
/// its own, and a 307 or 308 resends the request body. Each redirect target therefore goes
/// through the Rust layer's rule, `isAllowedRequestUrl(url:)`: https, or plain http to a dev host
/// only after `setAllowDevHttp(allow: true)`. A refused redirect fails the request with
/// ``refusedError(for:)``.
final class HttpsRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        // nil stops the redirect: the task completes with the 3xx response, which
        // `refusedRedirectTarget(in:)` turns into an error.
        completionHandler(Self.isAllowed(request.url) ? request : nil)
    }

    /// Whether a request may go to `url`.
    static func isAllowed(_ url: URL?) -> Bool {
        guard let url else {
            return false
        }
        return isAllowedRequestUrl(url: url.absoluteString)
    }

    /// The status codes that `URLSession` follows on its own. When the task ends on one of them
    /// with a `Location`, the delegate refused the redirect. Other 3xx codes (300, 304) never
    /// reach the delegate and are returned as they are.
    static let followedRedirectCodes: Set<Int> = [301, 302, 303, 307, 308]

    /// The redirect target of a response that ended on a refused redirect, if any.
    static func refusedRedirectTarget(in response: HTTPURLResponse) -> URL? {
        guard followedRedirectCodes.contains(response.statusCode),
            let location = response.value(forHTTPHeaderField: "Location"),
            let target = URL(string: location, relativeTo: response.url)?.absoluteURL,
            !isAllowed(target)
        else {
            return nil
        }
        return target
    }

    /// The error for a refused redirect. The wording matches the Rust layer's.
    static func refusedError(for target: URL) -> HttpClientError {
        .Other(error: "refused redirect target `\(target.absoluteString)`: https is required")
    }
}
