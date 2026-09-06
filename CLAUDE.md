# Time Served plugin - context for continuing this work

RuneLite plugin for the Time Served OSRS clan. Auto-submits bingo tile proofs
(screenshot on a matching drop) and has `!rank`/`!verify`/`!needed`/`!live`
chat commands. Team-combined skill XP / boss KC tiles are display-only here - the
plugin reports nothing for them; their progress is tracked entirely
server-side from WOM hiscores (see "Goal-progress tracking" below). Talks to
the clan site at `https://timeserved.vercel.app` (companion repo: `osrsclan`,
same parent folder) via `BingoApiClient`, authenticated with a plugin key
pasted into config (`BingoConfig.apiKey()`).

## Tile icons: a real skill sprite for xp-goal tiles

Written and reviewed 2026-09-06, alongside a matching website-side change
(the sibling `osrsclan` repo's `api/_lib/icons.ts`, `SKILL_ICON_FILES`).
`BingoPanel#loadIconInto` used to leave xp-goal tiles (team-combined skill
XP) with no picture at all, since they have no natural item to derive one
from. It now falls back to a built-in RuneLite skill sprite via a new
`SKILL_SPRITES` map (a `SpriteManager`-backed cache, kept separate from the
existing item-icon cache) - keyed by `goalKey`, case-insensitively, with
`Locale.ROOT` forced deliberately: a Turkish/Azeri client's default locale
lowercases "I" to a dotless "ı", which would otherwise miss this map for a
key like "FISHING" even though the website's (locale-independent) JS
lowercasing would still match it - the exact two-sides-disagree failure
this shared map exists to prevent.

**`SKILL_SPRITES` must be kept key-for-key identical to the website's
`SKILL_ICON_FILES`** - same 26 keys, including both alias pairs
(`defence`/`defense`, `runecraft`/`runecrafting`) - or the two sides will
disagree about which tiles get a skill icon at all. Verified in review to
match exactly; re-verify by hand if either map is ever touched again, there
is no automated test crossing this specific boundary.

An earlier version of this also carried an explicit per-tile icon override
(`BoardResponse.Tile.iconItemId`, an admin-pinned item id) - removed the
same day, before ever shipping to the plugin, as redundant: its whole
purpose (picking a specific item's picture) is already free by listing that
item first in the tile's item list, which is what the existing fallback
already does. See the website's own `CLAUDE.md` for the full reasoning.
Don't reintroduce it without a concrete case the reorder approach can't
cover.

## Request volume: one poll per tick, only while logged in

> **2026-09-06: the tick is now genuinely one request, and it no longer
> reaches a database.** Two changes on the same day, both worth knowing before
> touching `scheduledRefresh`:
>
> 1. **`checkBoardState()` is gone.** It ran as the second half of every tick,
>    asking `GET /api/board?resource=status` whether the board had changed -
>    which `onPolled` had *already* been told on the same tick, from the same
>    `board_config` row. Its own comment claimed it "isn't adding any load
>    beyond what a participant's poll() already costs"; that was simply wrong.
>    A separate URL is a separate CDN cache entry, so it was a separate origin
>    invocation and a separate database read every minute per participant,
>    doubling the rate at which participants woke Neon's compute for
>    information they already had. `fetchBoardState`/`BoardState` were removed
>    from `BingoApiClient` along with it. If you find yourself adding a second
>    periodic request, check first whether the poll can just carry the field.
>
> 2. **The site answers the poll from a CDN file, not Postgres.** See the site
>    repo's "The board marker" section. Nothing changes on this side - same
>    endpoint, same JSON, same cadence - but it means poll frequency is no
>    longer what decides whether the database gets to sleep, so tuning it is
>    no longer the lever it used to be. The lever now is *board fetches*, which
>    `shouldRefreshBoard` already gates on the panel being visible.
>
> Also: a bingo going active now clears `lastMyTeamFetchAt`, forcing every
> online client to re-check team membership on the next poll. Team membership
> is only ever checked while bingo is active, so at the moment the switch is
> flipped every client is holding whatever it last knew - which, for the normal
> "build the teams, then flip the switch" run-up, is "no team", with the nav
> icon hidden on that basis. Without the reset the icon did not appear until
> the 30-minute throttle happened to lapse, and the only fix a member could
> find was restarting the client. That is exactly what was hit in real testing.

> **Superseded 2026-09-02** - the merge-three-requests-into-one fix below
> shipped first and helped, but the database kept burning compute 24/7
> regardless (Neon has no way to tell "cheap ping" traffic from anything
> else - any request within its 5-minute auto-suspend window keeps the
> compute awake, and broadcast/live-stream pings from the *entire* clan,
> not just participants, were happening every 30-60s around the clock).
> Rather than just widening cache windows, broadcast and live-stream
> notifications were removed from the plugin entirely: `hasAnythingToPollFor()`
> now checks only whether a plugin key is set, so nobody without one (i.e.
> everyone except actual bingo participants) makes any periodic request at
> all. See "Broadcast and live-stream notifications: removed entirely"
> further down. The design below is kept for the parts that still apply
> (merging into one request, not polling while logged out, `boardChangedAt`)
> - just mentally cross out every mention of broadcast/live-stream as a
> reason to poll.

This plugin was, by a wide margin, the largest source of load on the clan
site, and in late August 2026 it took the site down. Not through any bug -
just by doing something small far too often, from far too many clients, at
a scale the free hosting tier could not absorb. The full incident write-up
lives in `osrsclan/CLAUDE.md`; what matters here is the plugin's half.

**What it was doing.** Every scheduled tick fired three separate requests -
bingo status, clan broadcast, live streams - unconditionally, for as long as
the client process was running. Logged in or sitting at the login screen made
no difference. That is roughly 4,300 requests per member per day for a plugin
doing nothing at all, and on top of that, whenever a bingo event was on, a
fourth request re-downloaded the entire board (tiles, teams, rosters, every
submission) once a minute whether or not one single thing on it had changed.

**What it does now** (`BingoPlugin#poll`):

- **One request per tick**, `BingoApiClient#fetchPluginPoll` ->
  `GET /api/plugin-poll`, carrying all three answers. They were always
  fetched on the same tick anyway, so nothing arrives any later than before.
- **The site sets the cadence**, via `pollSeconds` on that response
  (clamped here to 1-15 minutes). One minute while a bingo event is running,
  because that is when the board is what members are actually watching;
  slower between events, when the only things this carries are "someone went
  live" and the occasional admin broadcast and nobody can tell 60 seconds
  from 180. It lives server-side so it can also be retuned mid-month without
  a plugin release - hosting quotas are monthly and hard, and going over
  takes the site down for everyone until the month rolls over, which is not
  something a constant compiled into installs that update whenever they feel
  like it can be relied on to fix in time.
- **Nothing while logged out.** Every one of these results is delivered as a
  game chat message or an in-game board, so polling at the login screen was
  spending requests on notifications with nowhere to go.
- **The board is only re-fetched when it has actually changed.** The poll
  response carries `boardChangedAt`, an opaque marker the site moves whenever
  anything the board is built from changes. The plugin compares it against
  the value its current board was fetched with. A real change is still picked
  up on the very next tick; what's gone is re-downloading an identical board
  every minute for hours.
- **Backoff on failure**, doubling to a 15-minute cap. Without it, a site
  outage turns every online plugin into a client retrying once a minute
  forever, which is maximum load at the moment the site can least afford it -
  and that is exactly how a database problem became a total outage.
- **Sits still on a `degraded` response.** When the site can't reach its own
  database it answers 200 with last-known values and `degraded: true` rather
  than erroring (an error response can't be CDN-cached, so an erroring
  endpoint multiplies its own load). Acting on those values could announce a
  stale broadcast or conclude an event has ended when it hasn't, so the
  plugin skips the tick instead. Costs at most a minute.

**Bingo work is gated on actually being in a bingo, not on one existing.**
Most of the clan runs this plugin for the chat commands and the notifications
and is never in a bingo team. For them the plugin does no bingo work at all,
even mid-event:

- The board is never fetched while `myTeamId` is null and the panel is shut.
  There is nothing it could be used for - the only reason to hold a board with
  the panel closed is the item-id watch list for auto-submission, and the
  server refuses submissions from a member with no team anyway.
- The poll cadence stays slow. The site sends two (`pollSeconds` and
  `participantPollSeconds`) and the plugin picks; the fast one applies only
  while genuinely on a team. An event a member is not in changes nothing they
  can see, so speeding up their poll for its duration would spend most of the
  event's budget on people who cannot tell the difference.
- `maybeRefreshMyTeam` throttles on elapsed time, not on whether a team was
  found. An earlier version skipped only when a team id was already known,
  which had everybody *not* in the bingo re-asking on every single poll and
  getting the same "no team" answer forever - exactly the wrong people paying
  the most.

`hasAnythingToPollFor()` goes one step further: with both notification toggles
off and no plugin key, there is nothing the periodic poll could deliver, so it
doesn't run at all. Chat commands are unaffected - they are sent when typed
and never poll.

**The RuneProfile sync reminder remembers its answer.** It used to fire on
every login, for every install, calling the most expensive endpoint on the
site (a clan roster lookup plus three upstream profile fetches) to answer a
question that once answered yes never changes again. Now: confirmed synced,
never asks again; not synced, asks at most once a day rather than once a
session.

**The board fetch is anonymous and shared.** `fetchBoard` sends no key and
asks for `?view=plugin`. The site serves one cached copy of that response to
everybody, which is what stops a single person's drop costing one full board
render per online member; `view=plugin` strips the per-proof blob and avatar
URLs BoardResponse was already discarding, which is most of the payload once
an event has real submissions on it. Don't add an Authorization header to it -
that asks for a private copy of a public answer. Which team is *yours* comes
from `fetchMyTeam` instead: once on startup, on a key change, and at most
every half hour, since team assignment happens before an event rather than
during one.

**The board is only re-fetched when someone is looking.** Nearly everything
that changes minute-to-minute during an event is display data - standings,
teammates' submissions, goal totals. The one thing the plugin needs while
nobody has the panel open is the item-id watch list, and that only changes
when an admin edits tiles. So: panel open, refresh on every change; panel
closed, refresh every ten minutes. Auto-submission is unaffected either way -
it fires off a loot event, not off a poll.

**Proofs are JPEG, not PNG.** A lossless PNG of a full game frame is close to
PNG's worst case and real proofs were landing near a megabyte each, charged
against the site's storage quota for as long as the board lives and against
its transfer quota every time anyone opens a tile to look. High-quality JPEG
is several times smaller with no loss of the only property a proof needs,
which is being readable. `contentTypeOf` sniffs PNG magic bytes so a proof
queued on disk by an older build and retried after an update is still
declared correctly.

**During an event the tick is still one minute, deliberately.** The goal was
never to make members wait longer for a board update, a rank or a broadcast -
a plugin showing stale data has no reason to exist. The goal was to stop
paying for the same answers over and over, and to spend the budget that
remains on the moments people are actually looking. If load needs cutting
further, merge or de-duplicate more requests, and raise the *idle* cadence,
before touching the during-an-event one.

**Don't** add a plugin key, a player name, or anything else per-member to
`fetchPluginPoll`. It is anonymous and byte-identical for every caller
specifically so that a single CDN cache entry can serve the whole clan;
making it per-member is the same as having no cache at all. Anything that
genuinely needs to be per-member belongs on `fetchBoard`, which is
authenticated and rare.

## Nav icon/sidebar panel: only shown while on a team

**2026-09-02, follow-up to the removal above.** The sidebar panel used to
appear for anyone with `showSidebar` on, regardless of team membership -
which meant someone with a leftover plugin key but no team could still open
it and see the clan-wide standings. That's not a privacy issue (that data was
already fully public on the website), but `checkPanelOpened()` triggers a
real, uncached board fetch the moment the panel opens while `bingoActive` is
true - the expensive query, not the cheap shared ping - for someone who isn't
even playing. Since the same information is available on the website, there
was no reason to keep paying for that.

`updateNavVisibility()` now gates the nav icon on `myTeamId != null` in
addition to the existing `showSidebar` toggle, and is called from every place
that could change either: `refreshMyTeam()`'s callback (including its
empty-key early return), and the `showSidebar` config-change handler. The nav
button is no longer added unconditionally in `startUp()` - team membership
isn't known synchronously, so the icon simply doesn't appear until
`refreshMyTeam()`'s async result resolves (a brief, harmless gap at login,
not a bug).

