package com.spruceid.mobile.sdk

import com.spruceid.mobile.sdk.rs.AsyncHttpClient
import com.spruceid.mobile.sdk.rs.HttpRequest
import com.spruceid.mobile.sdk.rs.HttpResponse
import com.spruceid.mobile.sdk.rs.SyncHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpMethod
import io.ktor.util.toMap
import kotlinx.coroutines.runBlocking

private val ktorClient = HttpClient(CIO)

private suspend fun ktorHttpClient(request: HttpRequest): HttpResponse {
    val res = ktorClient.request(request.url) {
        method = HttpMethod(request.method)
        for ((k, v) in request.headers) {
            headers[k] = v
        }
        setBody(request.body)
    }

    return HttpResponse(
        statusCode = res.status.value.toUShort(),
        headers = res.headers.toMap().mapValues { it.value.joinToString(",") },
        body = res.readRawBytes(),
    )
}

class Oid4vciSyncHttpClient : SyncHttpClient {
    override fun httpClient(request: HttpRequest): HttpResponse {
        return runBlocking { ktorHttpClient(request) }
    }
}

class Oid4vciAsyncHttpClient : AsyncHttpClient {
    override suspend fun httpClient(request: HttpRequest): HttpResponse {
        return ktorHttpClient(request)
    }
}
