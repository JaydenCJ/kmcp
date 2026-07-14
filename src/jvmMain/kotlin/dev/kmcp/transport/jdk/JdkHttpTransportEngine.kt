package dev.kmcp.transport.jdk

import dev.kmcp.transport.HttpRequestData
import dev.kmcp.transport.HttpResponseData
import dev.kmcp.transport.HttpTransportEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * [HttpTransportEngine] backed by `java.net.http.HttpClient` (JDK 11+).
 *
 * This is the default engine for JVM applications and requires no third
 * party dependency. Blocking I/O runs on [Dispatchers.IO].
 */
public class JdkHttpTransportEngine(
    connectTimeout: Duration = Duration.ofSeconds(10),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
) : HttpTransportEngine {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .build()

    override suspend fun execute(request: HttpRequestData): HttpResponseData =
        withContext(Dispatchers.IO) {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(request.url))
                .timeout(requestTimeout)
            request.headers.forEach { (name, value) -> builder.header(name, value) }
            val body = request.body
            if (body != null) {
                builder.method(request.method, HttpRequest.BodyPublishers.ofString(body))
            } else {
                builder.method(request.method, HttpRequest.BodyPublishers.noBody())
            }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            HttpResponseData(
                status = response.statusCode(),
                headers = response.headers().map()
                    .mapKeys { (name, _) -> name.lowercase() }
                    .mapValues { (_, values) -> values.joinToString(", ") },
                body = response.body(),
            )
        }
}
