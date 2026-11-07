# Setting up dungeons

A dungeon is something you build once, anywhere in the world. When a player (or a party) steps on a pad, the server
copies it into the dungeon dimension just for them, and wipes the copy again when they're done. So every run starts
fresh: same build, new mobs, refilled chests.

You need to be an admin (op) and in creative for the setup. Turn dungeons on in the panel first:
**Community → Games → Dungeons**, switch **Dungeons on**.

Contents:
1. [Your first dungeon in 5 steps](#1-your-first-dungeon-in-5-steps)
2. [Markers: start, exit, checkpoints, mobs, bosses](#2-markers)
3. [Pads, pools and parties](#3-pads-pools-and-parties)
4. [Gear and items](#4-gear-and-items)
5. [Random rooms](#5-random-rooms)
6. [Rules, messages and rewards (panel)](#6-rules-messages-and-rewards-panel)
7. [Command list](#7-command-list)

---

## 1. Your first dungeon in 5 steps

1. **Build it.** Anywhere, as a closed area (walls, floor, ceiling). Chests with a loot table refill every run.
2. **Mark the box.** Stand at one corner and type `/dungeon create crypt` (the name is one word), then go to the
   opposite corner (top and bottom count, so diagonally across) and type `/dungeon corner2`.
3. **Put in a start.** `/dungeon tool marker start` gives you a ready-made block. Place it where the party should
   appear. Add an exit the same way: `/dungeon tool marker exit`.
4. **Save it.** `/dungeon save crypt`. The game copies the build into the dungeon's template. **Save again after
   every change** you make to the build (or use "Save again from the build" in the panel).
5. **Add a pad.** Stand where players should start a run and type `/dungeon pad add default`. Players who stand
   on it for two seconds go in.

Try it yourself without a pad: `/dungeon try crypt`. Leave with `/dungeon leave`.

> The markers disappear in the copy, so players never see them.

---

## 2. Markers

Markers are **data structure blocks** inside the build. What they do is set by their text (the block's "name"
field). The easiest way to get one is `/dungeon tool marker <text>`, which hands you the block already filled in.

| Text | What it does |
|---|---|
| `start` | Where the party appears. **Every dungeon needs one.** |
| `exit` | Stepping on it clears the dungeon (when the dungeon is set to "clear at an exit"). |
| `checkpoint` | Players with lives left come back here after dying. |
| `mob:zombie x3` | Spawns 3 zombies here when the copy is made. Any mob works, also NPCs: `mob:npc:knight x2`. |
| `boss:wither_skeleton {CustomName:'"Warden of Bones"'}` | Like `mob:`, but it counts as a boss: shows a boss bar, and with "clear when every boss is dead" the run ends when all bosses are dead. |
| `boss:ncc:frost_warden` | An NCC boss (built into the mod): `ncc:frost_warden`, `ncc:hive_queen`, `ncc:ember_colossus`, `ncc:tempest`, `ncc:drowned_admiral`, `ncc:bone_architect`, `ncc:creeper_king`, `ncc:echo_shepherd`, `ncc:coven_matron`, `ncc:phantom_monarch`, `ncc:piglin_warlord`, `ncc:ender_stalker`, `ncc:iron_tyrant`, `ncc:purpur_sentinel`, `ncc:cinder_lord`, `ncc:weeping_ghast`, `ncc:dire_alpha`, `ncc:heartwood_creaking`, `ncc:abyssal_eye`, `ncc:rotting_giant` or `ncc:arch_evoker` (all of them in `BOSSES.md`), with all its attacks and phases; it drops nothing. |
| `boss:katters:theron` | A Katters Structures boss (by Katter, built into the mod): `katters:arachne`, `katters:raj`, `katters:rusta`, `katters:tenku` or `katters:theron`, with all its attacks and phases; it drops nothing. As `mob:` it fights the same but doesn't count as a boss. |

---

## 3. Pads, pools and parties

- **Pools** group dungeons. A pad belongs to one pool and hands out a **random dungeon from that pool**. New
  dungeons are in the pool `default`. Move one with `/dungeon pool <dungeon> <pool>` (or in the panel). For
  example: put three easy dungeons in `easy`, then a pad for `easy` picks one of the three each time.
- **Pads**: `/dungeon pad add <pool>` where you stand. Remove the nearest one with `/dungeon pad remove`.
- **Waiting area**: when every copy is in use (panel: "Copies at once"), players can't go in. With
  `/dungeon pad wait <pool> [radius]` they wait in an area around you instead, and start as soon as a copy is free.
  Remove it with `/dungeon pad wait <pool> clear`.
- **Parties** (1–4 players): `/party invite <player>`. The **leader** steps on the pad, and party members within 16
  blocks come along.
- **Afterwards** everyone goes to the game hub spawn (or their own respawn point if there's no hub).

---

## 4. Gear and items

Set per dungeon in the panel under "Gear":

- **Kit**: players' own things are put aside when they enter and come back afterwards. Inside, they get the
  dungeon's kit: carry what the kit should hold and type `/dungeon kit <dungeon>`. "Items found inside" decides what
  happens to loot at the end: it vanishes, or it goes to their inbox.
- **Own**: players bring their own things. What they carry comes back after a death inside.

**Items that only exist in games**: give an item `custom_data={ncc_game_only:1b}` and it never leaves the game. It
vanishes when its player leaves the dungeon, and anywhere outside a game. It survives deaths while the player has
lives left. Use it in chest loot tables (`set_custom_data`), mob equipment and drops, or `/give`:

```
/give @p minecraft:iron_pickaxe[custom_data={ncc_game_only:1b}]
```

For chests use a loot table or `/item replace`. A tagged item in your own inventory vanishes within a second
outside a game, so you can't place it by hand. The same tag works in wave fights, quick rounds and game areas.

---

## 5. Random rooms

Optional. Random rooms make every run's layout a bit different: the dungeon has **spots** (openings), and each run
fills them with random rooms you built separately.

### How it fits together

- **A room** is its own small build with one or more **doors** (where it can attach).
- **A spot** is a place in the dungeon (or in another room) where a room can attach. It belongs to a **group**, e.g.
  `crypt`, and gets a random room of that group.
- A spot and a door match by their **door name**, e.g. `arch`. So a room only goes into a spot when it has a door
  with the same name. That way a narrow corridor door never gets a wide hall, for example.
- The room is turned so its door faces the spot, then placed right in front of it.
- **A spot that gets a room** opens up (becomes the doorway). **A spot that stays empty** turns into the block you
  chose for it, e.g. stone bricks, so it looks like a wall.
- Rooms can have spots of their own, so rooms grow out of rooms (up to the dungeon's "Rooms inside rooms, levels").
- **Above and below works too.** A spot facing up gets a room whose door faces down, placed on top of it, and the
  other way round: stairwells, shafts, a crypt under the hall. The jigsaw's front follows how you place it (against
  a floor or ceiling, or looking up or down). Rooms only turn around the vertical axis, so a vertical spot only takes
  rooms with a matching vertical door. Leave enough height in the dungeon's box.
- **Turning**: the tool blocks use the "rollable" joint, so a room can come in any of the 4 turns. Set a jigsaw's
  joint to "aligned" by hand to keep the turn its "top" direction gives (e.g. so stairs line up).
- **Rooms always stay inside the dungeon's box** and never overlap. So make the box big enough: leave empty space
  around the main build where rooms can go. A spot that has no room to fit stays empty.

Both doors and spots are **jigsaw blocks**. You don't need to fill them in by hand, `/dungeon tool` does it. For
checking or setting one up by hand, these are the fields:

| Jigsaw field | Door (`/dungeon tool door arch`) | Spot (`/dungeon tool spot crypt arch stone_bricks`) |
|---|---|---|
| Name | `minecraft:arch` | `minecraft:arch` |
| Target name | `minecraft:arch` | `minecraft:arch` |
| Target pool | `minecraft:empty` | `minecraft:crypt` (the group) |
| Turns into | `minecraft:air` (the doorway) | `minecraft:stone_bricks` (the wall when no room comes) |
| Joint type | rollable | rollable |

The target pool is what makes the difference: `empty` = a door, a group name = a spot.

### Step by step

1. **Build a room** somewhere outside the dungeon's box, e.g. a small hall.
2. **Put a door in it**: `/dungeon tool door arch` and place it in the room's outer wall where it should connect,
   **with the arrow (front) facing out of the room**.
3. **Save the room**: stand at one corner, `/dungeon room create hall1`, other corner `/dungeon corner2`, then
   `/dungeon room save hall1 crypt` (`crypt` is the group). Build more rooms the same way; all with group `crypt`
   are candidates for the same spots.
4. **Put a spot in the dungeon**: `/dungeon tool spot crypt arch stone_bricks` and place it in the dungeon's wall
   where a room may attach, **with its front facing out** to where the room should go. `crypt` is the group,
   `arch` the door name it takes, and `stone_bricks` what it becomes when no room comes (leave it out for air).
5. **Save the dungeon again**: `/dungeon save <dungeon>`. Check it with `/dungeon try <dungeon>`.

Markers (`mob:`, `boss:`, `checkpoint`, chests) work inside rooms too.

### Tuning (panel, Dungeons tab)

| Setting (panel name) | Where | What it does |
|---|---|---|
| Weight (picked more often) | per room | How often it's picked against the other rooms of its group (1–100). Also `/dungeon room weight <room> <n>`. |
| Chance it's considered | per room | % chance it's considered at all; otherwise the next room is tried. |
| At most per copy | per room | How often this room can appear in one run (0 = no limit). |
| filled % | per group | % chance a spot of this group gets a room at all; otherwise it stays closed. |
| from level | per group | 0 = rooms right away; 1 = only inside another room, … |
| Rooms inside rooms, levels | per dungeon | How many levels of rooms inside rooms (1 = only the dungeon's own spots). |
| Rooms per copy, at most | per dungeon | The most rooms in one run. |
| Spot % | per dungeon, per spot | Overrides the group's "filled %" for one spot. |

Change a room's groups with `/dungeon room group <room> <group>` (adds it, or removes it if it's already in).
`/dungeon room list` shows all groups and their rooms.

---

## 6. Rules, messages and rewards (panel)

Per dungeon on the panel's Dungeons page:

- **Lives**: 0 = out on the first death; otherwise back at the last checkpoint until they run out. Players who are
  out can watch as spectators.
- **Time limit** in minutes (0 = none).
- **Cleared when**: reaching an exit, or every boss dead.
- **Peaceful mobs fight**: cows, villagers, golems… attack players too (on by default).
- **Messages** on entering, while it's being prepared, when cleared and when failed. Placeholders: `{player}`,
  `{party}`, `{dungeon}`, `{time}`.
- **Commands** run once per player on entering and when leaving (without `/`, same placeholders).
- **Rewards**: on the panel's Rewards page, make a rule with one of the dungeon triggers. Each has a "Dungeon"
  field (empty = any dungeon) and a number:

  | Trigger | Who gets it | Number field | `{score}` |
  |---|---|---|---|
  | Clears a dungeon | the whole party | within … seconds (0 = any time) | the time |
  | Fails a dungeon | the whole party | – | the time |
  | Reaches a checkpoint in a dungeon | everyone still in | from checkpoint … of the run (0 = every one) | how many checkpoints so far |
  | Defeats a boss in a dungeon | everyone still in | from boss … of the run (0 = every one) | how many bosses so far |
  | Defeats every boss in a dungeon | everyone still in | within … seconds (0 = any time) | the time |

  "Everyone still in" means party members who aren't out (no lives left) or gone. A checkpoint counts once per
  run, also when players come back to it later. `{game}` is the dungeon's name.

Server-wide settings at the top of the Dungeons tab: copies at once, players per party, how fast copies are built
(pieces per tick). After a run, players can use a pad again after 1 second.

Inside a dungeon the market, inbox, pets, homes and Nice Actions teleports don't work.

---

## 7. Command list

| Command | Who | What |
|---|---|---|
| `/dungeon create <name>` / `/dungeon corner2` | admin | Mark the dungeon's box (first and opposite corner) |
| `/dungeon save <name>` | admin | Save the build (again after every change) |
| `/dungeon try <name>` | admin | Play it yourself without a pad |
| `/dungeon pool <name> <pool>` | admin | Move a dungeon to a pool |
| `/dungeon kit <name>` | admin | Make what you carry the dungeon's kit |
| `/dungeon delete <name>` | admin | Remove a dungeon (runs going on keep their copy) |
| `/dungeon tool marker <text>` | admin | A marker block (`start`, `exit`, `checkpoint`, `mob:…`, `boss:…`) |
| `/dungeon tool door <door>` | admin | A room door |
| `/dungeon tool spot <group> <door> [turns into]` | admin | A spot for random rooms |
| `/dungeon room create <name>` / `/dungeon corner2` | admin | Mark a room's box |
| `/dungeon room save <name> [group]` | admin | Save a room (again after changes) |
| `/dungeon room group <name> <group>` | admin | Add a room to a group, or take it out |
| `/dungeon room weight <name> <1–100>` | admin | How often a room is picked |
| `/dungeon room list` / `/dungeon room delete <name>` | admin | List or remove rooms |
| `/dungeon pad add <pool>` / `/dungeon pad remove` | admin | Add a pad here / remove the nearest |
| `/dungeon pad wait <pool> [radius]` / `… clear` | admin | Waiting area for a full pool |
| `/dungeon runs` | admin | Runs going on right now |
| `/dungeon list` | everyone | Pools, their dungeons and pads |
| `/dungeon top <name>` | everyone | Best times (solo, 2, 3, 4 players) |
| `/dungeon leave` | everyone | Leave the dungeon you're in |
| `/party invite <player>` | everyone | Build a party |
