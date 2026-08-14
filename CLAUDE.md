# Time Served plugin — context for continuing this work

RuneLite plugin for the Time Served OSRS clan. Auto-submits bingo tile proofs
(screenshot on a matching drop), reports team-combined skill XP / boss KC
live, and has `!verify`/`!needed`/`!live` chat commands. Talks to the clan
site at `https://timeserved.vercel.app` (companion repo: `osrsclan`, same
parent folder) via `BingoApiClient`, authenticated with a plugin key pasted
into config (`BingoConfig.apiKey()`).

## Current branch: `improvement/bingo-tracking`

This branch is not merged to `main` yet — it's out for review as
[PR #1](https://github.com/vandevkieboom/osrsclanplugin/pull/1). It was
built/reviewed entirely without a JDK available (no compiler access in that
environment), so **nothing in it has been compiled or run yet**. That's the
main thing left to do.

## What changed on this branch, and why

1. **KC-push debouncing** (`BingoPlugin`) — a kill streak on a tracked boss
   used to fire one report + one board refresh per kill. Now buffers the
   latest count and flushes after 15s of quiet (`pendingKcPush`,
   `KC_PUSH_COALESCE_MILLIS`), guarded by `kcPushLock` — a naive
   copy-then-clear drain had a real race that could silently drop an update
   landing mid-drain; the lock makes put/drain mutually exclusive.

2. **Disk-persisted retry queue** (`PendingSubmissionStore`, new file) —
   failed drop proofs and failed kc/xp reports used to live in an
   in-memory-only queue capped at 20, wiped on every restart. Now both
   persist to `<runelite dir>/timeserved-bingo-pending/` (JSON + PNG for
   proofs, JSON only for goal readings) and get reloaded on `startUp()`. Cap
   raised to 100 since disk makes holding more of them safe.

3. **Verification code overlay** (`BingoVerificationOverlay`, new file) —
   renders a manually-typed config value (`BingoConfig.verificationCode()`)
   plus a live UTC timestamp in the top-left corner, baked into every proof
   screenshot automatically (it's a normal overlay, so
   `drawManager.requestNextFrameListener` just picks it up as part of the
   frame). **Important**: this went through two versions. The first fetched
   a server-generated code from `GET /api/board` — that endpoint requires no
   auth at all, so anyone could've read the code, defeating the point. Fixed
   to be a plain manually-entered setting instead (an admin picks a code,
   announces it via Discord, each member pastes it in) — never touches the
   site. If you ever see anything reintroducing a server round-trip for this
   value, that's a regression of an already-identified security issue.

## What to actually test on this desktop

- [ ] `./gradlew compileJava` — first time this has compiled at all.
- [ ] `./gradlew runClient`, log in, set a plugin key.
- [ ] Get a bingo drop with the site/network reachable — confirm normal
      submit still works.
- [ ] Force a failure (wrong site URL, or disconnect), get a drop, confirm
      it queues; restart the client; confirm it retries and submits once
      reachable again (this is the main new behavior — didn't exist before).
- [ ] Same for a kc-tile kill while unreachable.
- [ ] Rapid-kill a tracked boss (or fake it) and confirm only one report
      goes out after ~15s quiet, not one per kill.
- [ ] Set a verification code in config, confirm it + a timestamp render
      top-left, and confirm a submitted proof screenshot actually has it
      baked in.

## Related: the `osrsclan` site repo

The same session also changed `osrsclan` (already merged to its `main`,
already pushed): `goal_progress` now self-corrects against WOM hiscores
(throttled, runs from `GET /api/board`) instead of trusting the plugin's
live push as the only source, and seeds a starting baseline for team
members who've never opened the plugin (mobile-only players). See that
repo's own `CLAUDE.md` for what's still an open decision there — a one-time
~318-row backfill hasn't been triggered on the live board yet, pending a
call on timing/fairness.
