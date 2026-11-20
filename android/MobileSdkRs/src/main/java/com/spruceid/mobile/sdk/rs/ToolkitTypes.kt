package com.spruceid.mobile.sdk.rs

// Compatibility re-exports for types that moved to the mobile-toolkit crate
// and are now generated into the com.spruceid.mobile.toolkit package. They
// keep pre-existing imports of these types from com.spruceid.mobile.sdk.rs
// compiling unchanged. New code should import com.spruceid.mobile.toolkit
// directly.
//
// This file is hand-written and tracked in git; everything else under
// src/main/java is generated (see android/.gitignore).

typealias Key = com.spruceid.mobile.toolkit.Key

typealias Value = com.spruceid.mobile.toolkit.Value

typealias KeyAlias = com.spruceid.mobile.toolkit.KeyAlias

typealias CryptoException = com.spruceid.mobile.toolkit.CryptoException

typealias KeyStore = com.spruceid.mobile.toolkit.KeyStore

typealias SigningKey = com.spruceid.mobile.toolkit.SigningKey

typealias HttpClientException = com.spruceid.mobile.toolkit.HttpClientException

typealias HttpRequest = com.spruceid.mobile.toolkit.HttpRequest

typealias HttpResponse = com.spruceid.mobile.toolkit.HttpResponse

typealias AsyncHttpClient = com.spruceid.mobile.toolkit.AsyncHttpClient

typealias StorageManagerException = com.spruceid.mobile.toolkit.StorageManagerException

typealias StorageManagerInterface = com.spruceid.mobile.toolkit.StorageManagerInterface
