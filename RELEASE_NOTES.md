## BrightHermes v0.7 — Markdown, pictures, cards, and READ

**Replies render as Markdown.** LightChat's parser, carried over: bold, italic, code, fenced code, lists, quotes, rules, links. Drawn to the deck's rules — no radii, no tints, one grey.

**June can send pictures.** `![what it is](url)` in a reply is an image — from the web, inline as a `data:` URI, or uploaded to the gateway's new `POST /images` and referenced as `/images/<id>.png` (the phone adds the token). Drawn grey, like everything else on this panel.

**June can send cards.** A fenced block whose info string is `html` (or `html 6` for six grid units tall) renders as a live black-and-white page in the reply, JavaScript on, with `brighthermes.fetch()` for calling the gateway back — the same surface as the deck widgets, inline in the conversation.

**She knows.** Every phone turn now carries a note of what the phone renders and what she can do to it — images, widgets, the digest tile, the lock card, the deck, the journal — with the gateway's address and token as seen from her container. Ask for a chart and she has somewhere to put it.

**READ.** Under your latest message, the moment the gateway has it and June has started — the read receipt, so you know it landed before the first word comes back.
