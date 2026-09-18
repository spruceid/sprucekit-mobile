//! HTTP client for the HACI service clients.
//!
//! Every request goes through the shared [`HttpClient`], so the native proxy
//! settings and trust store apply.

use std::time::Duration;

use http::{Method, Request, Response, StatusCode};
use serde::{de::DeserializeOwned, Serialize};

use crate::http_client::{HttpClient, HttpClientError};

const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Debug, thiserror::Error)]
pub enum HaciHttpError {
    #[error("failed to build request: {0}")]
    Request(String),

    #[error("request timed out after {} seconds", REQUEST_TIMEOUT.as_secs())]
    Timeout,

    #[error(transparent)]
    Transport(#[from] HttpClientError),

    #[error("failed to decode response body: {0}")]
    Body(String),
}

#[derive(Debug, Clone)]
pub struct HaciHttpClient(HttpClient);

impl HaciHttpClient {
    pub fn new() -> Self {
        Self(HttpClient::shared())
    }

    pub fn get(&self, url: String) -> HaciRequestBuilder {
        self.request(Method::GET, url)
    }

    pub fn post(&self, url: String) -> HaciRequestBuilder {
        self.request(Method::POST, url)
    }

    fn request(&self, method: Method, url: String) -> HaciRequestBuilder {
        HaciRequestBuilder {
            client: self.0.clone(),
            builder: Request::builder().method(method).uri(url),
            body: Ok(Vec::new()),
        }
    }
}

pub struct HaciRequestBuilder {
    client: HttpClient,
    builder: http::request::Builder,
    body: Result<Vec<u8>, HaciHttpError>,
}

impl HaciRequestBuilder {
    pub fn header(mut self, key: &str, value: impl AsRef<str>) -> Self {
        self.builder = self.builder.header(key, value.as_ref());
        self
    }

    /// Serialize `value` as the JSON body and set the content type.
    pub fn json<T: Serialize + ?Sized>(mut self, value: &T) -> Self {
        self.builder = self.builder.header("Content-Type", "application/json");
        self.body = serde_json::to_vec(value).map_err(|e| HaciHttpError::Request(e.to_string()));
        self
    }

    pub async fn send(self) -> Result<HaciResponse, HaciHttpError> {
        let request = self
            .builder
            .body(self.body?)
            .map_err(|e| HaciHttpError::Request(e.to_string()))?;
        let response = tokio::time::timeout(REQUEST_TIMEOUT, self.client.send(request))
            .await
            .map_err(|_| HaciHttpError::Timeout)??;
        Ok(HaciResponse(response))
    }
}

pub struct HaciResponse(Response<Vec<u8>>);

impl HaciResponse {
    pub fn status(&self) -> StatusCode {
        self.0.status()
    }

    pub fn text(self) -> Result<String, HaciHttpError> {
        String::from_utf8(self.0.into_body()).map_err(|e| HaciHttpError::Body(e.to_string()))
    }

    pub fn json<T: DeserializeOwned>(self) -> Result<T, HaciHttpError> {
        serde_json::from_slice(self.0.body()).map_err(|e| HaciHttpError::Body(e.to_string()))
    }
}
