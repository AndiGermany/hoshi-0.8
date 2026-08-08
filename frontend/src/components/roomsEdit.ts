/**
 * Der Edit-Vertrag von Scheibe 2 (SCHREIBEN) — eigene Datei (statt in
 * `views/RaeumeView.tsx` verschachtelt), damit sowohl der Reiter selbst als
 * auch die neuen Scheibe-1-Komponenten (`RoomsInbox`) ohne Zirkel-Import
 * darauf zugreifen können. `RaeumeView.tsx` re-exportiert beide Typen unter
 * denselben Namen weiter — bestehende Importe (`from '../views/RaeumeView'`)
 * bleiben unverändert gültig.
 */

/** Eine Raum-Option im Picker (aus dem Registry-Snapshot abgeleitet). */
export interface AreaOption {
  areaId: string;
  label: string;
}

/**
 * `enabled:false`/`undefined` ⇒ KEIN Picker (Flag zu, byte-neutral). KEIN
 * optimistisches UI: `onAssign` löst PUT + Registry-Reload aus, die Karte
 * wandert erst mit dem frischen Read.
 */
export interface RaeumeEdit {
  enabled: boolean;
  areas: AreaOption[];
  onAssign: (entityId: string, areaId: string) => void;
  /** Die Entity, deren Zuordnung gerade läuft (Picker disabled) — sonst `null`. */
  busyEntityId?: string | null;
  /** Die Entity mit dem letzten Fehler (ehrliche Meldung an der Zeile) — sonst `null`. */
  errorEntityId?: string | null;
  errorMessage?: string | null;
}
