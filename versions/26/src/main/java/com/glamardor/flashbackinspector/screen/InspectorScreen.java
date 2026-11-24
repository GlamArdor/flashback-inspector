package com.glamardor.flashbackinspector.screen;

import com.glamardor.flashbackinspector.InventoryLayout;
import com.glamardor.flashbackinspector.config.InspectorConfig;
import com.glamardor.flashbackinspector.playback.InspectorState;
import com.moulberry.flashback.Flashback;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The read-only inventory, as it stood on the tick currently on screen.
 *
 * <p>Two kinds of player end up here. The one who made the recording, whose whole inventory was
 * written into the file, and everybody else, of whom the file holds the worn items and the two
 * hands and nothing more – that is all a client is ever told about another player, so that is all
 * a recording of a client can contain.
 */
public class InspectorScreen extends Screen {
	private static final int SLOT = 18;
	private static final int PAD = 8;
	private static final int LINE = 11;
	private static final int COLUMNS = 9;
	private static final int MIN_WIDTH = 214;

	// Nearly opaque. A see-through panel let the lanterns and chains behind it line up with the
	// slots and read as items that were never there.
	private static final int PANEL_BACKGROUND = 0xFA100E12;
	private static final int PANEL_BORDER = 0xFF4C4652;
	private static final int SLOT_BACKGROUND = 0xFF2B2830;
	private static final int SLOT_MISSING = 0xFF1A181D;
	private static final int HEADING = 0xFFE0C070;
	private static final int MUTED = 0xFF9A93A6;

	@Nullable
	private final AbstractClientPlayer target;
	private final boolean isOwner;
	/** Whether this screen was asked for, as opposed to appearing on its own. */
	private final boolean requested;
	/** Set once the pause has been applied, so re-measuring the layout does not apply it again. */
	private boolean pauseApplied;

	private int left;
	private int top;
	private int panelWidth;
	private int panelHeight;
	private int gridLeft;

	private int headerTop;
	private int sourceTop;
	private int containerLabelTop;
	private int containerTop;
	private int containerRows;
	private int inventoryLabelTop;
	private int equipmentTop;
	private int mainTop;
	private int hotbarTop;
	private int buttonTop;

	/** The container size the current layout was measured for, so a change can be noticed. */
	private int laidOutContainerSize = -1;

	/** The stack under the mouse this frame, drawn last so its tooltip sits over everything. */
	@Nullable
	private ItemStack hovered;

	/** Opened on purpose, by the inventory key. May pause the replay, if that is set. */
	public InspectorScreen(@Nullable AbstractClientPlayer target) {
		this(target, true);
	}

	/**
	 * Opened by the follow mode, on the tick a container opened during the recording.
	 *
	 * <p>Never pauses, whatever the setting says. Pausing here would stop the replay on every
	 * container in the recording, and worse, it would stop it before the container could ever be
	 * seen to close – the tick that closes it would never arrive.
	 */
	public static InspectorScreen followed(@Nullable AbstractClientPlayer target) {
		return new InspectorScreen(target, false);
	}

	private InspectorScreen(@Nullable AbstractClientPlayer target, boolean requested) {
		super(Component.translatable("flashbackinspector.screen.title"));
		this.target = target;
		this.requested = requested;
		this.isOwner = target != null && InspectorState.get().isOwner(target.getUUID());
	}

	/**
	 * Whose inventory the inventory key should show: whoever is being followed, and failing that
	 * the player who made the recording.
	 */
	@Nullable
	public static AbstractClientPlayer defaultTarget() {
		AbstractClientPlayer spectating = Flashback.getSpectatingPlayer();
		if (spectating != null) {
			return spectating;
		}
		Minecraft client = Minecraft.getInstance();
		UUID owner = InspectorState.get().owner();
		if (owner != null && client.level != null
				&& client.level.getPlayerByUUID(owner) instanceof AbstractClientPlayer player) {
			return player;
		}
		return null;
	}

