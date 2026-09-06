# Time Served

A RuneLite plugin for the **Time Served** OSRS clan, built around
[timeserved.vercel.app](https://timeserved.vercel.app). It gives you clan
chat commands and, during a bingo event, automatically screenshots and
submits your drops as proof, no manual uploading needed.

If you never set a plugin key, the plugin makes no background requests at
all.

## Setup

1. Log in at [timeserved.vercel.app](https://timeserved.vercel.app) and go to
   **Settings → RuneLite plugin keys**.
2. Click **Generate**, then copy the key (it's only shown once).
3. In RuneLite's plugin settings, enable **Time Served** and paste the key
   into the **Plugin key** field.

**A plugin key is only needed for bingo participation.** The chat commands
work without one. If you're not currently taking part in a bingo, don't
bother setting a key at all, and once an event you did take part in ends,
it's good practice to clear the key (or revoke it on that same settings
page) rather than leave it sitting there. It also means if a key is ever
exposed, revoking it and generating a new one takes seconds.

## Chat commands

| Command | What it does |
|---|---|
| `!rank [name]` | Which clan rank tier that member (or you, if no name given) qualifies for, based on their synced RuneProfile. |
| `!verify [name]` | A stricter check: 6+ Crystal Armour Seeds and an Enhanced Crystal Weapon Seed, 800+ Corrupted Gauntlet KC, or a Twisted Bow. |
| `!needed [name]` | What's missing for the next rank tier up. |
| `!live` | Which clan members are currently streaming on Twitch. |
| `!event [name]` | The current BOTW/SOTW's leaderboard, or that member's own progress in it. |

All of these can be turned off with the **"Clan chat commands"** toggle if
you'd rather not have them.

## During a bingo event

- **Drops submit themselves.** The moment you receive an item matching one of
  your team's tiles, it's screenshotted and uploaded automatically. If the
  site is briefly unreachable, the submission is saved and retried
  automatically, so you won't lose it.
- **The sidebar panel** shows your team's board, tile progress, and clan
  standings (the same board is also on the website, if you'd rather check it
  there). It only appears while an event is actually running **and** you're
  on a team; there's nothing to show otherwise. Turn it off entirely with
  the **"Show bingo board"** toggle if you'd rather not see it.
- **Kill-count and skill-XP tiles need nothing from you.** Those are tracked
  automatically from Wise Old Man's hiscores, just make sure you're synced to
  the clan's Wise Old Man group and your progress shows up on the board on
  its own.
- **Optional extras**, each with its own toggle: a confirmation message (and a
  "crab dance" emote) when a drop submits successfully, and an on-screen
  codeword overlay for admin-run verification events.

## Troubleshooting

**My board isn't showing.** Three things all have to be true: a bingo event
is currently running, you're assigned to a team, and the "Show bingo board"
toggle is on. If you were just added to a team, it can take up to a minute to
notice; if it still hasn't shown up, try restarting the client.

**My drop didn't submit.** Check that an event is actually active, drops
made before an event officially starts aren't accepted on purpose, so
nothing counts early. Also make sure your plugin key is still valid (see
Setup above).

**A command isn't replying.** Make sure "Clan chat commands" is enabled in
the plugin's settings, and that the name you typed is spelled the way it
appears in-game.

## Privacy: what actually gets sent

- **Bingo proof**: only while an event is running, your key is set, and the
  drop matches one of your own team's tiles. A screenshot plus the item and
  tile involved is sent to the clan site. The plugin only reacts to loot you
  actually receive in-game (a kill, a chest, a casket, a raid reward);
  buying an item, withdrawing it from the bank, or trading for one does
  nothing, so nothing you didn't earn in-game can ever be submitted. Every
  submission still goes through an admin's manual review before it counts.
- **`!rank` / `!verify` / `!needed`**: sends the looked-up name to the clan
  site. No plugin key involved, it's the same public rank data already
  visible to anyone on the site's Clan Rankings page.
- **`!live`**: sends nothing about you at all, just asks which clan Twitch
  channels are currently live.
- **`!event`**: sends nothing about you either, just asks for the current
  BOTW/SOTW's public standings.

Which item drops count toward which tiles is configured by clan admins on
the site, not by you.
