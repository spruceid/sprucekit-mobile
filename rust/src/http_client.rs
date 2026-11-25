//! Shared HTTP transport for the Rust layer.
//!
//! The native SDK registers the platform HTTP client once with
//! [`configure_http_client`]. Every HTTP request that the Rust layer makes then
//! goes through that client. The request obeys the proxy settings, the system
//! trust store, and the network policies of the app.
//!
//! When no client is registered, the Rust layer uses a `reqwest` client. That
//! fallback is for tests and for desktop use. It does not read the proxy
//! settings of a mobile device.

use std::{
    collections::HashMap,
    fmt,
    sync::{Arc, OnceLock, PoisonError, RwLock},
};

use async_trait::async_trait;
use http::{
    header::{HeaderMap, HeaderName, HeaderValue},
    Method, Request, Response, StatusCode,
};

use crate::https::{require_https, require_https_str};

pub use mobile_toolkit::http_client::{
    AsyncHttpClient, HttpClientError, HttpRequest, HttpResponse,
};

static NATIVE_HTTP_CLIENT: RwLock<Option<Arc<dyn AsyncHttpClient>>> = RwLock::new(None);
static FALLBACK_HTTP_CLIENT: OnceLock<Arc<dyn AsyncHttpClient>> = OnceLock::new();

/// Register the native HTTP client for the Rust layer.
///
/// Call this function once when the app starts. A later call replaces the
/// registered client. Requests that started before the call keep the client
/// that they started with.
#[uniffi::export]
pub fn configure_http_client(client: Arc<dyn AsyncHttpClient>) {
    *NATIVE_HTTP_CLIENT
        .write()
        .unwrap_or_else(PoisonError::into_inner) = Some(client);
}

#[cfg(test)]
pub(crate) fn reset_http_client() {
    *NATIVE_HTTP_CLIENT
        .write()
        .unwrap_or_else(PoisonError::into_inner) = None;
}

fn registered_client() -> Option<Arc<dyn AsyncHttpClient>> {
    NATIVE_HTTP_CLIENT
        .read()
        .unwrap_or_else(PoisonError::into_inner)
        .clone()
}

fn fallback_client() -> Arc<dyn AsyncHttpClient> {
    FALLBACK_HTTP_CLIENT
        .get_or_init(|| {
            match reqwest::Client::builder()
                .use_rustls_tls()
                .redirect(https_redirect_policy())
                .build()
            {
                Ok(client) => Arc::new(ReqwestHttpClient(client)),
                Err(error) => Arc::new(UnavailableHttpClient(error.to_string())),
            }
        })
        .clone()
}

/// Redirect policy of the fallback client: every target must pass
/// `require_https`, then reqwest's default limit applies.
///
/// `HttpClient::send` checks only the first URL, and a 307 or 308 resends the
/// request body to the target, so each target is checked here. reqwest's
/// `https_only` is not used: it has no dev-host exception, and the setting can
/// change after the client is built.
fn https_redirect_policy() -> reqwest::redirect::Policy {
    reqwest::redirect::Policy::custom(|attempt| {
        match require_https(attempt.url(), "redirect target") {
            Ok(()) => reqwest::redirect::Policy::default().redirect(attempt),
            Err(error) => attempt.error(error),
        }
    })
}

/// HTTP client for the Rust layer.
///
/// The client wraps the registered native client, or the `reqwest` fallback
/// when no native client is registered. It is cheap to clone.
#[derive(Clone)]
pub struct HttpClient(Arc<dyn AsyncHttpClient>);

impl HttpClient {
    /// Return the shared client. The registered native client wins over the
    /// fallback.
    pub fn shared() -> Self {
        Self(registered_client().unwrap_or_else(fallback_client))
    }

    /// Wrap a specific client. Tests and callers with a dedicated client use
    /// this constructor.
    pub fn from_native(client: Arc<dyn AsyncHttpClient>) -> Self {
        Self(client)
    }

