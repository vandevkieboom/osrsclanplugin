# Time Served plugin — context for continuing this work

RuneLite plugin for the Time Served OSRS clan. Auto-submits bingo tile proofs
(screenshot on a matching drop), reports team-combined skill XP / boss KC
live, and has `!verify`/`!needed`/`!live` chat commands. Talks to the clan
site at `https://timeserved.vercel.app` (companion repo: `osrsclan`, same
parent folder) via `BingoApiClient`, authenticated with a plugin key pasted
into config (`BingoConfig.apiKey()`).

## Reference plugin: Anvil

A more mature, unrelated clan's plugin (`github.com/AhmedFathy2001/anvil-plugin`
— cloned as a sibling folder `../anvil-plugin` during this session for
comparison; re-clone it there if it's not present on this machine) was used
as a comparison point for "how do other clan-bingo plugins handle X" — it's a
generalized, multi-tenant "clan-operations platform" plugin (~26k lines,
white-label, any clan can point it at their own backend), a much bigger
scope than this plugin is trying to be. It is **not** a dependency and
can't be pointed at our site — its wire protocol (~25 `/api/plugin/*`
endpoints, a completely different data model built around "events" with
points/tiers/reveal-modes) is specific to its own backend, which we don't
run and have no access to.

Ideas actually adapted from it into this plugin (same principle, rewritten
to fit our own schema/auth, not copied code):
- KC/XP push debouncing (coalesce a kill streak into one request).
- Treating hiscores as a correcting backstop rather than the only source of
  truth for XP/KC (see `osrsclan`'s `reconcileGoalProgress`).
- Disk-persisted retry queue for failed submissions (`PendingSubmissionStore`).
- The idea of a verification code/timestamp baked into proof screenshots
  (though the *mechanism* ended up different from Anvil's — see below).

**Explicitly declined**, so don't re-suggest these without a fresh
conversation about scope — they were discussed and deliberately ruled out,
not overlooked: Combat Achievement / diary / timed-clear / item-gain /
loot-value tile types, weekly SotW/BotW competitions, multi-clan
federation, drop-luck statistics, OBS replay clips, a points/tiers/reveal-
mode scoring system, and Anvil's device-code Discord sign-in flow (the
existing pasted-plugin-key model is intentionally simpler and considered
sufficient for this clan's size).

## Scope / design philosophy

This is deliberately a **small, single-clan tool**, not a platform —
hardcoded to `timeserved.vercel.app`, no multi-tenant config, no ambition
to become Anvil. Bingo tiles are staying to exactly three kinds on
purpose: **item drops, team-combined boss KC, team-combined skill XP**.
That's a considered decision (see the Anvil section above), not a
temporary starting point — don't propose expanding tile types as a
"quick win" without checking first.

## Current branch: `improvement/bingo-tracking`

This branch is not merged to `main` yet — it's out for review as
[PR #1](https://github.com/vandevkieboom/osrsclanplugin/pull/1). Most of it
was originally built/reviewed without a JDK available (no compiler access in
that environment). **It has since actually been compiled and run** on a
desktop with a working JDK, and real bugs were found and fixed as a result
— see "Bugs found from real client testing" below. Keep testing incrementally
as more gets added; don't assume something's correct just because it was
carefully reasoned through without a compiler.

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

## Bugs found from real client testing (all fixed)

1. **Verification code was visible on-screen the whole session**, not just
   baked into screenshots — the overlay had no gating, so it always
   rendered. Fixed: `BingoVerificationOverlay` only draws while
   `setCaptureMode(true)`, which `BingoPlugin#captureAndSubmit` toggles on
   for exactly the one frame `drawManager` captures, then back off.
2. **XP tiles came back "completed" right after a board reset** — traced to
   a real race: `reportXpProgress` only checked `GameState.LOGGED_IN`,
   which can flip true a moment before skill data is actually populated
   (most likely right after a client restart). A `0` read in that gap
   becomes a **permanent** baseline (`recordGoalProgress` never touches
   `baseline_value` again), silently crediting a player's entire lifetime
   XP as "progress." Fixed with a stronger guard (also require
   `getLocalPlayer()` to exist) plus skipping the report outright if the
   read is `0`. Two already-poisoned rows were manually deleted from the
   live DB so they'd re-seed cleanly.
3. **Team Goals section visibly jumped/overlapped on every board refresh**
   — `BingoPanel.refresh()` does a full teardown-and-rebuild every time,
   and tile icons load asynchronously, so the board section's real height
   wasn't known until an icon callback fired later, letting Swing paint an
   intermediate half-built layout. Fixed by hiding the whole panel
   (`content.setVisible(false)`) for the duration of the rebuild, only
   revealing it once layout and scroll position are both settled.
4. **Rapid-killing a fast monster only ever submitted the first proof** —
   `recentlyAttempted`'s dedup window was 30 seconds, keyed only on tile
   id (meant to collapse `NpcLootReceived`/`LootReceived` firing twice for
   ONE kill). Real bug: any second genuine kill of the same monster within
   30 seconds got silently swallowed too, with no way to tell "duplicate
   event" from "actually a new kill." Shrunk to 1.2s (2 game ticks) —
   still covers the real duplicate-event case, no longer eats real kills.
5. **The submission banner didn't look like the collection-log style it
   was supposed to** — the first version used RuneLite's generic
   `PanelComponent` box. Replaced with a direct port of Anvil's
   `BingoClogBannerOverlay`, including its actual background asset
   (`clog_banner.png`, copied as-is — see `THIRD_PARTY_NOTICES.md`) and
   its open/hold/close animation, not an approximation.

## In-game Collection Log tab (first step only, untested)

Working toward showing the bingo board inside the actual Collection Log
interface instead of only the side panel — same thing Anvil does. This is
a bigger, multi-step effort; what exists so far is deliberately just the
first, independently-verifiable step:

- `BingoClogTabController` + `BingoClogIds` inject a real "Bingo" tab into
  the Collection Log's native tab row (3-slice sprite, correct spacing,
  copied render flags to match Jagex's own tabs), which currently just
  shows a plain placeholder message when clicked, and restores native
  content correctly when you navigate away (detected via the
  `COLLECTION_DRAW_LIST` script firing, which only happens when the user
  clicks a native tab/entry).
- The technique — exact tab geometry/sprite ids, hiding the native boss
  list, the navigate-away signal — is adapted from
  `AhmedFathy2001/anvil-plugin`'s `ClogTabController` (BSD 2-Clause
  License, Copyright (c) 2026 AhmedFathy2001; see `../anvil-plugin`,
  re-clone if not present). `BingoClogIds` holds the specific borrowed
  geometry/sprite constants, which Anvil's own file documents as measured
  from a live client via RuneLite's widget debug dump — if the injected
  tab ever renders wrong after a game update, that file plus
  `BingoClogTabController` are the only places that should need touching.
- **Deliberately not included yet**: the Adventure Log's alternate
  interface id (only the main Collection Log is handled), and the
  per-game-tick safety net Anvil also has as a backup to
  `COLLECTION_DRAW_LIST` (e.g. for a search input swallowing the click
  that would normally fire it) — add it if testing shows navigate-away
  isn't always reliable.
- **This has never been seen rendered.** Test it before building any real
  board content on top: open the Collection Log, confirm a correctly
  styled "Bingo" tab appears after the 5 native ones, click it and confirm
  the placeholder text shows and the native boss list hides, click a
  native tab and confirm everything restores properly (list reappears, our
  tab's sprite goes back to unselected). Once that's solid, the next step
  is porting `BingoPanel`'s board/goals/leaderboard rendering onto native
  widgets in place of the placeholder — a much bigger follow-up.

## Also added: an on-screen submission banner

`BingoProgressBanner` shows a brief (3s) banner top-center of the screen
when a bingo proof submits — same idea as Anvil's `BingoClogBannerOverlay`
(confirmed by reading their actual source: it's a plain custom `Overlay`,
not native game UI), simplified with no custom art asset or animation,
just `PanelComponent`/`LineComponent` like `BingoVerificationOverlay`
already uses.

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
