package com.glamardor.flashbackinspector.test;

import com.glamardor.flashbackinspector.playback.InspectorState;
import com.glamardor.flashbackinspector.screen.ContainerLogScreen;
import com.glamardor.flashbackinspector.screen.InspectorScreen;
import com.moulberry.flashback.Flashback;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens a replay in an ordinary client run and checks the inspector there. Game tests drive the
 * integrated server tick by tick, which Flashback's replay server does not survive, so playback is
 * checked here instead. Only active with -Dfi.driver.replay=&lt;zip&gt;.
 */
public final class ReplayDriver implements ClientModInitializer {
	private final List<String> lines = new ArrayList<>();
	private int failures;
	private int step;
	private int wait;
	private int idle;

	@Override
	public void onInitializeClient() {
		String replay = System.getProperty("fi.driver.replay");
		if (replay == null) {
			return;
		}
		lines.add("== Flashback Inspector 26.3, replay playback");
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			try {
				tick(mc, Path.of(replay));
			} catch (Throwable e) {
				check("driver crashed: " + e, false);
				finish(mc);
			}
		});
	}

	private void tick(Minecraft mc, Path replay) {
		if (wait > 0) {
			wait--;
			return;
		}
		switch (step) {
			case 0 -> {
				// the title screen, or the accessibility onboarding of a first start
				if (mc.level == null && mc.gui.overlay() == null && mc.gui.screen() != null && ++idle > 60) {
					Flashback.openReplayWorld(replay);
					step = 1;
					wait = 40;
				}
			}
			case 1 -> {
				if (Flashback.isInReplay() && mc.player != null && mc.level != null) {
					check("in the replay", true);
					step = 2;
					wait = 100;
				}
			}
			case 2 -> {
				InspectorState.seek(InspectorState.totalTicks() - 5);
				step = 3;
				wait = 80;
			}
			case 3 -> {
				check("inspector has recorded data", InspectorState.get().hasData());
				boolean sword = false;
				boolean emeralds = false;
				for (int i = 0; i < 41; i++) {
					ItemStack stack = InspectorState.get().inventory(i);
					sword |= stack.is(Items.DIAMOND_SWORD);
					emeralds |= stack.is(Items.EMERALD) && stack.getCount() == 13;
				}
				check("recorded inventory has the sword", sword);
				check("recorded inventory has 13 emeralds (not hotbar)", emeralds);
				check("the chest opening is in the log", InspectorState.get().hasOpenings());
				mc.gui.setScreen(new InventoryScreen(mc.player));
				step = 4;
				wait = 20;
			}
			case 4 -> {
				check("inventory screen replaced by the inspector: " + mc.gui.screen(), mc.gui.screen() instanceof InspectorScreen);
				shot(mc, "driver-inspector.png");
				step = 5;
				wait = 20;
			}
			case 5 -> {
				mc.gui.setScreen(new ContainerLogScreen(null));
				step = 6;
				wait = 20;
			}
			case 6 -> {
				shot(mc, "driver-container-log.png");
				step = 7;
				wait = 20;
			}
			case 7 -> finish(mc);
			default -> {
			}
		}
	}

	private void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, message -> {
		});
	}

	private void check(String what, boolean ok) {
		lines.add((ok ? "ok   " : "FAIL ") + what);
		if (!ok) {
			failures++;
		}
		write();
	}

	private void finish(Minecraft mc) {
		lines.add(failures == 0 ? "PASSED" : failures + " FAILED");
		write();
		step = 99;
		mc.stop();
	}

	private void write() {
		try {
			Files.write(Path.of("driver-test.log"), lines);
		} catch (IOException ignored) {
		}
	}
}
