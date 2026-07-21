import { useEffect, useRef, useState } from 'react';
import {
  type NightModeConfig,
  type NightModeDevice,
  type NightModeMode,
  NightModeLockedError,
  NightModeValidationError,
  fetchNightModeDevice,
  fetchNightModeDevices,
  saveNightModeDevice,
} from '../api/nightMode';
import { de } from '../i18n/de';
import { useUiStrings } from '../i18n';
import { NightWindowDial } from './NightWindowDial';

// ─────────────────────────────────────────────────────────────────────────────
//  Nachtmodus (Scheibe 3 von 3) — PRO GERÄT einstellbar (Andi 12.07). Backend
//  ist fertig (Scheibe 2): GET .../devices, GET/PUT .../{satelliteId}, s.
//  api/nightMode.ts. Diese Scheibe ist reines FE — die Settings-Section +
//  der Nacht-Fenster-Dial.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Alle sichtbaren Texte an einem Ort (auch von den Tests referenziert) — jetzt
 * eine Referenz auf den `de`-Katalog in `i18n/de.ts` (byte-gleich zum
 * bisherigen Stand). Gerendert wird `useUiStrings().nightMode`, s. unten.
 */
export const NIGHT_MODE_TEXTS = de.nightMode;

/** Wie lange ein „zuletzt gesehen" nur clientseitig beobachtet werden kann. */
const LAST_SEEN_KEY = 'hoshi.nightMode.lastSeen';

/** Defensiver Zugriff auf localStorage (node/SSR/privater Modus kennen es nicht). */
function safeStorage(): Storage | null {
  try {
    if (typeof localStorage !== 'undefined') return localStorage;
  } catch {
    /* Zugriff geblockt (privater Modus) — kein Bruch. */
  }
  return null;
}

function readLastSeenMap(): Record<string, number> {
  const store = safeStorage();
  if (!store) return {};
  try {
    const raw = store.getItem(LAST_SEEN_KEY);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return {};
    const out: Record<string, number> = {};
    for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof v === 'number' && Number.isFinite(v)) out[k] = v;
    }
    return out;
  } catch {
    return {};
  }
}

/**
 * Ehrlich: das Backend liefert KEINEN „zuletzt gesehen"-Zeitstempel — diese
 * Funktion merkt sich nur, was DIESER Browser selbst je beobachtet hat (jedes
 * Mal, wenn ein Gerät als `connected` gemeldet wurde). Nie eine erfundene Zeit.
 */
function recordSeenDevices(devices: NightModeDevice[]): Record<string, number> {
  const map = readLastSeenMap();
  let changed = false;
  const now = Date.now();
  for (const d of devices) {
    if (d.connected) {
      map[d.satelliteId] = now;
      changed = true;
    }
  }
  if (changed) {
    const store = safeStorage();
    try {
      store?.setItem(LAST_SEEN_KEY, JSON.stringify(map));
    } catch {
      /* Storage voll/geblockt — ignorieren. */
    }
  }
  return map;
}

/** „gerade eben"/„vor X Min./Std./Tagen" — nie ein erfundener Zeitpunkt (nur echte Beobachtung). */
export function formatLastSeen(ms: number | undefined, nowMs: number = Date.now()): string | null {
  if (!ms || ms <= 0) return null;
  const diffMs = Math.max(0, nowMs - ms);
  const minutes = Math.round(diffMs / 60000);
  if (minutes < 1) return 'gerade eben';
  if (minutes < 60) return `vor ${minutes} Min.`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `vor ${hours} Std.`;
  const days = Math.round(hours / 24);
  return `vor ${days} Tag${days === 1 ? '' : 'en'}`;
}

function pickDraft(device: NightModeDevice): NightModeConfig {
  return { enabled: device.enabled, mode: device.mode, from: device.from, to: device.to, dim: device.dim };
}

function sortDevices(devices: NightModeDevice[]): NightModeDevice[] {
  return [...devices].sort((a, b) => {
    if (a.connected !== b.connected) return a.connected ? -1 : 1;
    return a.satelliteId.localeCompare(b.satelliteId);
  });
}

function upsertDevice(devices: NightModeDevice[], device: NightModeDevice): NightModeDevice[] {
  const idx = devices.findIndex((d) => d.satelliteId === device.satelliteId);
  if (idx === -1) return [...devices, device];
  const next = [...devices];
  next[idx] = device;
  return next;
}

const clampDim = (v: number): number => Math.min(1, Math.max(0, v));

