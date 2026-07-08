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

#[uniffi::export(with_foreign)]
pub trait SyncHttpClient: Send + Sync {
    fn http_client(&self, request: HttpRequest) -> Result<HttpResponse, HttpClientError>;
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
