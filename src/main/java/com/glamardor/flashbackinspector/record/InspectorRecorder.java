package com.glamardor.flashbackinspector.record;

import com.glamardor.flashbackinspector.FlashbackInspector;
import com.glamardor.flashbackinspector.InventoryLayout;
import com.glamardor.flashbackinspector.config.InspectorConfig;
import com.glamardor.flashbackinspector.net.InspectorPayloads;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.record.Recorder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

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
	private ClientPlayerEntity lastPlayer;
	private int tickCounter;
	/** Set when a write threw, so one bad recording does not spend the rest of the session trying. */
	private boolean failed;
	/** Where the player was looking when the container opened, which is the container itself. */
	private BlockPos lastContainerPos;
	private Text lastContainerTitle = Text.empty();

	private InspectorRecorder() {
	}

	public static InspectorRecorder get() {
		return INSTANCE;
	}

	/** Called every client tick. Cheap and silent unless Flashback is actually recording. */
	public void tick(MinecraftClient client) {
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

		ClientPlayerEntity player = client.player;
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
			owner = player.getUuid();
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

	private void scanInventory(Recorder recorder, ClientPlayerEntity player) {
		List<InspectorPayloads.SlotChange> changes = null;
		for (int i = 0; i < InventoryLayout.SIZE; i++) {
			ItemStack current = read(player, i);
			if (!ItemStack.areEqual(lastInventory[i], current)) {
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

	private void scanContainer(Recorder recorder, MinecraftClient client, ClientPlayerEntity player) {
		ScreenHandler handler = player.currentScreenHandler;

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

		if (handler.syncId != lastContainerId) {
			lastContainerId = handler.syncId;
			lastContainerTitle = titleOf(client);
			lastContainerPos = lookedAtBlock(client);
			lastContainer = new ItemStack[size];
			List<ItemStack> contents = new ArrayList<>(size);
			for (int i = 0; i < size; i++) {
				ItemStack stack = handler.slots.get(i).getStack().copy();
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
			ItemStack current = handler.slots.get(i).getStack();
			if (!ItemStack.areEqual(lastContainer[i], current)) {
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
	public void appendSnapshot(Consumer<Packet<? super ClientPlayPacketListener>> consumer) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		if (player == null || lastInventory == null || !InspectorConfig.get().recordInventory) {
			return;
		}
		consumer.accept(new CustomPayloadS2CPacket(buildSnapshot(client, player)));
	}

	private InspectorPayloads.Snapshot buildSnapshot(MinecraftClient client, ClientPlayerEntity player) {
		List<ItemStack> inventory = new ArrayList<>(InventoryLayout.SIZE);
		for (int i = 0; i < InventoryLayout.SIZE; i++) {
			inventory.add(read(player, i).copy());
		}

		Optional<InspectorPayloads.ContainerView> container = Optional.empty();
		ScreenHandler handler = player.currentScreenHandler;
		if (InspectorConfig.get().recordContainers && isContainer(client, player, handler)) {
			int size = containerSize(handler);
			if (size > 0) {
				List<ItemStack> contents = new ArrayList<>(size);
				for (int i = 0; i < size; i++) {
					contents.add(handler.slots.get(i).getStack().copy());
				}
				container = Optional.of(new InspectorPayloads.ContainerView(handler.syncId,
						lastContainerId == handler.syncId ? lastContainerTitle : titleOf(client),
						contents, Optional.ofNullable(lastContainerPos)));
			}
		}

		return new InspectorPayloads.Snapshot(owner != null ? owner : player.getUuid(), inventory, container);
	}

	/**
	 * Whether what is open is a container, rather than something else wearing a screen handler.
	 *
	 * <p>A positive test on purpose. The creative inventory is the reason: it swaps
	 * {@code currentScreenHandler} for one of its own whose slots are the item list of whichever tab
	 * is showing, so "any handler that is not the player's" quietly recorded eighteen building
	 * blocks as the contents of a chest that was never opened.
	 */
	private static boolean isContainer(MinecraftClient client, ClientPlayerEntity player,
			ScreenHandler handler) {
		return handler != null
				&& handler != player.playerScreenHandler
				&& client.currentScreen instanceof HandledScreen<?>
				&& !(client.currentScreen instanceof CreativeInventoryScreen);
	}

	/**
	 * How many of a screen handler's slots belong to the container rather than to the player.
	 *
	 * <p>Every vanilla container puts the player's own thirty-six last, and so does everything built
	 * on the same base, which on a server means the plugins too.
	 */
	private static int containerSize(ScreenHandler handler) {
		int total = handler.slots.size();
		// Fewer than the player's own thirty-six means this is not laid out like a container at
		// all. Recording nothing beats recording the player's inventory a second time under the
		// name of whatever was open.
		return total > 36 ? total - 36 : 0;
	}

	private static Text titleOf(MinecraftClient client) {
		if (client.currentScreen instanceof HandledScreen<?> screen) {
			return screen.getTitle();
		}
		return client.currentScreen != null ? client.currentScreen.getTitle() : Text.empty();
	}

	private static BlockPos lookedAtBlock(MinecraftClient client) {
		return client.crosshairTarget instanceof BlockHitResult hit ? hit.getBlockPos() : null;
	}

	/** Reads one slot of our own layout off the live player. */
	private static ItemStack read(ClientPlayerEntity player, int index) {
		EquipmentSlot equipment = InventoryLayout.equipmentFor(index);
		if (equipment != null) {
			return player.getEquippedStack(equipment);
		}
		return player.getInventory().getStack(index);
	}

	private void write(Recorder recorder, CustomPayload payload) {
		if (failed) {
			return;
		}
		try {
			recorder.writePacketAsync(new CustomPayloadS2CPacket(payload), NetworkPhase.PLAY);
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
		lastContainerTitle = Text.empty();
		owner = null;
		lastPlayer = null;
		tickCounter = 0;
		failed = false;
	}
}
