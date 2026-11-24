package com.glamardor.flashbackinspector.hud;

import com.glamardor.flashbackinspector.config.ContainerMode;
import com.glamardor.flashbackinspector.config.InspectorConfig;
import com.glamardor.flashbackinspector.playback.InspectorState;
import com.moulberry.flashback.Flashback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A line saying a container was open on this tick, so the manual mode is discoverable without
 * having to guess when to press the key.
 */
public final class ContainerHint {
	private ContainerHint() {
	}

	public static void render(GuiGraphicsExtractor context, DeltaTracker tickCounter) {
		Minecraft client = Minecraft.getInstance();
		InspectorConfig config = InspectorConfig.get();
		if (!config.showContainerHint || config.containerMode == ContainerMode.FOLLOW) {
			return;
		}
		if (client.gui.screen() != null || client.gui.hud.isHidden() || !Flashback.isInReplay()) {
			return;
		}
		InspectorState state = InspectorState.get();
		if (!state.hasContainer()) {
			return;
		}

		Component line = Component.translatable("flashbackinspector.hint.container",
				state.containerTitle(), client.options.keyInventory.getTranslatedKeyMessage());
		int width = client.font.width(line);
		int x = (context.guiWidth() - width) / 2;
		int y = context.guiHeight() - 59;
		context.fill(x - 4, y - 3, x + width + 4, y + 11, 0x90000000);
		context.text(client.font, line, x, y, 0xFFE0C070);
	}
}
