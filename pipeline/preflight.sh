#!/usr/bin/env bash
# pipeline/preflight.sh — der ZUSTANDSARME Vorab-Check (READ-ONLY, STARTET NICHTS).
#
# Unterschied zu pipeline/doctor.sh: doctor.sh misst den LAUFENDEN Stack (echter
# /v1/chat-Roundtrip, GET /health, TCP-Connect — alles Interaktion mit einem
# bereits lebenden Prozess). preflight.sh läuft VOR jeder Installation/jedem
# Start und fragt nur: „kann diese Maschine Hoshi überhaupt fahren?" — ohne
# irgendetwas zu starten, zu laden oder herunterzuladen, und ohne ein einziges
# Netzwerk-Paket zu senden (auch keine localhost-Health-Probes — das bleibt
# doctors Job). Zwei Ausnahmen, bewusst gewählt:
#   - Werkzeuge werden nach Version gefragt (python3/git/node/npm/curl --version)
#     — das ist ein reiner Prozessaufruf ohne Seiteneffekt, kein Hoshi-Start.
#   - Ports werden über die Kernel-Socket-Tabelle gelesen (`lsof -iTCP -sTCP:LISTEN`),
#     NICHT über einen TCP-Connect — das befragt den Kernel, nicht den Prozess,
#     der auf dem Port lauscht (kein Handshake mit einem laufenden Sidecar).
#
# Aufruf:
#   pipeline/preflight.sh                        # Profil local-mac (Default)
#   pipeline/preflight.sh --profile local-mac     # alles auf einer Maschine (Normalfall)
#   pipeline/preflight.sh --profile split         # Backend auf anderem Host (Andis Setup) —
#                                                  #   prüft NICHTS Netzwerkiges zusätzlich,
#                                                  #   druckt nur den Hinweis, welche Env-Vars
#                                                  #   gesetzt sein müssen (zustandsarm).
#
# Exit-Code (wie doctor.sh: 0/2/3, gleiche Bedeutung):
#   0  startklar — alle Grundvoraussetzungen erfüllt
#   2  läuft vermutlich, aber mit Einschränkungen (mind. eine WARN-Zeile)
#   3  fehlende Grundvoraussetzung (python3/git/curl fehlt o.ä. — kein sinnvoller Start möglich)
#
# NICHT in bin/hoshi eingehängt — das macht der Orchestrator selbst (Hot-File).

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
set +e   # Diagnose: Probes dürfen „fehlschlagen", wir werten selbst aus (wie doctor.sh).

cd "$REPO_ROOT"

# ── Profil-Parsing ────────────────────────────────────────────────────────────
PROFILE="local-mac"
while [ $# -gt 0 ]; do
    case "$1" in
        --profile)
            PROFILE="${2:-}"; shift 2 ;;
        --profile=*)
            PROFILE="${1#--profile=}"; shift ;;
        *)
            fail "Unbekanntes Argument: $1 (erwartet: --profile local-mac|split)"
            exit 3 ;;
    esac
done
case "$PROFILE" in
    local-mac|split) ;;
    *) fail "Ungültiges --profile '$PROFILE' (erwartet: local-mac|split)"; exit 3 ;;
esac

say "Preflight — zustandsarmer Vorab-Check (Profil: $PROFILE). Read-only, startet/lädt NICHTS."
echo

RC=0
note_degraded() { [ "$RC" -lt 2 ] && RC=2; }

# ── (1) OS + Architektur ──────────────────────────────────────────────────────
OS_NAME="$(uname -s 2>/dev/null || echo '?')"
ARCH_NAME="$(uname -m 2>/dev/null || echo '?')"
if [ "$OS_NAME" = "Darwin" ] && [ "$ARCH_NAME" = "arm64" ]; then
    ok "OS/Arch      : $OS_NAME/$ARCH_NAME (erwartet)"
else
    warn "OS/Arch      : $OS_NAME/$ARCH_NAME (erwartet: Darwin/arm64 — MLX/Metal-Sidecars brauchen Apple-Silicon)"
    note_degraded
fi

# ── (2) python3 vorhanden + Version ≥3.10 ─────────────────────────────────────
# Sidecars (Brain/STT/Speaker) sind alle Python — ohne python3 kein Bootstrap.
if command -v python3 >/dev/null 2>&1; then
    PY_VER="$(python3 -c 'import sys;print("%d.%d"%sys.version_info[:2])' 2>/dev/null || echo '')"
    PY_MAJ="${PY_VER%%.*}"; PY_MIN="${PY_VER##*.}"
    if [ -n "$PY_VER" ] && { [ "${PY_MAJ:-0}" -gt 3 ] || { [ "${PY_MAJ:-0}" -eq 3 ] && [ "${PY_MIN:-0}" -ge 10 ]; }; }; then
        ok "python3      : $PY_VER (>=3.10)"
    else
        warn "python3      : Version '${PY_VER:-?}' (<3.10 erwartet — Sidecar-Bootstraps können scheitern)"
        note_degraded
    fi
