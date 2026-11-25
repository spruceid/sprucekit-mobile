import SpruceIDMobileSdkRs
import XCTest

@testable import MobileSdk

/// Serves canned responses so the tests need no network:
/// - `/to-public` redirects (307) to `http://evil.example/cb`;
/// - `/to-public-308` redirects (308) to `http://evil.example/cb`;
/// - `/hop` redirects (308) to `https://verifier.example/to-public`, so the second hop is http;
/// - `/to-https` redirects (307) to `https://verifier.example/ok`;
/// - `/choices` answers `300 Multiple Choices` with an http `Location`, which `URLSession`
///   never follows;
/// - anything else answers 200 with the body `ok`.
/// Every URL it is asked to load is recorded in `requested`.
final class RedirectStubProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var requestedURLs: [URL] = []

    static var requested: [URL] {
        lock.lock()
        defer { lock.unlock() }
        return requestedURLs
    }

    static func reset() {
        lock.lock()
        defer { lock.unlock() }
        requestedURLs = []
    }

    override static func canInit(with request: URLRequest) -> Bool { true }
    override static func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let client else { return }
        Self.lock.lock()
        Self.requestedURLs.append(url)
        Self.lock.unlock()

        let redirect: (status: Int, location: String)? =
            switch url.path {
            case "/to-public": (307, "http://evil.example/cb")
            case "/to-public-308": (308, "http://evil.example/cb")
            case "/hop": (308, "https://verifier.example/to-public")
            case "/to-https": (307, "https://verifier.example/ok")
            default: nil
            }

        if url.path == "/choices" {
            let response = HTTPURLResponse(
                url: url, statusCode: 300, httpVersion: "HTTP/1.1",
                headerFields: ["Location": "http://evil.example/cb"])!
            client.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client.urlProtocolDidFinishLoading(self)
            return
        }

        if let (status, location) = redirect, let target = URL(string: location) {
            let redirect = HTTPURLResponse(
                url: url, statusCode: status, httpVersion: "HTTP/1.1",
                headerFields: ["Location": location])!
            var next = request
            next.url = target
            client.urlProtocol(self, wasRedirectedTo: next, redirectResponse: redirect)
            // When the delegate refuses the redirect, the load ends with the 3xx response.
            client.urlProtocol(self, didReceive: redirect, cacheStoragePolicy: .notAllowed)
            client.urlProtocolDidFinishLoading(self)
            return
        }

        let response = HTTPURLResponse(
            url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: [:])!
        client.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client.urlProtocol(self, didLoad: Data("ok".utf8))
        client.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

