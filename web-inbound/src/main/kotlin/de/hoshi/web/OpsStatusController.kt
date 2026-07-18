package de.hoshi.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * **OpsStatusController** — der Ops-Status-Rand für die UI: Sidecar-Gesundheit +
 * Mac-RAM-Druck als ein blocking-freier JSON-Snapshot.
 *
 *  - `GET /api/v1/ops/status` — geschützter `/api/v1`-Pfad, kommt nur mit gültigem
 *    Token (oder über Loopback) durch die [PerimeterWebFilter]-Wand; ohne/falscher
 *    Token ⇒ 401 (identisch zu `/api/v1/ping`, `/api/v1/voice`).
 *
 * Der Endpoint liest NUR den letzten [SidecarHealthService]-Snapshot (kein Probe-Call,
 * kein Blocking im Request). Bei `HOSHI_SIDECAR_WATCH_ENABLED=false` (Default) liefert
 * der Service `{"enabled":false}` (HTTP 200, byte-neutral); sonst den vollen Contract:
 *
 * ```json
 * { "enabled": true, "overall": "OK|DEGRADED|DOWN",
 *   "memory": { "level": "OK|WARN|CRITICAL|UNKNOWN", "source": "brain-health", "detail": "…" },
 *   "sidecars": [ { "name": "brain|whisper-stt|bridge|speaker-id|…", "status": "OK|DEGRADED|DOWN", "detail": "…" } ],
 *   "voice": { "engine": "openai|say|piper|voxtral", "cloud": true },
 *   "allLocal": false,
 *   "ts": 1234567890123 }
 * ```
 *
 * `voice` trägt die aktive TTS-Engine für Toms ☁️-Cloud-Banner („Cloud nur mit
 * Banner"): `cloud=true` nur bei OpenAI (einziger Egress-Pfad). Seit dem Runtime-
 * Switch (9edbb1d/b4844d0) folgt das Feld dem GEWÄHLTEN Laufzeit-Wunsch — nicht mehr
 * nur der Boot-Config — s. [SidecarHealthService.currentVoice]/[VoiceStatus]-KDoc.
 *
 * `allLocal` ist Andis Schloss-Bedingung: `true` nur wenn STT + Brain + die
 * gewählte TTS-Engine ALLE lokal sind, s. [SidecarHealthService.deriveAllLocal].
 */
@RestController
class OpsStatusController(
    private val sidecarHealth: SidecarHealthService,
) {

    @GetMapping("/api/v1/ops/status")
    fun status(): Any = sidecarHealth.current()
}
