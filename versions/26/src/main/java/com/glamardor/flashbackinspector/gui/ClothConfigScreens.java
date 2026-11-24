package com.glamardor.flashbackinspector.gui;

import com.glamardor.flashbackinspector.config.ContainerMode;
import com.glamardor.flashbackinspector.config.InspectorConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Cloth Config version of the settings screen. Loaded reflectively-by-classloading only when Cloth
 * is installed – never touch this class without checking first.
 */
final class ClothConfigScreens {
	private ClothConfigScreens() {
	}

	static Screen build(@Nullable Screen parent) {
		InspectorConfig config = InspectorConfig.get();
		InspectorConfig defaults = new InspectorConfig();

		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.translatable("flashbackinspector.config.title"))
				.setSavingRunnable(config::save);
		// The replay carries on behind the settings, and half of what is being set here is about
		// what shows up on top of it.
		builder.setTransparentBackground(true);
		// One long list with the categories down the side, rather than tabs. Tabbed, the search box
		// only ever looks inside the tab you are standing in, so finding a setting means knowing
		// which tab it lives in first – which is exactly what a search is for.
		builder.setGlobalized(true);
		builder.setGlobalizedExpanded(true);

		ConfigEntryBuilder entries = builder.entryBuilder();

		ConfigCategory recording = builder.getOrCreateCategory(
				Component.translatable("flashbackinspector.category.recording"));
		recording.addEntry(toggle(entries, "record_inventory", config.recordInventory,
				defaults.recordInventory, value -> config.recordInventory = value));
		recording.addEntry(toggle(entries, "record_containers", config.recordContainers,
				defaults.recordContainers, value -> config.recordContainers = value));
		recording.addEntry(entries.startIntSlider(text("scan_interval"), config.scanIntervalTicks, 1, 20)
				.setDefaultValue(defaults.scanIntervalTicks)
				.setTooltip(tooltip("scan_interval"))
				.setTextGetter(value -> Component.translatable("flashbackinspector.unit.ticks", value))
				.setSaveConsumer(value -> config.scanIntervalTicks = value)
				.build());

		ConfigCategory viewing = builder.getOrCreateCategory(
				Component.translatable("flashbackinspector.category.viewing"));
		viewing.addEntry(toggle(entries, "replace_inventory_key", config.replaceInventoryKey,
				defaults.replaceInventoryKey, value -> config.replaceInventoryKey = value));
		viewing.addEntry(entries.startEnumSelector(text("container_mode"), ContainerMode.class,
						config.containerMode)
				.setDefaultValue(defaults.containerMode)
				.setEnumNameProvider(value -> ((ContainerMode) value).getDisplayName())
				.setTooltip(tooltip("container_mode"))
				.setSaveConsumer(value -> config.containerMode = value)
				.build());
		viewing.addEntry(toggle(entries, "container_hint", config.showContainerHint,
				defaults.showContainerHint, value -> config.showContainerHint = value));
		viewing.addEntry(toggle(entries, "pause_when_opened", config.pauseWhenOpened,
				defaults.pauseWhenOpened, value -> config.pauseWhenOpened = value));
		viewing.addEntry(toggle(entries, "show_timecode", config.showTimecode,
				defaults.showTimecode, value -> config.showTimecode = value));

		return builder.build();
	}

	private static me.shedaniel.clothconfig2.gui.entries.BooleanListEntry toggle(ConfigEntryBuilder entries,
			String key, boolean current, boolean fallback, Consumer<Boolean> setter) {
		return entries.startBooleanToggle(text(key), current)
				.setDefaultValue(fallback)
				.setTooltip(tooltip(key))
				.setSaveConsumer(setter)
				.build();
	}

	private static Component text(String key) {
		return Component.translatable("flashbackinspector.option." + key);
	}

	private static Component[] tooltip(String key) {
		return new Component[] { Component.translatable("flashbackinspector.option." + key + ".tooltip") };
	}
}
