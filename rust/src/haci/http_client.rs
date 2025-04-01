#[derive(Debug, Clone)]
pub struct HaciHttpClient(reqwest::Client);

impl AsRef<reqwest::Client> for HaciHttpClient {
    fn as_ref(&self) -> &reqwest::Client {
        &self.0
    }
}

impl HaciHttpClient {
    pub fn new() -> Self {
        Self(
            reqwest::Client::builder()
                .use_rustls_tls()
                .timeout(std::time::Duration::from_secs(30))
                .build()
                .unwrap_or_else(|e| panic!("Failed to build HTTP client: {}", e)),
        )
    }

    pub fn get(&self, url: String) -> reqwest::RequestBuilder {
        self.0.get(url)
    }

    pub fn post(&self, url: String) -> reqwest::RequestBuilder {
        self.0.post(url)
    }
}
