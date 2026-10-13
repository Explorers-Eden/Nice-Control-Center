# Claims: in-game test checklist

Test on an offline-mode server with two accounts:
- **A** owns the claims.
- **B** is another player, with no role unless a step gives one.

Before you start, in the panel:
- Switch claims on (**Claims → Settings → Claims on**).
- Allow the Overworld.
- Set a permanent area under **World → Borders & reset**.

## Claim mode and the tool

- [ ] `/claim mode` with something in your hand: you get the claim tool (a golden hoe that glows), and your item moves to a free slot.
- [ ] Same with a full inventory: the item is kept, and comes back when you run `/claim mode` again.
- [ ] Each of these ends claim mode and takes the tool away:
  - [ ] dropping it (Q)
  - [ ] putting it in a chest, then closing the chest
  - [ ] putting it in a bundle (shouldn't be possible)
  - [ ] putting it in an item frame or on an armor stand (shouldn't be possible)
  - [ ] dying (the tool doesn't drop with your items)
  - [ ] logging out (on the next join you don't have it, and a kept item is back)
- [ ] The "Enter / Leave claim mode" button in the `/claim` menu does the same as `/claim mode`.
- [ ] The action bar shows "Claim mode · N claim blocks · /claim mode to leave".
- [ ] An ordinary golden hoe does nothing special.

## Claiming

- [ ] Right-click a block, then the opposite corner: the claim is made, and the "Your new claim" dialog (name and colour) opens.
- [ ] Each of these is refused with a clear message:
  - [ ] too small (under 5 wide or 100 blocks)
  - [ ] not enough claim blocks
  - [ ] overlapping another claim
  - [ ] a corner outside the permanent area
  - [ ] in a dimension that isn't allowed (Nether, if you didn't tick it)
- [ ] Right-click the first corner again: the selection is cancelled.
- [ ] Right-click a corner of your claim, then somewhere else: the claim is resized, and your claim blocks go up or down.
- [ ] Clicking a block far away (up to 100 blocks) works too.
- [ ] Sneak + right-click: the claim menu for the claim you're in, or "Your claims" outside one.
- [ ] Border blocks only you see:
  - glowstone at the corners;
  - concrete in the claim's colour every 10 blocks along the edges;
  - a diamond block for the first corner;
  - in caves, they show near your height.
- [ ] The border blocks go away after 30 seconds, or when you leave claim mode. Breaking or clicking them shows the real block.

## The `/claim` menu

- [ ] **Outside a claim:** your claim blocks (from playing, bought, bonus, used), the claim mode button and a list of your claims.
- [ ] **Inside a claim:** owner, size, your role, and what everyone else may do.
- [ ] **Members:**
  - [ ] add B from the online list
  - [ ] add B by typing the name
  - [ ] change B's role
  - [ ] remove B
  - [ ] B gets a chat message when added
- [ ] **Everyone else:** switch each option, save, and check the claim info shows it.
- [ ] **Name & colour:** a preset colour; your own `#hex`; an invalid hex (shows an error); a name longer than 32 characters (refused).
- [ ] **Subclaims:** "Make a subclaim", then two clicks inside the claim. Give B a higher role in the subclaim only, and check B can do more there than in the rest of the claim.
- [ ] **Delete a subclaim** and **abandon a claim:** both ask first. Abandoning gives the claim blocks back.
- [ ] **Shop** (switch it on in the panel first):
  - [ ] buy with enough of the item
  - [ ] try without enough (refused)
  - [ ] buy up to the limit you set (then refused)
- [ ] **Commands:** `/claim list`, `/claim trust B builder`, `/claim untrust B`, `/claim abandon` (asks first).

## Protection: B with no role

B must **not** be able to:
- [ ] break or place blocks; place against a block of the claim from outside
- [ ] use buckets (fill or empty) in the claim
- [ ] open chests, barrels, furnaces or other storage
- [ ] open a chest minecart, hopper minecart or chest boat (riding a minecart or boat is fine)
- [ ] use doors, trapdoors, gates, buttons or levers
- [ ] trade with villagers, or use animals (feed, shear, milk, leash)
- [ ] use or break armor stands and item frames
- [ ] hurt animals or villagers, also with arrows
- [ ] trample farmland
- [ ] press a wooden button or set off a target block by shooting it
- [ ] light TNT with a flame arrow
- [ ] hit A (PvP off)

Also check:
- [ ] Each refusal shows "this land belongs to …" in the action bar, and briefly shows the border.
- [ ] No ghost blocks or ghost items stay behind after a refusal.
- [ ] Monsters can still be fought in the claim.

Then give B each role in turn, and check B can do exactly what it allows:
- [ ] **Visitor:** doors, buttons, villager trading
- [ ] **Containers:** also chests, chest minecarts, animals, villagers, interaction entities
- [ ] **Builder:** also building, buckets, frames, armor stands
- [ ] **Co-owner:** also the menu (members below co-owner, settings, resizing, subclaims), but not abandoning

Switch "Everyone may open chests and interact with entities" on: B (no role) can now open chests and trade.

As an operator, `/claim admin bypass`: you can do everything everywhere and claim anywhere.

## The world (stand near the claim yourself)

- [ ] TNT and creepers next to the claim don't break blocks inside, and don't hurt animals, frames or armor stands inside.
- [ ] Water and lava placed outside don't flow into the claim.
- [ ] A piston outside can't push blocks into the claim.
- [ ] Fire next to the claim doesn't spread into it; lava next to it doesn't set things inside on fire.
- [ ] Endermen don't take or place blocks in the claim.
- [ ] A zombie on hard difficulty doesn't break a door in the claim.
- [ ] A ravager or the wither doesn't break blocks in the claim.
- [ ] With "Explosions" / "Fire" / "Mobs change blocks" switched on, each of these works again.

## Claim blocks and playtime

- [ ] Blocks go up every minute of play (100 per hour by default).
- [ ] They stop while you're AFK (after 5 minutes by default).
- [ ] **Claims → Claim blocks** in the panel: changing a player's bonus shows up in game in `/claim list`.

## Panel, map and info page

- [ ] **Claims list:** edit name, colour, corners, owner, members and switches with the server running, and again with it stopped.
- [ ] **Server claim:** make one and delete it.
- [ ] **Map:** claims show on the web map in their colour (subclaims dashed) within about 15 seconds, also while the server is paused with nobody online.
- [ ] **Public info page:** shows "Land claims" and "World borders".
- [ ] **GOML import:** on a copy of the live world with GOML installed, run Check, then Import. Owners, co-owners and trusted players are carried over, and owners got claim blocks for the land.

## Merl (with Merl 1.6.2)

- [ ] `/merl where's my claim`, `/merl where's Steve's claim` and `/merl my farm claim` (by name) find the claim, and **[Guide me]** leads there.
- [ ] With no claim: Merl suggests `/claim mode`.
- [ ] `/merl how many claim blocks do I get`, `/merl how big is the permanent area` and `/merl how long until junk items despawn` answer from the server's settings.
