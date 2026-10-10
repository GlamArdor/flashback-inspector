package com.glamardor.flashbackinspector.playback;

import com.glamardor.flashbackinspector.FlashbackInspector;
import com.glamardor.flashbackinspector.InventoryLayout;
import com.glamardor.flashbackinspector.net.InspectorPayloads;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The viewing half: what the recording player was carrying at the tick currently on screen.
 *
 * <p>Worked out from {@link ReplayIndex}, which reads the payloads straight out of the replay file,
 * so the state at any tick is known whether or not Flashback managed to deliver the packets that
 * led there, and the container log has the whole recording from the start. Until the index has
 * loaded, the packets Flashback plays fill in as before.
 */
public final class InspectorState {
	private static final InspectorState INSTANCE = new InspectorState();

	private UUID owner;
	private final ItemStack[] inventory = new ItemStack[InventoryLayout.SIZE];

	private int containerId = -1;
	private Text containerTitle = Text.empty();
	private ItemStack[] containerContents = new ItemStack[0];
	private BlockPos containerPos;

	/** Every container opening seen so far, keyed by the replay tick it happened on. */
	private final TreeMap<Integer, ContainerOpening> openings = new TreeMap<>();

	/** The replay the index below belongs to, so the next replay opened gets an index of its own. */
	private ReplayServer indexedServer;
	private CompletableFuture<ReplayIndex> loading;
	private ReplayIndex index;
	/** The tick the state was last worked out for, and the next index entry not yet applied. */
	private int syncedTick = -1;
	private int cursor;

	/** An opening, as shown in the log. */
	public record ContainerOpening(int tick, Text title, @Nullable BlockPos pos, int size) {
	}

	private InspectorState() {
		clear();
	}

	public static InspectorState get() {
		return INSTANCE;
	}

	public void clear() {
		indexedServer = null;
		loading = null;
		index = null;
		syncedTick = -1;
		cursor = 0;
		resetPlayback();
		openings.clear();
	}

	private void resetPlayback() {
		owner = null;
		java.util.Arrays.fill(inventory, ItemStack.EMPTY);
		containerId = -1;
		containerTitle = Text.empty();
		containerContents = new ItemStack[0];
		containerPos = null;
	}

	// --- the index ---

