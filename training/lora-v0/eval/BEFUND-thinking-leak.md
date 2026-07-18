# Befund: Thinking-Leak im Blind-A/B (Lars, 2026-07-02)

Im Lauf ab-20260702-0133.jsonl leakten ALLE 40 Basis-Antworten gemma-Thinking-Artefakte (`<|channel>thought` / "Thinking Process"), der Adapter 0/40 — die LoRA hat die saubere Antwort-Form mittrainiert, das ist ein echter Adapter-Effekt.
Aber: das prod-Brain (server_e4b.py) unterdrückt Thinking ohnehin via `enable_thinking=False` in apply_chat_template; das mlx_lm-CLI in generate-ab.sh tat das nicht — der Vergleich war dadurch unfair zur Basis.
Fix: generate-ab.sh gibt jetzt `--chat-template-config '{"enable_thinking": false}'` an BEIDE Seiten (identische Bedingungen, prod-gleicher Mechanismus); der faire Ton-Vergleich läuft thinking-frei neu.
