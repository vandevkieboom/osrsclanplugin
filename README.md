# Time Served

A RuneLite plugin for the Time Served clan — general clan tooling, with bingo
as its first feature. When you receive a drop that matches one of your team's
bingo tiles, it screenshots your client and submits it as proof to the clan
site automatically, so nobody has to take and upload screenshots by hand.

## What it sends, and when

While the plugin is enabled **and** a plugin key is set, receiving a drop that
matches one of your own team's tiles uploads:

- a screenshot of your game client at that moment,
- the matched OSRS item id and tile id.

It goes to `https://timeserved.vercel.app`. Nothing is sent if the key field is
empty, and the plugin only ever sees loot your own client receives.

Every submission still lands in the clan site's admin review queue — this
plugin doesn't approve anything, it just saves you the manual upload.

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
actually obtained in game — buying an item, withdrawing it from the bank or
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
