package com.glamardor.flashbackinspector.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.glamardor.flashbackinspector.FlashbackInspector;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Everything the settings screen edits, and the only thing written to disk. */
public class InspectorConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static InspectorConfig instance;

	// --- recording ---

	/** Whether anything at all is written into new recordings. */
	public boolean recordInventory = true;
	/** Whether containers are written as well as the inventory. */
	public boolean recordContainers = true;
	/**
	 * How many ticks between two looks at the inventory.
	 *
	 * <p>Only the slots that changed are written, so the cost of looking more often is the looking,
	 * not the writing. One tick catches an item passing through a slot for a single tick, which is
	 * exactly what a fast shift-click looks like.
	 */
	public int scanIntervalTicks = 1;

	// --- viewing ---

	/** Whether the inventory key opens the inspector instead of the empty spectator inventory. */
	public boolean replaceInventoryKey = true;
	public ContainerMode containerMode = ContainerMode.MANUAL;
	/** A line above the hotbar while the recording has a container open. */
	public boolean showContainerHint = true;
	/** Pause the replay when the inspector opens, so the contents stop moving while being read. */
	public boolean pauseWhenOpened = true;
	/** Draw the replay timecode on the inspector, so a screenshot of it says when it was taken. */
	public boolean showTimecode = true;

	public static InspectorConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public void resetToDefaults() {
		InspectorConfig defaults = new InspectorConfig();
		this.recordInventory = defaults.recordInventory;
		this.recordContainers = defaults.recordContainers;
		this.scanIntervalTicks = defaults.scanIntervalTicks;
		this.replaceInventoryKey = defaults.replaceInventoryKey;
		this.containerMode = defaults.containerMode;
		this.showContainerHint = defaults.showContainerHint;
		this.pauseWhenOpened = defaults.pauseWhenOpened;
		this.showTimecode = defaults.showTimecode;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("flashbackinspector.json");
	}

	private static InspectorConfig load() {
		Path path = file();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				InspectorConfig loaded = GSON.fromJson(reader, InspectorConfig.class);
				if (loaded != null) {
					loaded.clamp();
					return loaded;
				}
			} catch (Exception e) {
				FlashbackInspector.LOGGER.warn("Could not read {}, starting from the defaults", path, e);
			}
		}
		return new InspectorConfig();
	}

	private void clamp() {
		this.scanIntervalTicks = Math.max(1, Math.min(20, this.scanIntervalTicks));
		if (this.containerMode == null) {
			this.containerMode = ContainerMode.MANUAL;
		}
	}

	public void save() {
		clamp();
		try (Writer writer = Files.newBufferedWriter(file())) {
			GSON.toJson(this, writer);
		} catch (IOException e) {
			FlashbackInspector.LOGGER.warn("Could not write the settings", e);
		}
	}
}
