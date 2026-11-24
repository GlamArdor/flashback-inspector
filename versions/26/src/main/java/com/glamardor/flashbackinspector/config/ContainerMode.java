package com.glamardor.flashbackinspector.config;

import net.minecraft.network.chat.Component;

/** What the inspector does when the recording reaches a container the player had open. */
public enum ContainerMode {
	/**
	 * Nothing opens by itself. A hint appears, and the container sits in the inspector next to the
	 * inventory when the inventory key is pressed.
	 *
	 * <p>The default, because any open screen takes the mouse away from Flashback's editor: while
	 * one is up the editor stops accepting clicks, and pressing play closes it again. Fine when the
	 * point is to look at something, in the way when the point is to move a camera.
	 */
	MANUAL,
	/** The container opens and closes on its own, on the ticks it did during the recording. */
	FOLLOW;

	public Component getDisplayName() {
		return Component.translatable("flashbackinspector.container_mode." + name().toLowerCase(java.util.Locale.ROOT));
	}

	public ContainerMode next() {
		return this == MANUAL ? FOLLOW : MANUAL;
	}
}
