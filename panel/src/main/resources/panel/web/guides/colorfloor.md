# Colour floor

A 3×3 grid of coloured platforms. A colour is called, and that colour vanishes; players must be on another one. After a few colours the grid fills again, and every refill is faster. The last one standing wins.

## Set it up

1. `/game create colorfloor <name>` where players appear.
2. Stand where the grid's corner goes: `/game edit <name> grid`.
3. Optional: a play box with `corner1` / `corner2` (else it's around the grid).
4. An entrance: `/game edit <name> entrance add`.

After changing the platform size or gap: `/game edit <name> build`.

## Settings

- **Platforms**: wool or concrete (nine of the 16 colours, picked anew each time), size and gap.
- **Speed**: seconds before a colour vanishes, how much faster after each refill, the fastest, colours before the grid refills.
- **Mobs**: a mob from the pool every … colours, how many at most; peaceful ones attack too unless switched off.

## How quick rounds work

- Everyone who steps in during the **join window** (10 s by default) plays the same round, each for their own score.
- Nobody dies: a deadly hit knocks them out (their score still counts).
- Players who step in during a round are queued for the next one, in the spectator area when there is one.
- Their own things are put aside and given back afterwards (the **kit** option), or they play with their own gear.
- With two or more players the winner gets the winner commands and the Rewards trigger "Wins a round".
- Test it with `/game try <name>`: a round with only you.