    /// Send a request and return the full response.
    ///
    /// A URL that is not https is refused before it reaches the wrapped
    /// client; see `crate::https::require_https`.
    pub async fn send(
        &self,
        request: Request<Vec<u8>>,
    ) -> Result<Response<Vec<u8>>, HttpClientError> {
        require_https_str(&request.uri().to_string(), "request URL").map_err(|e| {
            HttpClientError::Other {
                error: e.to_string(),
            }
        })?;
        let request = request_to_native(request)?;
        let response = self.0.http_client(request).await?;
        response_from_native(response)
    }

    /// Send a GET request without a body.
    pub async fn get(&self, url: &str) -> Result<Response<Vec<u8>>, HttpClientError> {
        let request = Request::get(url)
            .body(Vec::new())
            .map_err(|_| HttpClientError::RequestBuilder)?;
        self.send(request).await
    }
}

impl fmt::Debug for HttpClient {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("HttpClient")
    }
}

#[async_trait]
impl openid4vp::core::util::AsyncHttpClient for HttpClient {
    async fn execute(&self, request: Request<Vec<u8>>) -> anyhow::Result<Response<Vec<u8>>> {
        self.send(request).await.map_err(anyhow::Error::from)
    }
}

#[async_trait]
impl openidvp_draft18::core::util::AsyncHttpClient for HttpClient {
    async fn execute(&self, request: Request<Vec<u8>>) -> anyhow::Result<Response<Vec<u8>>> {
        self.send(request).await.map_err(anyhow::Error::from)
    }
}

/// Convert an `http` request into the plain record that crosses the FFI
/// boundary.
pub(crate) fn request_to_native(request: Request<Vec<u8>>) -> Result<HttpRequest, HttpClientError> {
    Ok(HttpRequest {
        url: request.uri().to_string(),
        method: request.method().to_string(),
        headers: headermap_to_hashmap(request.headers())?,
        body: request.into_body(),
    })
}

/// Convert the plain response record from the FFI boundary into an `http`
/// response.
pub(crate) fn response_from_native(
    response: HttpResponse,
) -> Result<Response<Vec<u8>>, HttpClientError> {
    let status =
        StatusCode::from_u16(response.status_code).map_err(|_| HttpClientError::ResponseBuilder)?;
    let mut builder = Response::builder().status(status);
    for (key, value) in response.headers {
        let name = HeaderName::from_bytes(key.as_bytes())
            .map_err(|_| HttpClientError::HeaderKeyParse { key: key.clone() })?;
        let value =
            HeaderValue::from_str(&value).map_err(|_| HttpClientError::HeaderValueParse {
                value: value.clone(),
            })?;
        builder = builder.header(name, value);
    }
    builder
        .body(response.body)
        .map_err(|_| HttpClientError::ResponseBuilder)
}

/// Flatten a header map into one string per header name. Repeated headers
/// join with a comma.
pub(crate) fn headermap_to_hashmap(
    headers: &HeaderMap,
) -> Result<HashMap<String, String>, HttpClientError> {
    headers
        .keys()
        .map(|k| {
            Ok((
                k.to_string(),
                headers
                    .get_all(k)
                    .iter()
                    .map(|v| v.to_str().map_err(|_| HttpClientError::HeaderParse))
                    .collect::<Result<Vec<_>, _>>()?
                    .join(","),
            ))
        })
        .collect()
}

/// Fallback client for tests and desktop use.
struct ReqwestHttpClient(reqwest::Client);

#[async_trait]
impl AsyncHttpClient for ReqwestHttpClient {
    async fn http_client(&self, request: HttpRequest) -> Result<HttpResponse, HttpClientError> {
        let method = Method::from_bytes(request.method.as_bytes())
            .map_err(|_| HttpClientError::MethodParse)?;
        let mut builder = self.0.request(method, &request.url);
        for (key, value) in request.headers {
            builder = builder.header(key, value);
        }
        let response =
            builder
                .body(request.body)
                .send()
                .await
                .map_err(|e| HttpClientError::Other {
                    error: e.to_string(),
                })?;
        let status_code = response.status().as_u16();
        let headers = headermap_to_hashmap(response.headers())?;
        let body = response
            .bytes()
            .await
            .map_err(|e| HttpClientError::Other {
                error: e.to_string(),
            })?
            .to_vec();
        Ok(HttpResponse {
            status_code,
            headers,
            body,
        })
    }
}

