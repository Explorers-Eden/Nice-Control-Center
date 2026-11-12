# Whack-a-mole

Heads pop out of a grid of holes for a moment. Hitting or right-clicking one scores it; some cost points.

## Set it up

1. `/game create moles <name>` where players appear.
2. Stand where the grid's corner goes: `/game edit <name> grid` (lays the holes toward +X and +Z). Again after changing the size.
3. An entrance: `/game edit <name> entrance add`.

No box needed: it's around the grid. Players appear in the middle of the grid, on top of it.

## Settings

- **Holes per side** and **blocks between holes**.
- **Hole block**: a redstone lamp or copper bulb that lights up while its head is up.
- **Seconds a head stays up** (down to a third by the end), **heads up at once**.
- **Mace** in the kit: no damage, swings like a fist.
- **Heads**: one per line, `head=points`: a mob head (`minecraft:zombie_head=1`) or a player head's skin as a URL, hash or base64 value. Below 0 costs points, e.g. `minecraft:wither_skeleton_skull=-3`.

## How quick rounds work

- Everyone who steps in during the **join window** (10 s by default) plays the same round, each for their own score.
- Nobody dies: a deadly hit knocks them out (their score still counts).
- Players who step in during a round are queued for the next one, in the spectator area when there is one.
- Their own things are put aside and given back afterwards (the **kit** option), or they play with their own gear.
- With two or more players the winner gets the winner commands and the Rewards trigger "Wins a round".
- Test it with `/game try <name>`: a round with only you.