else
    fail "python3      : FEHLT — kein Sidecar-Bootstrap (Brain/STT/Speaker sind Python) möglich"
    RC=3
fi

# ── (3) git vorhanden ──────────────────────────────────────────────────────────
if command -v git >/dev/null 2>&1; then
    ok "git          : $(git --version 2>/dev/null | head -1)"
else
    fail "git          : FEHLT — Pipeline/Deploy (pipeline/*.sh) brauchen git durchgängig"
    RC=3
fi

# ── (4) node + npm vorhanden ───────────────────────────────────────────────────
if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
    ok "node/npm     : $(node --version 2>/dev/null)/$(npm --version 2>/dev/null) (Frontend-Build, frontend/)"
else
    warn "node/npm     : nicht (vollständig) gefunden — Frontend-Build nicht möglich, Backend allein läuft trotzdem"
    note_degraded
fi

# ── (5) curl vorhanden ─────────────────────────────────────────────────────────
# Praktisch jedes Pipeline-Skript (doctor/heal/up/deploy/…) probt darüber.
if command -v curl >/dev/null 2>&1; then
    ok "curl         : $(curl --version 2>/dev/null | head -1)"
else
    fail "curl         : FEHLT — doctor/heal/up/deploy können den Stack dann nicht mehr prüfen"
    RC=3
fi

# ── (6) JDK 21 auffindbar (NUR Existenz, kein java-Aufruf) ────────────────────
# JAVA_HOME: das release-Metadatafile lesen (reiner Datei-Read, kein Prozessstart)
# statt `java -version` auszuführen — sonst hätte "nur Existenz" keine Bedeutung.
JDK21_HINT=""
if [ -n "${JAVA_HOME:-}" ] && grep -q 'JAVA_VERSION="21' "$JAVA_HOME/release" 2>/dev/null; then
    JDK21_HINT="JAVA_HOME=$JAVA_HOME (release: 21)"
fi
if [ -z "$JDK21_HINT" ]; then
    GRADLE_JDK21_DIR="$(ls -d "$HOME"/.gradle/jdks/*eclipse_adoptium-21* 2>/dev/null | head -1)"
    [ -n "$GRADLE_JDK21_DIR" ] && JDK21_HINT="$GRADLE_JDK21_DIR"
fi
if [ -n "$JDK21_HINT" ]; then
    ok "JDK 21       : gefunden ($JDK21_HINT)"
else
    warn "JDK 21       : weder \$JAVA_HOME (release=21) noch ~/.gradle/jdks/*eclipse_adoptium-21* gefunden — Gradle kann es via foojay-resolver nachladen (braucht Netz beim ersten Build)"
    note_degraded
fi

# ── (7) freier Plattenplatz ────────────────────────────────────────────────────
# $HOME statt REPO_ROOT: HF-Cache + Gradle-JDKs + Modelle landen unter $HOME,
# unabhängig davon, wo das Repo geklont liegt.
DISK_AVAIL_KB="$(df -Pk "$HOME" 2>/dev/null | awk 'NR==2{print $4}')"
if [ -n "${DISK_AVAIL_KB:-}" ]; then
    DISK_AVAIL_GB=$(( DISK_AVAIL_KB / 1024 / 1024 ))
    if [ "$DISK_AVAIL_GB" -ge 20 ]; then
        ok "Plattenplatz : ${DISK_AVAIL_GB}G frei (>=20G)"
    elif [ "$DISK_AVAIL_GB" -ge 5 ]; then
        warn "Plattenplatz : ${DISK_AVAIL_GB}G frei (<20G — knapp für weitere Modell-Downloads)"
        note_degraded
    else
        warn "Plattenplatz : ${DISK_AVAIL_GB}G frei (<5G — DEGRADED, Modell-Downloads werden vermutlich scheitern)"
        note_degraded
    fi
else
    warn "Plattenplatz : nicht ermittelbar (df lieferte nichts unter \$HOME)"
    note_degraded
fi