	@Override
	protected void init() {
		InspectorState state = InspectorState.get();
		int containerSize = isOwner && state.hasContainer() ? state.containerSize() : 0;
		laidOutContainerSize = containerSize;
		containerRows = containerSize > 0 ? (containerSize + COLUMNS - 1) / COLUMNS : 0;

		panelWidth = Math.max(COLUMNS * SLOT + PAD * 2, MIN_WIDTH);

		// Measured from the panel's own corner first, then shifted once it is placed. Every row has
		// its space reserved here and nowhere else, which is what keeps the sections off each other.
		int y = PAD;
		headerTop = y;
		y += LINE;
		sourceTop = y;
		y += LINE + 4;
		if (containerRows > 0) {
			containerLabelTop = y;
			y += LINE;
			containerTop = y;
			y += containerRows * SLOT + 6;
		}
		inventoryLabelTop = y;
		y += LINE;
		equipmentTop = y;
		y += SLOT + 6;
		mainTop = y;
		y += 3 * SLOT + 4;
		hotbarTop = y;
		y += SLOT + PAD;
		buttonTop = y;
		y += 18 + PAD;
		panelHeight = y;

		left = (this.width - panelWidth) / 2;
		top = Math.max(4, (this.height - panelHeight) / 2);
		gridLeft = left + (panelWidth - COLUMNS * SLOT) / 2;

		headerTop += top;
		sourceTop += top;
		containerLabelTop += top;
		containerTop += top;
		inventoryLabelTop += top;
		equipmentTop += top;
		mainTop += top;
		hotbarTop += top;
		buttonTop += top;

		addRenderableWidget(Button.builder(Component.translatable("flashbackinspector.button.log"),
						button -> Minecraft.getInstance().gui.setScreen(new ContainerLogScreen(this)))
				.bounds(left + PAD, buttonTop, panelWidth - PAD * 2, 18)
				.build());

		// Once, and only for a screen that was asked for. init runs again on every resize and every
		// time the container behind the screen changes size, and pausing there would grind the
		// replay to a halt on each one.
		if (requested && !pauseApplied && InspectorConfig.get().pauseWhenOpened) {
			pauseApplied = true;
			InspectorState.pause();
		}
	}

	/**
	 * The panel itself goes in behind the widgets.
	 *
	 * <p>Drawn here rather than in {@code render}, because the log button is a child widget and
	 * children are drawn by {@code super.render} – a panel painted afterwards would cover it.
	 */
	@Override
	public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		super.extractBackground(context, mouseX, mouseY, delta);
		context.fill(left, top, left + panelWidth, top + panelHeight, PANEL_BACKGROUND);
		context.outline(left, top, panelWidth, panelHeight, PANEL_BORDER);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		InspectorState state = InspectorState.get();

		// A container can open, close or change size while this screen is up – in the follow mode
		// that is the normal case. Re-measure rather than draw a grid into space reserved for
		// something else.
		int containerSize = isOwner && state.hasContainer() ? state.containerSize() : 0;
		if (containerSize != laidOutContainerSize) {
			rebuildWidgets();
			return;
		}

		super.extractRenderState(context, mouseX, mouseY, delta);
		hovered = null;

		drawHeader(context, state);

		if (containerRows > 0) {
			context.text(this.font, containerHeading(state),
					left + PAD, containerLabelTop, HEADING);
			drawGrid(context, mouseX, mouseY, gridLeft, containerTop, state.containerSize(),
					state::container, true);
		}

		context.text(this.font,
				Component.translatable("flashbackinspector.section.inventory"), left + PAD,
				inventoryLabelTop, HEADING);
		drawEquipmentRow(context, mouseX, mouseY, gridLeft, equipmentTop);

