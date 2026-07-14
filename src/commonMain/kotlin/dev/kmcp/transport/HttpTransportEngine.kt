package dev.kmcp.transport

/** An HTTP request as seen by a pluggable [HttpTransportEngine]. */
public data class HttpRequestData(
    public val method: String,
    public val url: String,
    public val headers: Map<String, String>,
    public val body: String? = null,
)

/** An HTTP response returned by a pluggable [HttpTransportEngine]. */
public data class HttpResponseData(
    public val status: Int,
    /** Response headers with lower-cased names. */
    public val headers: Map<String, String>,
    public val body: String,
) {
    /** The `content-type` header without parameters, lower-cased. */
    public fun contentType(): String =
        headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() ?: ""
}

/**
 * Pluggable HTTP engine behind [StreamableHttpClientTransport].
 *
 * kmcp ships a `java.net.http` engine for the JVM. Android apps typically
 * plug in an OkHttp-based engine and iOS apps an NSURLSession-based engine;
 * both are ~20 lines because the interface is a single suspend call.
 */
public interface HttpTransportEngine {
    /** Executes [request] and returns the full response. */
    public suspend fun execute(request: HttpRequestData): HttpResponseData

    /** Releases engine resources. Default implementation does nothing. */
    public fun close() {}
}
