# SPDX-License-Identifier: Apache-2.0
"""Erzeugt kohärente, vollsynthetische Nagori-Szenarien — niemals Goldlabels."""

from __future__ import annotations

import argparse
import random
import secrets
from datetime import datetime, timedelta, timezone
from pathlib import Path

from io_utils import publish_directory_no_replace, remove_private_tree, utc_now, write_new_json, write_new_jsonl
from schema import SCHEMA_VERSION, ensure_private_directory, scenario_indexes, validate_dataset_id, validate_query


DEFAULT_ROOT = Path.home() / ".hoshi" / "memory-bench" / "intake"
GENERATOR_VERSION = 1
QUERY_TYPES = (
    "semantic_paraphrase",
    "exact_entity",
    "temporal_update",
    "conversation_context",
    "metadata_disambiguation",
    "exact_entity",
    "no_answer",
    "cross_speaker",
    "no_answer",
    "metadata_disambiguation",
    "no_answer",
    "no_answer",
)
FAMILY_QUESTION_TEMPLATES = (
    (
        "In {world}: Wie trinke ich morgens inzwischen mein Getränk?",
        "In {world}: Wie heißt meine Zahnarztpraxis?",
        "In {world}: Wo liegt mein Ersatzschlüssel jetzt, nachdem ich ihn umgelegt habe?",
        "In {world}: Was fand ich bei der Reise nach {trip} besonders angenehm?",
        "In {world}: Was habe ich über die Akustik oder Nutzung im {room} gesagt?",
        "In {world}: Welchen Merksatz habe ich für mein Projekt notiert?",
        "In {world}: Wie heißt mein Haustier?",
        "In {world}: Welchen Code hat mein Fahrradschloss?",
        "In {world}: Wie lautet die Telefonnummer von {dentist}?",
        "In {world}: Was habe ich über das Badezimmer notiert?",
        "In {world}: Welche Suppe wollte ich am Wochenende kochen?",
        "In {world}: Welche Serienfolge habe ich gestern gesehen?",
    ),
    (
        "Erinnere dich an {world}: Was kommt neuerdings morgens in meine Tasse?",
        "Erinnere dich an {world}: Bei welcher Zahnarztpraxis bin ich?",
        "Erinnere dich an {world}: An welchen neuen Ort kam der Ersatzschlüssel?",
        "Erinnere dich an {world}: Welcher Ort gefiel mir in {trip} besonders?",
        "Erinnere dich an {world}: Welche Besonderheit nannte ich für den Raum {room}?",
        "Erinnere dich an {world}: Wie war der Projekt-Merksatz?",
        "Erinnere dich an {world}: Welchen Namen trägt mein Haustier?",
        "Erinnere dich an {world}: Was ist die Zahlenfolge meines Fahrradschlosses?",
        "Erinnere dich an {world}: Unter welcher Nummer erreiche ich {dentist}?",
        "Erinnere dich an {world}: Welche Badezimmer-Notiz gab es?",
        "Erinnere dich an {world}: Was wollte ich am Wochenende als Suppe machen?",
        "Erinnere dich an {world}: Was habe ich gestern für eine Folge geschaut?",
    ),
    (
        "Für {world}: Wie mag ich mein morgendliches Getränk jetzt?",
        "Für {world}: Zu welchem Zahnarzt gehe ich laut meiner Notiz?",
        "Für {world}: Wohin habe ich den Ersatzschlüssel zuletzt gelegt?",
        "Für {world}: Was mochte ich am Stadtpark von {trip}?",
        "Für {world}: Was gilt in {room} für Sprache oder Nutzung?",
        "Für {world}: Nenne meinen notierten Projektcode.",
        "Für {world}: Nenne den Namen meines Haustiers.",
        "Für {world}: Nenne meinen eigenen Fahrradschloss-Code.",
        "Für {world}: Nenne die Telefonnummer der Praxis {dentist}.",
        "Für {world}: Welche Information habe ich zum Badezimmer hinterlegt?",
        "Für {world}: Was war mein Suppenplan fürs Wochenende?",
        "Für {world}: Welche Episode einer Serie war gestern dran?",
    ),
    (
        "Was steht in {world} als aktuelle Vorliebe für mein Getränk am Morgen?",
        "Was steht in {world} als Name meiner Zahnarztpraxis?",
        "Was steht in {world} als aktueller Ablageort des Ersatzschlüssels?",
        "Was steht in {world} über meinen Lieblingsort auf der Reise nach {trip}?",
        "Was steht in {world} zur Raum-Situation in {room}?",
        "Was steht in {world} als Merksatz für mein Projekt?",
        "Was steht in {world} über den Namen meines Haustiers?",
        "Was steht in {world} über den Code meines eigenen Fahrradschlosses?",
        "Was steht in {world} über die Rufnummer von {dentist}?",
        "Was steht in {world} über das Badezimmer?",
        "Was steht in {world} über eine Suppe für das Wochenende?",
        "Was steht in {world} über die gestern gesehene Serienfolge?",
    ),
    (
        "Aus meinen Notizen in {world}: Was ist meine neue Morgengetränk-Gewohnheit?",
        "Aus meinen Notizen in {world}: Wie lautet der Praxisname beim Zahnarzt?",
        "Aus meinen Notizen in {world}: Welcher Schlüsselort ist der neueste?",
        "Aus meinen Notizen in {world}: Was war in {trip} mein ruhiger Lieblingsplatz?",
        "Aus meinen Notizen in {world}: Welche Aussage gehört zum {room}?",
        "Aus meinen Notizen in {world}: Welche Kennung gehört zum Projekt?",
        "Aus meinen Notizen in {world}: Gibt es einen Haustiernamen?",
        "Aus meinen Notizen in {world}: Gibt es einen Code für mein Fahrradschloss?",
        "Aus meinen Notizen in {world}: Gibt es eine Telefonnummer für {dentist}?",
        "Aus meinen Notizen in {world}: Gibt es etwas zum Badezimmer?",
        "Aus meinen Notizen in {world}: Gibt es einen Suppenwunsch fürs Wochenende?",
        "Aus meinen Notizen in {world}: Gibt es eine gestern gesehene Serienfolge?",
    ),
    (
        "Kurz für {world}: Mein Getränk morgens — wie jetzt?",
        "Kurz für {world}: Meine Zahnarztpraxis heißt wie?",
        "Kurz für {world}: Ersatzschlüssel aktuell wo?",
        "Kurz für {world}: Was war in {trip} besonders schön?",
        "Kurz für {world}: Welche Notiz gehört zu {room}?",
        "Kurz für {world}: Projekt-Merksatz?",
        "Kurz für {world}: Haustiername?",
        "Kurz für {world}: Mein Fahrradschloss-Code?",
        "Kurz für {world}: Telefonnummer von {dentist}?",
        "Kurz für {world}: Badezimmer-Notiz?",
        "Kurz für {world}: Wochenend-Suppe?",
        "Kurz für {world}: Gestern gesehene Serienfolge?",
    ),
)

