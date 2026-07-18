import { useCallback, useEffect, useRef, useState } from 'react';
import { SkillLockedError, fetchSkills, setSkill } from '../api/skills';
import type { Skill } from '../api/types';

/**
 * Server-State-Hook über die Skills (S2.3). Quelle der Wahrheit ist der Server
 * (Satellit + Browser + ct-106 müssen übereinstimmen) — KEIN localStorage.
 *
 * Idiom gespiegelt von {@link useOpsStatus}: Fetch-on-mount mit AbortController +
 * `aliveRef`-Cleanup. Kein Polling (die anderen Server-Status-Hooks pollen nur den
 * volatilen Ops-Status; Skills ändern sich nur durch Andis Toggle, darum reicht
 * Fetch-on-mount + Re-merge der autoritativen PUT-Antwort).
 *
 * `toggle(id)`:
 *  - gesperrte (`locked`) Zeilen werden gar nicht erst gesendet,
 *  - sonst PUT, dann den AUTORITATIVEN Server-Zustand mergen (nicht optimistisch raten),
 *  - 409 ⇒ NICHT umklappen, sondern die Zeile ehrlich als gesperrt markieren.
 */
export interface UseSkillsResult {
  skills: Skill[];
  loading: boolean;
  error: string | null;
  /** id des Skills, dessen PUT gerade läuft (Toggle disabled während des Flugs). */
  busyId: string | null;
  toggle: (id: string) => void;
}

export function useSkills(): UseSkillsResult {
  const [skills, setSkills] = useState<Skill[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const aliveRef = useRef(true);
  // Aktuelle Liste ohne Re-Render-Abhängigkeit lesen (toggle bleibt stabil).
  const skillsRef = useRef<Skill[]>(skills);
  skillsRef.current = skills;

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();

    void (async () => {
      try {
        const next = await fetchSkills(controller.signal);
        if (aliveRef.current) {
          setSkills(next);
          setError(null);
        }
      } catch (e) {
        if (aliveRef.current) {
          setError(e instanceof Error ? e.message : 'Skills laden fehlgeschlagen.');
        }
      } finally {
        if (aliveRef.current) setLoading(false);
      }
    })();

    return () => {
      aliveRef.current = false;
      controller.abort();
    };
  }, []);

  const toggle = useCallback((id: string) => {
    const skill = skillsRef.current.find((s) => s.id === id);
    // gesperrt oder unbekannt ⇒ kein Write (die Decke ist zu, der Toggle greift nicht).
    if (!skill || skill.locked) return;

    setBusyId(id);
    setError(null);
    void (async () => {
      try {
        const updated = await setSkill(id, !skill.enabled);
        if (aliveRef.current) {
          setSkills((cur) => cur.map((s) => (s.id === id ? updated : s)));
        }
      } catch (e) {
        if (!aliveRef.current) return;
        if (e instanceof SkillLockedError) {
          // 409: die Decke ging beim Deploy zu — NICHT umklappen, ehrlich sperren.
          setSkills((cur) =>
            cur.map((s) =>
              s.id === id ? { ...s, locked: true, ceilingOpen: false, effective: false } : s,
            ),
          );
        } else {
          setError(e instanceof Error ? e.message : 'Umschalten fehlgeschlagen.');
        }
      } finally {
        if (aliveRef.current) setBusyId(null);
      }
    })();
  }, []);

  return { skills, loading, error, busyId, toggle };
}
