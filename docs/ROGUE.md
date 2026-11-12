# Rogue Runs

A rogue run is a chain of rooms. Players clear a room and pick a door. The next room comes up, a bit harder than the
last, and so on until everyone is out (or until a set number of rooms is cleared). There's no box to build for the
run itself: its rooms are picked at random from room groups. Rogue runs are their own thing: they have their own
rooms, room groups, pads, pools and waiting areas (all made with `/rogue …`), kept apart from the dungeons' (dungeons
never get rogue rooms, rogue runs never get dungeon rooms). They're played in their own dimension,
`nicecontrolcenter:rogue` (shown as "Rogue Runs" in purple in the tab list), set up like the dungeons' one (a void
world with its own light setup, no tweaks or tuning, the dungeon quick actions menu). Parties and kits work as in
[DUNGEONS.md](DUNGEONS.md). Room names are shared with dungeon rooms (their blocks are kept by name), so a rogue room
can't have the name of a dungeon room. Since 1.110 they're kept in their own file (`nicecontrolcenter-rogue.json`);
rogue runs made before are moved there on the first start.

**One life:** players who die are out and watch their party until the run ends. Leaving or logging out is out too.

**Boons and coins:** after a fight, each player picks one of 3 boons (more health, damage, speed, effects, items…)
that lasts for the rest of the run. Foes pay run coins, spent in shop rooms. Between runs, players buy unlocks with
points that help in every run.

## 1. Your first rogue run

1. Make the run: `/rogue create Crypt` (or in the panel, **Games → Rogue Runs**, type a name and press
   **New rogue run**). It gets groups named after it: `crypt_start`, `crypt_fight`, `crypt_elite`, `crypt_boss`,
   and the optional `crypt_treasure`, `crypt_shop` and `crypt_rest`.
