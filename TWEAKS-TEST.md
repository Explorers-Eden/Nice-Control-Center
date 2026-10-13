# Tweaks and market: in-game test checklist

Test with mod 1.10.0 and panel 1.9.0, on a test server with two accounts:
- **A** sells and fells.
- **B** buys.

Switch each tweak on under **Tweaks** first.

Already tested on the test server (no player needed):
- Portals in any shape: they light, a broken frame takes them down, an open frame doesn't light, and rectangles still work.
- Market listings: they show, a listing can be taken off to the mailbox, the database copy works.
- Item icons, including the resource pack's custom models.

## Anvil (Tweaks → Gameplay)

- [ ] Combine two heavily worked items that would cost 40+ levels. The anvil shows **39** (or your limit), not "Too Expensive!", and you can take the result.
- [ ] Rename an item that went through the anvil many times: it costs **1** level (or your renaming cost).
- [ ] With both switches off, vanilla behaviour is back.

## Nether portals (Tweaks → Gameplay)

- [ ] **Shapes:** a circle and an arch of obsidian light with flint and steel, on the floor of the frame and against its side.
- [ ] **Travel:** going through such a portal works both ways, and the exit portal in the Nether is a normal one.
- [ ] **Breaking the frame:** breaking one obsidian block takes the whole portal down.
- [ ] **Size:** a frame bigger than "Biggest portal" doesn't light.

## Tree felling (Tweaks → Trees & veins; remove the FallingTree mod)

- [ ] **Normal tree:** break one log with an axe and the whole tree comes down, from the top.
- [ ] **Sneaking** breaks just the one log.
- [ ] **Axe wear:** the axe loses one durability per log. With an almost-broken axe it stops before breaking, with a message.
- [ ] **Leaves** fall within a few seconds.
- [ ] **Player builds:** a wall of placed logs without leaves around only loses the log you broke.
- [ ] **Different trees:**
  - big trees: dark oak, jungle 2×2;
  - crimson/warped (wart blocks and shroomlights go too);
  - a mangrove (its roots go too);
  - cherry, azalea.
- [ ] **One log per hit:** with "One log per hit, from the top", each hit removes the top log and the tree sinks.
- [ ] **Drops where the first log was:** with that switched on, everything drops at the first log.
- [ ] **Claims:** in someone else's claim, felling doesn't break their logs, and you get the claim message.
- [ ] **Creative:** in creative it only fells with "Also in creative mode" switched on.

## Vein mining (Tweaks → Trees & veins)

- [ ] **Normal vein:** mine one ore with a pickaxe and the whole vein comes out. Iron and deepslate iron count as one vein.
- [ ] **Sneaking** mines just one block.
- [ ] **Enchantments:** Fortune and Silk Touch apply to every block; you get XP from coal, redstone, lapis…
- [ ] **Size limit:** a vein bigger than "Biggest vein" stops at the limit.

## AFK and tab list (Tweaks → Gameplay)

- [ ] **AFK tag:** stand still for the AFK minutes. "AFK" appears after your name in the tab list, and **your Fabled Roots race colour/prefix stays**.
- [ ] **Coming back:** move or look around, and the tag goes within a second or two.
- [ ] **Other players:** B sees A's tag too.
- [ ] **Header/footer:** they show; `{players}`, `{tps}`, `{ping}` and `{time}` fill in.
- [ ] **Switching off:** with the header/footer off, they disappear.

## Market (Tweaks → Market, currency diamonds)

- [ ] **Selling more than a stack:** A runs `/market` → Sell → picks cobblestone, 200 at 1 diamond each. All 200 leave A's inventory (several stacks).
- [ ] **Selling a special item:** A sells a renamed, enchanted item and an item with a custom model. They show correctly in the dialog (icon, name, glint) and on the public info page.
- [ ] **Browsing:** B runs `/market` → Browse. Search works; "Cheapest first", "Newest first" and pages work.
- [ ] **Buying part of a listing:** B opens the cobblestone listing and buys 70.
  - B's diamonds go down by 70, and B gets 70 cobblestone.
  - A gets a chat message.
  - 130 are left in the listing.
- [ ] **Not enough money:** buying more than you can pay shows "You need …"; buying more than is left shows "Only … left".
- [ ] **Full inventory:** B buys with a full inventory. What doesn't fit goes to B's mailbox, and Collect gives it later.
- [ ] **Earnings:** A's Mailbox shows the diamonds earned, and Collect gives them.
- [ ] **Taking it back:** A → My listings → the listing → Take it back, and the items arrive in the mailbox.
- [ ] **Expiry:** set "Listings last" to 1 day and wait a day, or with the server off lower a listing's `expires` in `world/nicecontrolcenter-market.json`. Within a minute of the next start, unsold items come back to the mailbox, and A is told on join.
- [ ] **Limits:** listings per player, the highest price, and banned items are refused with a message.
- [ ] **Panel:** Tweaks → Market shows everything with icons; Take off works; recent sales appear.
- [ ] **Public info page:** the Market tab lists what's for sale.

## Merl

- [ ] `/merl how does tree felling work`, `/merl is there a market` and `/merl how big can a portal be` answer from the server's settings.
