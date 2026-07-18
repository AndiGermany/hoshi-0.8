# Security Policy

Hoshi ist ein lokal-first Assistent für ein privates Zuhause. Sicherheit heißt hier vor allem:
**deine Daten verlassen das Gerät nicht ungewollt**, und **niemand im LAN kann Hoshi ungefragt steuern.**

## Grundsätze (Kernel-Invarianten)
1. **Default-deny Capability-Kernel.** Jede schreibwirkende Aktion (Smart-Home `call_service`,
   Einkaufsliste, Szenen) läuft durch *ein* Gate (`CapabilityBroker`) — DENY-by-default, least-privilege.
2. **Auth-Wand (ANDI-1).** Alle `/api/*`- und `/ws/*`-Pfade außer `/api/health` sind token-gated
   (`IngressAuthFilter`, konstantzeit-Vergleich, fail-closed). Loopback bleibt frei (lokales FE/Backend).
3. **Biometrie bleibt lokal.** Stimm-Profile (Speaker-ID-Embeddings) sind invertierbar = biometrisch.
   Sie werden nie egress-gesendet; aufgenommene Stimme dient ausschließlich der **Input**-Verbesserung.
4. **Cloud ist sanitisiert + opt-in.** Cloud-Pfade durchlaufen einen `PrivacySanitizer` (Namen/IDs/URLs raus)
   und sind default-OFF + abschaltbar.
5. **MCP-Tool-Server** bindet per Default Loopback (`127.0.0.1`); ein Fremd-Bind ohne Auth-Token fällt fail-closed zurück.

## Bekannte offene Punkte (transparent, getrackt)
| ID | Befund | Status |
|---|---|---|
| SEC-1 | **OpenClaw-Egress unsanitisiert.** Der `streamOpenClaw`-Pfad (aus 0.5) sendet Sprecher-Name + `haBaseUrl` ohne `PrivacySanitizer` an das LAN-Gateway. | **Quarantäne in 0.8** — portiert nur mit Sanitizer im Egress-Pfad; sonst nicht übernommen. Default-OFF. → [[04-MIGRATION-CHARTER]] |

## Eine Schwachstelle melden
Privates Projekt, aber offen für verantwortungsvolle Offenlegung (Responsible Disclosure):

- **Kontakt:** `hoshi.security@gmail.com` — bitte Sicherheitslücken NICHT über ein öffentliches
  Issue melden, sondern privat an diese Adresse.
- **Was hilfreich ist:** ein konkretes, reproduzierbares Szenario (kein automatisiertes Scanning —
  Hoshi läuft privat im LAN einzelner Haushalte, es gibt keine öffentliche Test-Instanz).
- **Was du erwarten kannst:** eine Rückmeldung, bevor der Fund öffentlich diskutiert wird (Issue/PR),
  und auf Wunsch eine Nennung im Fix-Commit.
- **Reaktionszeit:** best-effort — Einzelperson/Hobby-Projekt, kein SLA.

Sicherheitsrelevante Funde bitte generell nicht über öffentliche Issues, bis ein Fix steht.

### Wo ein Audit zuerst hinschauen würde (flag-gated Security-Features)
Die Kernel-Invarianten oben sind größtenteils konkrete Feature-Flags, nicht nur Doku — Referenz ist
[`tools/systemd/hoshi-0.8-backend.service`](tools/systemd/hoshi-0.8-backend.service) (Platzhalter wie
`__API_TOKEN__` werden erst beim Deploy gefüllt, nie committet):
- **Ingress-Auth-Wand:** `IngressAuthFilter` / `hoshi.perimeter.enabled` + `HOSHI_API_TOKEN` —
  konstantzeit-Tokenvergleich, fail-closed, nur Loopback ist ausgenommen.
- **Sprecher-Trust:** `HOSHI_SPEAKER_RECOGNITION_ENABLED` + `HOSHI_SPEAKER_RECOGNITION_THRESHOLD`
  (Schwelle, ab der eine Stimme als „erkannt" statt „Gast" gilt) sowie `HOSHI_SPEAKER_ENROLL_ENABLED`
  (Stimm-Anlernen) — im Zweifel gilt immer Gast, nie eine geratene Identität.

## Verifikations-Disziplin
`grün ≠ lebt`: Sicherheits-Akzeptanz heißt **Exploit live versucht → fällt zu**, nicht „gemerged".
Jede Kernel-Naht wird gegen den laufenden Stack gemessen (curl/log), nicht gegen die Selbstauskunft.