OLD_DRINKS = (
    "Filterkaffee ohne Zucker",
    "schwarzen Tee mit Zitrone",
    "Espresso ohne Milch",
    "Kakao mit wenig Zucker",
    "grünen Tee ohne Honig",
    "Cappuccino mit Zimt",
)
NEW_DRINKS = (
    "Filterkaffee mit Hafermilch",
    "schwarzen Tee ohne Zitrone",
    "Espresso mit einem Schuss Milch",
    "Kakao ganz ohne Zucker",
    "grünen Tee mit einem Löffel Honig",
    "Cappuccino ohne Zimt",
)
DENTISTS = (
    "Praxis Morgenstern",
    "Zahnhaus Linden",
    "Praxis Am Park",
    "Zahnteam Nord",
    "Praxis Elfenbein",
    "Zahnärzte Am Markt",
)
TRIP_CITIES = ("Bremen", "Leipzig", "Freiburg", "Lübeck", "Erfurt", "Bonn")
OLD_KEY_PLACES = (
    "in der Flurschublade",
    "im Korb neben der Garderobe",
    "in der oberen Küchenschublade",
    "im kleinen Schrank im Arbeitszimmer",
    "in der Kommode im Schlafzimmer",
    "in der Werkzeugkiste im Keller",
)
NEW_KEY_PLACES = (
    "in der blauen Dose im Abstellraum",
    "am Haken hinter der Wohnungstür",
    "im beschrifteten Glas im Vorratsschrank",
    "in der roten Mappe im Arbeitszimmer",
    "im oberen Fach der Garderobe",
    "in der Metallschachtel im Kellerregal",
)
ROOM_FACTS = (
    ("Arbeitszimmer", "Dort hallt Sprache am wenigsten"),
    ("Wohnzimmer", "Dort läuft am Nachmittag oft der Fernseher"),
    ("Küche", "Dort ist der Satellit neben dem Fenster"),
    ("Flur", "Dort ist das Mikrofon am weitesten entfernt"),
    ("Schlafzimmer", "Dort soll Hoshi abends besonders leise sprechen"),
    ("Esszimmer", "Dort sitzen bei Gesprächen meist mehrere Personen"),
)
PROJECT_CODES = ("Komet-17", "Mohn-42", "Nebel-8", "Kiesel-31", "Farn-26", "Lotus-9")
BIKE_CODES = ("1842", "7305", "4418", "9026", "3157", "6681")


