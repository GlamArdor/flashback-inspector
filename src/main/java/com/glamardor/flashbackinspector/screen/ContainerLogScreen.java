package com.glamardor.flashbackinspector.screen;

import com.glamardor.flashbackinspector.playback.InspectorState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Every container the recording player opened, with the tick it happened on. Clicking one takes the
 * replay there and stops it, which is the short way from "something was taken" to the frame it was
 * taken on.
 *
 * <p>The whole recording is listed from the start, read out of the replay file. Only while that read
 * is still going, or if it failed, does the list fall back to what has been watched so far.
 */
public class ContainerLogScreen extends Screen {
	private static final int ROW_WIDTH = 320;

	@Nullable
	private final Screen parent;

	public ContainerLogScreen(@Nullable Screen parent) {
		super(Text.translatable("flashbackinspector.log.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		List<InspectorState.ContainerOpening> openings = InspectorState.get().openings();
		OpeningList list = new OpeningList(this.client, this.width, this.height - 96, 40, 22);
		for (InspectorState.ContainerOpening opening : openings) {
			list.addWidget(ButtonWidget.builder(describe(opening),
							button -> InspectorState.seek(opening.tick()))
					.dimensions(0, 0, ROW_WIDTH, 20)
					.build());
		}
		addDrawableChild(list);

		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
				.dimensions(this.width / 2 - 75, this.height - 30, 150, 20)
				.build());
	}

	private static Text describe(InspectorState.ContainerOpening opening) {
		Text line = Text.literal(InspectorState.timecode(opening.tick()))
				.formatted(Formatting.GOLD)
				.append(Text.literal("  "))
				.append(titleOf(opening).copy().formatted(Formatting.WHITE));
		BlockPos pos = opening.pos();
		if (pos != null) {
			line = line.copy().append(Text.literal("  " + pos.getX() + " " + pos.getY() + " " + pos.getZ())
					.formatted(Formatting.DARK_GRAY));
		}
		return line;
	}

	/**
	 * The container's own name, or its size when the name is nothing readable. Plugin menus on a
	 * server often carry a title made of resource pack glyphs that draw the menu's background, which
	 * in a button is blank or a smear.
	 */
	private static Text titleOf(InspectorState.ContainerOpening opening) {
		String plain = opening.title().getString().strip();
		boolean readable = plain.codePoints().anyMatch(Character::isLetterOrDigit)
				&& plain.codePoints().noneMatch(c -> c >= 0xE000 && c <= 0xF8FF);
		return readable ? opening.title()
				: Text.translatable("flashbackinspector.log.untitled", opening.size());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 18, 0xFFFFFFFF);
		if (!InspectorState.get().hasOpenings()) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.translatable("flashbackinspector.log.empty"), this.width / 2, this.height / 2,
					0xFF9A93A6);
		}
	}

	@Override
	public void close() {
		MinecraftClient.getInstance().setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private static class OpeningList extends ElementListWidget<OpeningList.Entry> {
		OpeningList(MinecraftClient client, int width, int height, int y, int itemHeight) {
			super(client, width, height, y, itemHeight);
		}

		void addWidget(ClickableWidget widget) {
			addEntry(new Entry(widget));
		}

		@Override
		public int getRowWidth() {
			return ROW_WIDTH;
		}

		static class Entry extends ElementListWidget.Entry<Entry> {
			private final ClickableWidget widget;

			Entry(ClickableWidget widget) {
				this.widget = widget;
			}

			@Override
			public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
					int mouseX, int mouseY, boolean hovered, float tickDelta) {
				widget.setX(x);
				widget.setY(y);
				widget.setWidth(entryWidth);
				widget.render(context, mouseX, mouseY, tickDelta);
			}

			@Override
			public List<? extends Element> children() {
				return List.of(widget);
			}

			@Override
			public List<? extends Selectable> selectableChildren() {
				return List.of(widget);
			}
		}
	}
}
