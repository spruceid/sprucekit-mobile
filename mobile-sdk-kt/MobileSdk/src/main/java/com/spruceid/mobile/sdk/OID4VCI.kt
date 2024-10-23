package com.spruceid.mobile.sdk

import com.spruceid.mobile.sdk.rs.AsyncHttpClient
import com.spruceid.mobile.sdk.rs.HttpRequest
import com.spruceid.mobile.sdk.rs.HttpResponse
import com.spruceid.mobile.sdk.rs.SyncHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class Oid4vciSyncHttpClient: SyncHttpClient  {
    override fun httpClient(request: HttpRequest): HttpResponse {
        val connection: HttpsURLConnection = URL(request.url).openConnection() as HttpsURLConnection

        connection.requestMethod = request.method
        for ((k, v) in request.headers) {
            connection.setRequestProperty(k, v)
        }
        connection.doOutput = true
        connection.doInput = true

        val wr = DataOutputStream(connection.outputStream)
        wr.write(request.body)
        wr.flush()
        wr.close()

        val statusCode = connection.responseCode
        val stream = BufferedInputStream(connection.inputStream)
        val body = stream.readBytes()
        stream.close()

        val headers = connection.headerFields.mapValues { it.value.joinToString(",") }

        return HttpResponse(
            statusCode = statusCode.toUShort(),
            headers = headers,
            body = body,
        )
    }
}

class Oid4vciAsyncHttpClient: AsyncHttpClient {
    override suspend fun httpClient(request: HttpRequest): HttpResponse {
        val connection: HttpsURLConnection =
            withContext(Dispatchers.IO) {
                URL(request.url).openConnection()
            } as HttpsURLConnection

        connection.requestMethod = request.method
        for ((k, v) in request.headers) {
            connection.setRequestProperty(k, v)
        }
        connection.doOutput = true
        connection.doInput = true

        val wr = DataOutputStream(connection.outputStream)
        withContext(Dispatchers.IO) {
            wr.write(request.body)
            wr.flush()
            wr.close()
        }

        val statusCode = connection.responseCode
        val body: ByteArray
        withContext(Dispatchers.IO) {
            val stream = BufferedInputStream(connection.inputStream)
            body = stream.readBytes()
            stream.close()
        }
        val headers = connection.headerFields.mapValues { it.value.joinToString(",") }

        return HttpResponse(
            statusCode = statusCode.toUShort(),
            headers = headers,
            body = body,
        )
    }
}