import { describe, it, expect, vi, afterEach } from 'vitest';
import { CATALOGS, SUPPORTED_UI_LANGUAGES, setActiveUiLanguage } from '../i18n';
import { fetchWeatherLocation } from '../api/weatherLocation';
import { fetchNightModeDevices } from '../api/nightMode';
import { fetchSkills } from '../api/skills';
import { fetchCrew } from '../api/crew';

// ─────────────────────────────────────────────────────────────────────────────
//  Andi-Auftrag 2026-07-27 („fünf Sprachen ohne Sternchen", Teil 2): die
//  generischen 401-/HTTP-Status-Fehlerwürfe waren über ~12 `api/*.ts`-Dateien
//  verstreut eine hart deutsche Modul-Konstante (byte-gleich wiederholt), obwohl
//  einige davon WÖRTLICH im UI landen (useSkills, CrewOverlay, RaeumeView) und
//  der Rest zwar heute meist verschluckt wird, aber jederzeit landen KÖNNTE
//  (Katalog-Prinzip, s. api/chat.ts). Alle Stellen lesen jetzt
//  `resolveUiStrings(getActiveUiLanguage()).apiErrors.authWall`/`.httpStatus`.
//
//  Diese Suite prüft (a) die Katalog-Vollständigkeit über alle fünf Sprachen
//  und (b) stellvertretend an drei Dateien (weatherLocation/nightMode/skills),
//  dass die Übersetzung wirklich ankommt — plus crew.ts (Easter-Egg-Overlay).
// ─────────────────────────────────────────────────────────────────────────────

describe('apiErrors — authWall/httpStatus sind in allen fünf Sprachen echt befüllt', () => {
  it('jede Sprache hat ein nicht-leeres authWall mit "401", httpStatus enthält die Zahl', () => {
    expect(SUPPORTED_UI_LANGUAGES.length).toBe(5);
    for (const lang of SUPPORTED_UI_LANGUAGES) {
      const t = CATALOGS[lang].apiErrors;
      expect(t.authWall.trim().length, `${lang}.apiErrors.authWall`).toBeGreaterThan(0);
      expect(t.authWall, `${lang}.apiErrors.authWall`).toContain('401');
      expect(t.httpStatus(503), `${lang}.apiErrors.httpStatus`).toContain('503');
    }
  });

  it('DE bleibt byte-gleich zum bisherigen Wortlaut (ohne den VITE_TOKEN-Hinweis von chat.ts/voice.ts)', () => {
    expect(CATALOGS.de.apiErrors.authWall).toBe('401 — Token fehlt oder ist ungültig (Auth-Wand).');
    expect(CATALOGS.de.apiErrors.httpStatus(500)).toBe('Backend antwortete HTTP 500');
  });
});

describe('api/*.ts — generische Fehlertexte folgen der aktiven UI-Sprache (Stichproben)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setActiveUiLanguage('de');
  });

  it.each(['es', 'fr', 'it'] as const)(
    '%s: weatherLocation.ts — 401 wirft die übersetzte Auth-Wand-Zeile',
    async (lang) => {
      setActiveUiLanguage(lang);
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
      await expect(fetchWeatherLocation()).rejects.toThrow(CATALOGS[lang].apiErrors.authWall);
    },
  );

  it.each(['es', 'fr', 'it'] as const)(
    '%s: nightMode.ts — 500 wirft die übersetzte HTTP-Status-Zeile',
    async (lang) => {
      setActiveUiLanguage(lang);
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }));
      await expect(fetchNightModeDevices()).rejects.toThrow(CATALOGS[lang].apiErrors.httpStatus(500));
    },
  );

  it.each(['es', 'fr', 'it'] as const)(
    '%s: skills.ts (useSkills zeigt e.message direkt) — 401 übersetzt',
    async (lang) => {
      setActiveUiLanguage(lang);
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
      await expect(fetchSkills()).rejects.toThrow(CATALOGS[lang].apiErrors.authWall);
    },
  );

  it.each(['es', 'fr', 'it'] as const)(
    '%s: crew.ts (CrewOverlay zeigt e.message direkt) — HTTP-Fehler übersetzt',
    async (lang) => {
      setActiveUiLanguage(lang);
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }));
      await expect(fetchCrew()).rejects.toThrow(CATALOGS[lang].apiErrors.httpStatus(500));
    },
  );

  it('Deutsch (Gegenprobe + Default): bleibt byte-gleich zum Bestand', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    await expect(fetchWeatherLocation()).rejects.toThrow('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  });
});
