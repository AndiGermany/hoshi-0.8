package de.hoshi.adapters.ha

import java.time.Duration
import java.time.Instant

/**
 * **VacuumFamily** — die BE-Seite der Sauger-Familie: welche Entity IST der
 * Sauger, welche Entities gehören zu ihm, und wie überlebt sein Zustand den
 * Energiesparmodus.
 *
 * **Warum das hier existiert (Andi 2026-08-21, wörtlich): „Beim Sauger, ja, das
 * ist Lärm, aber dann müssen wir die Daten cachen und verwenden. Meistens ist er
 * einfach im Energiesparmodus."** Ein echter Sauger schläft den Großteil des
 * Tages im WLAN-Standby — HA meldet ihn dann `unavailable`. Bisher fiel die
 * Kachel dafür auf „zuletzt gesehen vor X" zurück: der NORMALZUSTAND sah aus
 * wie ein Ausfall. [carryCache] dreht das um — die zuletzt LIVE gesehenen Werte
 * werden weitergeliefert, aber IMMER als Cache markiert
 * ([HomeRegistryEntity.fromCacheSinceMs]).
 *
 * **Stamm statt Suffix-Liste (bewusste Abweichung vom FE):** das FE kennt eine
 * feste Suffix-Map (`frontend/src/components/homeTiles.ts#VACUUM_FAMILY_SUFFIXES`,
 * 18 Einträge: `batterie`, `reinigen`, `verbleibende_filterzeit`, …), weil es
 * jedem Mitglied eine EIGENE Kachel-Zeile zuordnen muss. Der BE muss das nicht:
 * er braucht nur zu wissen, ob eine Entity ZUM SELBEN GERÄT gehört, und das
 * entscheidet HA's entityId-Stamm (`vacuum.<stem>` ⇒ `<domain>.<stem>_<…>`)
 * generisch. Die Stamm-Regel ist eine echte OBERMENGE der FE-Suffix-Map — sie
 * kann nie ein Familienmitglied VERPASSEN, das das FE kennt, und sie muss bei
 * einem neuen Sensor NICHT nachgezogen werden. Eine hier duplizierte Suffix-
 * Liste wäre dagegen eine Sync-Falle zwischen zwei Sprachen (s. RESULT.md
 * „Rate-Stellen"). Der Preis: liegt zufällig eine geräte-fremde Entity mit
 * demselben Stamm-Präfix in HA, bekäme auch sie den Cache-Carry — das ist
 * derselbe Sauger-Stamm, also dasselbe Gerät, und damit kein Falschbefund.
 */
object VacuumFamily {

    /** HA-Domain der Sauger-Entity selbst. */
    const val DOMAIN = "vacuum"

    /**
     * Die erste `vacuum.*`-Entity im ganzen Snapshot — zuerst die zugeordneten
     * Areas (in Registry-Reihenfolge), dann `unassigned`. EXAKT dieselbe
     * Reihenfolge wie `homeTiles.ts#findVacuum`, damit BE und FE garantiert
     * DENSELBEN Sauger meinen, wenn ein Haus je mehrere hätte. `null` = kein
     * Sauger in der Registry.
     */
    fun find(snapshot: HomeRegistrySnapshot): HomeRegistryEntity? {
        for (area in snapshot.areas) {
            area.entities.firstOrNull { it.domain == DOMAIN }?.let { return it }
        }
        return snapshot.unassigned.firstOrNull { it.domain == DOMAIN }
    }

    /**
     * **Ist dieser Sauger GERADE bei HA zustellbar?** Genau dann, wenn sein
     * Zustand LIVE von HA kommt ([HomeRegistryEntity.fromCacheSinceMs] `null`)
     * UND brauchbar ist (weder `null` noch `unavailable`/`unknown`).
     *
     * **Warum das über eine Tat entscheidet (Bug 23.08.2026, Andi wörtlich:
     * „da steht, dass der auftrag an HA gegeben wurde, aber der sauger startet
     * nicht"):** Home Assistant nimmt einen Service-Call auf eine `unavailable`
     * Entity mit **HTTP 200** an und lässt ihn **kommentarlos fallen** —
     * `helpers/service.py#entity_service_call` (HA 2025.4.4, Z. 976–1002):
     * `if not entity.available: continue`, danach `if not entities: return None`.
     * Keine Exception, kein Log, kein Gerät. Ein 2xx von HA ist für einen
     * schlafenden Sauger also KEIN Beleg für irgendetwas. Diese Funktion bildet
     * HAs `entity.available` auf unserer Seite ab, damit wir gar nicht erst
     * senden, was HA beweisbar verwirft (Belege: `RESULT.md` §2/§3).
     *
     * **Der Cache-Carry ist genau hier die Falle:** [carryCache] macht den
     * schlafenden Sauger für die ANZEIGE zu `docked` (richtig so — das ist sein
     * Normalzustand, s. dortiges KDoc), und `homeTiles.ts#vacuumActionAvailability`
     * zeigt darum den „Start"-Knopf. Für eine TAT ist ein gemerkter Zustand aber
     * kein Zustellweg. Darum reicht `state == "docked"` hier NICHT — das
     * Cache-Flag muss mitgeprüft werden, und deshalb steht die Regel als EINE
     * Funktion hier statt als zwei Bedingungen im Controller.
     *
     * Rein, ohne Netz/Uhr ⇒ ohne HA testbar.
     */
    fun isLive(vacuum: HomeRegistryEntity): Boolean =
        vacuum.fromCacheSinceMs == null && isUsableState(vacuum.state)

