package com.glamardor.flashbackinspector.gui;

import com.glamardor.flashbackinspector.config.InspectorConfig;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The settings screen used when Cloth Config is not installed. Plain vanilla widgets, one scrolling
 * list, same options as the Cloth version.
 */
public class FallbackConfigScreen extends Screen {
	private static final int ROW_WIDTH = 310;

	@Nullable
	private final Screen parent;
	private final InspectorConfig config = InspectorConfig.get();

	public FallbackConfigScreen(@Nullable Screen parent) {
		super(Component.translatable("flashbackinspector.config.title"));
		this.parent = parent;
	}

	/** Leaves the replay visible behind the settings, the way the Cloth screen does. */
	@Override
	public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		if (this.minecraft != null && this.minecraft.level != null) {
			return;
		}
		super.extractBackground(context, mouseX, mouseY, delta);
	}

	@Override
	protected void init() {
		OptionList list = new OptionList(this.minecraft, this.width, this.height - 96, 40, 25);

		list.addHeader(Component.translatable("flashbackinspector.category.recording"));
		list.addWidget(toggle("record_inventory", () -> config.recordInventory,
				value -> config.recordInventory = value));
		list.addWidget(toggle("record_containers", () -> config.recordContainers,
				value -> config.recordContainers = value));
		list.addWidget(new TicksSlider("scan_interval", config.scanIntervalTicks,
				value -> config.scanIntervalTicks = value));

		list.addHeader(Component.translatable("flashbackinspector.category.viewing"));
		list.addWidget(toggle("replace_inventory_key", () -> config.replaceInventoryKey,
				value -> config.replaceInventoryKey = value));
		list.addWidget(cycleButton("container_mode", () -> config.containerMode.getDisplayName(),
				() -> config.containerMode = config.containerMode.next()));
		list.addWidget(toggle("container_hint", () -> config.showContainerHint,
				value -> config.showContainerHint = value));
		list.addWidget(toggle("pause_when_opened", () -> config.pauseWhenOpened,
				value -> config.pauseWhenOpened = value));
		list.addWidget(toggle("show_timecode", () -> config.showTimecode,
				value -> config.showTimecode = value));

		addRenderableWidget(list);

		addRenderableWidget(Button.builder(Component.translatable("flashbackinspector.config.reset"), button -> {
			config.resetToDefaults();
			rebuildWidgets();
		}).bounds(this.width / 2 - 154, this.height - 30, 150, 20).build());

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
				.bounds(this.width / 2 + 4, this.height - 30, 150, 20)
				.build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		super.extractRenderState(context, mouseX, mouseY, delta);
		context.centeredText(this.font, this.title, this.width / 2, 18, 0xFFFFFFFF);
		if (!ConfigScreenFactory.isClothPresent()) {
			context.centeredText(this.font,
					Component.translatable("flashbackinspector.config.no_cloth"), this.width / 2,
					this.height - 44, 0xFF9A9A9A);
		}
	}

	@Override
	public void onClose() {
		config.save();
		Minecraft.getInstance().gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private AbstractWidget toggle(String key, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		return cycleButton(key,
				() -> getter.get() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF,
				() -> setter.accept(!getter.get()));
	}

	private AbstractWidget cycleButton(String key, Supplier<Component> value, Runnable onClick) {
		Button button = Button.builder(label(key, value.get()), b -> {
			onClick.run();
			b.setMessage(label(key, value.get()));
		}).bounds(0, 0, ROW_WIDTH, 20).build();
		button.setTooltip(Tooltip.create(Component.translatable("flashbackinspector.option." + key + ".tooltip")));
		return button;
	}

	private static Component label(String key, Component value) {
		return Component.translatable("flashbackinspector.option." + key).append(": ").append(value);
	}

	/** Whole ticks, one to twenty. */
	private static class TicksSlider extends AbstractSliderButton {
		private static final int MIN = 1;
		private static final int MAX = 20;
		private final String key;
		private final Consumer<Integer> setter;

		TicksSlider(String key, int current, Consumer<Integer> setter) {
			super(0, 0, ROW_WIDTH, 20, Component.empty(),
					Mth.clamp((current - MIN) / (float) (MAX - MIN), 0.0f, 1.0f));
			this.key = key;
			this.setter = setter;
			setTooltip(Tooltip.create(Component.translatable("flashbackinspector.option." + key + ".tooltip")));
			updateMessage();
		}

		private int currentValue() {
			return Math.round(MIN + (MAX - MIN) * (float) this.value);
		}

		@Override
		protected void updateMessage() {
			setMessage(label(key, Component.translatable("flashbackinspector.unit.ticks", currentValue())));
		}

		@Override
		protected void applyValue() {
			setter.accept(currentValue());
		}
	}

	private static class OptionList extends ContainerObjectSelectionList<OptionList.Entry> {
		OptionList(Minecraft client, int width, int height, int y, int itemHeight) {
			super(client, width, height, y, itemHeight);
		}

		void addWidget(AbstractWidget widget) {
			addEntry(new WidgetEntry(widget));
		}

		void addHeader(Component text) {
			addEntry(new HeaderEntry(text));
		}

		@Override
		public int getRowWidth() {
			return ROW_WIDTH;
		}

		abstract static class Entry extends ContainerObjectSelectionList.Entry<com.glamardor.flashbackinspector.gui.FallbackConfigScreen.OptionList.Entry> {
		}

		static class WidgetEntry extends com.glamardor.flashbackinspector.gui.FallbackConfigScreen.OptionList.Entry {
			private final AbstractWidget widget;

			WidgetEntry(AbstractWidget widget) {
				this.widget = widget;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float tickDelta) {
				int index = 0, y = getContentY(), x = getContentX(), entryWidth = getContentWidth(), entryHeight = getContentHeight();
				widget.setX(x);
				widget.setY(y);
				widget.setWidth(entryWidth);
				widget.extractRenderState(context, mouseX, mouseY, tickDelta);
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return List.of(widget);
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of(widget);
			}
		}

		static class HeaderEntry extends com.glamardor.flashbackinspector.gui.FallbackConfigScreen.OptionList.Entry {
			private final Component text;

			HeaderEntry(Component text) {
				this.text = text;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float tickDelta) {
				int index = 0, y = getContentY(), x = getContentX(), entryWidth = getContentWidth(), entryHeight = getContentHeight();
				Minecraft client = Minecraft.getInstance();
				context.centeredText(client.font, text,
						x + entryWidth / 2, y + 8, 0xFFE0C070);
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return List.of();
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of();
			}
		}
	}
}
