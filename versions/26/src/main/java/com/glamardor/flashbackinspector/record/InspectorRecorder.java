package com.glamardor.flashbackinspector.record;

import com.glamardor.flashbackinspector.FlashbackInspector;
import com.glamardor.flashbackinspector.InventoryLayout;
import com.glamardor.flashbackinspector.config.InspectorConfig;
import com.glamardor.flashbackinspector.net.InspectorPayloads;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.record.Recorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The recording half. Runs on the client tick while Flashback is writing, notices what changed, and
 * hands the change to Flashback's recorder as a packet.
 *
 * <p>Nothing here ever touches the connection to the server. {@code writePacketAsync} appends to the
 * file being written and nothing else.
 */
public final class InspectorRecorder {
	private static final InspectorRecorder INSTANCE = new InspectorRecorder();

	/** The last thing written for each slot, so only differences go into the file. */
	private ItemStack[] lastInventory;
	private ItemStack[] lastContainer;
	private int lastContainerId = -1;
	private UUID owner;
	/**
	 * The player entity the last state was read off. The client makes a new one on every server
	 * switch behind a proxy, which is the moment to start over with a full snapshot.
	 */
	private LocalPlayer lastPlayer;
	private int tickCounter;
	/** Set when a write threw, so one bad recording does not spend the rest of the session trying. */
	private boolean failed;
	/** Where the player was looking when the container opened, which is the container itself. */
	private BlockPos lastContainerPos;
	private Component lastContainerTitle = Component.empty();

	private InspectorRecorder() {
	}

	public static InspectorRecorder get() {
		return INSTANCE;
	}

	/** Called every client tick. Cheap and silent unless Flashback is actually recording. */
	public void tick(Minecraft client) {
		Recorder recorder = Flashback.RECORDER;
		if (recorder == null) {
			// Recording stopped, or never started. Forget everything so the next recording opens
			// with a full snapshot rather than a delta against a file that no longer exists.
			reset();
			return;
		}

		InspectorConfig config = InspectorConfig.get();
		if (!config.recordInventory) {
			return;
		}

		LocalPlayer player = client.player;
		if (player == null || !recorder.readyToWrite()) {
			// Skipping rather than diffing: the state is left alone so the change is still pending
			// on the next tick, once the writer has caught up.
			return;
		}

		if (lastInventory == null || player != lastPlayer) {
			// A new player entity means a new server: whatever container was open belonged to the
			// old one, and a full state survives a viewer that misses the tick it was written on
			// far better than forty one slot changes do.
			lastPlayer = player;
			lastContainer = null;
			lastContainerId = -1;
			lastContainerPos = null;
			owner = player.getUUID();
			lastInventory = new ItemStack[InventoryLayout.SIZE];
			// A full snapshot first, so a replay opened at tick zero already knows everything.
			write(recorder, buildSnapshot(client, player));
			for (int i = 0; i < InventoryLayout.SIZE; i++) {
				lastInventory[i] = read(player, i).copy();
			}
			return;
		}

		tickCounter++;
		if (tickCounter < config.scanIntervalTicks) {
			return;
		}
		tickCounter = 0;

		scanInventory(recorder, player);
		if (config.recordContainers) {
			scanContainer(recorder, client, player);
		}
	}

	private void scanInventory(Recorder recorder, LocalPlayer player) {
		List<InspectorPayloads.SlotChange> changes = null;
		for (int i = 0; i < InventoryLayout.SIZE; i++) {
			ItemStack current = read(player, i);
			if (!ItemStack.matches(lastInventory[i], current)) {
				ItemStack copy = current.copy();
				lastInventory[i] = copy;
				if (changes == null) {
					changes = new ArrayList<>();
				}
				changes.add(new InspectorPayloads.SlotChange(i, copy));
			}
		}
		if (changes != null) {
			write(recorder, new InspectorPayloads.Slots(0, changes));
		}
	}

	private void scanContainer(Recorder recorder, Minecraft client, LocalPlayer player) {
		AbstractContainerMenu handler = player.containerMenu;

		if (!isContainer(client, player, handler)) {
			if (lastContainerId != -1) {
				write(recorder, new InspectorPayloads.ContainerEvent(false,
						new InspectorPayloads.ContainerView(lastContainerId, lastContainerTitle,
								List.of(), Optional.ofNullable(lastContainerPos))));
				lastContainerId = -1;
				lastContainer = null;
				lastContainerPos = null;
			}
			return;
		}

		int size = containerSize(handler);
		if (size <= 0) {
			return;
		}

		if (handler.containerId != lastContainerId) {
			lastContainerId = handler.containerId;
			lastContainerTitle = titleOf(client);
			lastContainerPos = lookedAtBlock(client);
			lastContainer = new ItemStack[size];
			List<ItemStack> contents = new ArrayList<>(size);
			for (int i = 0; i < size; i++) {
				ItemStack stack = handler.slots.get(i).getItem().copy();
				lastContainer[i] = stack;
				contents.add(stack);
			}
			write(recorder, new InspectorPayloads.ContainerEvent(true,
					new InspectorPayloads.ContainerView(lastContainerId, lastContainerTitle, contents,
							Optional.ofNullable(lastContainerPos))));
			return;
		}

		if (lastContainer == null || lastContainer.length != size) {
			// The same sync id with a different number of slots should not happen, but a changed
			// shape would silently misalign every slot after it, so start the container over.
			lastContainerId = -1;
			return;
		}

		List<InspectorPayloads.SlotChange> changes = null;
		for (int i = 0; i < size; i++) {
			ItemStack current = handler.slots.get(i).getItem();
			if (!ItemStack.matches(lastContainer[i], current)) {
				ItemStack copy = current.copy();
				lastContainer[i] = copy;
				if (changes == null) {
					changes = new ArrayList<>();
				}
				changes.add(new InspectorPayloads.SlotChange(i, copy));
			}
		}
		if (changes != null) {
			write(recorder, new InspectorPayloads.Slots(lastContainerId, changes));
		}
	}

