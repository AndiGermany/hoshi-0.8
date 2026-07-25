package de.hoshi.core.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * **Deutsch wackelt um kein Byte.** Harte Nebenbedingung von Andi fuer die
 * Englisch-Runde 2026-07-25 an [TimerIntent]/[ListIntent]: die englische
 * Erkennung kommt ADDITIV dazu, die deutsche Seite bleibt unveraendert.
 *
 * Die Vektoren unten sind kein Wunschbild, sondern ein MESSPROTOKOLL: sie wurden
 * VOR der Aenderung aus dem laufenden Code gezogen (Klassifikat + sortierte
 * Slot-Map als ein String) und danach unveraendert wieder eingespielt. Wer einen
 * dieser Werte anfassen muss, hat die deutsche Seite bewegt — dann ist der
 * Eingriff falsch, nicht der Test.
 *
 * Zwei Erwartungen sind ABSICHTLICH keine Ideal-Werte, sondern konservierte
 * Altlasten (sie waren vorher schon so und liegen ausserhalb dieser Scheibe):
 *  - „wer steht auf der Liste" landet als Eintrag „wer steht" auf der Liste —
 *    die deutsche Frage-Sperre fehlt (die englische ist jetzt da, s.
 *    [ListIntentEnglishTest]); eine deutsche waere eine DE-Verhaltensaenderung.
 *  - Die Feinheiten der deutschen Uhrzeit-Zweige bleiben, wie sie sind.
 */
class IntentGermanByteIdentityTest {

    private data class Vector(val text: String, val expected: String?)

    private fun vector(text: String, expected: String?) = Vector(text, expected)

    private fun fingerprint(call: ToolCall?): String? =
        call?.let { "${it.domain}/${it.service} ${it.data.toSortedMap()}" }

    @Test
    fun `deutsche Timer-Erkennung ist unveraendert`() {
        TIMER_VECTORS.forEach { v ->
            assertEquals(v.expected, fingerprint(TimerIntent.classify(v.text)), "DE-Timer hat sich geaendert bei: ${v.text}")
        }
    }

    @Test
    fun `deutsche Listen-Erkennung ist unveraendert`() {
        LIST_VECTORS.forEach { v ->
            assertEquals(v.expected, fingerprint(ListIntent.classify(v.text)), "DE-Liste hat sich geaendert bei: ${v.text}")
        }
    }

