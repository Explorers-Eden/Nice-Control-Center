# Anvil rain

Anvils fall from the top of the box, more and more of them. Players dodge for as long as they can; the last one standing wins. The score is how long they lasted.

## Set it up

1. `/game create anvils <name>` where players appear.
2. The box: `/game edit <name> corner1` and `corner2`; anvils fall from its top.
3. An entrance: `/game edit <name> entrance add`.

## Settings

- **Anvils a second** at the start and **% more every 10 seconds**.
- **Right above a player**: the share of anvils aimed at someone (70 % by default); the rest fall anywhere.
- Longest round (0 = until the last one is out).

> Anvils land without a sound, and anvil rain keeps hunger and saturation full.

## How quick rounds work

- Everyone who steps in during the **join window** (10 s by default) plays the same round, each for their own score.
- Nobody dies: a deadly hit knocks them out (their score still counts).
- Players who step in during a round are queued for the next one, in the spectator area when there is one.
- Their own things are put aside and given back afterwards (the **kit** option), or they play with their own gear.
- With two or more players the winner gets the winner commands and the Rewards trigger "Wins a round".
- Test it with `/game try <name>`: a round with only you.