# ── (8) RAM gesamt (die 16-GB-Wand, s. stack-lib.sh brain_guard_blocks) ───────
RAM_BYTES="$(sysctl -n hw.memsize 2>/dev/null || echo '')"
if [ -n "$RAM_BYTES" ]; then
    RAM_GB=$(( RAM_BYTES / 1024 / 1024 / 1024 ))
    if [ "$RAM_GB" -ge 16 ]; then
        ok "RAM gesamt   : ${RAM_GB}G (>=16G)"
    else
        warn "RAM gesamt   : ${RAM_GB}G (<16G — die 16-GB-Wand: e4b/12b + Sidecars werden eng, ggf. e2b nötig)"
        note_degraded
    fi
else
    warn "RAM gesamt   : nicht ermittelbar (sysctl hw.memsize fehlgeschlagen — kein macOS?)"
    note_degraded
fi

# ── (9) Ports — NUR informativ (belegt ist im laufenden Betrieb NORMAL) ───────
# Kernel-Socket-Tabelle (lsof -sTCP:LISTEN), KEIN TCP-Connect: das befragt den
# Kernel, nicht den Prozess dahinter — kein Handshake, keine Netzwerk-Interaktion.
echo
log "Ports (informativ — belegt ist im laufenden Betrieb normal, kein Fehler):"
port_hr() { printf '  %s\n' "──────┼─────────────┼────────"; }
printf '  %-5s │ %-11s │ %s\n' "PORT" "DIENST" "STATUS"
port_hr
port_status() { # port label
    local port="$1" label="$2" out
    if ! command -v lsof >/dev/null 2>&1; then
        printf '  %-5s │ %-11s │ %b%s%b\n' "$port" "$label" "$C_YELLOW" "? (kein lsof)" "$C_RESET"
        return
    fi
    out="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null)"
    if [ -n "$out" ]; then
        printf '  %-5s │ %-11s │ %b%s%b\n' "$port" "$label" "$C_DIM" "belegt" "$C_RESET"
    else
        printf '  %-5s │ %-11s │ %b%s%b\n' "$port" "$label" "$C_GREEN" "frei" "$C_RESET"
    fi
}
port_status 8082 "backend"
port_status 8041 "brain(e4b)"
port_status 8045 "piper-tts"
port_status 8035 "knowledge"
port_status 8044 "say-tts"
port_status 9001 "whisper-stt"
port_status 9002 "speaker-id"
port_hr
echo

# ── (10) models.json vorhanden + parsebar ─────────────────────────────────────
MODELS_JSON="$REPO_ROOT/models.json"
if [ ! -f "$MODELS_JSON" ]; then
    warn "models.json  : FEHLT ($MODELS_JSON) — Modell-Vollständigkeit nicht prüfbar"
    note_degraded
elif ! python3 -m json.tool "$MODELS_JSON" >/dev/null 2>&1; then
    warn "models.json  : vorhanden, aber NICHT parsebar (python3 -m json.tool schlägt fehl)"
    note_degraded