def _iso(base: datetime, *, days: int = 0, hours: int = 0) -> str:
    return (base + timedelta(days=days, hours=hours)).isoformat(timespec="seconds").replace("+00:00", "Z")


def _episode(
    scenario_number: int,
    number: int,
    speaker: str,
    occurred_at: str,
    text: str,
    channel: str,
    room: str,
) -> dict:
    return {
        "episodeId": f"episode-s{scenario_number:02d}-{number:02d}",
        "speakerId": speaker,
        "occurredAt": occurred_at,
        "text": text,
        "channel": channel,
        "room": room,
    }


def _draft_query(
    scenario_number: int,
    number: int,
    scenario_id: str,
    family: str,
    requester: str,
    as_of: str,
    query_type: str,
    text: str,
    context: list[str] | None = None,
) -> dict:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "queryId": f"query-s{scenario_number:02d}-{number:02d}",
        "scenarioId": scenario_id,
        "templateFamily": family,
        "requesterSpeakerId": requester,
        "asOf": as_of,
        "queryType": query_type,
        "text": text,
        "conversationContext": context or [],
        "state": "draft",
        "label": None,
        "reviewedAt": None,
        "revision": 1,
        "privacyFindings": [],
    }


def build_scenario(number: int, family_number: int, rng: random.Random) -> tuple[dict, list[dict]]:
    index = (number - 1) % len(OLD_DRINKS)
    scenario_id = f"scenario-s{number:02d}"
    family = f"family-f{family_number:02d}"
    speaker_a = f"speaker-s{number:02d}-a"
    speaker_b = f"speaker-s{number:02d}-b"
    base = datetime(2026, 1, 1, 8, 0, tzinfo=timezone.utc) + timedelta(days=(number - 1) * 20)
    room, room_fact = ROOM_FACTS[index]
    trip = TRIP_CITIES[index]
    dentist = DENTISTS[index]
    old_drink = OLD_DRINKS[index]
    new_drink = NEW_DRINKS[index]
    old_key = OLD_KEY_PLACES[index]
    new_key = NEW_KEY_PLACES[index]
    project_code = PROJECT_CODES[index]
    bike_code = BIKE_CODES[index]
    reminder_day = 10 + rng.randrange(1, 15)
    world = f"Testwelt S{number:02d}"
    episodes = [
        _episode(number, 1, speaker_a, _iso(base), f"Morgens trinke ich am liebsten {old_drink}.", "browser", "Arbeitszimmer"),
        _episode(number, 2, speaker_b, _iso(base, days=1), f"Mein Fahrradschloss hat den Code {bike_code}.", "satellite", "Flur"),
        _episode(number, 3, speaker_a, _iso(base, days=3), f"Meine Zahnarztpraxis heißt {dentist}.", "browser", "Arbeitszimmer"),
        _episode(number, 4, speaker_a, _iso(base, days=5), f"Der Ersatzschlüssel liegt {old_key}.", "satellite", "Flur"),
        _episode(number, 5, speaker_a, _iso(base, days=7), f"Für das Projekt habe ich den Merksatz {project_code} notiert.", "browser", room),
        _episode(number, 6, speaker_a, _iso(base, days=9), f"Bei der Reise nach {trip} mochte ich besonders den ruhigen Stadtpark.", "browser", "Wohnzimmer"),
        _episode(number, 7, speaker_a, _iso(base, days=12), f"Ab jetzt trinke ich morgens lieber {new_drink}.", "satellite", "Küche"),
        _episode(number, 8, speaker_a, _iso(base, days=14), f"Ich habe den Ersatzschlüssel jetzt {new_key} gelegt.", "browser", "Arbeitszimmer"),
        _episode(number, 9, speaker_b, _iso(base, days=16), f"Mein Ersatzschlüssel liegt weiterhin {old_key}.", "satellite", "Flur"),
        _episode(number, 10, speaker_a, _iso(base, days=18), f"Zum Raum {room} habe ich notiert: {room_fact}.", "satellite", room),
        _episode(number, 11, speaker_a, _iso(base, days=19), f"Am {reminder_day}. des Monats möchte ich die Pflanzen kontrollieren.", "browser", "Wohnzimmer"),
    ]
    scenario = {
        "schemaVersion": SCHEMA_VERSION,
        "scenarioId": scenario_id,
        "templateFamily": family,
        "episodes": episodes,
    }
    as_of = _iso(base, days=19, hours=4)
    questions = [
        template.format(world=world, trip=trip, room=room, dentist=dentist)
        for template in FAMILY_QUESTION_TEMPLATES[family_number - 1]
    ]
    contexts = [
        [],
        [],
        [],
        [f"Wir sprechen gerade über meine Reise nach {trip}."],
        [],
        [],
        [],
        [],
        [],
        [],
        [],
        [],
    ]
    queries = [
        _draft_query(number, position, scenario_id, family, speaker_a, as_of, query_type, text, contexts[position - 1])
        for position, (query_type, text) in enumerate(zip(QUERY_TYPES, questions), 1)
    ]
    return scenario, queries


