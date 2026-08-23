package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatRequest
import java.security.MessageDigest

/**
 * Baut die lokale Identität einer Gesprächskette. Der Kanal ist Teil des Schlüssels:
 * dieselbe Browser-Geräte-ID darf ein offenes Chat-Angebot nicht über `/voice` einlösen.
 * Roh-IDs werden nur gehasht im Prozessspeicher gehalten; der Schlüssel verlässt den
 * Kern nicht. Ein Sprecher ist ausschließlich nach erzwungener [SpeakerTrust]-Prüfung
 * ein Fallback — ein bloß behaupteter Name verbindet niemals zwei Turns.
 */
object ConversationKeys {
    enum class Channel(val wire: String) {
        CHAT("chat"),
        VOICE("voice"),
        WS("ws"),
        ;

        companion object {
            fun from(source: String?): Channel = when (source?.trim()?.lowercase()) {
                VOICE.wire -> VOICE
                WS.wire -> WS
                else -> CHAT
            }
        }
    }

    private const val DIGEST_HEX_CHARS = 32
    private const val MAX_CANONICAL_LENGTH = 96
    private val CANONICAL = Regex("^(chat|voice|ws):(device|session|chat|speaker|local)(:[0-9a-f]{32})?$")

    fun forDevice(channel: Channel, deviceId: String?): String? = identity(channel, "device", deviceId)

    fun forSession(sessionId: String?): String? = identity(Channel.WS, "session", sessionId)

    fun forChatId(chatId: String?): String? = identity(Channel.CHAT, "chat", chatId)

    fun local(channel: Channel): String = "${channel.wire}:local"

    /**
     * Kern-Fallback für alte oder direkt konstruierte Requests. Echte Inbounds setzen
     * [ChatRequest.conversationKey] selbst und überschreiben jeden Body-Claim.
     */
    fun resolve(request: ChatRequest, speakerTrustThreshold: Double): String {
        canonical(request.conversationKey)?.let { return it }
        val channel = Channel.from(request.source)
        if (channel == Channel.WS) forSession(request.chatId)?.let { return it }
        forDevice(channel, request.deviceId)?.let { return it }
        if (channel == Channel.CHAT) forChatId(request.chatId)?.let { return it }
        val trusted = SpeakerTrust.resolve(request.speakerContext, enforced = true, threshold = speakerTrustThreshold)
        if (trusted?.trusted == true) identity(channel, "speaker", trusted.speakerId)?.let { return it }
        return local(channel)
    }

    /** Nur bereits von einem Inbound erzeugte, kanonische Schlüssel werden übernommen. */
    fun canonical(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.length <= MAX_CANONICAL_LENGTH && CANONICAL.matches(it) }

    private fun identity(channel: Channel, kind: String, raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return "${channel.wire}:$kind:${digest(value)}"
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(DIGEST_HEX_CHARS)
}
