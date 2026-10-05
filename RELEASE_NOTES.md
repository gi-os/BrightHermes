## BrightHermes v0.8: Keyboard leaves after the token is entered

**The keyboard now goes away when you finish entering your token.** On the setup screen, the keyboard stayed up after you entered the token and pressed Done. Pressing Done started the connect attempt, but it never cleared focus from the token field, so the keyboard had no reason to close. Now Done clears focus first and then connects, so the keyboard goes away and you can see whether the connection worked.

Fixes light-reports#629: the keyboard would not go away after entering the token on the setup screen.