def generate_dataset(dataset: str, root: Path, scenario_count: int, family_count: int, seed: int) -> Path:
    validate_dataset_id(dataset)
    if scenario_count < 12 or scenario_count > 60:
        raise ValueError("scenario-count muss fuer den echten Vertrag zwischen 12 und 60 liegen")
    if family_count != len(FAMILY_QUESTION_TEMPLATES) or family_count > scenario_count // 2:
        raise ValueError("synthetic-v1 besitzt exakt 6 getrennte Templatefamilien; jede braucht mindestens 2 Szenarien")
    if scenario_count % family_count != 0:
        raise ValueError("scenario-count muss durch family-count teilbar sein")
    ensure_private_directory(root, create=True)
    output = root / dataset
    if output.exists():
        raise ValueError("Dataset existiert bereits; Intake wird nie ueberschrieben")
    temporary = root / f".{dataset}.tmp-{secrets.token_hex(8)}"
    temporary.mkdir(mode=0o700)
    try:
        rng = random.Random(seed)
        scenarios: list[dict] = []
        queries: list[dict] = []
        for number in range(1, scenario_count + 1):
            family_number = ((number - 1) % family_count) + 1
            scenario, drafts = build_scenario(number, family_number, rng)
            scenarios.append(scenario)
            queries.extend(drafts)
        scenario_map, episode_map = scenario_indexes(scenarios)
        for index, query in enumerate(queries, 1):
            validate_query(query, scenario_map, episode_map, f"queries:{index}")
        generated_at = utc_now()
        write_new_jsonl(temporary / "scenarios.jsonl", scenarios)
        write_new_jsonl(temporary / "queries.jsonl", queries)
        write_new_jsonl(
            temporary / "audit.jsonl",
            [{
                "event": "synthetic-generated",
                "at": generated_at,
                "generatorVersion": GENERATOR_VERSION,
                "seed": seed,
                "scenarioCount": scenario_count,
                "queryCount": len(queries),
                "labelsGenerated": False,
            }],
        )
        write_new_json(
            temporary / "intake.json",
            {
                "schemaVersion": SCHEMA_VERSION,
                "datasetId": dataset,
                "createdAt": generated_at,
                "generator": {
                    "name": "nagori-synthetic-v1",
                    "version": GENERATOR_VERSION,
                    "seed": seed,
                    "labelsGenerated": False,
                },
                "privacy": {
                    "syntheticOnly": True,
                    "audioPersisted": False,
                    "userDataRead": False,
                },
            },
        )
        publish_directory_no_replace(temporary, output)
    finally:
        remove_private_tree(temporary)
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset")
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--scenario-count", type=int, default=12)
    parser.add_argument("--family-count", type=int, default=6)
    parser.add_argument("--seed", type=int, default=20260811)
    parser.add_argument("--yes", action="store_true", help="synthetischen Intake wirklich anlegen")
    args = parser.parse_args()
    if not args.yes:
        parser.error("Erzeugung braucht --yes; es entstehen keine Labels oder Nutzerdaten")
    try:
        output = generate_dataset(args.dataset, args.root, args.scenario_count, args.family_count, args.seed)
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    print(f"[memory-bench] synthetischer Intake: {output}")
    print("[memory-bench] Labels erzeugt: NEIN — jetzt jede Query menschlich labeln und reviewen")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
