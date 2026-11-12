# Treasure grab

Glowing treasure appears on the floor of the box. Players grab as many points as they can before time's up.

## Set it up

1. `/game create treasure <name>` where players appear.
2. The box: `/game edit <name> corner1` and `corner2`.
3. An entrance: `/game edit <name> entrance add`.

## Settings

- **Treasure**: one per line, `item=points`, e.g. `minecraft:diamond=5`. The more it's worth, the rarer.
- **Treasure lying around at once**, round length.

## How quick rounds work

- Everyone who steps in during the **join window** (10 s by default) plays the same round, each for their own score.
- Nobody dies: a deadly hit knocks them out (their score still counts).
- Players who step in during a round are queued for the next one, in the spectator area when there is one.
- Their own things are put aside and given back afterwards (the **kit** option), or they play with their own gear.
- With two or more players the winner gets the winner commands and the Rewards trigger "Wins a round".
- Test it with `/game try <name>`: a round with only you.
