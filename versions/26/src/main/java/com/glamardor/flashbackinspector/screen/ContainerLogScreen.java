package com.glamardor.flashbackinspector.screen;

import com.glamardor.flashbackinspector.playback.InspectorState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

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
		super(Component.translatable("flashbackinspector.log.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		List<InspectorState.ContainerOpening> openings = InspectorState.get().openings();
		OpeningList list = new OpeningList(this.minecraft, this.width, this.height - 96, 40, 22);
		for (InspectorState.ContainerOpening opening : openings) {
			list.addWidget(Button.builder(describe(opening),
							button -> InspectorState.seek(opening.tick()))
					.bounds(0, 0, ROW_WIDTH, 20)
					.build());
		}
		addRenderableWidget(list);

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
				.bounds(this.width / 2 - 75, this.height - 30, 150, 20)
				.build());
	}

	private static Component describe(InspectorState.ContainerOpening opening) {
		Component line = Component.literal(InspectorState.timecode(opening.tick()))
				.withStyle(ChatFormatting.GOLD)
				.append(Component.literal("  "))
				.append(titleOf(opening).copy().withStyle(ChatFormatting.WHITE));
		BlockPos pos = opening.pos();
		if (pos != null) {
			line = line.copy().append(Component.literal("  " + pos.getX() + " " + pos.getY() + " " + pos.getZ())
					.withStyle(ChatFormatting.DARK_GRAY));
		}
		return line;
	}

	/**
	 * The container's own name, or its size when the name is nothing readable. Plugin menus on a
	 * server often carry a title made of resource pack glyphs that draw the menu's background, which
	 * in a button is blank or a smear.
	 */
	private static Component titleOf(InspectorState.ContainerOpening opening) {
		String plain = opening.title().getString().strip();
		boolean readable = plain.codePoints().anyMatch(Character::isLetterOrDigit)
				&& plain.codePoints().noneMatch(c -> c >= 0xE000 && c <= 0xF8FF);
		return readable ? opening.title()
				: Component.translatable("flashbackinspector.log.untitled", opening.size());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		super.extractRenderState(context, mouseX, mouseY, delta);
		context.centeredText(this.font, this.title, this.width / 2, 18, 0xFFFFFFFF);
		if (!InspectorState.get().hasOpenings()) {
			context.centeredText(this.font,
					Component.translatable("flashbackinspector.log.empty"), this.width / 2, this.height / 2,
					0xFF9A93A6);
		}
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static class OpeningList extends ContainerObjectSelectionList<OpeningList.Entry> {
		OpeningList(Minecraft client, int width, int height, int y, int itemHeight) {
			super(client, width, height, y, itemHeight);
		}

		void addWidget(AbstractWidget widget) {
			addEntry(new com.glamardor.flashbackinspector.screen.ContainerLogScreen.OpeningList.Entry(widget));
		}

		@Override
		public int getRowWidth() {
			return ROW_WIDTH;
		}

		static class Entry extends ContainerObjectSelectionList.Entry<com.glamardor.flashbackinspector.screen.ContainerLogScreen.OpeningList.Entry> {
			private final AbstractWidget widget;

			Entry(AbstractWidget widget) {
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
	}
}
