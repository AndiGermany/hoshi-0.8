package de.hoshi.core.port

/**
 * Read-only Vorpruefung, ob Hoshi einen externen Nachschlag HEUTE ehrlich
 * anbieten darf. Der eigentliche Call bleibt ausschliesslich Sache von
 * [EscalationPort]; diese Naht verrät weder Anbieter noch Modell und löst
 * selbst keinen Egress aus.
 *
 * Der Cloud-Adapter beantwortet damit nur lokal bekannte harte Gates (Key und
 * Tages-Cap). Netz/DNS koennen zwischen Angebot und Einloesung ausfallen; das
 * bleibt der bestehende Never-Silent-[EscalationResult.Unavailable]-Pfad.
 */
fun interface EscalationAvailabilityPort {
    fun canOffer(): Boolean

    companion object {
        /** Decke/Adapter aus: niemals ein Angebot versprechen. */
        val NONE: EscalationAvailabilityPort = EscalationAvailabilityPort { false }
    }
}
