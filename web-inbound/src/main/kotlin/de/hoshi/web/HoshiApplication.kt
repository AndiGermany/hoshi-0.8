package de.hoshi.web

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * **HoshiApplication** — erster Spring-Boot-Inbound-Adapter (WebFlux, reaktiv).
 *
 * M1-Abschluss: bootet den Context, an dem die [PerimeterWebFilter]-Wand
 * tatsaechlich greift. Der Trust-Kernel ([de.hoshi.kernel.PerimeterPort]) liegt
 * im `:capability-kernel`-Modul (Spring-frei) — hier wird seine reine
 * Entscheidungslogik in einen echten `WebFilter` verdrahtet.
 */
@SpringBootApplication
class HoshiApplication

/**
 * Boot-Banner (Easter-Egg, aus Hoshi 0.5 portiert). Wird ganz am Anfang von
 * [main] ausgegeben — taucht in `journalctl`/stdout beim Restart auf und stellt
 * kurz das Team vor, das Hoshi gebaut hat. Reine Box-Drawing-Zeichen als
 * String-Inhalt (Triple-Quote, gerade Anfuehrungszeichen als Delimiter).
 */
private const val HOSHI_BANNER = """
   ╭───────────────────────────────────────────────────────────────╮
   │   ★  H O S H I  0.8  —  Nagareboshi  ★                        │
   │   ────────────────────────────────────                        │
   │   warm. lokal. wach.                                          │
   │                                                               │
   │   crew: mira · jonas · lina · sara · lara · yuki ·            │
   │         kai · tom · lars · ravi · lia · felix ·               │
   │         nora · noa · nils · maja · eda · vera · timo          │
   │   captain: andi                                               │
   ╰───────────────────────────────────────────────────────────────╯
"""

fun main(args: Array<String>) {
    // Easter-Egg: Banner vor dem Spring-Boot — gibt der Boot-Sequenz Charakter
    // und stellt die Crew der "Stellar Bloom" kurz vor (faithful aus 0.5).
    print(HOSHI_BANNER)
    runApplication<HoshiApplication>(*args)
}
