# Time Served

A RuneLite plugin for the Time Served clan: chat commands plus automatic
bingo tile proof submission. When you receive a drop that matches one of your
team's bingo tiles, it screenshots your client and submits it as proof to the
clan site automatically, so nobody has to take and upload screenshots by hand.
Members who've never set a plugin key make no background requests to the
clan site at all, ever. Members who have one check in only occasionally
outside an active event, and more often while one's running.

## Features

- **Automatic bingo proof submission** - screenshots and uploads proof the
  moment a matching drop lands, with a disk-persisted retry queue if the site
  is unreachable so a dropped connection doesn't lose your proof.
- **`!rank [name]`** - reports which clan rank tier a member (or the sender,
  if no name is given) is eligible for, based on their synced RuneProfile
  data. Same check as the site's "Auto-Verify" button.
- **`!verify [name]`** - checks a separate, stricter gate: whether the member
  has 6+ Crystal Armour Seeds plus an Enhanced Crystal Weapon Seed, 800+
  Corrupted Gauntlet kc, or a Twisted Bow.
- **`!needed [name]`** - what's missing for the next rank tier up.
- **`!live`** - which clan members are currently streaming on Twitch.
- **Sidebar panel** - your team's board, goal-tile progress, and a clan
  leaderboard, with collapsible sections. Only appears at all while you're on
  a bingo team - there's nothing in it for anyone else. Can be hidden
  entirely via the **"Show bingo board"** toggle even while on a team.
- **On-screen codeword overlay** - an optional, draggable overlay showing an
  admin-announced verification codeword (and, if enabled, a live timestamp),
  matching the Wise Old Man plugin's overlay style. It has to actually be on
  screen at the moment you take/receive a proof to end up baked into that
  screenshot - it's a normal overlay, not a hidden watermark.
- **Bingo submit notifications** - an optional chat message confirming a
  proof went through (or failed), and an optional "crab dance" emote played
  on a successful bingo drop.
- **RuneProfile sync reminder** - a one-time-per-day nudge (not once per
  login) if your RuneProfile isn't synced yet, since several commands and
  bingo verification depend on it.

All chat commands can be turned off entirely with the **"Clan chat commands"**
config toggle. Notification message colors (for the sync reminder/command
usage messages, and bingo submissions separately) are configurable too.

## Kill-count and skill-XP tiles need no action from you

Item-drop tiles are the only kind you interact with directly. Team-combined
boss KC and skill-XP tiles are tracked entirely by the clan site itself, from
your synced RuneProfile hiscores - the plugin doesn't read or report your
live KC/XP at all. As long as your RuneProfile is synced, your progress on
those tiles is picked up automatically; the board just displays the number
the site has already computed.

## What it sends, and when

- **Bingo proof**: while the plugin is enabled **and** a plugin key is set,
  receiving a drop that matches one of your own team's tiles uploads a
  screenshot of your game client at that moment, plus the matched OSRS item
  id and tile id, to `https://timeserved.vercel.app`. Nothing is sent if the
  key field is empty, and the plugin only ever sees loot your own client
  receives. Every submission still lands in the clan site's admin review
  queue - this plugin doesn't approve anything, it just saves you the manual
  upload.
- **`!rank`/`!verify`/`!needed`**: sends the looked-up RSN (your own name by
  default, or whichever name was typed) to `https://timeserved.vercel.app`.
  No plugin key involved - this is the same public data already visible on
  the clan site's Clan Rankings page to anyone, logged in or not.
- **`!live`**: no RSN sent at all - just asks the clan site which of its
  configured Twitch channels are currently live, on demand when typed.

## Setup

1. Log in on the clan site and go to **Settings → RuneLite plugin keys**.
2. Generate a key and copy it (it's only shown once).
3. In RuneLite, enable **Time Served** and paste the key into
   **Plugin key**.

If a key is ever exposed, revoke it on the same settings page and generate a
new one.

## How detection works

The plugin listens for RuneLite's loot events (`NpcLootReceived` for kills, and
`LootReceived` for chests, caskets and raid rewards). Those only fire for loot
actually obtained in game - buying an item, withdrawing it from the bank or
receiving it in a trade produces no event, so a bought item can't be passed off
as a drop.

Which item ids count for which tile is configured by clan admins on the site.
Tiles with no item ids stay manual-upload only.

The server re-validates every submission (that the item satisfies the tile, and
that the tile still needs proof), so a stale board in the client can't create a
bogus submission.

## Building locally

```bash
./gradlew build       # compile
./gradlew runClient   # launch RuneLite with the plugin side-loaded
```

Requires JDK 11.
