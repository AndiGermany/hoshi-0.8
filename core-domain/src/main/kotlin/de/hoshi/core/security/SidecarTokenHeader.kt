package de.hoshi.core.security

/**
 * **SidecarTokenHeader** — die EINE Konstante für die opt-in Token-Wand aller
 * sechs Python-Sidecars (brain :8041, stt :9001, speaker :9002, knowledge :8035,
 * piper :8045, say :8044; s. die jeweiligen `sidecars/.../server.py`, Commit
 * 62fccc7 „die Tuer bekommt ein Schloss"). Env `HOSHI_SIDECAR_TOKEN` gesetzt ⇒
 * jeder Request außer `GET /health` verlangt genau diesen Header, sonst 401.
 *
 * **Kein zentraler Client-Builder:** jeder Adapter baut seinen eigenen
 * WebClient/HttpClient (Konstruktor-Parameter, kein Spring-Bean-Client). Diese
 * Konstante ist deshalb der gemeinsame Nenner statt eines Copy-Paste-Literals —
 * jeder betroffene Adapter bekommt einen eigenen `token`-Konstruktorparameter
 * (Default `""`, Muster `baseUrl`) und hängt [NAME] NUR an, wenn der Wert nicht
 * leer ist. Leer ⇒ kein Header ⇒ byte-identisches Verhalten zu vor der Token-Wand.
 *
 * **Verwechslungsgefahr, bewusst vermieden:** `PerimeterWebFilter.X_HOSHI_TOKEN`
 * (web-inbound) trägt zufällig denselben String-Wert — das ist die WAND DER
 * EIGENEN API für EINGEHENDE Requests (`hoshi.perimeter.token`/`HOSHI_API_TOKEN`).
 * Dieser Header hier gilt für AUSGEHENDE Requests dieses Backends AN die
 * Sidecars und lebt an einer komplett eigenen Property/einem eigenen Wert
 * (`hoshi.sidecar.token`/`HOSHI_SIDECAR_TOKEN`) — nie der Perimeter-Token.
 */
object SidecarTokenHeader {
    const val NAME = "X-Hoshi-Token"
}