final class HttpsRedirectPolicyTests: XCTestCase {
    private func stubConfiguration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RedirectStubProtocol.self]
        return configuration
    }

    private func post(_ url: String) -> HttpRequest {
        HttpRequest(url: url, method: "POST", headers: [:], body: Data("vp_token=secret".utf8))
    }

    override func setUp() {
        super.setUp()
        RedirectStubProtocol.reset()
        setAllowDevHttp(allow: false)
    }

    override func tearDown() {
        setAllowDevHttp(allow: false)
        super.tearDown()
    }

    private let devHttpURLs = [
        "http://localhost/cb", "http://127.0.0.1:3000/cb", "http://[::1]/cb", "http://10.0.2.2/cb",
        "http://192.168.1.10/cb", "http://[fd00::1]/cb"
    ]

    // MARK: The rule

    func testHttpsIsAllowed() {
        XCTAssertTrue(HttpsRedirectPolicy.isAllowed(URL(string: "https://verifier.example/cb")))
        XCTAssertTrue(HttpsRedirectPolicy.isAllowed(URL(string: "HTTPS://verifier.example/cb")))
    }

    func testPublicHttpAndOtherSchemesAreRefused() {
        for url in [
            "http://evil.example/cb",
            "http://172.32.0.1/cb",
            "http://localhost.evil.example/cb",
            "http://127.0.0.1.evil.example/cb",
            "http://dev.local/cb",
            "ftp://verifier.example/cb",
            "file:///etc/passwd",
            "openid4vp://authorize"
        ] {
            XCTAssertFalse(HttpsRedirectPolicy.isAllowed(URL(string: url)), url)
        }
        XCTAssertFalse(HttpsRedirectPolicy.isAllowed(nil))
    }

    func testDevHttpIsRefusedByDefault() {
        for url in devHttpURLs {
            XCTAssertFalse(HttpsRedirectPolicy.isAllowed(URL(string: url)), url)
        }
    }

    func testDevHttpIsAllowedAfterOptIn() {
        setAllowDevHttp(allow: true)
        for url in devHttpURLs {
            XCTAssertTrue(HttpsRedirectPolicy.isAllowed(URL(string: url)), url)
        }
        for url in ["http://evil.example/cb", "http://172.32.0.1/cb", "http://dev.local/cb"] {
            XCTAssertFalse(HttpsRedirectPolicy.isAllowed(URL(string: url)), url)
        }
    }

    func testRefusedRedirectTargetResolvesRelativeLocations() {
        let response = HTTPURLResponse(
            url: URL(string: "https://verifier.example/a/b")!, statusCode: 302,
            httpVersion: nil, headerFields: ["Location": "/c"])!
        XCTAssertNil(HttpsRedirectPolicy.refusedRedirectTarget(in: response))

        let downgrade = HTTPURLResponse(
            url: URL(string: "https://verifier.example/a")!, statusCode: 308,
            httpVersion: nil, headerFields: ["Location": "http://evil.example/cb"])!
        XCTAssertEqual(
            HttpsRedirectPolicy.refusedRedirectTarget(in: downgrade)?.absoluteString,
            "http://evil.example/cb")
    }

    // MARK: Through the clients

    func testAsyncClientRefusesARedirectToPublicHttp() async {
        await assertRefused(path: "/to-public")
    }

    func testAsyncClientRefusesA308ToPublicHttp() async {
        await assertRefused(path: "/to-public-308")
    }

    func testAsyncClientRefusesAnHttpHopAfterAnHttpsHop() async {
        await assertRefused(path: "/hop")
        XCTAssertTrue(RedirectStubProtocol.requested.contains { $0.path == "/to-public" })
    }

    func testAsyncClientReturnsA300AsItIs() async throws {
        let client = Oid4vciAsyncHttpClient(configuration: stubConfiguration())
        let response = try await client.httpClient(
            request: post("https://verifier.example/choices"))
        XCTAssertEqual(response.statusCode, 300)
    }

    private func assertRefused(path: String) async {
        let client = Oid4vciAsyncHttpClient(configuration: stubConfiguration())
        do {
            _ = try await client.httpClient(request: post("https://verifier.example\(path)"))
            XCTFail("the redirect to http was followed")
        } catch HttpClientError.Other(let error) {
            XCTAssertEqual(
                error, "refused redirect target `http://evil.example/cb`: https is required")
        } catch {
            XCTFail("unexpected error: \(error)")
        }
        XCTAssertFalse(RedirectStubProtocol.requested.contains { $0.host == "evil.example" })
    }

    func testAsyncClientFollowsARedirectToHttps() async throws {
        let client = Oid4vciAsyncHttpClient(configuration: stubConfiguration())
        let response = try await client.httpClient(
            request: post("https://verifier.example/to-https"))
        XCTAssertEqual(response.statusCode, 200)
        XCTAssertEqual(response.body, Data("ok".utf8))
    }

    func testSyncClientFollowsARedirectToHttps() throws {
        let client = Oid4vciSyncHttpClient(configuration: stubConfiguration())
        let response = try client.httpClient(request: post("https://verifier.example/to-https"))
        XCTAssertEqual(response.statusCode, 200)
        XCTAssertEqual(response.body, Data("ok".utf8))
    }

    func testSyncClientRefusesARedirectToPublicHttp() {
        let client = Oid4vciSyncHttpClient(configuration: stubConfiguration())
        XCTAssertThrowsError(
            try client.httpClient(request: post("https://verifier.example/to-public"))
        ) { error in
            guard case HttpClientError.Other(let message) = error else {
                return XCTFail("unexpected error: \(error)")
            }
            XCTAssertTrue(message.contains("refused redirect target"), message)
        }
        XCTAssertFalse(RedirectStubProtocol.requested.contains { $0.host == "evil.example" })
    }
}