## Broadcast and live-stream notifications: removed entirely

> **Correction, 2026-09-06.** The Neon reasoning below is right about
> *broadcast* and wrong about *live streams*. The site's `api/twitch-live.ts`
> contains no database calls at all - it asks Twitch and returns the answer -
> so live-stream polling could not have kept Neon's compute awake. Broadcast
> was the half that read `board_config` out of Postgres every tick. What
> live-stream polling actually spent was **Vercel edge requests** (1M/month on
> Hobby; 100+ installs at one check a minute is ~4.3M), which is a real reason
> not to bring it back, but a different one. Don't cite Neon against it.
>
> Note also that **`!live` still works** - `onLiveCommand` was never removed.
> Only the unprompted every-minute notification was. That contrast is the
> transferable lesson here: on-demand costs one request when somebody asks;
> on-a-timer costs ~1,440/day/member whether or not anyone cares. Reach for
> the former whenever a feature can be shaped that way.

2026-09-02, on top of the merge-into-one-request fix above. Even after that
fix, Neon compute usage stayed close to 24/7: its free-tier auto-suspend
fires after 5 minutes of true inactivity, but the combined poll's edge cache
window (30s default) meant *some* clan member's cache-miss reached the
database roughly every 30-60 seconds, all day, forever - resetting that
5-minute countdown before it could ever complete. The traffic keeping the
database awake had nothing to do with bingo: it was every online install
(several hundred people, whether or not they'd ever touched bingo) checking
for a live-stream notice or an admin broadcast.

Rather than raise the cache window (which only delays the problem and adds
notification latency), both features were dropped:

- `BingoPlugin`: `handleStreams()`, `handleBroadcast()`,
  `previouslyLiveUsernames`, `LAST_SEEN_BROADCAST_KEY` all gone.
  `hasAnythingToPollFor()` is now just `!config.apiKey().trim().isEmpty()` -
  a plugin key is what a bingo participant has, so between events (or for
  anyone who's never touched bingo) this plugin now makes **zero** periodic
  requests, not just cheap ones.
- `BingoConfig`: `notifyLiveStreams`/`notifyBroadcasts` toggles removed.
  `clanMessageColor()` still exists (the RuneProfile-sync reminder and chat
  command usage messages still use it) but its description no longer
  mentions the removed features.
- `BingoApiClient.PollResponse`: `broadcast`/`streams` fields and the
  `Broadcast` class removed. `!live` is unaffected - it was always a
  separate on-demand call (`fetchLiveStreams()` -> `GET /api/twitch-live`),
  never fed by the periodic poll.
- Site side (`osrsclan`): `api/plugin-poll.ts` no longer queries
  `broadcast_message`/`broadcast_updated_at` or fetches Twitch streams at
  all; `POST /api/admin/board?resource=broadcast` and
  `GET /api/runeprofile-proxy?resource=broadcast` (the admin's send-a-message
  endpoint and the pre-consolidation broadcast poll) are gone, along with the
  Board Config panel's whole Broadcast tab. `db/schema.sql` drops the
  `broadcast_message`/`broadcast_updated_at` columns. See that repo's own
  CLAUDE.md for the full site-side change.
- `pollSeconds` on the site is now tied directly to `bingo_active` (fast
  while an event is on, slow otherwise) instead of "was a broadcast sent in
  the last 15 minutes" - the old `needsFastPolling()` logic is gone along
  with the feature it existed for. This also fixes a latent inconsistency
  that was never actually noticed in production: the fast/slow cadence was
  never really tied to whether bingo was active in the first place.

**Don't reintroduce either feature without a fresh conversation about
scope.** They were deliberately cut, not merely deferred - this was a direct
tradeoff of "members lose two convenience notifications" against "the
database can actually go to sleep when nobody's running a bingo," made with
the user's explicit sign-off (small non-monetized clan project, doesn't want
to pay to keep a background feature alive).

## Goal-progress tracking (XP/KC tiles) - reworked to hiscores-only

The plugin used to parse kill-count chat lines and push its own skill-XP
readings directly to the site, with the server treating each member's
first-ever report as their permanent baseline. Real desktop testing showed
this was fundamentally unreliable: the first kill on a kill-count tile
sometimes never counted (a `NpcLootReceived`/`LootReceived` firing order
quirk could beat the chat-line parse), an XP tile sometimes silently
recorded a baseline mid-session and credited already-gained XP as
"progress," and a board reset didn't reliably re-seed every member's
baseline at the same moment. The common cause: baselines were set
implicitly, whenever a report happened to be the first one the server saw,
staggered across whenever each person's client next reported (or never, for
a player who doesn't run the plugin).

The fix removed all of it from this plugin: `KILLCOUNT_PATTERN`, the
`onChatMessage` chat-parsing handler, the `pendingKcPush`/`kcPushLock`
debounce buffer, `reportXpProgress`/`skillFromName`, and
`BingoApiClient#reportProgress` (the goal-progress HTTP call) are all gone,
along with the goal fields (`goalKind`/`goalKey`/`goalValue`, `saveGoal()`,
`isGoal()`) that used to let `PendingSubmissionStore` hold a failed goal
report alongside a failed drop proof - that store is proof-only now.
`refreshBoard()` only ever builds the item-id lookup `handleLoot()` checks
drops against; xp/kc tiles are skipped entirely when building it, since
there's nothing left to report for them. `BoardResponse.Tile.goalKind`/
`teamProgress` stay, purely for `BingoPanel` to *display* the number the
server already computed.

The website side now owns 100% of xp/kc tracking, sourced only from real
hiscores - see `osrsclan`'s `CLAUDE.md` for `seedGoalBaselines` /
`refreshGoalLatestValues`. The plugin-writable
`POST /api/board?resource=goal-progress` endpoint was removed on that side
too, so there's no longer any path for a plugin (buggy or malicious) to
write an arbitrary progress value directly.

## Backing off board polling when no bingo event is running

> **Partly superseded** - the `bingo_active` gate described below still
> exists and still works, but the "cheap ping" is no longer its own request:
> it is one field on the combined poll above, which also now says whether the
> board has changed at all. See "Request volume" above.

This plugin is a general clan tool, not bingo-only - most members keep it
running for `!verify`/`!needed`/`!live`, clan broadcasts, and live-stream
notifications whether or not a bingo event exists. Before this, the board
refresh (`refreshBoard`, feeding both `BingoPanel` and the item-drop
watch-list - a real query over tiles/teams/submissions) polled
`GET /api/board` every minute forever regardless, which is real ongoing
load against the site's (free-tier) Vercel invocation quota for a feature
that's often not even active.

The fix went through two designs; only the second is in the code now:

1. **First attempt (replaced, don't reintroduce)**: read an
   `bingoActive` flag off the normal board response, and once false, back
   the *entire* board fetch down to once per 30 minutes. This worked for
   cutting cost, but made turning an event back on take up to 30 minutes
   to be noticed - backwards, since re-activating is exactly the moment
   you want picked up fast, not slow.
2. **What's actually implemented**: `GET /api/board?resource=status` is a
   second, separate, deliberately tiny endpoint on the site that returns
   *only* `{ bingoActive }`, cached at Vercel's edge for 30s
   (`BingoApiClient#fetchBingoStatus`, `BingoApiClient.BingoStatus`). It's
   cheap enough that `checkBingoStatus()` polls it every single
   `scheduledRefresh` tick (every minute) *unconditionally* - that's the
   only bingo-related thing that happens at all while inactive. The real
   (expensive) `refreshBoard()` + `retryPendingSubmissions()` only run
   inside `checkBingoStatus`'s success callback, when the ping itself says
   `bingoActive` is true. `bingoActive` (the field) also gates the
   `refreshBoard()` call in `onGameStateChanged`, which fires on every
   area/instance load (raids, minigames - not just literal login), so
   that doesn't sneak in extra expensive fetches while inactive either.
   `refreshBoard()` is still called directly, unconditionally, from
   `startUp()`, `onConfigChanged("apiKey")`, and after a successful proof
   submission - real, rare, user-driven moments that should always get an
   immediate check regardless of the last known ping result.

**Important, don't reintroduce the first design.** A separate, even
simpler mistake also got made and reverted along the way: lowering
`scheduledRefresh`'s overall period from 1 minute to 2 minutes, reasoning
that fewer ticks means fewer requests. That's wrong here -
`checkLiveStreams`/`checkBroadcast` share the same `@Schedule` method, and
slowing them down directly delays "a clan member just went live" and
admin broadcast notifications, which people actually notice. Reverted
back to 1 minute; the actual request-volume fix is the cheap-ping/
expensive-fetch split above, plus response caching on the site side (see
`osrsclan`'s `CLAUDE.md` - `twitch-live.ts` and now
`runeprofile-proxy.ts`'s broadcast branch both cache at Vercel's edge, so
polling every minute doesn't mean invoking the function every minute).
Prefer edge caching / a cheap-ping-then-expensive-fetch split over slowing
down client polling wherever this trade-off comes up again - both
decouple request frequency from backend cost, rather than trading delay
for cost directly the way a slower interval does.

## Sidebar panel: config toggle, styling, no more submit toast

- **`showSidebar` config toggle** (`BingoConfig`, default on) - flips the
  bingo nav icon in the client sidebar on/off live via
  `onConfigChanged("showSidebar")`, no restart needed.
- **Removed the "X auto-detected - submitted" toast** from `BingoPanel`
  (`buildToast`, `notifyAutoSubmitted`, `lastAutoSubmitItem`/`lastAutoSubmitAt`,
  `TOAST_WINDOW_MILLIS` all gone) - the chat message confirmation
  (`notifyPlayer`, gated by the existing "Notify on bingo submit" toggle)
  is untouched and is now the only on-submit confirmation.
- **Visual polish**: the three progress bars (board/goals/leaderboard) were
  inconsistent thicknesses (6px vs 4px) - unified to 8px on the shared
  `ProgressBar` class. The team-color indicators in the header and
  leaderboard rows were text glyphs (`■`/`●`), which render inconsistently
  at small sizes depending on font - replaced with `colorChip()`, a small
  painted rounded-rect swatch.

## Reference plugin: Anvil

A more mature, unrelated clan's plugin (`github.com/AhmedFathy2001/anvil-plugin`
- cloned as a sibling folder `../anvil-plugin` during this session for
comparison; re-clone it there if it's not present on this machine) was used
as a comparison point for "how do other clan-bingo plugins handle X" - it's a
generalized, multi-tenant "clan-operations platform" plugin (~26k lines,
white-label, any clan can point it at their own backend), a much bigger
scope than this plugin is trying to be. It is **not** a dependency and
can't be pointed at our site - its wire protocol (~25 `/api/plugin/*`
endpoints, a completely different data model built around "events" with
points/tiers/reveal-modes) is specific to its own backend, which we don't
run and have no access to.

Ideas actually adapted from it into this plugin (same principle, rewritten
to fit our own schema/auth, not copied code):
- Treating hiscores as the source of truth for XP/KC tracking rather than a
  plugin's own live reporting - see "Goal-progress tracking" above. (An
  earlier version of this plugin instead adapted Anvil's kill-count-push
  debouncing idea; that was removed along with the rest of the live-push
  path once hiscores-only replaced it entirely.)
- Disk-persisted retry queue for failed submissions (`PendingSubmissionStore`).
- The idea of a verification code/timestamp baked into proof screenshots
  (though the *mechanism* ended up different from Anvil's - see below).

**Explicitly declined**, so don't re-suggest these without a fresh
conversation about scope - they were discussed and deliberately ruled out,
not overlooked: Combat Achievement / diary / timed-clear / item-gain /
loot-value tile types, weekly SotW/BotW competitions, multi-clan
federation, drop-luck statistics, OBS replay clips, a points/tiers/reveal-
mode scoring system, and Anvil's device-code Discord sign-in flow (the
existing pasted-plugin-key model is intentionally simpler and considered
sufficient for this clan's size).

## On-screen codeword overlay (`BingoCodewordOverlay`)

Originally built as a second overlay alongside `BingoVerificationOverlay` -
that one only ever rendered for the single frame `drawManager` captures
(baked into proof screenshots, never actually visible during play), while
this one is the opposite: a persistent, player-draggable/resizable overlay
showing the codeword + live UTC timestamp, on screen the whole session.
`BingoVerificationOverlay` has since been removed, so `BingoCodewordOverlay`
is now the only one - gated by `BingoConfig.showLiveCodewordOverlay()`
(default off). Since it's the only mechanism left, if a member wants the
codeword baked into a proof screenshot, this overlay needs to actually be
enabled and visible at capture time - there is no longer a
capture-only/invisible-during-play path.

The ask was to match the Wise Old Man RuneLite plugin's on-screen overlay
"exactly." Four rounds were spent guessing the styling/behavior from
screenshots alone before actually going and getting the real source -
worth recording so this isn't repeated:
1. First pass drew a dark rounded panel + border with custom `Graphics2D`
   paint code, guessing from screenshots.
2. Second pass **removed** the panel/border entirely, wrongly guessing the
   box outline in round 1's screenshots was RuneLite's overlay-edit-mode
   selection outline rather than real styling.
3. User confirmed the panel/border is real; reinstated with custom paint
   code, then went through a font-shrink-to-fit attempt and a manual
   token/line-wrap attempt, both guessed independently rather than sourced
   - the user explicitly said the font-shrink version "is terrible" and
   asked to revert to the plain-clipping version rather than keep either.
4. **User then asked directly: go check the actual WOM plugin's source.**
   `wise-old-man-master` in this same parent folder turned out to be the
   WOM **website** (Next.js monorepo), not the plugin - that's a different
   repo, `wise-old-man/wiseoldman-runelite-plugin` on GitHub, not present
   on this machine, found via `WebSearch` and cloned fresh into the scratch
   directory to read its actual overlay class:
   `net/wiseoldman/ui/CodeWordOverlay.java`.

**What WOM's real overlay does - and what this port now mirrors exactly**
(no more guessing from screenshots): it's not custom paint code at all.
`CodeWordOverlay extends OverlayPanel` (not raw `Overlay`), and `render()`
just adds one `net.runelite.client.ui.overlay.components.LineComponent` -
`.left(codeword).leftColor(...).right(formattedTimestamp).rightColor(...)`
- to `panelComponent.getChildren()`, then calls `super.render(graphics)`.
Every behavior the screenshots showed comes from RuneLite's own components,
not anything WOM (or this plugin) built:
- `OverlayPanel`'s constructor already calls `setResizable(true)` and its
  `render()` renders the dark panel background + two-tone border stroke
  automatically (`BackgroundComponent`, derived from the panel's background
  color - no border color to configure separately, it's computed from the
  fill color).
- `LineComponent.render()` is where "sticky to the right, one line when
  wide, wraps into extra lines when narrow" actually lives (RuneLite core,
  `net.runelite.client.ui.overlay.components.LineComponent`): if
  `left + right` text combined is narrower than the panel's current dragged
  width, it's one row, left text at the left edge, right text right-aligned
  to the panel's right edge. Once it doesn't fit, the right side gets
  roughly 1/3 of the panel width as a word-wrap budget and breaks onto as
  many additional right-aligned lines as needed (word by word, so
  `"15/08/2026 19:11 UTC"` can end up as three separate lines) while the
  left side keeps its own line(s) - this is exactly why the codeword can
  share a row with the date but the time/"UTC" drop to their own rows once
  the panel's too narrow. Panel height then just naturally follows however
  many lines that produced.
- Timestamp format is `DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'")`
  - copied verbatim from WOM's `FORMATTER` constant.
- `config.codewordColor()`/`config.timestampColor()` default to WOM's own
  defaults too: `new Color(0x00FF6A)` (green) and `new Color(0xFFFFFF)`
  (white), copied from `WomUtilsConfig`.
- Position: `OverlayPosition.ABOVE_CHATBOX_RIGHT` + `setPriority(PRIORITY_LOW)`,
  matching WOM's constructor exactly - this is just a starting anchor, the
  position is still freely draggable anywhere (RuneLite's default
  movable/snappable behavior for any non-`DYNAMIC`/`DETACHED`/`TOOLTIP`
  position).

**Current `BingoCodewordOverlay` is now a direct, faithful, verified port**
- not a guess. If it ever looks or behaves differently from real WOM again,
the fix is almost certainly "make it match `CodeWordOverlay`/`LineComponent`
more closely," not another from-scratch paint-code attempt. The earlier
custom-paint versions (rounded rect, manual token wrapping, font-shrinking)
are gone; don't reintroduce that approach without a concrete reason WOM's
own component-based approach doesn't work here.

(`BingoVerificationOverlay`'s hardcoded `Color.WHITE`/`Color.LIGHT_GRAY`
burn-in colors, mentioned in earlier notes here, are moot - that class no
longer exists.)

Manually verified in a running client: broadcast delivery was confirmed
working end-to-end on 2026-09-01 (see "History" above). Still worth
confirming specifically for this overlay - set a codeword, enable the
toggle, confirm it renders, drag it wide (one line, codeword left/timestamp
right) and narrow (wraps into extra lines), restart the client and confirm
position/size/enabled state all persisted (see the checklist below).

## Scope / design philosophy

This is deliberately a **small, single-clan tool**, not a platform -
hardcoded to `timeserved.vercel.app`, no multi-tenant config, no ambition
to become Anvil. Bingo tiles are staying to exactly three kinds on
purpose: **item drops, team-combined boss KC, team-combined skill XP**.
That's a considered decision (see the Anvil section above), not a
temporary starting point - don't propose expanding tile types as a
"quick win" without checking first.

## History: the `improvement/bingo-tracking` branch

Most of the goal-tracking rework, the disk-persisted retry queue, and the
verification-codeword work below was built on a branch called
`improvement/bingo-tracking`, merged to `main` in two PRs:
[PR #1](https://github.com/vandevkieboom/osrsclanplugin/pull/1) (disk-persisted
retry queue + manual verification code) and, later,
[PR #5](https://github.com/vandevkieboom/osrsclanplugin/pull/5) (the
request-volume cut described above, merged 2026-09-01). Everything described
in this file now lives on `main` - there is no separate feature branch to
track anymore. Much of it was originally built/reviewed without a JDK
available (no compiler access in that environment); it has since actually
been compiled and run on a desktop with a working JDK, and real bugs were
found and fixed as a result - see "Bugs found from real client testing"
below. **That pattern repeated as recently as 2026-09-01**: the
`encodeAndUpload` failure-retry path referenced a variable (`png`) that had
been renamed to `image` during the PNG->JPEG change and no longer existed,
which failed compilation outright until it was caught and fixed. Don't
assume something's correct just because it was carefully reasoned through
without a compiler - keep testing incrementally as more gets added.

## What changed on this branch, and why

1. **KC/XP live push removed entirely** (`BingoPlugin`) - see
   "Goal-progress tracking" above for why. An intermediate version of this
   branch had a KC-push debounce (`pendingKcPush`/`kcPushLock`,
   `KC_PUSH_COALESCE_MILLIS`) that coalesced a kill streak into one report;
   it's gone now along with everything else in the live-push path.

2. **Disk-persisted retry queue** (`PendingSubmissionStore`, new file) -
   failed drop proofs used to live in an in-memory-only queue capped at 20,
   wiped on every restart. Now persists to
   `<runelite dir>/timeserved-bingo-pending/` (JSON + the encoded screenshot
   per proof - `screenshotFile` is still named `<id>.png` even though the
   bytes written there are JPEG since the PNG->JPEG change; `contentTypeOf`
   sniffs magic bytes rather than trusting the extension, so this is a stale
   filename, not a functional bug) and gets reloaded on `startUp()`. Cap
   raised to 100 since disk makes holding more of them safe. (This used to
   also hold failed goal-progress reports; that half was removed with the
   rest of the live-push path.)

3. **Verification code overlay** - originally `BingoVerificationOverlay` (now
   removed, see "On-screen codeword overlay" below - `BingoCodewordOverlay` is
   the only codeword overlay left in the codebase), which rendered a
   manually-typed config value (`BingoConfig.verificationCode()`) plus a live
   UTC timestamp, baked into every proof screenshot automatically. **The
   still-relevant part**: this went through two versions early on. The first
   fetched a server-generated code from `GET /api/board` - that endpoint
   requires no auth at all, so anyone could've read the code, defeating the
   point. Fixed to be a plain manually-entered setting instead (an admin
   picks a code, announces it via Discord, each member pastes it in) - never
   touches the site. That manually-entered model carried over to
   `BingoCodewordOverlay` and still applies. If you ever see anything
   reintroducing a server round-trip for this value, that's a regression of
   an already-identified security issue.

## Bugs found from real client testing (all fixed)

1. **Verification code was visible on-screen the whole session**, not just
   baked into screenshots - the overlay had no gating, so it always
   rendered. Fixed at the time by only drawing while a capture-mode flag was
   on. Moot now: `BingoVerificationOverlay` (the class this bug was in) has
   since been removed entirely in favor of `BingoCodewordOverlay`, which is
   an always-visible-when-enabled overlay by design (see "On-screen codeword
   overlay" below) - there's no "hidden except during capture" mode to have
   this bug in anymore.
2. **XP tiles came back "completed" right after a board reset** - traced to
   a real race in the now-removed `reportXpProgress`: it only checked
   `GameState.LOGGED_IN`, which can flip true a moment before skill data is
   actually populated (most likely right after a client restart). A `0`
   read in that gap became a **permanent** baseline, silently crediting a
   player's entire lifetime XP as "progress." Patched at the time with a
   stronger guard, but this whole reporting path - and the class of bug it
   was prone to - is now moot: it was removed entirely in favor of
   hiscores-only tracking (see "Goal-progress tracking" above), which never
   reads a live in-client value at all.
3. **Team Goals section visibly jumped/overlapped on every board refresh**
   - `BingoPanel.refresh()` does a full teardown-and-rebuild every time,
   and tile icons load asynchronously, so the board section's real height
   wasn't known until an icon callback fired later, letting Swing paint an
   intermediate half-built layout. Fixed by hiding the whole panel
   (`content.setVisible(false)`) for the duration of the rebuild, only
   revealing it once layout and scroll position are both settled.
4. **Rapid-killing a fast monster only ever submitted the first proof** -
   `recentlyAttempted`'s dedup window was 30 seconds, keyed only on tile
   id (meant to collapse `NpcLootReceived`/`LootReceived` firing twice for
   ONE kill). Real bug: any second genuine kill of the same monster within
   30 seconds got silently swallowed too, with no way to tell "duplicate
   event" from "actually a new kill." Shrunk to 1.2s (2 game ticks) -
   still covers the real duplicate-event case, no longer eats real kills.
5. **The submission banner didn't look like the collection-log style it
   was supposed to** - the first version used RuneLite's generic
   `PanelComponent` box. Replaced with a direct port of Anvil's
   `BingoClogBannerOverlay`. Moot now - see "Removed: Collection Log tab and
   submission banner" below, both are gone.

## Removed: Collection Log tab and submission banner

Two features were prototyped and later dropped in commit `0868edf`
("Drop the Collection Log tab, drop-submission popup, and item lootbeams"),
along with ground-item lootbeam highlighting:

- **In-game Collection Log tab** (`BingoClogTabController` + `BingoClogIds`,
  adapted from `AhmedFathy2001/anvil-plugin`'s `ClogTabController`) - was
  working toward showing the bingo board inside the actual Collection Log
  interface via a real injected "Bingo" tab. Dropped as an early prototype
  with real style/positioning bugs (tab-label overflow among them) that
  weren't worth chasing further given the scope this plugin is aiming for
  (see "Scope / design philosophy" below). If this is ever revisited, treat
  it as a fresh effort rather than resurrecting the removed classes -
  nothing about the current codebase depends on it.
- **On-screen submission banner** (`BingoProgressBanner`, a direct port of
  Anvil's `BingoClogBannerOverlay` including its `clog_banner.png` asset) -
  had a mismatched header style and an off-center popup that weren't fixed
  before the decision was made to drop it rather than keep polishing it. The
  chat-message confirmation (`notifyPlayer`, gated by "Notify on bingo
  submit") is the only on-submit confirmation now.

Don't reintroduce either without a fresh conversation about scope - they
were deliberately dropped, not abandoned mid-flight for lack of time.

## What to actually test on this desktop

- [x] `./gradlew compileJava` - passes as of 2026-09-01 (see "History" above
      for the `png`/`image` compile bug that had to be fixed first).
- [x] `./gradlew runClient`, log in, set a plugin key - done 2026-09-01.
- [ ] Get a bingo drop with the site/network reachable - confirm normal
      submit still works.
- [ ] Force a failure (wrong site URL, or disconnect), get a drop, confirm
      it queues; restart the client; confirm it retries and submits once
      reachable again. **Highest-priority remaining item** - this is the
      exact code path the 2026-09-01 compile bug was in, and it has never
      been exercised at runtime.
- [ ] Open a kill-count or xp tile's board display and confirm it shows a
      number at all (server-computed `teamProgress`) - there's nothing left
      in the plugin to trigger for it, so this only proves the read path.
- [ ] Set a codeword in config, enable "Display codeword"
      (`showLiveCodewordOverlay`), confirm it + a timestamp render via
      `BingoCodewordOverlay` (draggable, anchored near the chatbox - not
      top-left, that was the now-removed `BingoVerificationOverlay`'s
      position), and confirm a submitted proof screenshot has it baked in
      only if the overlay was actually visible at capture time.
- [ ] Toggle "Show sidebar" off/on in config, confirm the nav icon
      disappears/reappears immediately with no restart.
- [ ] On the site's Board Config admin page, untick "Bingo event active",
      wait a couple minutes, confirm debug logs show `refreshBoard`/the
      "Bingo board refreshed" line has stopped appearing while
      `checkBingoStatus` keeps ticking every minute regardless; re-tick it
      and confirm the very next scheduled tick (within ~1 minute, not 30)
      does a real board refresh again.
- [ ] With "Bingo event active" off, confirm live-stream-went-live and
      admin broadcast notifications still arrive within about a minute -
      these must not be affected by the bingo-active backoff at all.

## Related: the `osrsclan` site repo

This session's rework touched both repos together - the plugin side removed
all live KC/XP reporting (see "Goal-progress tracking" above); the site side
(`osrsclan`) split the old mixed correction+seeding function into
`seedGoalBaselines` (explicit, only from a reset or a tile's goal being
created/changed) and `refreshGoalLatestValues` (correction-only backstop),
and removed the now-unnecessary `POST /api/board?resource=goal-progress`
endpoint entirely. See that repo's own `CLAUDE.md` for the full design.
