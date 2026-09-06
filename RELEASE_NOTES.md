## BrightHermes v0.6 — no more doubles

**Messages showed twice; sometimes two lights blinked.** Two causes, both fixed.

The doubles: on every open the transcript is re-fetched from the server and merged with what the phone already had, and the merge kept any local message "newer than the last stored one" — judged by the phone's clock against the server's. The two clocks disagree by enough that a message the server already had came back beside its own copy. The merge now matches on words, not on time: history is the truth, and the only live rows that survive are a reply still streaming or a message the server has not written down. Two identical consecutive rows from the server collapse to one.

The two lights: a reply that was streaming when the socket dropped — locking the phone, switching apps, a reconnect — stayed marked pending for ever, because the turn died on the server with the old connection and its `done` never came. The next question added a second light beside it. Now a fresh connection settles every pending reply (an empty one goes, one with words stays as what arrived) and re-fetches the transcript, and a new `start` for a bot drops any stale blank pending of that bot's.

Gateway: replies are asked for at `reasoning_effort: low` (June's default is medium), and every turn's time-to-first-word and total are in `docker logs brighthermes`.
