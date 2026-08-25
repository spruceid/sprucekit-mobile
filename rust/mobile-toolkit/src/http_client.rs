use std::collections::HashMap;

use async_trait::async_trait;

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

#[derive(uniffi::Record, Clone, Debug)]
/// Plain Rust object representation of an HttpResponse that can be exported
/// through `uniffi` and is used in `WithForeign` trait definitions for HTTP
/// clients.
pub struct HttpResponse {
    pub status_code: u16,
    pub headers: HashMap<String, String>,
    pub body: Vec<u8>,
}

#[uniffi::export(with_foreign)]
#[async_trait]
pub trait AsyncHttpClient: Send + Sync {
    async fn http_client(&self, request: HttpRequest) -> Result<HttpResponse, HttpClientError>;
}
