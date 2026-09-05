<img src="app/src/main/res/drawable/ic_launcher.png" alt="" width="72" align="left" />

# BrightHermes

A glanceable agent for the **Light Phone III**. Info Deck on top, June below, and a quiet pipe
feeding her everything the other Bright apps know. The Light Phone philosophy applied to an
agent: tool, not toy; glance, don't glow.

Package `com.gios.brighthermes`. Plain APK, `light-common`, no Google anything.

## What is on the screen

```
 63°          7:30p        3·1            ← the deck as a strip (tap: grid · long-press: edit)
 rain 7pm     Dinner       June
 ──────────────────────────────────
                          Kitchen to 100%  ← you, right, medium
 Done. Living room still at 40%.           ← June, left, full width
 ● homeassistant                           ← what she is doing, never a spinner
 Summarize my day                       ↵  ← chips from the server
 Say something                             ← LightTextField, 3dp underline, 80% width
 ───
 Hold here, or the camera button, to talk
```

A wheel click cycles the deck: strip → grid → line. In the grid the clock gets the big face and
June's digest the full-width row. Everything is `#FFF` and `#BBB` on `#000` on a 15dp grid; the
design is `docs/design/BrightHermes.dc.html` (direction **1B, Transcript**).

## How it is wired

| Layer | Where | What |
| --- | --- | --- |
| App | LPIII — Compose, `light-common` | Deck renderer, chat client, push-to-talk, deck provider |
| Gateway | BasilNet — `brighthermes-gw`, FastAPI, `:8650`, `hermes.basilnet.com` via the Cloudflare tunnel | Deck spec + tile payloads, WebSocket chat relay, journal |
| June | BasilNet — Hermes Agent API server `:8642` | The agent: sessions, memory, tools, cron |

The app only ever talks to the gateway: `GET /deck`, `PUT /deck/layout`, `GET /thread`,
`POST /ingest`, and `WS /ws` for chat. One bearer token, one `X-Device` header. The protocol is
documented at the top of the gateway's `app.py` and mirrored in `chat/Protocol.kt`.

**Network only while in front.** `onStart` opens the socket and refreshes the deck; `onStop`
closes everything. A screen-on while the app is in front refreshes the deck again. Nothing polls
while the panel is dark — the lesson every Bright app that touched the lock face had to learn.

## The deck

A tile is `label + value + sub + action`. That is the whole shape; anything that needs more is a
screen, and the `action` deep link (`brighthermes://tile/weather`) opens one. The layout is an
ordered list of `{id, span}` on two columns — singles pair up on a row, a double takes the row.

Remote tiles (weather, next, home, digest) are filled by the gateway. Local tiles (clock, and
later now-playing, transit, LightPods) are filled here in `deck/LocalTiles.kt` and never wait on
the network. The digest is the tile no other app can draw; a Hermes cron job fills it with

```
curl -X POST -H "Authorization: Bearer $T" https://hermes.basilnet.com/tiles/digest \
     -d '{"value":"3 done","sub":"1 waiting on you"}'
```

**For BrightControl.** `content://com.gios.brighthermes.deck/tiles` answers one row per slot —
`id, span, label, value, sub, action, updatedAt, staleAt` — from the last deck fetched, with a
`notifyChange` on every fetch. Lock face = deck at rest; app = deck + conversation. One deck,
two canvases, zero drift.

## Bots beside June

June is the default. The gateway can list other **Hermes agents** as bots (`BOTS` in its `.env`:
id, name, url, key) — a second profile on June's own gateway, or a Hermes running on another
box. Each is a whole agent, so the phone gets the same sessions, tool markers and server-kept
transcript from every one of them. The roster comes with the socket's `ok` frame; each user
frame names its bot; the transcript on screen is one bot's at a time. With more than one
configured, the listening bot's name sits at the right of the input row — tap it to talk to the
next.

## Push-to-talk

Hold the camera button's **first stage** to talk. Press it **all the way in** to send. Let go
without pressing and nothing is sent. The panel inverts to white while you hold it.

Transcription is on the phone: NVIDIA Parakeet TDT 110M (int8) through sherpa-onnx — the same
model and code BrightThumb ships for voice typing, ported in `voice/Listener.kt`. A transducer
decodes only the audio it was given, so a short command is text in a few hundred milliseconds
once the model is warm (it is warmed on open). Gradle fetches the model at build time; nothing
is downloaded on the phone and no audio leaves it.

The camera button arrives as ordinary key events — LightOS dispatches `FOCUS` and `CAMERA` to
the focused window, and `light-common`'s `LightKeys` names them — so there is no service and
no special permission behind this, only `RECORD_AUDIO`.

## Building

CI builds, signs and publishes every push to `main`; `check.yml` compiles every other branch
without publishing. Locally:

```sh
./gradlew :app:assembleDebug
```

`light-common` resolves from GitHub Packages, which needs `gpr.user` / `gpr.key` in
`local.properties` (a PAT with `read:packages`). Secrets the workflows expect: `KEYSTORE_B64`,
`KEYSTORE_PASSWORD`, `GPR_USER`, `GPR_TOKEN`, `REPORT_TOKEN`, `INDEX_DISPATCH_TOKEN`.

The signing certificate is pinned in `signing-fingerprint.txt`; a build whose certificate
drifts is refused before it is published.

## Not yet

- Now-playing, transit and LightPods tiles (readers in `LocalTiles.kt` when their apps expose one).
- The journal writer in `light-common` (`Journal.log()` → this app's provider → `/ingest`). The
  gateway side is done.
- Agent-initiated messages arriving while the app is closed (needs a push path; screen-on
  refresh covers the deck).
- Spoken replies.

## License

MIT.
