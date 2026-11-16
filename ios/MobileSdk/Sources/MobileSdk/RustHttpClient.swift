import Foundation
import SpruceIDMobileSdkRs

/// Routes every HTTP request that the Rust layer makes through a native client.
///
/// The native client obeys the proxy settings and the trust store of the device.
/// Call `configure` once when the app starts. A later call replaces the client.
/// Without this call the Rust layer uses its own HTTP client, and that client
/// does not read the device proxy settings.
public enum RustHttpClient {
    public static func configure(client: AsyncHttpClient = Oid4vciAsyncHttpClient()) {
        configureHttpClient(client: client)
    }
}