else
    ok "models.json  : vorhanden + parsebar ($MODELS_JSON)"

    # ── (11) required-Modelle: NUR Existenz-Check (Pfad/Cache da?) ───────────
    # Bewusst schlanker als tools/models-verify.sh (keine Hash-/Snapshot-/
    # refs-Tiefenprüfung) — das ist doctors/models-verify's Job, hier zählt nur
    # „ist überhaupt etwas da, bevor wir starten wollen?".
    if [ -n "${HUGGINGFACE_HUB_CACHE:-}" ]; then
        HF_HUB_CACHE="$HUGGINGFACE_HUB_CACHE"
    elif [ -n "${HF_HOME:-}" ]; then
        HF_HUB_CACHE="$HF_HOME/hub"
    else
        HF_HUB_CACHE="$HOME/.cache/huggingface/hub"
    fi
    HOSHI_05_ROOT="${HOSHI_05_ROOT:-$HOME/IdeaProjects/Hoshi_0.5}"

    while IFS=$'\t' read -r mid mtype status detail; do
        [ -z "$mid" ] && continue
        case "$status" in
            OK)   ok   "  Modell ${mid} (${mtype}): ${detail}" ;;
            SKIP) log  "  Modell ${mid} (${mtype}): ${detail}" ;;
            *)    warn "  Modell ${mid} (${mtype}): ${detail}"; note_degraded ;;
        esac
    done < <(python3 - "$MODELS_JSON" "$HF_HUB_CACHE" "$HOSHI_05_ROOT" <<'PYEOF'
import json
import sys
from pathlib import Path

manifest_path, hf_cache, hoshi05 = sys.argv[1:4]
hf_cache = Path(hf_cache)

try:
    data = json.loads(Path(manifest_path).read_text())
except Exception as e:
    print(f"?\t?\tFEHLT\tManifest nicht lesbar: {e}")
    sys.exit(0)

for m in data.get("models", []):
    if not m.get("required"):
        continue
    mid, mtype = m.get("id", "?"), m.get("type", "?")
    try:
        if mtype == "hf":
            repo_dir = hf_cache / ("models--" + m["hf_repo"].replace("/", "--"))
            if repo_dir.is_dir():
                print(f"{mid}\t{mtype}\tOK\tCache-Verzeichnis da: {repo_dir}")
            else:
                print(f"{mid}\t{mtype}\tFEHLT\tkein Cache-Verzeichnis: {repo_dir}")
        elif mtype == "hf-direct-file":
            # models.json v2 (b44fd9d): repo-nativer Pfad heisst sidecar_local_path
            # und ist die Wahrheit fuer Frischklone; local_path ($HOSHI_05_ROOT)
            # ist der Alt-Standort und faellt mit It-5 (0.5-Split). Beide pruefen,
            # repo-nativ gewinnt — der 0.5-Treffer wird ehrlich als solcher benannt.
            repo_root = Path(manifest_path).resolve().parent  # models.json wohnt im Repo-Root
            repo_p = repo_root / m["sidecar_local_path"] if m.get("sidecar_local_path") else None
            legacy_p = Path(m["local_path"].replace("$HOSHI_05_ROOT", hoshi05)) if m.get("local_path") else None
            if repo_p is not None and repo_p.exists():
                print(f"{mid}\t{mtype}\tOK\tDatei da (repo-nativ): {repo_p}")
            elif legacy_p is not None and legacy_p.exists():
                print(f"{mid}\t{mtype}\tOK\tDatei da (0.5-Altpfad, faellt mit It-5): {legacy_p}")
            else:
                print(f"{mid}\t{mtype}\tFEHLT\tDatei fehlt: {repo_p or legacy_p}")
        else:
            print(f"{mid}\t{mtype}\tSKIP\tkein Pfad-Feld im Manifest (type={mtype}) -- Existenz-Check hier ausgelassen, siehe LIMITATIONS")
    except Exception as e:
        print(f"{mid}\t{mtype}\tFEHLT\tCheck fehlgeschlagen: {e}")
PYEOF
)
fi
echo

# ── Split-Profil: KEINE Netz-Probe, nur der Env-Var-Hinweis ───────────────────
if [ "$PROFILE" = "split" ]; then
    say "Profil 'split' — Backend läuft auf einem anderen Host als die Sidecars (Andis Setup: Backend ct-106, Sidecars Mac)."
    log "Diese Maschine wird NICHT übers Netz geprüft (zustandsarm, keine Remote-Probes — das ist doctors Job)."
    log "Falls hier das BACKEND läuft, müssen diese Variablen auf den Sidecar-Host zeigen"
    log "(Single Source of Truth: tools/systemd/hoshi-0.8-backend.service):"
    log "  hoshi.brain.base-url / HOSHI_BRAIN_BASE_URL   http://<sidecar-host>:8041  (Brain e4b)"
    log "  HOSHI_STT_BASE_URL                             http://<sidecar-host>:9001  (Whisper-STT)"
    log "  HOSHI_SPEAKER_BASE_URL                          http://<sidecar-host>:9002  (Speaker-ID)"
    log "  HOSHI_KNOWLEDGE_BRIDGE_BASE_URL                 http://<sidecar-host>:8035  (Knowledge-Bridge)"
    log "  HOSHI_TTS_SAY_BASE_URL                          http://<sidecar-host>:8044  (say-TTS)"
    log "  HOSHI_EPISODIC_EMBED_URL                        http://<sidecar-host>:11434 (Ollama/Embeddings)"
    log "  HOSHI_HA_BASE_URL                               http://<home-assistant>:8123"
    log "  HOSHI_API_TOKEN, HOSHI_HA_TOKEN                 Secrets — NIE hier eintragen, kommen aus /etc/hoshi-0.8/secrets.env"
    echo
fi

# ── Gesamt-Urteil ──────────────────────────────────────────────────────────────
case "$RC" in
    0) ok   "Urteil: STARTKLAR — Grundvoraussetzungen erfüllt (Profil: $PROFILE)." ;;
    2) warn "Urteil: EINSCHRAENKUNGEN — läuft vermutlich, aber mit Abstrichen, siehe WARN oben (Profil: $PROFILE)." ;;
    3) fail "Urteil: BLOCKIERT — fehlende Grundvoraussetzung, siehe FEHLER oben (Profil: $PROFILE)." ;;
esac
exit "$RC"