/// Client that reports the fallback build error on every request.
struct UnavailableHttpClient(String);

#[async_trait]
impl AsyncHttpClient for UnavailableHttpClient {
    async fn http_client(&self, _request: HttpRequest) -> Result<HttpResponse, HttpClientError> {
        Err(HttpClientError::Other {
            error: format!("fallback HTTP client is not available: {}", self.0),
        })
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use wiremock::{
        matchers::{header, method, path},
        Mock, MockServer, ResponseTemplate,
    };

    use super::*;
    use crate::https::test_support::DevHttp;

    /// Records every request, then forwards it to the fallback client so
    /// other tests that run at the same time keep a working transport.
    struct RecordingClient {
        requests: Mutex<Vec<HttpRequest>>,
    }

    #[async_trait]
    impl AsyncHttpClient for RecordingClient {
        async fn http_client(&self, request: HttpRequest) -> Result<HttpResponse, HttpClientError> {
            self.requests
                .lock()
                .unwrap_or_else(PoisonError::into_inner)
                .push(request.clone());
            fallback_client().http_client(request).await
        }
    }

    #[tokio::test]
    async fn fallback_client_sends_get_and_returns_the_response() {
        let _dev = DevHttp::set(true).await;
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/status"))
            .respond_with(
                ResponseTemplate::new(200)
                    .insert_header("x-test", "yes")
                    .set_body_string("ok"),
            )
            .mount(&server)
            .await;

        let response = HttpClient::from_native(fallback_client())
            .get(&format!("{}/status", server.uri()))
            .await
            .expect("request succeeds");

        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()["x-test"], "yes");
        assert_eq!(response.body(), b"ok");
    }

    #[tokio::test]
    async fn registered_client_receives_every_request() {
        let _dev = DevHttp::set(true).await;
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/echo"))
            .and(header("x-request", "1"))
            .respond_with(ResponseTemplate::new(201).set_body_bytes(b"created".to_vec()))
            .mount(&server)
            .await;

        let recorder = Arc::new(RecordingClient {
            requests: Mutex::new(Vec::new()),
        });
        configure_http_client(recorder.clone());

        let url = format!("{}/echo", server.uri());
        let request = Request::post(&url)
            .header("x-request", "1")
            .body(b"payload".to_vec())
            .unwrap();
        let result = HttpClient::shared().send(request).await;
        reset_http_client();

        let response = result.expect("request succeeds");
        assert_eq!(response.status(), StatusCode::CREATED);
        assert_eq!(response.body(), b"created");

        let requests = recorder
            .requests
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        let recorded = requests
            .iter()
            .find(|r| r.url == url)
            .expect("the registered client saw the request");
        assert_eq!(recorded.method, "POST");
        assert_eq!(recorded.headers["x-request"], "1");
        assert_eq!(recorded.body, b"payload");
    }

    #[tokio::test]
    async fn send_refuses_http_to_a_public_host_before_the_client_sees_it() {
        let recorder = Arc::new(RecordingClient {
            requests: Mutex::new(Vec::new()),
        });
        let client = HttpClient::from_native(recorder.clone());

        for url in [
            "http://evil.example/cb",
            "ftp://evil.example/cb",
            "http://172.32.0.1/cb",
        ] {
            let request = Request::post(url)
                .body(b"vp_token=secret".to_vec())
                .unwrap();
            let error = client.send(request).await.unwrap_err();
            assert!(
                matches!(&error, HttpClientError::Other { error }
                    if error.contains("request URL") && error.contains("https is required")),
                "unexpected error for {url}: {error:?}"
            );
        }

        assert!(recorder
            .requests
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .is_empty());
    }