    /** Der entityId-Stamm hinter der Domain (`vacuum.roborock_qrevo` ⇒ `roborock_qrevo`). */
    fun stemOf(vacuum: HomeRegistryEntity): String = vacuum.entityId.substringAfter('.', "")

    /**
     * Gehört [entityId] zum Gerät mit dem Stamm [stem]? Die Entity selbst
     * (`<domain>.<stem>`) oder ein Geschwister-Sensor (`<domain>.<stem>_<…>`).
     * Ein LÄNGERER Stamm zählt NICHT (`roborock2_batterie` ist ein anderes
     * Gerät) — darum der Unterstrich als Pflicht-Trenner.
     */
    fun isMember(entityId: String, stem: String): Boolean {
        if (stem.isBlank()) return false
        val local = entityId.substringAfter('.', "")
        return local == stem || local.startsWith("${stem}_")
    }

    /**
     * **Der Cache-Carry.** Für JEDE Entity der Sauger-Familie, die GERADE
     * unbrauchbar ist (`null`/`unavailable`/`unknown` — Energiesparmodus) und
     * einen gemerkten Stand hat ([HomeRegistryEntity.lastKnown], vom
     * [HaHomeRegistryAdapter] aus dem [LastKnownStateStore] angehängt), werden
     * `state` und `attrs` aus diesem Stand weitergeliefert UND
     * [HomeRegistryEntity.fromCacheSinceMs] gesetzt.
     *
     * **Ehrlichkeit (Kagami):** der Cache wird NIE als frisch ausgegeben — das
     * Feld ist genau dann und nur dann gesetzt, wenn die Werte aus dem Cache
     * kommen; ein Aufrufer, der es ignoriert, sieht Werte, aber kein Aufrufer,
     * der es liest, kann getäuscht werden. [HomeRegistryEntity.lastKnown] bleibt
     * zusätzlich unverändert dran (kein bestehender Leser verliert etwas).
     *
     * **Obergrenze [maxAge]:** ist der gemerkte Stand älter, passiert GAR
     * NICHTS — die Entity bleibt bei ihrem heutigen Unavailable-Bild
     * (`state=unavailable` + `lastKnown`). Ein wochenalter Akkustand ist keine
     * Information mehr, sondern eine Behauptung. `Duration.ZERO`/negativ
     * schaltet den Carry vollständig ab (ehrlicher Aus-Schalter ohne Flag).
     *
     * Rein und ohne Netz/Uhr-Zugriff ⇒ ohne HA testbar.
     */
    fun carryCache(snapshot: HomeRegistrySnapshot, now: Instant, maxAge: Duration): HomeRegistrySnapshot {
        if (maxAge.isZero || maxAge.isNegative) return snapshot
        val vacuum = find(snapshot) ?: return snapshot
        val stem = stemOf(vacuum)
        if (stem.isBlank()) return snapshot

        fun carry(entity: HomeRegistryEntity): HomeRegistryEntity {
            if (!isMember(entity.entityId, stem)) return entity
            // Live brauchbar ⇒ nichts zu retten (und NIE einen frischen Wert überschreiben).
            if (isUsableState(entity.state)) return entity
            val lk = entity.lastKnown ?: return entity
            val seenAt = runCatching { Instant.parse(lk.seenAt) }.getOrNull() ?: return entity
            // Zu alt ⇒ heutiges Unavailable-Bild, kein Carry (s. KDoc).
            if (Duration.between(seenAt, now) > maxAge) return entity
            return entity.copy(
                state = lk.state,
                attrs = lk.attrs,
                fromCacheSinceMs = seenAt.toEpochMilli(),
            )
        }

        return snapshot.copy(
            areas = snapshot.areas.map { area -> area.copy(entities = area.entities.map(::carry)) },
            unassigned = snapshot.unassigned.map(::carry),
        )
    }

    /** Dieselbe Regel wie [HaHomeRegistryAdapter] und `homeTiles.ts#isEntityAvailable`. */
    private fun isUsableState(state: String?): Boolean =
        state != null && state != "unavailable" && state != "unknown"
}