    private val TIMER_VECTORS = listOf(
        vector("stell einen Timer auf zehn Minuten", "timer/set {durationSeconds=600, kind=TIMER}"),
        vector("Timer auf 5 Minuten", "timer/set {durationSeconds=300, kind=TIMER}"),
        vector("stell einen Timer für 1 Stunde 30", "timer/set {durationSeconds=5400, kind=TIMER}"),
        vector("Timer auf eine halbe Stunde", "timer/set {durationSeconds=1800, kind=TIMER}"),
        vector("Timer auf anderthalb Stunden", "timer/set {durationSeconds=5400, kind=TIMER}"),
        vector("Timer auf eine Viertelstunde", "timer/set {durationSeconds=900, kind=TIMER}"),
        vector("Timer auf eine dreiviertel Stunde", "timer/set {durationSeconds=2700, kind=TIMER}"),
        vector("Timer auf 20 Sekunden", "timer/set {durationSeconds=20, kind=TIMER}"),
        vector("Timer auf eine Minute", "timer/set {durationSeconds=60, kind=TIMER}"),
        vector("Küchentimer auf 3 Minuten", "timer/set {durationSeconds=180, kind=TIMER}"),
        vector("Kurzzeitwecker auf 12 Minuten", "timer/set {durationSeconds=720, kind=ALARM}"),
        vector("stell den Nudel-Timer auf 8 Minuten", "timer/set {durationSeconds=480, kind=TIMER, label=Nudel}"),
        vector("Wecker um sieben", "timer/set {clockForceTomorrow=false, clockHour=7, clockMinute=0, kind=ALARM}"),
        vector("weck mich um 7 Uhr", "timer/set {clockForceTomorrow=false, clockHour=7, clockMinute=0, kind=ALARM}"),
        vector("weck mich um 7 Uhr 30", "timer/set {clockForceTomorrow=false, clockHour=7, clockMinute=30, kind=ALARM}"),
        vector("weck mich 7:30", "timer/set {clockForceTomorrow=false, clockHour=7, clockMinute=30, kind=ALARM}"),
        vector("stell den Wecker auf 22.57 Uhr", "timer/set {clockForceTomorrow=false, clockHour=22, clockMinute=57, kind=ALARM}"),
        vector("weck mich um halb acht", "timer/set {clockForceTomorrow=false, clockHour=7, clockMinute=30, kind=ALARM}"),
        vector("weck mich um viertel vor acht", "timer/set {clockForceTomorrow=false, clockHour=7, clockMinute=45, kind=ALARM}"),
        vector("weck mich um viertel nach sieben", "timer/set {clockForceTomorrow=false, clockHour=7, clockMinute=15, kind=ALARM}"),
        vector("weck mich um dreiviertel acht", "timer/set {clockForceTomorrow=false, clockHour=7, clockMinute=45, kind=ALARM}"),
        vector("weck mich morgen früh", "timer/set {clockForceTomorrow=true, clockHour=7, clockMinute=0, kind=ALARM}"),
        vector("erinner mich heute abend", "timer/set {clockForceTomorrow=false, clockHour=20, clockMinute=0, kind=REMINDER}"),
        vector("weck mich um Mitternacht", "timer/set {clockForceTomorrow=false, clockHour=0, clockMinute=0, kind=ALARM}"),
        vector("weck mich morgen um 6", "timer/set {clockForceTomorrow=true, clockHour=6, clockMinute=0, kind=ALARM}"),
        vector("weck mich um 7:30 am Abend", "timer/set {clockForceTomorrow=false, clockHour=20, clockMinute=0, kind=ALARM}"),
        vector("stell den Wecker auf 7 am Abend", "timer/set {clockForceTomorrow=false, clockHour=20, clockMinute=0, kind=ALARM}"),
        vector("erinner mich in 10 Minuten an die Suppe", "timer/set {durationSeconds=600, kind=REMINDER, label=Suppe}"),
        vector("erinnere mich um 8 an den Müll", "timer/set {clockForceTomorrow=false, clockHour=8, clockMinute=0, kind=REMINDER, label=Müll}"),
        vector("denk dran mich in 5 Minuten zu erinnern", "timer/set {durationSeconds=300, kind=REMINDER}"),
        vector("wie lange läuft der Timer noch", "timer/query {text=wie lange läuft der Timer noch}"),
        vector("wie lange geht der Timer noch", "timer/query {text=wie lange geht der Timer noch}"),
        vector("wie lange noch", "timer/query {text=wie lange noch}"),
        vector("wann klingelt der Wecker", "timer/query {kindHint=ALARM, text=wann klingelt der Wecker}"),
        vector("läuft gerade ein Timer", "timer/query {text=läuft gerade ein Timer}"),
        vector("wie viele Timer laufen", "timer/query {text=wie viele Timer laufen}"),
        vector("zeig mir die Timer", "timer/query {text=zeig mir die Timer}"),
        vector("stopp den Timer", "timer/cancel {all=false, text=stopp den timer}"),
        vector("stoppe den Timer", "timer/cancel {all=false, text=stoppe den timer}"),
        vector("Timer abbrechen", "timer/cancel {all=false, text=timer abbrechen}"),
        vector("alle Timer löschen", "timer/cancel {all=true, text=alle timer löschen}"),
        vector("stell den Wecker ab", "timer/cancel {all=false, text=stell den wecker ab}"),
        vector("lösch die Erinnerung", "timer/cancel {all=false, text=lösch die erinnerung}"),
        vector("stopp den Tee-Timer", "timer/cancel {all=false, text=stopp den tee timer}"),
        vector("stell einen Timer", null),
        vector("wie lange dauert Pasta kochen", null),
        vector("mach das Licht aus", null),
        vector("kein Timer bitte", null),
        vector("nicht wecken", null),
        vector("wie spät ist es", null),
    )

    private val LIST_VECTORS = listOf(
        vector("Setz Milch auf die Liste", "list/add {item=Milch}"),
        vector("setz mir bitte Milch auf die Einkaufsliste", "list/add {item=Milch}"),
        vector("pack 500 g Hack auf die Liste", "list/add {item=500 g Hack}"),
        vector("schreib Brot auf den Einkaufszettel", "list/add {item=Brot}"),
        vector("Milch auf die Liste", "list/add {item=Milch}"),
        vector("füg Butter zur Liste hinzu", "list/add {item=Butter}"),
        vector("notier Zucker auf meiner Liste", "list/add {item=Zucker}"),
        vector("was steht auf der Liste", "list/read {}"),
        vector("was steht auf meiner Liste", "list/read {}"),
        vector("was ist auf der Liste", "list/read {}"),
        vector("zeig mir die Liste", "list/read {}"),
        vector("lies mir die Liste vor", "list/read {}"),
        vector("was muss ich noch einkaufen", "list/read {}"),
        vector("nimm Milch von der Liste", "list/remove {all=false, item=Milch}"),
        vector("streich Brot von der Einkaufsliste", "list/remove {all=false, item=Brot}"),
        vector("lösch Butter aus der Liste", "list/remove {all=false, item=Butter}"),
        vector("leer die Liste", "list/remove {all=true}"),
        vector("die ganze Liste löschen", "list/remove {all=true}"),
        vector("räum die Liste leer", "list/remove {all=true}"),
        vector("wer steht auf der Gästeliste", null),
        vector("mach mir eine Liste von Ideen für Papas Geburtstag", null),
        vector("wer steht auf der Liste", "list/add {item=wer steht}"),
        vector("kein Brot auf die Liste", null),
    )
}
