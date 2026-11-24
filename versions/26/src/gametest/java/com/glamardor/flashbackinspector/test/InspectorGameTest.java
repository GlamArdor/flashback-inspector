package com.glamardor.flashbackinspector.test;

import com.mojang.blaze3d.platform.InputConstants;
import com.moulberry.flashback.Flashback;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Records a short session with Flashback and checks that the inspector's data went into it. */
public final class InspectorGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext ctx) {
		TestLog log = new TestLog("Flashback Inspector 26.3");
		log.startup(ctx);
		Path replay = null;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("gamemode survival @a");
			sp.getServer().runCommand("tp @a 0.5 -60 0.5 0 0");
			sp.getServer().runCommand("setblock 1 -60 3 minecraft:chest{Items:[{Slot:4b,id:\"minecraft:golden_apple\",count:7}]}");
			ctx.waitTicks(20);

			ctx.runOnClient(mc -> Flashback.startRecordingReplay());
			ctx.waitTicks(40);
			log.check("recording", ctx.computeOnClient(mc -> Flashback.RECORDER != null));
			// things in the inventory, then later more of them, then a chest opened and emptied a little
			sp.getServer().runCommand("give @a minecraft:diamond_sword");
			sp.getServer().runCommand("give @a minecraft:cobblestone 40");
			sp.getServer().runCommand("item replace entity @a inventory.10 with minecraft:emerald 13");
			ctx.waitTicks(40);
			ctx.getInput().lookAt(new BlockPos(1, -60, 3));
			ctx.waitTicks(5);
			ctx.getInput().pressKey(options -> options.keyUse);
			ctx.waitForScreen(ContainerScreen.class);
			ctx.waitTicks(30);
			ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
			ctx.waitForScreen(null);
			ctx.waitTicks(60);
			Path folder = ctx.computeOnClient(mc -> Flashback.getReplayFolder());
			long before = System.currentTimeMillis();
			ctx.runOnClient(mc -> Flashback.finishRecordingReplay());
			ctx.waitTicks(20);
			log.info("after finishing: screen " + ctx.computeOnClient(mc -> String.valueOf(mc.gui.screen())));
			ctx.takeScreenshot("inspector-after-finish");
			// Flashback asks for a name; take its suggestion
			ctx.runOnClient(mc -> {
				for (var child : mc.gui.screen().children()) {
					if (child instanceof net.minecraft.client.gui.components.Button button
							&& button.getMessage().getString().equals("Save Replay")) {
						button.onPress(new net.minecraft.client.input.MouseButtonEvent(0, 0,
								new net.minecraft.client.input.MouseButtonInfo(0, 0)));
					}
				}
			});
			ctx.waitFor(mc -> newestReplay(folder, before) != null, 400);
			replay = newestReplay(folder, before);
			log.check("replay written: " + replay, replay != null);
		} catch (Throwable e) {
			log.fail("recording crashed: " + e);
			e.printStackTrace();
		}

		// Playback is checked by ReplayDriver in an ordinary client (./gradlew runReplayDriver -Preplay=<zip>):
		// the game test harness steps the integrated server itself, which the replay server does not survive.
		if (replay != null) {
			try (var zip = new java.util.zip.ZipFile(replay.toFile())) {
				String stream = new String(zip.getInputStream(zip.getEntry("c0.flashback")).readAllBytes(),
						java.nio.charset.StandardCharsets.ISO_8859_1);
				log.check("snapshot of the inventory in the recording", stream.contains("flashbackinspector:snapshot"));
				log.check("slot changes in the recording", stream.contains("flashbackinspector:slots"));
				log.check("chest opening in the recording", stream.contains("flashbackinspector:container"));
			} catch (Exception e) {
				log.fail("reading the recording: " + e);
			}
		}
		log.finish();
	}

	private static Path newestReplay(Path folder, long after) {
		if (!Files.isDirectory(folder)) {
			return null;
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter(p -> p.toString().endsWith(".zip"))
					.filter(p -> p.toFile().lastModified() >= after - 2000)
					.max(Comparator.comparingLong(p -> p.toFile().lastModified()))
					.orElse(null);
		} catch (Exception e) {
			return null;
		}
	}
}
