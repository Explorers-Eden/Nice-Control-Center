package eu.explorerseden.nicecontrolcenter.claims;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Claims.Claim;
import eu.explorerseden.nicecontrolcenter.Claims.Role;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;

import java.util.Locale;
import eu.explorerseden.nicecontrolcenter.core.Plop;

/**
 * /claim: the claim menu. Everything players need is in the menu; the subcommands are shortcuts.
 * /claim admin is for operators (or nicecontrolcenter.claims.admin).
 */
public final class ClaimCommand {
	public static final String PERMISSION_USE = "nicecontrolcenter.claims.use";
	public static final String PERMISSION_ADMIN = "nicecontrolcenter.claims.admin";

	private ClaimCommand() {
	}

	private static final SuggestionProvider<CommandSourceStack> ONLINE = (ctx, b) -> {
		for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) b.suggest(p.getPlainTextName());
		return b.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> ROLES = (ctx, b) -> {
		for (Role r : Role.values()) if (r != Role.OWNER) b.suggest(r.label.toLowerCase(Locale.ROOT).replace("-", ""));
		return b.buildFuture();
	};

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("claim")
				.requires(eu.explorerseden.nicecontrolcenter.command.Everyone.unless(PERMISSION_USE))
				.executes(ctx -> player(ctx, p -> ClaimDialogs.openAt(p, p.blockPosition())))
				.then(Commands.literal("mode").executes(ctx -> player(ctx, ClaimMode::toggle)))
				.then(Commands.literal("list").executes(ctx -> player(ctx, ClaimCommand::list)))
				.then(Commands.literal("trust")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(ONLINE)
								.executes(ctx -> trust(ctx, Role.BUILD))
								.then(Commands.argument("role", StringArgumentType.word()).suggests(ROLES)
										.executes(ctx -> trust(ctx, Role.parse(StringArgumentType.getString(ctx, "role")))))))
				.then(Commands.literal("untrust")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(ONLINE).executes(ClaimCommand::untrust)))
				.then(Commands.literal("abandon").executes(ctx -> player(ctx, p -> {
					Claim c = ClaimStore.claimAt(p.level(), p.blockPosition());
					if (c == null) ClaimTool.tell(p, "Stand in the claim you want to abandon.", ChatFormatting.RED);
					else ClaimDialogs.handle(p, "abandon", java.util.Optional.of(tag(c)));
				})))
				.then(Commands.literal("admin")
						.requires(Permissions.require(PERMISSION_ADMIN, PermissionLevel.GAMEMASTERS))
						.then(Commands.literal("bypass").executes(ctx -> {
							ServerPlayer p = ctx.getSource().getPlayerOrException();
							boolean on = ClaimTool.toggleBypass(p);
							ClaimTool.tell(p, on ? "Bypass on: claims don't stop you, and you can change every claim." : "Bypass off.", ChatFormatting.GOLD);
							return 1;
						}))
						.then(Commands.literal("delete").executes(ctx -> {
							ServerPlayer p = ctx.getSource().getPlayerOrException();
							Claim c = ClaimStore.claimAt(p.level(), p.blockPosition());
							if (c == null) {
								ClaimTool.tell(p, "There's no claim here.", ChatFormatting.RED);
								return 0;
							}
							ClaimStore.data().delete(c);
							ClaimStore.changed();
							ClaimTool.tell(p, "Deleted " + c.displayName() + " (" + (c.ownerName == null ? "server" : c.ownerName) + ").", ChatFormatting.GOLD);
							return 1;
						}))));
	}

	private static net.minecraft.nbt.CompoundTag tag(Claim c) {
		net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
		t.putString("claim", c.id);
		return t;
	}

	private static int player(CommandContext<CommandSourceStack> ctx, java.util.function.Consumer<ServerPlayer> run) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		if (!ClaimStore.enabled()) {
			ClaimTool.tell(p, "Claims are off on this server.", ChatFormatting.RED);
			return 0;
		}
		run.accept(p);
		return 1;
	}

	private static void list(ServerPlayer p) {
		Claims.Data data = ClaimStore.data();
		String me = p.getStringUUID();
		var mine = data.ownedBy(me);
		p.sendSystemMessage(Component.literal(mine.size() + (mine.size() == 1 ? " claim · " : " claims · ") + data.available(me) + " claim blocks available").withStyle(ChatFormatting.GOLD));
		Plop.play(p);
		for (Claim c : mine) {
			p.sendSystemMessage(Component.literal("■ ").withColor(c.color).append(Component.literal(c.displayName() + " · " + c.box().width() + " × " + c.box().length()
					+ " at " + (c.minX + c.maxX) / 2 + ", " + (c.minZ + c.maxZ) / 2 + " (" + c.dim.replace("minecraft:", "") + ")").withStyle(ChatFormatting.GRAY)));
			Plop.play(p);
		}
	}

	private static NameAndId find(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "player");
		var server = ctx.getSource().getServer();
		ServerPlayer online = server.getPlayerList().getPlayerByName(name);
		if (online != null) return online.nameAndId();
		return server.services().nameToIdCache().get(name).orElse(null);
	}

	private static Claim managedHere(ServerPlayer p) {
		Claim c = ClaimStore.claimAt(p.level(), p.blockPosition());
		if (c == null) {
			ClaimTool.tell(p, "Stand in your claim first.", ChatFormatting.RED);
			return null;
		}
		if (!ClaimTool.can(p, c, Role.MANAGE)) {
			ClaimTool.tell(p, "Only the owner and co-owners can change " + c.displayName() + ".", ChatFormatting.RED);
			return null;
		}
		return c;
	}

	private static int trust(CommandContext<CommandSourceStack> ctx, Role role) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		if (!ClaimStore.enabled()) return 0;
		Claim c = managedHere(p);
		if (c == null) return 0;
		if (role == null || role == Role.OWNER) {
			ClaimTool.tell(p, "Roles: visitor, containers, builder, coowner.", ChatFormatting.RED);
			return 0;
		}
		if (role == Role.MANAGE && ClaimTool.role(p, c) != Role.OWNER) {
			ClaimTool.tell(p, "Only the owner can add co-owners.", ChatFormatting.RED);
			return 0;
		}
		NameAndId who = find(ctx);
		if (who == null) {
			ClaimTool.tell(p, "Nobody called \"" + StringArgumentType.getString(ctx, "player") + "\" has played here.", ChatFormatting.RED);
			return 0;
		}
		String err = ClaimStore.data().setMember(c, who.id().toString(), who.name(), role, System.currentTimeMillis());
		if (err != null) {
			ClaimTool.tell(p, err, ChatFormatting.RED);
			return 0;
		}
		ClaimStore.changed();
		ClaimTool.tell(p, who.name() + " is now " + role.label.toLowerCase(Locale.ROOT) + " in " + c.displayName() + ".", ChatFormatting.GREEN);
		return 1;
	}

	private static int untrust(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		if (!ClaimStore.enabled()) return 0;
		Claim c = managedHere(p);
		if (c == null) return 0;
		NameAndId who = find(ctx);
		Claims.Member m = who == null ? null : c.members.get(who.id().toString());
		if (m == null) {
			ClaimTool.tell(p, StringArgumentType.getString(ctx, "player") + " isn't a member here.", ChatFormatting.RED);
			return 0;
		}
		if (m.role == Role.MANAGE && ClaimTool.role(p, c) != Role.OWNER) {
			ClaimTool.tell(p, "Only the owner can remove co-owners.", ChatFormatting.RED);
			return 0;
		}
		ClaimStore.data().removeMember(c, who.id().toString(), System.currentTimeMillis());
		ClaimStore.changed();
		ClaimTool.tell(p, m.name + " is no longer a member of " + c.displayName() + ".", ChatFormatting.GRAY);
		return 1;
	}

}
