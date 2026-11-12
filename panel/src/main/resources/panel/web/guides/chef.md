# Cauldron chef

The cook NPC orders food. Players take ingredients from the supply chests, craft or cook them in the kitchen, and right-click the cook to serve. The most orders served wins.

## Set it up

1. Make an NPC for the cook (Community → NPCs) and stand it in the kitchen.
2. `/game create chef <name>` where players appear.
3. The kitchen: `/game edit <name> corner1` and `corner2`.
4. Fill chests with ingredients and `/game edit <name> station add` looking at each (`station remove`, `station clear`). They're filled with that again at each round and every 30 seconds.
5. Put crafting tables, furnaces and smokers in the kitchen; players may use everything in it whatever the claim says.
6. Pick the cook on the card, and an entrance: `/game edit <name> entrance add`.

## Settings

- **Orders**: one per line, `items = points`, items as `bread x2, cookie x4 = 5`; picked at random. Served within 20 seconds: +1.
- **Seconds before the next order**.

Their own things are always put aside.

## How quick rounds work

- Everyone who steps in during the **join window** (10 s by default) plays the same round, each for their own score.
- Nobody dies: a deadly hit knocks them out (their score still counts).
- Players who step in during a round are queued for the next one, in the spectator area when there is one.
- Their own things are put aside and given back afterwards (the **kit** option), or they play with their own gear.
- With two or more players the winner gets the winner commands and the Rewards trigger "Wins a round".
- Test it with `/game try <name>`: a round with only you.
