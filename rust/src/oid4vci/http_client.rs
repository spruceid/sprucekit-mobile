use std::{collections::HashMap, future::Future, pin::Pin, str::FromStr, sync::Arc};

use async_trait::async_trait;
use oid4vci::oauth2::{
    http::{HeaderMap, Method, Request, Response, StatusCode, Uri},
    HttpRequest as ExtHttpRequest, HttpResponse as ExtHttpResponse,
    SyncHttpClient as ExtSyncHttpClient,
};

#[derive(thiserror::Error, uniffi::Error, Debug)]
pub enum HttpClientError {
    #[error("failed to build request")]
    RequestBuilder,

    #[error("failed to build response")]
    ResponseBuilder,

    #[error("failed to parse url")]
    UrlParse,

    #[error("failed to parse method")]
    MethodParse,

    #[error("failed to parse header")]
    HeaderParse,

    #[error("failed to parse header key: {key}")]
    HeaderKeyParse { key: String },

    #[error("failed to parse header value: {value}")]
    HeaderValueParse { value: String },

    #[error("failed to parse header entry: ({key}, {value})")]
    HeaderEntryParse { key: String, value: String },

    #[error("other error: {error}")]
    Other { error: String },
}

impl From<String> for HttpClientError {
    fn from(value: String) -> Self {
        Self::Other { error: value }
    }
}

#[derive(uniffi::Record, Clone, Debug)]
/// Plain Rust object representation of an HttpRequest that can be exported
/// through `uniffi` and is used in `WithForeign` trait definitions for HTTP
/// clients.
pub struct HttpRequest {
    pub url: String,
    pub method: String,
    pub headers: HashMap<String, String>,
    pub body: Vec<u8>,
}

impl TryFrom<ExtHttpRequest> for HttpRequest {
    type Error = HttpClientError;

    fn try_from(req: ExtHttpRequest) -> Result<Self, Self::Error> {
        Ok(Self {
            url: req.uri().to_string(),
            method: req.method().to_string(),
            headers: headermap_to_hashmap(req.headers())?,
            body: req.body().clone(),
        })
    }
}

impl TryInto<ExtHttpRequest> for HttpRequest {
    type Error = HttpClientError;

    fn try_into(self) -> Result<ExtHttpRequest, Self::Error> {
        let mut request = Request::builder()
            .method(Method::from_str(&self.method).map_err(|_| HttpClientError::MethodParse)?)
            .uri(Uri::from_str(&self.url).map_err(|_| HttpClientError::UrlParse)?);

        for (k, v) in self.headers {
            request = request.header(k, v);
        }

        request
            .body(self.body)
            .map_err(|_| HttpClientError::RequestBuilder)
    }
}

#[derive(uniffi::Record, Clone, Debug)]
/// Plain Rust object representation of an HttpResponse that can be exported
/// through `uniffi` and is used in `WithForeign` trait definitions for HTTP
/// clients.
pub struct HttpResponse {
    pub status_code: u16,
    pub headers: HashMap<String, String>,
    pub body: Vec<u8>,
}

impl TryFrom<ExtHttpResponse> for HttpResponse {
    type Error = HttpClientError;

    fn try_from(res: ExtHttpResponse) -> Result<Self, Self::Error> {
        Ok(Self {
            status_code: res.status().as_u16(),
            headers: headermap_to_hashmap(res.headers())?,
            body: res.body().clone(),
        })
    }
}

impl TryInto<ExtHttpResponse> for HttpResponse {
    type Error = HttpClientError;

    fn try_into(self) -> Result<ExtHttpResponse, Self::Error> {
        let mut response = Response::builder().status(
            StatusCode::from_u16(self.status_code)
                .map_err(|_| "failed to parse status code".to_string())
                .map_err(HttpClientError::from)?,
        );

        for (k, v) in self.headers {
            response = response.header(k, v);
        }

        response
            .body(self.body)
            .map_err(|_| HttpClientError::ResponseBuilder)
    }
}

#[uniffi::export(with_foreign)]
pub trait SyncHttpClient: Send + Sync {
    fn http_client(&self, request: HttpRequest) -> Result<HttpResponse, HttpClientError>;
}

impl ExtSyncHttpClient for dyn SyncHttpClient {
    type Error = HttpClientError;

    fn call(&self, request: ExtHttpRequest) -> Result<ExtHttpResponse, Self::Error> {
        let request: HttpRequest = request.try_into()?;
        let response: HttpResponse = self.http_client(request)?;
        let response: ExtHttpResponse = response.try_into()?;
        Ok::<_, HttpClientError>(response)
    }
}

#[uniffi::export(with_foreign)]
#[async_trait]
pub trait AsyncHttpClient: Send + Sync {
    async fn http_client(&self, request: HttpRequest) -> Result<HttpResponse, HttpClientError>;
}

pub struct Oid4vciHttpClient(pub Arc<dyn AsyncHttpClient>);

impl<'c> oid4vci::oauth2::AsyncHttpClient<'c> for Oid4vciHttpClient {
    type Error = HttpClientError;
    type Future =
        Pin<Box<dyn Future<Output = Result<ExtHttpResponse, HttpClientError>> + Send + 'c>>;

    fn call(&'c self, request: ExtHttpRequest) -> Self::Future {
        Box::pin(async move {
            let request: HttpRequest = request.try_into()?;
            let response: HttpResponse = self.0.http_client(request).await?;
            let response: ExtHttpResponse = response.try_into()?;
            Ok::<_, HttpClientError>(response)
        })
    }
}

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