	/**
	 * Called every client tick in a replay. Starts reading the file the first time, and once it has
	 * been read, brings the state to the tick on screen.
	 */
	public void tick() {
		ReplayServer server = replayServer();
		MinecraftClient client = MinecraftClient.getInstance();
		if (server == null || client.getNetworkHandler() == null) {
			return;
		}
		if (server != indexedServer) {
			clear();
			indexedServer = server;
			var registries = client.getNetworkHandler().getRegistryManager();
			loading = CompletableFuture.supplyAsync(() -> {
				try {
					return ReplayIndex.load(server, registries);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
		}
		if (index == null && loading != null && loading.isDone()) {
			try {
				index = loading.join();
				openings.clear();
				for (ReplayIndex.Entry entry : index.entries()) {
					if (entry.payload() instanceof InspectorPayloads.ContainerEvent event && event.opened()) {
						addOpening(entry.tick(), event.view());
					}
				}
				syncedTick = -1;
			} catch (Exception e) {
				// Left on the packets, which is how it worked before there was an index at all.
				FlashbackInspector.LOGGER.warn("Could not read the replay file, following the packets instead", e);
			}
			loading = null;
		}
		if (index != null) {
			syncTo(server.getReplayTick());
		}
	}

	private boolean indexed() {
		return index != null;
	}

	/**
	 * Moves the state to a tick. Forward, only what happened since is applied; backward, it starts
	 * over from the beginning, which is a few thousand slot writes at the very most. Every snapshot
	 * on the way overwrites everything, so starting earlier than needed is never wrong.
	 */
	private void syncTo(int tick) {
		if (tick == syncedTick) {
			return;
		}
		if (tick < syncedTick || syncedTick < 0) {
			resetPlayback();
			cursor = 0;
		}
		List<ReplayIndex.Entry> entries = index.entries();
		while (cursor < entries.size() && entries.get(cursor).tick() <= tick) {
			switch (entries.get(cursor).payload()) {
				case InspectorPayloads.Snapshot snapshot -> doApplySnapshot(snapshot);
				case InspectorPayloads.Slots slots -> doApplySlots(slots);
				case InspectorPayloads.ContainerEvent event -> doApplyContainerEvent(event);
				default -> {
				}
			}
			cursor++;
		}
		syncedTick = tick;
	}

	// --- reading ---

	public boolean hasData() {
		return owner != null;
	}

	@Nullable
	public UUID owner() {
		return owner;
	}

	public boolean isOwner(UUID uuid) {
		return owner != null && owner.equals(uuid);
	}

	public ItemStack inventory(int index) {
		return index >= 0 && index < inventory.length ? inventory[index] : ItemStack.EMPTY;
	}

	public boolean hasContainer() {
		return containerId != -1;
	}

	public Text containerTitle() {
		return containerTitle;
	}

	public int containerSize() {
		return containerContents.length;
	}

	public ItemStack container(int index) {
		return index >= 0 && index < containerContents.length ? containerContents[index] : ItemStack.EMPTY;
	}

	@Nullable
	public BlockPos containerPos() {
		return containerPos;
	}

	public List<ContainerOpening> openings() {
		return new ArrayList<>(openings.values());
	}

	public boolean hasOpenings() {
		return !openings.isEmpty();
	}

	// --- writing, from the packet receivers ---

	public void applySnapshot(InspectorPayloads.Snapshot snapshot) {
		if (!indexed()) {
			doApplySnapshot(snapshot);
		}
	}

	public void applySlots(InspectorPayloads.Slots slots) {
		if (!indexed()) {
			doApplySlots(slots);
		}
	}

	public void applyContainerEvent(InspectorPayloads.ContainerEvent event) {
		if (indexed()) {
			return;
		}
		doApplyContainerEvent(event);
		if (event.opened()) {
			int tick = currentTick();
			if (tick >= 0) {
				addOpening(tick, event.view());
			}
		}
	}

	private void doApplySnapshot(InspectorPayloads.Snapshot snapshot) {
		owner = snapshot.owner();
		List<ItemStack> stacks = snapshot.inventory();
		for (int i = 0; i < inventory.length; i++) {
			inventory[i] = i < stacks.size() ? stacks.get(i) : ItemStack.EMPTY;
		}
		snapshot.container().ifPresentOrElse(this::openContainer, this::closeContainer);
	}

	private void doApplySlots(InspectorPayloads.Slots slots) {
		if (slots.containerId() == 0) {
			for (InspectorPayloads.SlotChange change : slots.changes()) {
				if (change.slot() >= 0 && change.slot() < inventory.length) {
					inventory[change.slot()] = change.stack();
				}
			}
			return;
		}
		if (slots.containerId() != containerId) {
			// A delta for a container we never saw opened, which happens when the replay is opened
			// partway through. Nothing sensible to apply it to; the next snapshot will sort it out.
			return;
		}
		for (InspectorPayloads.SlotChange change : slots.changes()) {
			if (change.slot() >= 0 && change.slot() < containerContents.length) {
				containerContents[change.slot()] = change.stack();
			}
		}
	}

	private void doApplyContainerEvent(InspectorPayloads.ContainerEvent event) {
		if (event.opened()) {
			openContainer(event.view());
		} else {
			closeContainer();
		}
	}

	private void openContainer(InspectorPayloads.ContainerView view) {
		containerId = view.containerId();
		containerTitle = view.title();
		containerContents = view.contents().toArray(new ItemStack[0]);
		containerPos = view.pos().orElse(null);
	}

	private void closeContainer() {
		containerId = -1;
		containerTitle = Text.empty();
		containerContents = new ItemStack[0];
		containerPos = null;
	}

	/**
	 * Adds an opening to the log.
	 *
	 * <p>Keyed by tick, so watching the same stretch twice – which happens on every seek when the
	 * log is built from packets – leaves one entry rather than one per pass.
	 */
	private void addOpening(int tick, InspectorPayloads.ContainerView view) {
		openings.put(tick, new ContainerOpening(tick, view.title(), view.pos().orElse(null),
				view.contents().size()));
	}

	/** The replay tick on screen, or -1 when this is not a replay. */
	public static int currentTick() {
		ReplayServer server = replayServer();
		return server == null ? -1 : server.getReplayTick();
	}

	public static int totalTicks() {
		ReplayServer server = replayServer();
		return server == null ? 0 : server.getTotalReplayTicks();
	}

	/** Moves the replay to a tick and stops it there. */
	public static void seek(int tick) {
		ReplayServer server = replayServer();
		if (server != null) {
			server.replayPaused = true;
			server.goToReplayTick(tick);
		}
	}

	public static void pause() {
		ReplayServer server = replayServer();
		if (server != null) {
			server.replayPaused = true;
		}
	}

	@Nullable
	public static ReplayServer replayServer() {
		return MinecraftClient.getInstance().getServer() instanceof ReplayServer server ? server : null;
	}

	/** A tick count as {@code h:mm:ss}, the way Flashback's own timeline reads. */
	public static String timecode(int tick) {
		int seconds = tick / 20;
		return String.format("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
	}
}
