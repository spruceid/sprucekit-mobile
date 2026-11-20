import Foundation
import SpruceIDMobileSdkRs

/// Redirect rule for the native HTTP clients that carry the Rust layer's requests.
///
/// The Rust layer checks the first URL of every request (`HttpClient::send`), but it never sees
/// the redirects that `URLSession` follows. A redirect from an https endpoint to plain http would
/// turn a 307 or 308 on the OID4VP response POST into a clear-text resend of the `vp_token`. App
/// Transport Security blocks that hop only when the app has no ATS exception.
///
/// The rule matches the Rust layer's `require_https`:
/// - a redirect to `https` is followed;
/// - a redirect to `http` is followed only in debug builds, and only to a loopback or private LAN
///   host: `localhost`, IPv4 `127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`,
///   IPv6 `::1` and `fc00::/7`;
/// - every other redirect is refused, and the request fails with ``refusedError(for:)``.
final class HttpsRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        // Passing nil stops the redirect: the task completes with the 3xx response, which
        // `refusedRedirectTarget(in:)` then turns into an error.
        completionHandler(Self.isAllowed(request.url) ? request : nil)
    }

    /// Whether a request may go to `url`.
    static func isAllowed(_ url: URL?) -> Bool {
        guard let url, let scheme = url.scheme?.lowercased() else {
            return false
        }
        if scheme == "https" {
            return true
        }
        #if DEBUG
            if scheme == "http", let host = url.host {
                return isDevHost(host)
            }
        #endif
        return false
    }

    /// Return true for `localhost` or a loopback or private LAN IP literal.
    static func isDevHost(_ rawHost: String) -> Bool {
        let host = rawHost.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if host.caseInsensitiveCompare("localhost") == .orderedSame {
            return true
        }

        var ipv4 = in_addr()
        if inet_pton(AF_INET, host, &ipv4) == 1 {
            let octets = withUnsafeBytes(of: ipv4) { Array($0) }
            return octets[0] == 127
                || octets[0] == 10
                || (octets[0] == 172 && (16...31).contains(octets[1]))
                || (octets[0] == 192 && octets[1] == 168)
        }

        var ipv6 = in6_addr()
        if inet_pton(AF_INET6, host, &ipv6) == 1 {
            let bytes = withUnsafeBytes(of: ipv6) { Array($0) }
            let isLoopback = bytes[0..<15].allSatisfy { $0 == 0 } && bytes[15] == 1
            let isUniqueLocal = (bytes[0] & 0xfe) == 0xfc
            return isLoopback || isUniqueLocal
        }

        return false
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
