package de.hoshi.web

import de.hoshi.core.dto.SpeakerContext
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.ObjectProvider

/**
 * **SpeakerDisplayNameResolver** — schließt die Text-Chat-Namens-Lücke.
 *
 * Der Voice-/WS-Rand kennt bei einer erkannten Person IMMER einen `displayName`
 * ([VoiceInboundController]/`AudioWebSocketHandler` setzen ihn direkt beim Bauen
 * des [SpeakerContext]). Der reine Text-Chat ([ChatStreamController]) kennt dagegen
 * NUR eine behauptete `speakerId` (FE-Default „gast"/`VITE_SPEAKER_ID`, s.
 * `frontend/src/api/chat.ts`) — ohne `displayName` bleibt es beim DTO-Default
 * `"Unbekannt"`, den [de.hoshi.core.pipeline.PersonaService.systemPrompt] im Prompt
 * auf „du"/„die Person" kollabiert. Eine enrollte, per Stimme längst bekannte Person
 * wird beim TIPPEN also nie beim Namen genannt.
 *
 * **Naht statt neuem Store:** [resolve] schaut NUR im ohnehin vorhandenen, per Enroll
 * (S2) befüllten [SpeakerProfileStore] nach — demselben Store, den auch
 * [SpeakerIdentifyService] (S3) liest. Kein zweiter Namens-Index, keine neue
 * Persistenz.
 *
 * **Gated über dieselbe BESTEHENDE Property wie [SpeakerIdentifyService]**
 * (`HOSHI_SPEAKER_RECOGNITION_ENABLED`, s. `PipelineConfig.speakerIdentifyService`):
 * [enabled]`=false` (Default) ⇒ [resolve] ist reiner Passthrough ⇒ byte-neutral zum
 * heutigen Verhalten. Ist zusätzlich `HOSHI_SPEAKER_ENROLL_ENABLED=false` (kein
 * [SpeakerProfileStore]-Bean), liefert [storeProvider] `null` ⇒ ebenfalls
 * unverändert — kein Crash, kein zweites Flag nötig.
 *
 * **Nie überschreiben, nie raten:** ein bereits gesetzter `displayName` (Voice-/
 * WS-Rand hat ihn schon gefüllt) bleibt unangetastet — nur der DTO-Default
 * `"Unbekannt"` wird ersetzt. Eine unbekannte `speakerId` (kein Treffer im Store)
 * bleibt ebenfalls unverändert — kein Fehler, kein Fallback-Raten (Vera-Regel:
 * lieber „du" als ein falscher Name).
 */
class SpeakerDisplayNameResolver(
    private val enabled: Boolean,
    private val storeProvider: ObjectProvider<SpeakerProfileStore>,
) {

    /**
     * Löst [context] best-effort auf: `null`/Flag-OFF/kein Store/kein Treffer/schon
     * gesetzter Name ⇒ [context] unverändert zurück. Nur ein sicherer Treffer
     * (`speakerId` == Profil-Name) ersetzt den DTO-Default-`displayName` durch den
     * groß geschriebenen Profil-Namen — exakt dieselbe Großschreib-Konvention wie
     * [VoiceInboundController] (`rec.name.replaceFirstChar { it.uppercase() }`).
     */
    fun resolve(context: SpeakerContext?): SpeakerContext? {
        if (context == null || !enabled) return context
        if (context.displayName != DEFAULT_DISPLAY_NAME) return context // Voice/WS hat schon einen Namen gesetzt
        val store = storeProvider.getIfAvailable() ?: return context // Enroll OFF ⇒ keine Profile
        val profile = store.get(context.speakerId) ?: return context // unbekannte Id ⇒ unverändert, kein Raten
        return context.copy(displayName = profile.name.replaceFirstChar { it.uppercase() })
    }

    companion object {
        /** Spiegel von [SpeakerContext.displayName]s DTO-Default — NUR den kollabiert [resolve]. */
        const val DEFAULT_DISPLAY_NAME = "Unbekannt"

        /**
         * Fester [ObjectProvider], der [value] (ggf. `null`) unverändert zurückgibt — für
         * Konstruktion OHNE Spring-Context (Default-Parameter, direkt gebaute Tests). Im
         * echten Betrieb liefert Spring selbst einen [ObjectProvider], der die
         * `@ConditionalOnProperty`-Bean live nachschlägt (Muster
         * `PipelineConfig.speakerIdentifyService`); dieser Helfer bildet dasselbe Verhalten
         * nur STATISCH nach.
         */
        fun <T> providerOf(value: T?): ObjectProvider<T> = object : ObjectProvider<T> {
            override fun getObject(): T = value ?: throw NoSuchBeanDefinitionException(Any::class.java)
            override fun getObject(vararg args: Any?): T = getObject()
            override fun getIfAvailable(): T? = value
            override fun getIfUnique(): T? = value
        }
    }
}
