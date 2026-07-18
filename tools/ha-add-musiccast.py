#!/usr/bin/env python3
"""MusicCast-Integration (Yamaha RX-V6A, 192.168.178.31) in HA anlegen.

Andi-Werkzeug (Option 2, 2026-07-03): der Agent darf HA-Integrationen im
Auto-Mode nicht anlegen (Guardrail) — dieses Skript macht es auf Andis Zuruf.

Lauf:  python3 tools/ha-add-musiccast.py
(braucht ein venv/Interpreter mit dem Paket `websockets` installiert, z.B.:
 python3 -m venv .venv && .venv/bin/pip install websockets && .venv/bin/python3 tools/ha-add-musiccast.py
 Token kommt aus ~/.hoshi/secrets.json["ha"], wird nie geloggt)

Ablauf: WS flow/init (handler yamaha_musiccast) -> falls host gefragt: 192.168.178.31
-> confirm -> danach media_player.*-Entity ausgeben (= HOSHI_RADIO_TARGET).
"""
import asyncio, json, os, sys

YAMAHA_IP = "192.168.178.31"
SECRETS_PATH = os.path.expanduser("~/.hoshi/secrets.json")

async def main():
    import websockets
    tok = json.load(open(SECRETS_PATH))["ha"]
    async with websockets.connect("ws://192.168.178.56:8123/api/websocket") as ws:
        hello = json.loads(await ws.recv())
        print("HA:", hello.get("ha_version"))
        await ws.send(json.dumps({"type": "auth", "access_token": tok}))
        assert json.loads(await ws.recv())["type"] == "auth_ok"
        mid = 0

        async def call(msg):
            nonlocal mid
            mid += 1
            msg["id"] = mid
            await ws.send(json.dumps(msg))
            while True:
                r = json.loads(await ws.recv())
                if r.get("id") == mid:
                    return r

        r = await call({"type": "config_entries/flow/init", "handler": "yamaha_musiccast"})
        if not r.get("success"):
            print("flow/init via WS nicht verfügbar:", r.get("error"), "— versuche REST…")
            import urllib.request
            hdr = {"Authorization": "Bearer " + tok, "Content-Type": "application/json"}
            req = urllib.request.Request(
                "http://192.168.178.56:8123/api/config/config_entries/flow",
                data=json.dumps({"handler": "yamaha_musiccast"}).encode(), headers=hdr)
            flow = json.load(urllib.request.urlopen(req, timeout=20))
        else:
            flow = r["result"]
        print("Step:", flow.get("step_id"), "| Schema:", json.dumps(flow.get("data_schema"))[:200])

        # host-Step ausfüllen, bis der Flow fertig ist (max 3 Schritte)
        import urllib.request
        hdr = {"Authorization": "Bearer " + tok, "Content-Type": "application/json"}
        for _ in range(3):
            if flow.get("type") == "create_entry":
                print("✓ Integration angelegt:", flow.get("title"))
                break
            data = {}
            names = [s.get("name") for s in (flow.get("data_schema") or [])]
            if "host" in names:
                data["host"] = YAMAHA_IP
            req = urllib.request.Request(
                f"http://192.168.178.56:8123/api/config/config_entries/flow/{flow['flow_id']}",
                data=json.dumps(data).encode(), headers=hdr)
            flow = json.load(urllib.request.urlopen(req, timeout=30))
            print("→", flow.get("type"), flow.get("step_id") or flow.get("title"))

        # Entity finden
        await asyncio.sleep(4)
        req = urllib.request.Request("http://192.168.178.56:8123/api/states",
                                     headers={"Authorization": "Bearer " + tok})
        states = json.load(urllib.request.urlopen(req, timeout=10))
        for s in states:
            if s["entity_id"].startswith("media_player.") and "lg_" not in s["entity_id"]:
                print("ZIEL-ENTITY:", s["entity_id"], "| state:", s["state"])

asyncio.run(main())