		boolean legacyHotbar = !isOwner && hasLegacyHotbar();
		drawGrid(context, mouseX, mouseY, gridLeft, mainTop, InventoryLayout.MAIN_SIZE,
				index -> isOwner ? state.inventory(InventoryLayout.MAIN_START + index) : ItemStack.EMPTY,
				isOwner);
		drawGrid(context, mouseX, mouseY, gridLeft, hotbarTop, InventoryLayout.HOTBAR_SIZE,
				this::hotbar, isOwner || legacyHotbar);

		if (!isOwner) {
			drawNotice(context, legacyHotbar
					? "flashbackinspector.not_recorded"
					: "flashbackinspector.not_sent");
		}

		if (hovered != null && !hovered.isEmpty()) {
			context.setTooltipForNextFrame(this.font, hovered, mouseX, mouseY);
		}
	}

	/** Name on the left, timecode on the right, and what the file actually holds underneath. */
	private void drawHeader(GuiGraphicsExtractor context, InspectorState state) {
		int textLeft = left + PAD;
		int textRight = left + panelWidth - PAD;

		int stampWidth = 0;
		if (InspectorConfig.get().showTimecode) {
			int tick = InspectorState.currentTick();
			if (tick >= 0) {
				Component stamp = Component.literal(InspectorState.timecode(tick) + "  ·  " + tick);
				stampWidth = this.font.width(stamp) + 8;
				context.text(this.font, stamp,
						textRight - this.font.width(stamp), headerTop, MUTED);
			}
		}

		Component name = target == null
				? Component.translatable("flashbackinspector.no_target").withStyle(ChatFormatting.GRAY)
				: Component.literal(target.getGameProfile().name());
		int room = textRight - textLeft - stampWidth;
		if (this.font.width(name) > room) {
			name = Component.literal(this.font.plainSubstrByWidth(name.getString(), room))
					.setStyle(name.getStyle());
		}
		context.text(this.font, name, textLeft, headerTop, 0xFFFFFFFF);

		if (target != null) {
			String source;
			if (isOwner) {
				source = "flashbackinspector.source.recorder";
			} else if (hasLegacyHotbar()) {
				source = "flashbackinspector.source.legacy";
			} else {
				source = "flashbackinspector.source.equipment_only";
			}
			context.text(this.font, Component.translatable(source), textLeft,
					sourceTop, MUTED);
		}
	}

	/**
	 * The line explaining why most of the grid is empty.
	 *
	 * <p>Wrapped to the panel and laid over the greyed-out slots, which are the very thing it is
	 * explaining. A single unwrapped line ran off both sides of the window.
	 */
	private void drawNotice(GuiGraphicsExtractor context, String key) {
		List<FormattedCharSequence> lines = this.font.split(
				Component.translatable(key).withStyle(ChatFormatting.GRAY), panelWidth - PAD * 4);
		int height = lines.size() * LINE;
		int y = mainTop + (3 * SLOT - height) / 2;
		context.fill(left + PAD, y - 3, left + panelWidth - PAD, y + height + 1, 0xF0100E12);
		for (FormattedCharSequence line : lines) {
			context.text(this.font, line,
					left + (panelWidth - this.font.width(line)) / 2, y, MUTED);
			y += LINE;
		}
	}

	/**
	 * The container's own title, with its coordinates after it when there is room.
	 *
	 * <p>The coordinates are the first thing dropped if the title is long: they are in the log as
	 * well, whereas a title cut in half is gone.
	 */
	private Component containerHeading(InspectorState state) {
		Component heading = Component.empty().append(state.containerTitle());
		BlockPos pos = state.containerPos();
		if (pos == null) {
			return heading;
		}
		Component withPos = Component.empty().append(state.containerTitle()).append(Component.literal("  "))
				.append(Component.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ())
						.withStyle(ChatFormatting.DARK_GRAY));
		return this.font.width(withPos) <= panelWidth - PAD * 2 ? withPos : heading;
	}

	/** The worn items and the off hand, in one row: head, chest, legs, feet, then the off hand. */
	private void drawEquipmentRow(GuiGraphicsExtractor context, int mouseX, int mouseY, int x, int y) {
		for (int i = 0; i < InventoryLayout.ARMOUR.length; i++) {
			drawSlot(context, mouseX, mouseY, x + i * SLOT, y, equipment(InventoryLayout.ARMOUR[i]), true);
		}
		// A gap, then the off hand, so it reads as a hand rather than a fifth piece of armour.
		int offhandX = x + (InventoryLayout.ARMOUR.length + 1) * SLOT;
		drawSlot(context, mouseX, mouseY, offhandX, y, equipment(EquipmentSlot.OFFHAND), true);
	}

	/**
	 * One slot of worn equipment.
	 *
	 * <p>Read off the live entity rather than out of our own recording, and for everyone: Flashback
	 * already writes an equipment packet for every player it sees, so this works on the recording
	 * player too, and on replays made before this mod was ever installed.
	 */
	private ItemStack equipment(EquipmentSlot slot) {
		if (target == null) {
			return ItemStack.EMPTY;
		}
		if (isOwner) {
			int index = switch (slot) {
				case HEAD -> InventoryLayout.HEAD;
				case CHEST -> InventoryLayout.CHEST;
				case LEGS -> InventoryLayout.LEGS;
				case FEET -> InventoryLayout.FEET;
				case OFFHAND -> InventoryLayout.OFFHAND;
				default -> -1;
			};
			ItemStack recorded = InspectorState.get().inventory(index);
			if (!recorded.isEmpty()) {
				return recorded;
			}
		}
		return ((Player) target).getItemBySlot(slot);
	}

	/**
	 * The hotbar, falling back to what Flashback itself recorded.
	 *
	 * <p>Flashback's own {@code recordHotbar} writes the nine slots as container packets, and on
	 * playback it puts them straight onto the player entity's inventory on the client. That is the
	 * one part of an inventory that exists in a replay made before this mod, so it is worth reading
	 * rather than drawing an empty row over the top of it.
	 */
	private ItemStack hotbar(int index) {
		if (isOwner) {
			ItemStack recorded = InspectorState.get().inventory(InventoryLayout.HOTBAR_START + index);
			if (!recorded.isEmpty()) {
				return recorded;
			}
		}
		return target == null ? ItemStack.EMPTY : ((Player) target).getInventory().getItem(index);
	}

	/** Whether Flashback's own hotbar recording reached this player, which only the recorder's does. */
	private boolean hasLegacyHotbar() {
		if (target == null) {
			return false;
		}
		for (int i = 0; i < InventoryLayout.HOTBAR_SIZE; i++) {
			if (!((Player) target).getInventory().getItem(i).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private interface SlotSource {
		ItemStack get(int index);
	}

	private void drawGrid(GuiGraphicsExtractor context, int mouseX, int mouseY, int x, int y, int count,
			SlotSource source, boolean present) {
		for (int i = 0; i < count; i++) {
			int column = i % COLUMNS;
			int row = i / COLUMNS;
			drawSlot(context, mouseX, mouseY, x + column * SLOT, y + row * SLOT, source.get(i), present);
		}
	}

	private void drawSlot(GuiGraphicsExtractor context, int mouseX, int mouseY, int x, int y, ItemStack stack,
			boolean present) {
		context.fill(x, y, x + SLOT - 2, y + SLOT - 2, present ? SLOT_BACKGROUND : SLOT_MISSING);
		if (!stack.isEmpty()) {
			context.item(stack, x + 1, y + 1);
			context.itemDecorations(this.font, stack, x + 1, y + 1);
		}
		if (present && mouseX >= x && mouseX < x + SLOT - 2 && mouseY >= y && mouseY < y + SLOT - 2) {
			context.fill(x, y, x + SLOT - 2, y + SLOT - 2, 0x40FFFFFF);
			if (!stack.isEmpty()) {
				hovered = stack;
			}
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
