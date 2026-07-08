use std::{collections::HashMap, future::Future, pin::Pin, sync::Arc};

use oid4vci::oauth2::{
    http::{HeaderMap, Response, StatusCode},
    HttpRequest as ExtHttpRequest, HttpResponse as ExtHttpResponse,
    SyncHttpClient as ExtSyncHttpClient,
};

pub use mobile_toolkit::http_client::{
    AsyncHttpClient, HttpClientError, HttpRequest, HttpResponse,
};

fn ext_request_to_http(req: ExtHttpRequest) -> Result<HttpRequest, HttpClientError> {
    Ok(HttpRequest {
        url: req.uri().to_string(),
        method: req.method().to_string(),
        headers: headermap_to_hashmap(req.headers())?,
        body: req.body().clone(),
    })
}

fn http_to_ext_response(res: HttpResponse) -> Result<ExtHttpResponse, HttpClientError> {
    let mut response = Response::builder().status(
        StatusCode::from_u16(res.status_code)
            .map_err(|_| "failed to parse status code".to_string())
            .map_err(HttpClientError::from)?,
    );

    for (k, v) in res.headers {
        response = response.header(k, v);
    }

    response
        .body(res.body)
        .map_err(|_| HttpClientError::ResponseBuilder)
}

#[derive(thiserror::Error, uniffi::Error, Debug)]
pub enum SyncHttpClientError {
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

impl From<SyncHttpClientError> for HttpClientError {
    fn from(value: SyncHttpClientError) -> Self {
        match value {
            SyncHttpClientError::RequestBuilder => HttpClientError::RequestBuilder,
            SyncHttpClientError::ResponseBuilder => HttpClientError::ResponseBuilder,
            SyncHttpClientError::UrlParse => HttpClientError::UrlParse,
            SyncHttpClientError::MethodParse => HttpClientError::MethodParse,
            SyncHttpClientError::HeaderParse => HttpClientError::HeaderParse,
            SyncHttpClientError::HeaderKeyParse { key } => HttpClientError::HeaderKeyParse { key },
            SyncHttpClientError::HeaderValueParse { value } => {
                HttpClientError::HeaderValueParse { value }
            }
            SyncHttpClientError::HeaderEntryParse { key, value } => {
                HttpClientError::HeaderEntryParse { key, value }
            }
            SyncHttpClientError::Other { error } => HttpClientError::Other { error },
        }
    }
}

#[uniffi::export(with_foreign)]
pub trait SyncHttpClient: Send + Sync {
    fn http_client(&self, request: HttpRequest) -> Result<HttpResponse, SyncHttpClientError>;
}

impl ExtSyncHttpClient for dyn SyncHttpClient {
    type Error = HttpClientError;

    fn call(&self, request: ExtHttpRequest) -> Result<ExtHttpResponse, Self::Error> {
        let request: HttpRequest = ext_request_to_http(request)?;
        let response: HttpResponse = self.http_client(request)?;
        let response: ExtHttpResponse = http_to_ext_response(response)?;
        Ok::<_, HttpClientError>(response)
    }
}

pub struct Oid4vciHttpClient(pub Arc<dyn AsyncHttpClient>);

impl<'c> oid4vci::oauth2::AsyncHttpClient<'c> for Oid4vciHttpClient {
    type Error = HttpClientError;
    type Future =
        Pin<Box<dyn Future<Output = Result<ExtHttpResponse, HttpClientError>> + Send + 'c>>;

    fn call(&'c self, request: ExtHttpRequest) -> Self::Future {
        Box::pin(async move {
            let request: HttpRequest = ext_request_to_http(request)?;
            let response: HttpResponse = self.0.http_client(request).await?;
            let response: ExtHttpResponse = http_to_ext_response(response)?;
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
