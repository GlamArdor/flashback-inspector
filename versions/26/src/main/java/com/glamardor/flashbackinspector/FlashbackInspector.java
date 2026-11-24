package com.glamardor.flashbackinspector;

import com.glamardor.flashbackinspector.config.ContainerMode;
import com.glamardor.flashbackinspector.config.InspectorConfig;
import com.glamardor.flashbackinspector.hud.ContainerHint;
import com.glamardor.flashbackinspector.net.InspectorPayloads;
import com.glamardor.flashbackinspector.playback.InspectorState;
import com.glamardor.flashbackinspector.record.InspectorRecorder;
import com.glamardor.flashbackinspector.screen.ContainerLogScreen;
import com.glamardor.flashbackinspector.screen.InspectorScreen;
import com.mojang.blaze3d.platform.InputConstants;
import com.moulberry.flashback.Flashback;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlashbackInspector implements ClientModInitializer {
	public static final String MOD_ID = "flashbackinspector";
	public static final Logger LOGGER = LoggerFactory.getLogger("Flashback Inspector");

	public static KeyMapping logKey;

	/** True while the follow mode has a container up, so it can take it down again by itself. */
	private static boolean followOpened;
	private static boolean wasInReplay;

	@Override
	public void onInitializeClient() {
		PayloadTypeRegistry.clientboundPlay().register(InspectorPayloads.Snapshot.ID, InspectorPayloads.Snapshot.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(InspectorPayloads.Slots.ID, InspectorPayloads.Slots.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(InspectorPayloads.ContainerEvent.ID,
				InspectorPayloads.ContainerEvent.CODEC);

		// Only ever applied inside a replay. These payloads have no business arriving from a real
		// server, and a server that sent one would be telling the client what to believe about a
		// recording it is not making.
		ClientPlayNetworking.registerGlobalReceiver(InspectorPayloads.Snapshot.ID, (payload, context) ->
				context.client().execute(() -> {
					if (Flashback.isInReplay()) {
						InspectorState.get().applySnapshot(payload);
					}
				}));
		ClientPlayNetworking.registerGlobalReceiver(InspectorPayloads.Slots.ID, (payload, context) ->
				context.client().execute(() -> {
					if (Flashback.isInReplay()) {
						InspectorState.get().applySlots(payload);
					}
				}));
		ClientPlayNetworking.registerGlobalReceiver(InspectorPayloads.ContainerEvent.ID, (payload, context) ->
				context.client().execute(() -> {
					if (Flashback.isInReplay()) {
						InspectorState.get().applyContainerEvent(payload);
					}
				}));

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "container_hint"), ContainerHint::render);

		logKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.flashbackinspector.log", InputConstants.Type.KEYBOARD, InputConstants.KEY_K,
				KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"))));

		ClientTickEvents.END_CLIENT_TICK.register(FlashbackInspector::tick);
	}

	private static void tick(Minecraft client) {
		InspectorRecorder.get().tick(client);

		boolean inReplay = Flashback.isInReplay();
		if (!inReplay) {
			if (wasInReplay) {
				// Leaving a replay: the state belongs to the file that was open, not to the next one.
				InspectorState.get().clear();
				followOpened = false;
				wasInReplay = false;
			}
			// Drain the keybind so a press made outside a replay does not fire on the way into one.
			while (logKey != null && logKey.consumeClick()) {
				// discarded on purpose
			}
			return;
		}
		wasInReplay = true;
		InspectorState.get().tick();

		while (logKey != null && logKey.consumeClick()) {
			if (client.gui.screen() == null || client.gui.screen() instanceof InspectorScreen) {
				client.gui.setScreen(new ContainerLogScreen(null));
			}
		}

		if (InspectorConfig.get().containerMode == ContainerMode.FOLLOW) {
			followContainer(client);
		} else if (followOpened) {
			followOpened = false;
		}
	}

	/**
	 * Opens and closes the inspector on the same ticks the container opened and closed.
	 *
	 * <p>Done here rather than when the packet arrives, because Flashback puts back whatever screen
	 * was up before a custom payload was handled – a screen opened inside the receiver would be
	 * closed again before it was ever drawn.
	 */
	private static void followContainer(Minecraft client) {
		InspectorState state = InspectorState.get();
		if (state.hasContainer() && !followOpened) {
			if (client.gui.screen() == null) {
				AbstractClientPlayer target = InspectorScreen.defaultTarget();
				if (target != null) {
					client.gui.setScreen(InspectorScreen.followed(target));
					followOpened = true;
				}
			}
		} else if (!state.hasContainer() && followOpened) {
			followOpened = false;
			if (client.gui.screen() instanceof InspectorScreen) {
				client.gui.setScreen(null);
			}
		}
	}

	/** Whether the inventory key should hand out the inspector instead of an empty spectator bag. */
	public static boolean shouldReplaceInventoryKey() {
		return InspectorConfig.get().replaceInventoryKey && Flashback.isInReplay();
	}
}
