package com.glamardor.flashbackinspector.net;

import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The three things this mod puts into a replay.
 *
 * <p>They ride the recording as ordinary clientbound custom payloads, which is the one kind of
 * packet Flashback copies into the file byte for byte and hands back to the viewing client
 * untouched. Two consequences worth remembering. A replay recorded with this mod opens perfectly
 * well without it: every action in the file is length prefixed on its own, so an unknown payload is
 * read as opaque bytes and thrown away without disturbing the packet after it. And nothing here may
 * ever be sent to a server – these only exist on the way into a file and on the way out of one.
 */
public final class InspectorPayloads {
	public static final String NAMESPACE = "flashbackinspector";

	private InspectorPayloads() {
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(NAMESPACE, path);
	}

	/** One slot and what was in it. */
	public record SlotChange(int slot, ItemStack stack) {
		public static final StreamCodec<RegistryFriendlyByteBuf, SlotChange> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, SlotChange::slot,
				ItemStack.OPTIONAL_STREAM_CODEC, SlotChange::stack,
				SlotChange::new);
	}

	/**
	 * Everything at once: who was recording, their whole inventory, and the container they had open
	 * if there was one.
	 *
	 * <p>Sent when recording starts and again into every one of Flashback's snapshots. That second
	 * one is what makes seeking work – jump anywhere in the replay and Flashback rebuilds the world
	 * from the nearest snapshot, so the inventory has to be in there too or it would arrive empty.
	 */
	public record Snapshot(UUID owner, List<ItemStack> inventory, Optional<ContainerView> container)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Snapshot> ID = new CustomPacketPayload.Type<>(id("snapshot"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC.cast(), Snapshot::owner,
				ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), Snapshot::inventory,
				ByteBufCodecs.optional(ContainerView.CODEC), Snapshot::container,
				Snapshot::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** An open container, described in full. */
	public record ContainerView(int containerId, Component title, List<ItemStack> contents, Optional<BlockPos> pos) {
		public static final StreamCodec<RegistryFriendlyByteBuf, ContainerView> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, ContainerView::containerId,
				ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC, ContainerView::title,
				ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), ContainerView::contents,
				ByteBufCodecs.optional(BlockPos.STREAM_CODEC.cast()), ContainerView::pos,
				ContainerView::new);
	}

	/**
	 * Slots that changed since the previous tick.
	 *
	 * <p>{@code containerId} of zero means the player's own inventory; anything else is the sync id
	 * of the container that was open, which is what makes an item visibly travel from one grid to
	 * the other when the replay is watched with the inspector up.
	 */
	public record Slots(int containerId, List<SlotChange> changes) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Slots> ID = new CustomPacketPayload.Type<>(id("slots"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Slots> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Slots::containerId,
				SlotChange.CODEC.apply(ByteBufCodecs.list()), Slots::changes,
				Slots::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** A container was opened, with everything that was in it, or the open one was closed. */
	public record ContainerEvent(boolean opened, ContainerView view) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ContainerEvent> ID = new CustomPacketPayload.Type<>(id("container"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ContainerEvent> CODEC = StreamCodec.composite(
				ByteBufCodecs.BOOL.<RegistryFriendlyByteBuf>cast(), ContainerEvent::opened,
				ContainerView.CODEC, ContainerEvent::view,
				ContainerEvent::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}
}