	/**
	 * Adds the current state to one of Flashback's snapshots.
	 *
	 * <p>Called from the mixin on {@code Recorder.writeCustomSnapshot}, which Flashback leaves empty
	 * for exactly this. Without it, seeking past a snapshot would leave the inspector showing
	 * whatever it happened to have seen on the way there.
	 */
	public void appendSnapshot(Consumer<Packet<? super ClientGamePacketListener>> consumer) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || lastInventory == null || !InspectorConfig.get().recordInventory) {
			return;
		}
		consumer.accept(new ClientboundCustomPayloadPacket(buildSnapshot(client, player)));
	}

	private InspectorPayloads.Snapshot buildSnapshot(Minecraft client, LocalPlayer player) {
		List<ItemStack> inventory = new ArrayList<>(InventoryLayout.SIZE);
		for (int i = 0; i < InventoryLayout.SIZE; i++) {
			inventory.add(read(player, i).copy());
		}

		Optional<InspectorPayloads.ContainerView> container = Optional.empty();
		AbstractContainerMenu handler = player.containerMenu;
		if (InspectorConfig.get().recordContainers && isContainer(client, player, handler)) {
			int size = containerSize(handler);
			if (size > 0) {
				List<ItemStack> contents = new ArrayList<>(size);
				for (int i = 0; i < size; i++) {
					contents.add(handler.slots.get(i).getItem().copy());
				}
				container = Optional.of(new InspectorPayloads.ContainerView(handler.containerId,
						lastContainerId == handler.containerId ? lastContainerTitle : titleOf(client),
						contents, Optional.ofNullable(lastContainerPos)));
			}
		}

		return new InspectorPayloads.Snapshot(owner != null ? owner : player.getUUID(), inventory, container);
	}

	/**
	 * Whether what is open is a container, rather than something else wearing a screen handler.
	 *
	 * <p>A positive test on purpose. The creative inventory is the reason: it swaps
	 * {@code currentScreenHandler} for one of its own whose slots are the item list of whichever tab
	 * is showing, so "any handler that is not the player's" quietly recorded eighteen building
	 * blocks as the contents of a chest that was never opened.
	 */
	private static boolean isContainer(Minecraft client, LocalPlayer player,
			AbstractContainerMenu handler) {
		return handler != null
				&& handler != player.inventoryMenu
				&& client.gui.screen() instanceof AbstractContainerScreen<?>
				&& !(client.gui.screen() instanceof CreativeModeInventoryScreen);
	}

	/**
	 * How many of a screen handler's slots belong to the container rather than to the player.
	 *
	 * <p>Every vanilla container puts the player's own thirty-six last, and so does everything built
	 * on the same base, which on a server means the plugins too.
	 */
	private static int containerSize(AbstractContainerMenu handler) {
		int total = handler.slots.size();
		// Fewer than the player's own thirty-six means this is not laid out like a container at
		// all. Recording nothing beats recording the player's inventory a second time under the
		// name of whatever was open.
		return total > 36 ? total - 36 : 0;
	}

	private static Component titleOf(Minecraft client) {
		if (client.gui.screen() instanceof AbstractContainerScreen<?> screen) {
			return screen.getTitle();
		}
		return client.gui.screen() != null ? client.gui.screen().getTitle() : Component.empty();
	}

	private static BlockPos lookedAtBlock(Minecraft client) {
		return client.hitResult instanceof BlockHitResult hit ? hit.getBlockPos() : null;
	}

	/** Reads one slot of our own layout off the live player. */
	private static ItemStack read(LocalPlayer player, int index) {
		EquipmentSlot equipment = InventoryLayout.equipmentFor(index);
		if (equipment != null) {
			return player.getItemBySlot(equipment);
		}
		return player.getInventory().getItem(index);
	}

	private void write(Recorder recorder, CustomPacketPayload payload) {
		if (failed) {
			return;
		}
		try {
			recorder.writePacketAsync(new ClientboundCustomPayloadPacket(payload), ConnectionProtocol.PLAY);
		} catch (Throwable t) {
			// A recording that keeps going without the inventory is worth more than a crash. Held in
			// a flag of our own rather than by turning the setting off, so the settings screen does
			// not end up showing a choice the player never made.
			FlashbackInspector.LOGGER.warn("Could not write to the recording, leaving this one alone", t);
			failed = true;
		}
	}

	private void reset() {
		lastInventory = null;
		lastContainer = null;
		lastContainerId = -1;
		lastContainerPos = null;
		lastContainerTitle = Component.empty();
		owner = null;
		lastPlayer = null;
		tickCounter = 0;
		failed = false;
	}
}
