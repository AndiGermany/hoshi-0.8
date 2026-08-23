package de.hoshi.adapters.news

import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal class SafeFeedParser(
    private val maxItems: Int = 100,
    private val maxTitleChars: Int = 300,
    private val maxSnippetChars: Int = 1_000,
    private val maxIdChars: Int = 512,
    private val maxLinkChars: Int = 2_048,
) {
    init {
        require(maxItems in 1..500)
    }

    fun parse(body: ByteArray): ParsedFeed {
        if (body.isEmpty()) throw FeedParseException("empty_feed")
        val document = try {
            secureFactory().newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
                setErrorHandler(ThrowingErrorHandler)
            }.parse(ByteArrayInputStream(body))
        } catch (e: Exception) {
            throw FeedParseException("xml_rejected", e)
        }

        val rssItems = document.getElementsByTagName("item")
        val atomItems = document.getElementsByTagNameNS("*", "entry")
        val nodes = if (rssItems.length > 0) rssItems else atomItems
        if (nodes.length == 0) throw FeedParseException("unsupported_or_empty_feed")

        val entries = ArrayList<RawFeedEntry>(minOf(nodes.length, maxItems))
        var rejected = (nodes.length - maxItems).coerceAtLeast(0)
        for (index in 0 until minOf(nodes.length, maxItems)) {
            val element = nodes.item(index) as? Element
            if (element == null) {
                rejected += 1
                continue
            }
            val raw = parseEntry(element)
            if (raw == null) rejected += 1 else entries += raw
        }
        return ParsedFeed(entries, rejected)
    }

    private fun parseEntry(element: Element): RawFeedEntry? {
        val title = childText(element, "title")?.toPlainText(maxTitleChars)
        val link = atomLink(element) ?: childText(element, "link")?.bounded(maxLinkChars)
        if (title.isNullOrBlank() || link.isNullOrBlank()) return null

        val snippet = sequenceOf("description", "summary", "content")
            .mapNotNull { childText(element, it) }
            .firstOrNull()
            ?.toPlainText(maxSnippetChars)
            ?.takeIf { it.isNotBlank() }
        val sourceId = sequenceOf("guid", "id")
            .mapNotNull { childText(element, it) }
            .firstOrNull()
            ?.bounded(maxIdChars)
        val published = sequenceOf("pubDate", "published", "updated", "dc:date")
            .mapNotNull { childText(element, it) }
            .mapNotNull(::parseInstant)
            .firstOrNull()
        return RawFeedEntry(sourceId, title, snippet, link, published)
    }

    private fun childText(parent: Element, requestedName: String): String? {
        val wanted = requestedName.substringAfter(':')
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            val local = child.localName ?: child.nodeName.substringAfter(':')
            if (local.equals(wanted, ignoreCase = true)) return child.textContent?.trim()
        }
        return null
    }

    private fun atomLink(parent: Element): String? {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            val local = child.localName ?: child.nodeName.substringAfter(':')
            if (!local.equals("link", ignoreCase = true)) continue
            val rel = child.getAttribute("rel")
            if (rel.isBlank() || rel == "alternate") {
                return child.getAttribute("href").takeIf { it.isNotBlank() }?.bounded(maxLinkChars)
            }
        }
        return null
    }

    private fun parseInstant(raw: String): Instant? {
        val value = raw.trim().take(128)
        return runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }

    private fun String.bounded(max: Int): String? = trim().takeIf { it.length in 1..max }

    private fun String.toPlainText(max: Int): String? {
        if (length > max * 20) return null
        val out = StringBuilder(minOf(length, max))
        var insideTag = false
        for (char in this) {
            when (char) {
                '<' -> insideTag = true
                '>' -> if (insideTag) {
                    insideTag = false
                    out.append(' ')
                }
                else -> if (!insideTag) out.append(char)
            }
            if (out.length > max * 2) break
        }
        val normalized = out.toString()
            .filterNot { char ->
                Character.isISOControl(char) || Character.getType(char) == Character.FORMAT.toInt()
            }
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim()
        return normalized.takeIf { it.isNotEmpty() }?.take(max)
    }

    private fun secureFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }

    private object ThrowingErrorHandler : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit
        override fun error(exception: SAXParseException): Nothing = throw exception
        override fun fatalError(exception: SAXParseException): Nothing = throw exception
    }
}