// ─────────────────────────────────────────────────────────────────────────────
//  Präsentation: Geräte-Liste + manuelles Targeting + leerer Zustand.
//  (prop-getrieben → node-testbar via renderToStaticMarkup, Muster von
//  SpeakerListView/SkillsSection.)
// ─────────────────────────────────────────────────────────────────────────────

export interface NightModeDeviceRow {
  device: NightModeDevice;
  /** Nur relevant, wenn `!device.connected` — s. {@link formatLastSeen}. */
  lastSeenLabel: string | null;
}

export interface NightModeDeviceListViewProps {
  rows: NightModeDeviceRow[];
  selectedId: string | null;
  loading?: boolean;
  error?: string | null;
  onSelect: (device: NightModeDevice) => void;
  manualId: string;
  manualBusy?: boolean;
  manualNote?: string | null;
  onManualId: (value: string) => void;
  onManualSubmit: () => void;
}

export function NightModeDeviceListView({
  rows,
  selectedId,
  loading,
  error,
  onSelect,
  manualId,
  manualBusy,
  manualNote,
  onManualId,
  onManualSubmit,
}: NightModeDeviceListViewProps) {
  const t = useUiStrings();
  const NIGHT_MODE_TEXTS = t.nightMode;
  const isEmpty = !loading && rows.length === 0;
  return (
    <>
      {loading && rows.length === 0 && <p className="settings__hint">lädt…</p>}
      {error && (
        <p className="settings__hint" role="alert">
          {error}
        </p>
      )}

      {rows.length > 0 && (
        <div className="settings__nightdevices" role="radiogroup" aria-label="Gerät">
          {rows.map(({ device, lastSeenLabel }) => {
            const active = device.satelliteId === selectedId;
            const hint = device.connected
              ? NIGHT_MODE_TEXTS.onlineHint
              : lastSeenLabel
                ? NIGHT_MODE_TEXTS.offlineHint(lastSeenLabel)
                : NIGHT_MODE_TEXTS.neverSeenHint;
            return (
              <button
                key={device.satelliteId}
                type="button"
                role="radio"
                aria-checked={active}
                className={`settings__nightdevice ${active ? 'is-active' : ''} ${
                  device.connected ? '' : 'settings__nightdevice--offline'
                }`}
                onClick={() => onSelect(device)}
              >
                <span className="settings__nightdevicename">{device.satelliteId}</span>
                <span className="settings__nightdevicehint">{hint}</span>
              </button>
            );
          })}
        </div>
      )}

      {isEmpty && (
        <div className="settings__nightempty">
          <p className="settings__hint">{NIGHT_MODE_TEXTS.empty}</p>
          <p className="settings__hint">{NIGHT_MODE_TEXTS.emptyHint}</p>
        </div>
      )}

      <div className="settings__nightmanual">
        <label className="settings__label" htmlFor="nightmode-manual-id">
          {NIGHT_MODE_TEXTS.manualLabel}
        </label>
        <div className="settings__nightmanualrow">
          <input
            id="nightmode-manual-id"
            type="text"
            className="settings__text"
            placeholder={NIGHT_MODE_TEXTS.manualPlaceholder}
            value={manualId}
            disabled={manualBusy}
            onChange={(e) => onManualId(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') onManualSubmit();
            }}
          />
          <button
            type="button"
            className="settings__savebtn"
            disabled={manualBusy || !manualId.trim()}
            onClick={onManualSubmit}
          >
            {manualBusy ? NIGHT_MODE_TEXTS.saving : NIGHT_MODE_TEXTS.manualButton}
          </button>
        </div>
        {manualNote && (
          <p className="settings__hint" role="status">
            {manualNote}
          </p>
        )}
      </div>
    </>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  Präsentation: das Settings-Kärtchen des GEWÄHLTEN Geräts — Master-Toggle,
//  Modus, Dial+Zeitfelder, Dimm-Slider, Speichern. (prop-getrieben, testbar.)
// ─────────────────────────────────────────────────────────────────────────────

export interface NightModeDeviceCardProps {
  draft: NightModeConfig;
  busy?: boolean;
  note?: string | null;
  onToggleEnabled: () => void;
  onMode: (mode: NightModeMode) => void;
  onFrom: (time: string) => void;
  onTo: (time: string) => void;
  onDim: (percent: number) => void;
  onSave: () => void;
}

export function NightModeDeviceCard({
  draft,
  busy,
  note,
  onToggleEnabled,
  onMode,
  onFrom,
  onTo,
  onDim,
  onSave,
}: NightModeDeviceCardProps) {
  const t = useUiStrings();
  const NIGHT_MODE_TEXTS = t.nightMode;
  const dimPercent = Math.round(draft.dim * 100);
  return (
    <div className="settings__nightcard">
      <div className="settings__nightmaster">
        <span className="settings__label">{NIGHT_MODE_TEXTS.master}</span>
        <button
          type="button"
          role="switch"
          aria-checked={draft.enabled}
          aria-label={NIGHT_MODE_TEXTS.master}
          className={`settings__toggle ${draft.enabled ? 'is-on' : ''}`}
          onClick={onToggleEnabled}
        >
          <span className="settings__toggleknob" aria-hidden="true" />
        </button>
      </div>

      <div className={`settings__nightdetails ${draft.enabled ? '' : 'is-disabled'}`}>
        <div className="settings__nightmodes" role="radiogroup" aria-label="Modus">
          <button
            type="button"
            role="radio"
            aria-checked={draft.mode === 'SCHEDULE'}
            className={`settings__nightmodebtn ${draft.mode === 'SCHEDULE' ? 'is-active' : ''}`}
            disabled={!draft.enabled}
            onClick={() => onMode('SCHEDULE')}
          >
            {NIGHT_MODE_TEXTS.modeSchedule}
          </button>
          <button
            type="button"
            role="radio"
            aria-checked={draft.mode === 'ALWAYS'}
            className={`settings__nightmodebtn ${draft.mode === 'ALWAYS' ? 'is-active' : ''}`}
            disabled={!draft.enabled}
            onClick={() => onMode('ALWAYS')}
          >
            {NIGHT_MODE_TEXTS.modeAlways}
          </button>
        </div>

        {draft.mode === 'SCHEDULE' && (
          <div className="settings__nightwindow">
            <NightWindowDial
              from={draft.from}
              to={draft.to}
              onFromChange={onFrom}
              onToChange={onTo}
              disabled={!draft.enabled}
            />
            <div className="settings__nighttimes">
              <label className="settings__nighttimefield">
                <span className="settings__hint">{NIGHT_MODE_TEXTS.fromLabel}</span>
                <input
                  type="time"
                  className="settings__time"
                  value={draft.from}
                  disabled={!draft.enabled}
                  onChange={(e) => onFrom(e.target.value)}
                />
              </label>
              <label className="settings__nighttimefield">
                <span className="settings__hint">{NIGHT_MODE_TEXTS.toLabel}</span>
                <input
                  type="time"
                  className="settings__time"
                  value={draft.to}
                  disabled={!draft.enabled}
                  onChange={(e) => onTo(e.target.value)}
                />
              </label>
            </div>
          </div>
        )}

        <div className="settings__nightdim">
          <label className="settings__label" htmlFor="nightmode-dim">
            {NIGHT_MODE_TEXTS.dimLabel}: {dimPercent}%
          </label>
          <div className="settings__nightdimrow">
            <input
              id="nightmode-dim"
              type="range"
              className="settings__range"
              min={0}
              max={100}
              step={1}
              value={dimPercent}
              disabled={!draft.enabled}
              onChange={(e) => onDim(Number(e.target.value))}
            />
            {/* Live-Vorschau: dunkelt entsprechend `dim` ab (0 = hell, 1 = ganz dunkel). */}
            <span className="settings__nightswatch" aria-hidden="true">
              <span className="settings__nightswatchbase" />
              <span className="settings__nightswatchdim" style={{ opacity: draft.dim }} />
            </span>
          </div>
        </div>
      </div>

      <div className="settings__nightsave">
        <button type="button" className="settings__savebtn" disabled={busy} onClick={onSave}>
          {busy ? NIGHT_MODE_TEXTS.saving : NIGHT_MODE_TEXTS.save}
        </button>
        {note && (
          <p className="settings__hint settings__nightnote" role="status">
            {note}
          </p>
        )}
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  Container: lädt die Geräteliste beim Mount (AbortController + aliveRef,
//  Idiom von PrivacySection/SpeakerSection), hält Auswahl + Entwurf + Speichern.
//  API-Funktionen sind injizierbar (Muster von EnrollDialog) — so ist der ganze
//  Flow ohne Live-Backend testbar.
// ─────────────────────────────────────────────────────────────────────────────

export interface NightModeSectionProps {
  fetchDevices?: (signal?: AbortSignal) => Promise<NightModeDevice[]>;
  fetchDevice?: (satelliteId: string, signal?: AbortSignal) => Promise<NightModeDevice>;
  saveDevice?: (satelliteId: string, config: NightModeConfig, signal?: AbortSignal) => Promise<NightModeDevice>;
}

export function NightModeSection({
  fetchDevices = fetchNightModeDevices,
  fetchDevice = fetchNightModeDevice,
  saveDevice = saveNightModeDevice,
}: NightModeSectionProps = {}) {
  const t = useUiStrings();
  const NIGHT_MODE_TEXTS = t.nightMode;
  const [devices, setDevices] = useState<NightModeDevice[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastSeen, setLastSeen] = useState<Record<string, number>>({});
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [draft, setDraft] = useState<NightModeConfig | null>(null);
  const [saveBusy, setSaveBusy] = useState(false);
  const [saveNote, setSaveNote] = useState<string | null>(null);
  const [manualId, setManualId] = useState('');
  const [manualBusy, setManualBusy] = useState(false);
  const [manualNote, setManualNote] = useState<string | null>(null);
  const aliveRef = useRef(true);

  const selectDeviceFrom = (device: NightModeDevice) => {
    setSelectedId(device.satelliteId);
    setDraft(pickDraft(device));
    setSaveNote(null);
  };

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();
    void (async () => {
      try {
        const list = await fetchDevices(controller.signal);
        if (!aliveRef.current) return;
        const sorted = sortDevices(list);
        setDevices(sorted);
        setLastSeen(recordSeenDevices(sorted));
        if (sorted.length > 0) selectDeviceFrom(sorted[0]);
        setError(null);
      } catch {
        if (aliveRef.current) setError(NIGHT_MODE_TEXTS.loadError);
      } finally {
        if (aliveRef.current) setLoading(false);
      }
    })();
    return () => {
      aliveRef.current = false;
      controller.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleManualSubmit = () => {
    const id = manualId.trim();
    if (!id || manualBusy) return;
    setManualBusy(true);
    setManualNote(null);
    void (async () => {
      try {
        const device = await fetchDevice(id);
        if (!aliveRef.current) return;
        setDevices((prev) => sortDevices(upsertDevice(prev ?? [], device)));
        if (device.connected) {
          setLastSeen((prev) => ({ ...prev, [device.satelliteId]: Date.now() }));
        }
        selectDeviceFrom(device);
        setManualId('');
      } catch {
        if (aliveRef.current) setManualNote(NIGHT_MODE_TEXTS.manualNotFound);
      } finally {
        if (aliveRef.current) setManualBusy(false);
      }
    })();
  };

  const updateDraft = (patch: Partial<NightModeConfig>) =>
    setDraft((d) => (d ? { ...d, ...patch } : d));

  const handleSave = () => {
    if (!selectedId || !draft || saveBusy) return;
    setSaveBusy(true);
    setSaveNote(null);
    void (async () => {
      try {
        const updated = await saveDevice(selectedId, draft);
        if (!aliveRef.current) return;
        setDevices((prev) => sortDevices(upsertDevice(prev ?? [], updated)));
        setDraft(pickDraft(updated));
        setSaveNote(NIGHT_MODE_TEXTS.saved);
      } catch (e) {
        if (!aliveRef.current) return;
        if (e instanceof NightModeLockedError) setSaveNote(NIGHT_MODE_TEXTS.locked);
        else if (e instanceof NightModeValidationError) setSaveNote(e.message || NIGHT_MODE_TEXTS.invalid);
        else setSaveNote(NIGHT_MODE_TEXTS.failed);
      } finally {
        if (aliveRef.current) setSaveBusy(false);
      }
    })();
  };

  const rows: NightModeDeviceRow[] = (devices ?? []).map((device) => ({
    device,
    lastSeenLabel: device.connected ? null : formatLastSeen(lastSeen[device.satelliteId]),
  }));

  return (
    <section className="settings__group">
      <h3 className="settings__label">{NIGHT_MODE_TEXTS.groupTitle}</h3>
      <p className="settings__hint">{NIGHT_MODE_TEXTS.intro}</p>

      <NightModeDeviceListView
        rows={rows}
        selectedId={selectedId}
        loading={loading}
        error={error}
        onSelect={selectDeviceFrom}
        manualId={manualId}
        manualBusy={manualBusy}
        manualNote={manualNote}
        onManualId={setManualId}
        onManualSubmit={handleManualSubmit}
      />

      {draft && (
        <NightModeDeviceCard
          draft={draft}
          busy={saveBusy}
          note={saveNote}
          onToggleEnabled={() => updateDraft({ enabled: !draft.enabled })}
          onMode={(mode) => updateDraft({ mode })}
          onFrom={(time) => updateDraft({ from: time })}
          onTo={(time) => updateDraft({ to: time })}
          onDim={(percent) => updateDraft({ dim: clampDim(percent / 100) })}
          onSave={handleSave}
        />
      )}
    </section>
  );
}
