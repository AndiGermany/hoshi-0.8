package de.hoshi.adapters.news

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.math.max

internal class FeedFetcher(
    private val source: FeedSourceDefinition,
    private val timeout: Duration = Duration.ofSeconds(5),
    private val maxResponseBytes: Int = 1_000_000,
    private val maxRedirects: Int = 2,
    private val userAgent: String = "Hoshi/0.8 local-current-affairs",
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(!timeout.isZero && !timeout.isNegative)
        require(maxResponseBytes in 1..10_000_000)
        require(maxRedirects in 0..5)
    }

    fun fetch(validators: FeedValidators): FeedFetchResult {
        val started = nanoTime()
        var uri = source.feedUri
        var requests = 0
        var requestBytes = 0L
        var responseBytes = 0L

        return try {
            while (true) {
                if (!FeedSourceDefinition.isAllowedUri(uri, source.allowedFeedHosts, source.allowInsecureLoopback)) {
                    return failed(
                        FeedFailureReason.REDIRECT_REJECTED,
                        null,
                        requests,
                        requestBytes,
                        responseBytes,
                        started,
                    )
                }
                val request = request(uri, validators)
                requests += 1
                requestBytes += estimateRequestBytes(request)
                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                responseBytes += estimateResponseHeadBytes(response)
                val status = response.statusCode()

                if (status in REDIRECTS) {
                    responseBytes += response.body().use { drainBounded(it, 8_192) }
                    if (requests > maxRedirects) {
                        return failed(
                            FeedFailureReason.REDIRECT_REJECTED,
                            status,
                            requests,
                            requestBytes,
                            responseBytes,
                            started,
                        )
                    }
                    val location = response.headers().firstValue("Location").orElse(null)
                        ?: return failed(
                            FeedFailureReason.REDIRECT_REJECTED,
                            status,
                            requests,
                            requestBytes,
                            responseBytes,
                            started,
                        )
                    if (location.length > 2_048 || location.any { it == '\r' || it == '\n' }) {
                        return failed(
                            FeedFailureReason.REDIRECT_REJECTED,
                            status,
                            requests,
                            requestBytes,
                            responseBytes,
                            started,
                        )
                    }
                    uri = runCatching { uri.resolve(location) }.getOrElse {
                        return failed(
                            FeedFailureReason.REDIRECT_REJECTED,
                            status,
                            requests,
                            requestBytes,
                            responseBytes,
                            started,
                        )
                    }
                    continue
                }

                if (status == 304) {
                    responseBytes += response.body().use { drainBounded(it, 1_024) }
                    return FeedFetchResult.NotModified(
                        validators = validatorsFrom(response, validators),
                        requests = requests,
                        requestBytesEstimate = requestBytes,
                        responseBytesEstimate = responseBytes,
                        durationMs = elapsedMs(started),
                    )
                }

                if (status != 200) {
                    responseBytes += response.body().use { drainBounded(it, 8_192) }
                    return failed(
                        FeedFailureReason.HTTP_STATUS,
                        status,
                        requests,
                        requestBytes,
                        responseBytes,
                        started,
                    )
                }

                val contentType = response.headers().firstValue("Content-Type").orElse("").lowercase()
                if (contentType.isNotBlank() && CONTENT_TYPES.none { contentType.contains(it) }) {
                    responseBytes += response.body().use { drainBounded(it, 8_192) }
                    return failed(
                        FeedFailureReason.CONTENT_TYPE,
                        status,
                        requests,
                        requestBytes,
                        responseBytes,
                        started,
                    )
                }

                val body = response.body().use { it.readNBytes(maxResponseBytes + 1) }
                responseBytes += body.size
                if (body.size > maxResponseBytes) {
                    return failed(
                        FeedFailureReason.RESPONSE_TOO_LARGE,
                        status,
                        requests,
                        requestBytes,
                        responseBytes,
                        started,
                    )
                }
                return FeedFetchResult.Modified(
                    body = body,
                    validators = validatorsFrom(response, validators),
                    requests = requests,
                    requestBytesEstimate = requestBytes,
                    responseBytesEstimate = responseBytes,
                    durationMs = elapsedMs(started),
                )
            }
            @Suppress("UNREACHABLE_CODE")
            failed(FeedFailureReason.UNKNOWN, null, requests, requestBytes, responseBytes, started)
        } catch (_: HttpConnectTimeoutException) {
            failed(FeedFailureReason.TIMEOUT, null, requests, requestBytes, responseBytes, started)
        } catch (_: HttpTimeoutException) {
            failed(FeedFailureReason.TIMEOUT, null, requests, requestBytes, responseBytes, started)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            failed(FeedFailureReason.NETWORK, null, requests, requestBytes, responseBytes, started)
        } catch (_: Exception) {
            failed(FeedFailureReason.NETWORK, null, requests, requestBytes, responseBytes, started)
        }
    }

    private fun request(uri: URI, validators: FeedValidators): HttpRequest {
        val builder = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9")
            .header("User-Agent", userAgent)
            .GET()
        validators.etag?.takeIf { safeValidator(it) }?.let { builder.header("If-None-Match", it) }
        validators.lastModified?.takeIf { safeValidator(it) }?.let { builder.header("If-Modified-Since", it) }
        return builder.build()
    }

    private fun validatorsFrom(response: HttpResponse<*>, previous: FeedValidators): FeedValidators =
        FeedValidators(
            etag = response.headers().firstValue("ETag").orElse(previous.etag)?.takeIf(::safeValidator),
            lastModified = response.headers().firstValue("Last-Modified").orElse(previous.lastModified)
                ?.takeIf(::safeValidator),
        )

    private fun safeValidator(value: String): Boolean =
        value.length <= 512 && value.none { it == '\r' || it == '\n' }

    private fun estimateRequestBytes(request: HttpRequest): Long {
        val path = request.uri().rawPath.orEmpty() + request.uri().rawQuery?.let { "?$it" }.orEmpty()
        var bytes = "GET $path HTTP/1.1\r\n".toByteArray(StandardCharsets.US_ASCII).size.toLong()
        request.headers().map().forEach { (name, values) ->
            values.forEach { value -> bytes += "$name: $value\r\n".toByteArray(StandardCharsets.UTF_8).size }
        }
        return bytes + 2
    }

    private fun estimateResponseHeadBytes(response: HttpResponse<*>): Long {
        var bytes = "HTTP/1.1 ${response.statusCode()}\r\n".length.toLong()
        response.headers().map().forEach { (name, values) ->
            values.forEach { value -> bytes += "$name: $value\r\n".toByteArray(StandardCharsets.UTF_8).size }
        }
        return bytes + 2
    }

    private fun drainBounded(stream: InputStream, maxBytes: Int): Int = stream.readNBytes(maxBytes).size

    private fun failed(
        reason: FeedFailureReason,
        status: Int?,
        requests: Int,
        requestBytes: Long,
        responseBytes: Long,
        started: Long,
    ) = FeedFetchResult.Failed(
        reason = reason,
        status = status,
        requests = requests,
        requestBytesEstimate = requestBytes,
        responseBytesEstimate = responseBytes,
        durationMs = elapsedMs(started),
    )

    private fun elapsedMs(started: Long): Long = max(0L, (nanoTime() - started) / 1_000_000L)

    companion object {
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val CONTENT_TYPES = listOf("xml", "rss", "atom")
    }
}
