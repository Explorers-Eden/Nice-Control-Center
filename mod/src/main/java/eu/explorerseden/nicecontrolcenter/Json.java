package eu.explorerseden.nicecontrolcenter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Shared Gson setup for the dashboard API, recordings and reports. */
public final class Json {
	public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();

	private Json() {
	}
}