    #[tokio::test]
    async fn send_follows_the_dev_http_setting_after_the_client_is_built() {
        let server = MockServer::start().await;
        Mock::given(path("/ok"))
            .respond_with(ResponseTemplate::new(200).set_body_string("ok"))
            .mount(&server)
            .await;
        let client = HttpClient::from_native(fallback_client());
        let url = format!("{}/ok", server.uri());

        let dev = DevHttp::set(false).await;
        let error = client.get(&url).await.unwrap_err();
        assert!(
            matches!(&error, HttpClientError::Other { error } if error.contains("https is required")),
            "unexpected error: {error:?}"
        );
        drop(dev);

        let _dev = DevHttp::set(true).await;
        assert_eq!(client.get(&url).await.unwrap().body(), b"ok");
    }

    #[test]
    fn response_from_native_rejects_an_invalid_header_name() {
        let response = HttpResponse {
            status_code: 200,
            headers: HashMap::from([("bad header".to_string(), "value".to_string())]),
            body: Vec::new(),
        };

        assert!(matches!(
            response_from_native(response),
            Err(HttpClientError::HeaderKeyParse { key }) if key == "bad header"
        ));
    }

    /// With dev http allowed, a loopback server can redirect; the target is
    /// checked too.
    #[tokio::test]
    async fn fallback_client_refuses_a_redirect_to_a_public_http_host() {
        let _dev = DevHttp::set(true).await;
        let server = MockServer::start().await;
        Mock::given(path("/to-public"))
            .respond_with(
                ResponseTemplate::new(307).insert_header("location", "http://evil.example/cb"),
            )
            .mount(&server)
            .await;

        let request = Request::post(format!("{}/to-public", server.uri()))
            .body(b"vp_token=secret".to_vec())
            .unwrap();
        let error = HttpClient::from_native(fallback_client())
            .send(request)
            .await
            .unwrap_err();

        assert!(
            matches!(&error, HttpClientError::Other { error }
                // reqwest's message drops the policy's reason. "error following
                // redirect" comes only from a refused redirect; a fetch of
                // evil.example would fail with "error sending request".
                if error.starts_with("error following redirect")),
            "unexpected error: {error:?}"
        );
    }

    #[tokio::test]
    async fn fallback_client_follows_a_redirect_to_loopback() {
        let _dev = DevHttp::set(true).await;
        let server = MockServer::start().await;
        Mock::given(path("/to-loopback"))
            .respond_with(
                ResponseTemplate::new(307)
                    .insert_header("location", format!("{}/ok", server.uri())),
            )
            .mount(&server)
            .await;
        Mock::given(path("/ok"))
            .respond_with(ResponseTemplate::new(200).set_body_string("ok"))
            .mount(&server)
            .await;

        let response = HttpClient::from_native(fallback_client())
            .get(&format!("{}/to-loopback", server.uri()))
            .await
            .expect("redirect to loopback is followed");

        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.body(), b"ok");
    }

    async fn redirect_chain(server: &MockServer, hops: u32) {
        for n in 1..=hops {
            Mock::given(path(format!("/chain/{n}")))
                .respond_with(
                    ResponseTemplate::new(307)
                        .insert_header("location", format!("{}/chain/{}", server.uri(), n - 1)),
                )
                .mount(server)
                .await;
        }
        Mock::given(path("/chain/0"))
            .respond_with(ResponseTemplate::new(200).set_body_string("ok"))
            .mount(server)
            .await;
    }

    #[tokio::test]
    async fn fallback_client_follows_ten_redirects() {
        let _dev = DevHttp::set(true).await;
        let server = MockServer::start().await;
        redirect_chain(&server, 10).await;
        let response = HttpClient::from_native(fallback_client())
            .get(&format!("{}/chain/10", server.uri()))
            .await
            .expect("ten redirects are followed");
        assert_eq!(response.body(), b"ok");
    }

    #[tokio::test]
    async fn fallback_client_refuses_an_eleventh_redirect() {
        let _dev = DevHttp::set(true).await;
        let server = MockServer::start().await;
        redirect_chain(&server, 11).await;
        let error = HttpClient::from_native(fallback_client())
            .get(&format!("{}/chain/11", server.uri()))
            .await
            .unwrap_err();
        assert!(
            matches!(&error, HttpClientError::Other { error } if error.starts_with("error following redirect")),
            "unexpected error: {error:?}"
        );
    }
}
