## BrightHermes v0.1 — the deck, and June under it

**First build.** A glanceable agent for the Light Phone III: an Info Deck on top, a conversation with June below, and a quiet pipe carrying what the other Bright apps know back to her.

- **The deck.** Weather, what's next on the calendar, the home, and June's own digest, as `label · value · sub` tiles on a two-column grid. Three states — a strip, the full grid, a single line — and a wheel click walks between them. Long-press the strip to rearrange; the arrangement is kept on the server per phone, so a reinstall gets it back.
- **The conversation.** Direction 1B from the design brief: no sender labels, June left and full width, you right and set in medium. Replies stream in; a two-frame blink stands in for a spinner and names the tool June is using. The transcript is June's own session on the server — the phone keeps nothing.
- **Push-to-talk.** Hold the camera button's first stage to talk, press it all the way to send, let go without pressing to throw it away. Transcribed on the phone with Parakeet through sherpa-onnx — BrightThumb's voice code — so a two-second command is text in a few hundred milliseconds and works with no signal.
- **Bots beside June.** Any OpenAI-compatible endpoint the gateway lists is one tap away at the right of the input row; same frames, own transcript.
- **For the lock face.** `content://com.gios.brighthermes.deck/tiles` hands BrightControl the same snapshot the app draws.
- **Setup** is a server and a token. `hermes.basilnet.com` is prefilled; anyone running the gateway points it at theirs.

Server side: the `brighthermes-gw` gateway on BasilNet, in front of a Hermes Agent's API server.
