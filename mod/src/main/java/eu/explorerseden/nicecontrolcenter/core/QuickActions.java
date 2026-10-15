package eu.explorerseden.nicecontrolcenter.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Market;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Pets;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.nio.file.Path;

/**
 * The Nice Actions data pack's quick actions menu (the G key, dialog nice_actions:main) gets buttons for the
 * claim menu, pets and the market: added to its JSON while the server loads it, so the data pack itself stays
 * as it is. Only for what's switched on when the server starts (dialogs are sent to players as loaded).
 */
public final class QuickActions {
	private static final Identifier MENU = Identifier.fromNamespaceAndPath("nice_actions", "main");

	private QuickActions() {
	}

	/** From the registry loader: the dialog's JSON, with our buttons when it's the Nice Actions menu. */
	public static JsonElement patch(ResourceKey<?> key, JsonElement json) {
		if (!key.isFor(Registries.DIALOG) || !key.identifier().equals(MENU) || !(json instanceof JsonObject menu)
				|| !(menu.get("actions") instanceof JsonArray actions)) {
			return json;
		}
		try {
			Path config = FabricLoader.getInstance().getConfigDir();
			int added = 0;
			if (Claims.read(config.resolve(Claims.FILE)).enabled) added += add(actions, "Land Claims", "Claim land, see your claims and who may build there.", "claim");
			if (Pets.read(config.resolve(Pets.FILE)).enabled) added += add(actions, "Pets", "Your pets and the pet shop.", "pets");
			if (Market.read(config.resolve(Market.FILE)).enabled) added += add(actions, "Market", "Buy from other players and sell your own items.", "market");
			if (added > 0) NiceControlCenter.LOGGER.info("Added {} button(s) to the Nice Actions menu", added);
		} catch (RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Couldn't add buttons to the Nice Actions menu: {}", e.toString());
		}
		return json;
	}

	/** One button running the command as the player; none if the menu already has one for it. */
	private static int add(JsonArray actions, String label, String tooltip, String command) {
		for (JsonElement e : actions) {
			if (e instanceof JsonObject a && a.get("action") instanceof JsonObject act && act.has("command")
					&& act.get("command").getAsString().replaceFirst("^/", "").equals(command)) {
				return 0;
			}
		}
		JsonObject button = new JsonObject();
		JsonObject text = new JsonObject();
		text.addProperty("text", label);
		button.add("label", text);
		JsonObject tip = new JsonObject();
		tip.addProperty("text", tooltip);
		button.add("tooltip", tip);
		JsonObject action = new JsonObject();
		action.addProperty("type", "run_command");
		action.addProperty("command", command);
		button.add("action", action);
		actions.add(button);
		return 1;
	}
}