2. Build a few rooms (at most 64 × 64 × 64 each) and put markers in them. The markers are data structure blocks, the
   same as dungeon markers, and `/rogue tool marker <text>` gives you one:

   | Marker | What it does |
   |---|---|
   | `start` | Where players arrive (needed). |
   | `door` | Where players pick the next room. There's one door per marker, so 2 or 3 doors make a choice. |
   | `spawn` | Where the run's mobs come (shared out over all spawn markers). |
   | `mob:<id> [xN]` | Fixed mobs, as in dungeons (they get tougher with the room too). |
   | `boss:<id>` | The boss of a boss room (else one from the run's boss list comes at a spawn marker). |
   | `shop` | Shop rooms: the shop NPC stands here; a right-click opens the shop (without one, it opens when they arrive). |

3. Save each room into a group: `/rogue room create <room>` at one corner, `/rogue corner2` at the other, then
   `/rogue room save <room> crypt_fight` (or `crypt_elite`, `crypt_boss`, `crypt_start`). A room can be in several
   groups: the same hall can be a fight room and an elite room.
4. Put a pad down: `/rogue pad add <pool>` with the pool the run is in (`default` unless you changed it).
5. Try it: `/rogue try Crypt`.

The first room comes from the start group (a safe room whose doors are open at once). With no room in that group, the
run starts in a fight room.

## 2. How a run goes

- Every foe in the room down → the doors open, each showing what's behind it:

  | Room | |
  |---|---|
  | ⚔ Fight | Mobs; a room reward afterwards. |
  | ☠ Elite | Tougher mobs, double coins; a room reward afterwards. |
  | ♛ Boss | A boss; coins and a room reward afterwards. |
  | ✦ Treasure | No mobs: a free boon (and whatever its chests hold: loot tables roll fresh). |
  | 🛒 Shop | No mobs: spend run coins. |
  | ❤ Rest | No mobs: heals and feeds everyone, takes harmful effects away (all set in the panel). |

  A type only shows up when its group has rooms, so a run with only fight rooms works too.
- A cleared fight, elite or boss room gives the party one **room reward**, picked by weight: a boon choice, run coins,
  more max health or more armor for the rest of the run. With a boon choice every player picks one (30 seconds, then one is picked
  for them; `/rogue boon` opens the choice again) and the doors wait until everyone has chosen. Treasure rooms
  always give a boon choice.
- The party leader picks by standing on a door for 2 seconds (playing solo, you pick yourself).
- The next room is pasted next to the old one, everyone is brought there, and the old room is wiped.
- Every few rooms (**Boss room every … rooms**), every door leads to a boss room.
- The run ends when everyone is out. A win: **Rooms to win** cleared (0 = endless), or no room left to go to.

## 3. Settings (panel, Games → Rogue Runs, on the run's card)

| Setting | What it does |
|---|---|
| First room group, fight/elite/boss groups | Where each room type's rooms come from: one group, or several (`crypt_easy, crypt_hard`). |
| How often (weight) | How often a door leads to each room type. |
| Boons | Boons after rooms and in shops, or none in this run. |
| Boss room every … rooms | 5 = rooms 5, 10, 15… are boss rooms (0 = never). |
| Rooms to win | 0 = endless. |
| Mobs | The mobs spawn markers draw from (`zombie`, `skeleton x2`, `npc:…`; `w5` = five times as often). |
| Bosses | One of these at random in a boss room (`ncc:…`, `katters:…`, any mob; `w5` = five times as often). |
| Mobs in the first room, more per room, % per extra player | How many mobs a room gets (at most 40). |
| % more health and damage per room, elite % on top | How much tougher each room gets. Bosses get the same as more health. |

Party size, kit, time limit, messages and commands work as for dungeons. Room weight, chance and "at most per run" are
on each rogue room; each rogue room group has a **room range**: from room … to room … (0 = no end), under Rogue Runs
room groups.

**Rooms that change as the run goes deeper:** give a room type several groups, each with its own range. For example
fight rooms from `crypt_fight_easy, crypt_fight_hard`, with `crypt_fight_easy` from room 0 to room 5 and
`crypt_fight_hard` from room 6 on: rooms 1–5 come from the easy group, then the hard one. Overlapping ranges mix them.
A room type only comes up while one of its groups has rooms for that room number.

Messages can use `{depth}`, the number of rooms cleared.

## 4. Boons, coins, the shop and unlocks (panel, Games → Rogue Runs)

These are the same for every rogue run.

- **Boons**: what each one does (an attribute like `minecraft:max_health` added flat or as a percent, an effect whose
  level grows with each one taken, an item as in /give, a heal, or a command), how often one player can take it in a
  run, and how often it's offered (weight 0: only in the shop). They come off when the run ends for the player.
- **Room rewards**: the kinds above with their weight, amount and "+ per room" (coins, health and armor give more the
  deeper the run).
- **Rest rooms**: heal %, food and saturation, harmful effects taken away or not, effects given
  (`minecraft:regeneration 5 1`: id, seconds, level), a message and commands.
- **Coins**: per foe for everyone still in (elite rooms pay more, bosses a fixed amount). They're only for the run.
- **Shop**: which boons shop rooms sell and the price (prices grow with the room number), and the shop NPC's name and
  skin.
- **Unlocks**: bought with points between runs, a level at a time (level 2 costs twice the price). Kinds: coins at the
  start of each run, a boon at the start, boon rerolls, one more boon to choose from. The menu looks like a reward
  shop (each unlock with its icon, level, price and a Buy link). Players open it with `/rogue unlocks`, or put it on
  an NPC: pick **Rogue unlocks** as the NPC's shop (Community → NPCs) or `/npc shop <npc> rogue_unlocks`.

## 5. Rewards and the board

- Rewards triggers: **rogueRoom** (each room cleared, from room …), **rogueBoss** (each boss beaten),
  **rogueEnd** (the run is over, at least … rooms cleared). A win fires **dungeonCleared** as well.
- The best run per party size (most rooms, then the faster time) is on the run's card and in `/rogue top <name>`.

## 6. Commands

| Command | Who | What |
|---|---|---|
| `/rogue create <name> [pool]` | admin | Make a rogue run |
| `/rogue pick <slot> <door>` | admin | Go through a door (for console tests) |
| `/rogue try <name>` | admin | Play it yourself (from the console: a 3-minute test with nobody in it) |
| `/rogue boon` | everyone | The boon choice again (if you closed it) |
| `/rogue shop` | everyone | The shop again (in a shop room) |
| `/rogue unlocks` | everyone | Buy unlocks with points |
| `/rogue leave` | everyone | Leave the run (you're out) |
| `/rogue room create <name>` / `/rogue corner2` | admin | Mark a rogue room's box |
| `/rogue room save <name> [group]` | admin | Save a rogue room (again after changes) |
| `/rogue room group <name> <group>` / `… weight <name> <1–100>` | admin | Its groups, how often it's picked |
| `/rogue room list` / `/rogue room delete <name>` | admin | List or remove rogue rooms |
| `/rogue tool marker <text>` | admin | A marker block (`start`, `door`, `spawn`, `shop`, `mob:…`, `boss:…`) |
| `/rogue pad add <pool>` / `/rogue pad remove` | admin | Add a rogue run pad here / remove the nearest |
| `/rogue pad wait <pool> [radius]` / `… clear` | admin | Waiting area for a full pool |
| `/rogue kit <name>` (`add`, `clear`) / `/rogue pool <name> <pool>` / `/rogue delete <name>` | admin | Kit, pool, remove |
| `/rogue runs` / `/rogue list` / `/rogue top <name>` | admin / everyone | Runs going on, pools, the best runs |
