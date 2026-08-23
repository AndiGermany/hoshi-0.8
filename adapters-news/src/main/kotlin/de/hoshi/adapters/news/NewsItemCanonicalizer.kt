package de.hoshi.adapters.news

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

internal class NewsItemCanonicalizer(
    private val source: FeedSourceDefinition,
    private val ttl: Duration = Duration.ofDays(7),
    private val hardRetention: Duration = Duration.ofDays(14),
) {
    init {
        require(!ttl.isNegative && !ttl.isZero)
        require(hardRetention >= ttl)
    }

    fun canonicalize(raw: RawFeedEntry, fetchedAt: Instant): CanonicalFeedItem? {
        val title = raw.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val canonicalUri = canonicalUri(raw.link ?: return null) ?: return null
        val canonicalUrl = canonicalUri.toASCIIString()
        val sourceItemId = raw.sourceId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 } ?: canonicalUrl
        val publishedAt = raw.publishedAt
        val softExpiry = fetchedAt.plus(ttl)
        val hardExpiry = publishedAt?.plus(hardRetention)
        val expiresAt = if (hardExpiry != null && hardExpiry < softExpiry) hardExpiry else softExpiry
        val hash = sha256(
            listOf(title, raw.snippet.orEmpty(), canonicalUrl, publishedAt?.toString().orEmpty()).joinToString("\u0000"),
        )
        return CanonicalFeedItem(
            id = sha256("${source.source.name}\u0000$sourceItemId"),
            source = source.source,
            sourceItemId = sourceItemId,
            canonicalUrl = canonicalUrl,
            title = title,
            snippet = raw.snippet?.trim()?.takeIf { it.isNotEmpty() },
            attribution = source.attribution,
            publishedAt = publishedAt,
            fetchedAt = fetchedAt,
            contentHash = hash,
            expiresAt = expiresAt,
        )
    }

    private fun canonicalUri(raw: String): URI? {
        val parsed = runCatching { URI(raw.trim()).let { URI(it.toString().substringBefore('#')) } }.getOrNull() ?: return null
        if (!FeedSourceDefinition.isAllowedUri(parsed, source.allowedArticleHosts, allowInsecureLoopback = false)) return null
        val keptQuery = parsed.rawQuery
            ?.split('&')
            ?.filter { part ->
                val name = runCatching {
                    URLDecoder.decode(part.substringBefore('='), StandardCharsets.UTF_8).lowercase()
                }.getOrDefault(part.substringBefore('=').lowercase())
                name !in TRACKING_PARAMS && !name.startsWith("utm_")
            }
            ?.joinToString("&")
            ?.takeIf { it.isNotEmpty() }
        val port = if (parsed.port == 443) "" else parsed.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
        val path = parsed.rawPath.ifBlank { "/" }
        val query = keptQuery?.let { "?$it" }.orEmpty()
        return runCatching { URI("https://${parsed.host.lowercase()}$port$path$query").normalize() }.getOrNull()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private val TRACKING_PARAMS = setOf("wt_mc", "wt_zmc", "fbclid", "gclid", "mc_cid", "mc_eid")
    }
}
