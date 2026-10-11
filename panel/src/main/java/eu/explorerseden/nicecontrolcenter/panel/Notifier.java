package eu.explorerseden.nicecontrolcenter.panel;

import java.util.Map;

/** Alerts for admins (Discord's alert events): backups, tasks, trims, disk, memory. */
interface Notifier {
	Notifier NONE = (type, values) -> { };

	void alert(String type, Map<String, String> values);
}
