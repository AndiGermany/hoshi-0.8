import type { ReactNode, SVGProps } from 'react';

/**
 * **Domain-Glyphs** — muted Inline-SVGs je HA-Entity-Domain (Räume-Reiter,
 * Scheibe 1 des Geräte-Zuordnungs-Konzepts). Selbes Muster wie `icons.tsx`
 * (24er-Viewbox, stroke `currentColor`, `.glyph`-Größenklasse, `aria-hidden` —
 * der Domain-Text daneben trägt die Semantik, nie das Icon allein).
 *
 * Bewusst eine EIGENE, kleine Datei statt Anbau an `icons.tsx`: dies sind
 * DOMAIN-Symbole (fachlich an HA gebunden), keine allgemeinen UI-Controls.
 * Nur die häufigsten Domains bekommen ein eigenes Glyph; alles andere (jede
 * künftige Entity-Art — HA kennt Dutzende Domains) fällt ehrlich auf
 * [DeviceGlyph] zurück, statt für jede Nische ein neues Icon zu pflegen.
 */

interface GlyphProps {
  className?: string;
}

function Svg({
  name,
  className,
  children,
  ...rest
}: GlyphProps & { name: string; children: ReactNode } & SVGProps<SVGSVGElement>) {
  return (
    <svg
      className={`${className ?? 'glyph'} glyph--domain-${name}`}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...rest}
    >
      {children}
    </svg>
  );
}

/** `light` — Glühbirne. */
function LightDomainGlyph({ className }: GlyphProps) {
  return (
    <Svg name="light" className={className}>
      <circle cx="12" cy="10" r="6.5" />
      <path d="M9.5 19h5" />
      <path d="M10 21.5h4" />
      <path d="M12 3.5v0" />
    </Svg>
  );
}

/** `switch` — Kippschalter. */
function SwitchDomainGlyph({ className }: GlyphProps) {
  return (
    <Svg name="switch" className={className}>
      <rect x="4" y="8" width="16" height="8" rx="4" />
      <circle cx="15" cy="12" r="2.6" fill="currentColor" stroke="none" />
    </Svg>
  );
}

/** `climate` — Thermometer. */
function ClimateDomainGlyph({ className }: GlyphProps) {
  return (
    <Svg name="climate" className={className}>
      <path d="M12 14.5V5a2 2 0 0 0-4 0v9.5a4 4 0 1 0 4 0z" />
      <line x1="8" y1="8" x2="10" y2="8" />
    </Svg>
  );
}

/** `cover` — Rollo/Jalousie. */
function CoverDomainGlyph({ className }: GlyphProps) {
  return (
    <Svg name="cover" className={className}>
      <rect x="4" y="4" width="16" height="16" rx="1.5" />
      <line x1="4" y1="9" x2="20" y2="9" />
      <line x1="4" y1="13.5" x2="20" y2="13.5" />
      <line x1="9" y1="13.5" x2="9" y2="18.5" />
    </Svg>
  );
}

/** `media_player` — Bildschirm mit Play-Dreieck. */
function MediaDomainGlyph({ className }: GlyphProps) {
  return (
    <Svg name="media" className={className}>
      <rect x="3" y="5" width="18" height="12" rx="1.5" />
      <path d="M10.5 8.5v6l5-3z" fill="currentColor" stroke="none" />
      <line x1="9" y1="20.5" x2="15" y2="20.5" />
    </Svg>
  );
}

/** `fan` — drei Flügel. */
function FanDomainGlyph({ className }: GlyphProps) {
  return (
    <Svg name="fan" className={className}>
      <circle cx="12" cy="12" r="1.6" fill="currentColor" stroke="none" />
      <path d="M12 12c0-3.5 2-6.5 5-6.5 2 0 3 1.4 3 2.8 0 2.6-3.3 3.7-8 3.7z" />
      <path d="M12 12c-3.2 1.6-4.9 4.6-3.6 6.9 1 1.8 2.9 2 4 1 1.9-1.7 1.1-4.8-.4-7.9z" />
      <path d="M12 12c-3.4-1.3-6.5-.4-7.2 1.9-.6 2 .5 3.5 2 3.8 2.4.5 4.2-2 5.2-5.7z" />
    </Svg>
  );
}

/** `lock` — Vorhängeschloss (Muster identisch zu `icons.tsx#LockGlyph`, eigene Kopie
 * für die Domain-Reihe, damit diese Datei ohne Cross-Import auskommt). */
function LockDomainGlyph({ className }: GlyphProps) {
  return (
    <Svg name="lock" className={className}>
      <rect x="5" y="11" width="14" height="9.5" rx="2" />
      <path d="M8.5 11V7.5a3.5 3.5 0 0 1 7 0V11" />
    </Svg>
  );
}

/** `sensor`/`binary_sensor` — Funk-/Messwellen. */
function SensorDomainGlyph({ className }: GlyphProps) {
  return (
    <Svg name="sensor" className={className}>
      <circle cx="12" cy="12" r="2" fill="currentColor" stroke="none" />
      <path d="M8.5 8.5a5 5 0 0 0 0 7" />
      <path d="M15.5 8.5a5 5 0 0 1 0 7" />
      <path d="M5.5 5.5a9.5 9.5 0 0 0 0 13" />
      <path d="M18.5 5.5a9.5 9.5 0 0 1 0 13" />
    </Svg>
  );
}

/** Generischer Fallback — jede Domain ohne eigenes Glyph (ehrlich „ein Gerät", kein Rätsel-Icon). */
export function DeviceGlyph({ className }: GlyphProps) {
  return (
    <Svg name="device" className={className}>
      <rect x="5" y="5" width="14" height="14" rx="3" />
      <circle cx="12" cy="12" r="2.5" />
    </Svg>
  );
}

/** Domain (HA-`entity_id`-Präfix) → das passende Glyph; unbekannt ⇒ [DeviceGlyph]. */
const DOMAIN_GLYPHS: Record<string, (p: GlyphProps) => ReactNode> = {
  light: LightDomainGlyph,
  switch: SwitchDomainGlyph,
  climate: ClimateDomainGlyph,
  cover: CoverDomainGlyph,
  media_player: MediaDomainGlyph,
  fan: FanDomainGlyph,
  lock: LockDomainGlyph,
  sensor: SensorDomainGlyph,
  binary_sensor: SensorDomainGlyph,
};

/** Rendert das Domain-Glyph für `domain`, mit [DeviceGlyph]-Fallback für jede unbekannte Domain. */
export function DomainGlyph({ domain, className }: GlyphProps & { domain: string }) {
  const Glyph = DOMAIN_GLYPHS[domain] ?? DeviceGlyph;
  return <Glyph className={className} />;
}
