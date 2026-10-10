package com.glamardor.flashbackinspector.playback;

import com.glamardor.flashbackinspector.FlashbackInspector;
import com.glamardor.flashbackinspector.mixin.ReplayServerAccessor;
import com.glamardor.flashbackinspector.net.InspectorPayloads;
import com.moulberry.flashback.playback.PlayableChunk;
import com.moulberry.flashback.playback.ReplayServer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.DynamicRegistryManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything this mod put into a replay, read straight out of the chunk files, each with the tick
 * it belongs to.
 *
 * <p>Waiting for Flashback to play the payloads is not enough. Some never reach the client: when the
 * recording moves from a proxy lobby to the server behind it, the registries change, Flashback puts
 * the viewer through configuration again, and a payload played on that tick arrives in the wrong
 * phase and is thrown away. The snapshot at the head of the next chunk would have repaired it, but
 * Flashback only plays snapshots when seeking. And a log built from what went past only ever knew
 * the stretches that had been watched. Reading the file sidesteps all of it.
 */
public final class ReplayIndex {
	private static final String ACTION_NEXT_TICK = "flashback:action/next_tick";
	private static final String ACTION_GAME_PACKET = "flashback:action/game_packet";
	private static final byte[] PREFIX = (InspectorPayloads.NAMESPACE + ":").getBytes(StandardCharsets.UTF_8);

	/** One payload of ours and the tick it was written on, in file order. */
	public record Entry(int tick, CustomPayload payload) {
	}

	private final List<Entry> entries;

	private ReplayIndex(List<Entry> entries) {
		this.entries = entries;
	}

	public List<Entry> entries() {
		return entries;
	}

	/** Reads every chunk of the replay. Slow on a long recording, so not on the render thread. */
	public static ReplayIndex load(ReplayServer server, DynamicRegistryManager registries) throws Exception {
		TreeMap<Integer, PlayableChunk> chunks = ((ReplayServerAccessor) server).flashbackinspector$chunks();
		List<Entry> entries = new ArrayList<>();
		for (Map.Entry<Integer, PlayableChunk> chunk : new TreeMap<>(chunks).entrySet()) {
			try {
				readChunk(Files.readAllBytes(chunk.getValue().path), chunk.getKey(), registries, entries);
			} catch (Exception e) {
				// One unreadable chunk costs that stretch, not the whole replay.
				FlashbackInspector.LOGGER.warn("Could not read {} of the replay", chunk.getValue().path, e);
			}
		}
		return new ReplayIndex(entries);
	}

	/**
	 * The chunk layout, as Flashback writes it: a magic int, the names of the actions used in this
	 * chunk, the snapshot's length, then the snapshot and the ticks after it as one run of actions.
	 * Every action is its index into the names, an int length and that many bytes, so anything that
	 * is not ours is stepped over without being understood.
	 */
	private static void readChunk(byte[] bytes, int startTick, DynamicRegistryManager registries,
			List<Entry> out) {
		ByteBuf buf = Unpooled.wrappedBuffer(bytes);
		buf.readInt();
		int count = readVarInt(buf);
		String[] actions = new String[count];
		for (int i = 0; i < count; i++) {
			byte[] name = new byte[readVarInt(buf)];
			buf.readBytes(name);
			actions[i] = new String(name, StandardCharsets.UTF_8);
		}
		buf.readInt();

		int tick = startTick;
		while (buf.isReadable()) {
			int action = readVarInt(buf);
			int size = buf.readInt();
			if (action < 0 || action >= count || size < 0 || size > buf.readableBytes()) {
				throw new IllegalStateException("Chunk out of step at byte " + buf.readerIndex());
			}
			int start = buf.readerIndex();
			buf.skipBytes(size);
			if (ACTION_NEXT_TICK.equals(actions[action])) {
				tick++;
			} else if (ACTION_GAME_PACKET.equals(actions[action])) {
				CustomPayload payload = decode(buf.slice(start, size), registries);
				if (payload != null) {
					out.add(new Entry(tick, payload));
				}
			}
		}
	}

	/** A game packet, if it is one of ours: packet id, identifier, then the payload's own bytes. */
	private static CustomPayload decode(ByteBuf packet, DynamicRegistryManager registries) {
		readVarInt(packet);
		if (!packet.isReadable()) {
			return null;
		}
		int length = readVarInt(packet);
		if (length <= PREFIX.length || length > packet.readableBytes()) {
			return null;
		}
		for (int i = 0; i < PREFIX.length; i++) {
			if (packet.getByte(packet.readerIndex() + i) != PREFIX[i]) {
				return null;
			}
		}
		String path = packet.toString(packet.readerIndex() + PREFIX.length, length - PREFIX.length,
				StandardCharsets.UTF_8);
		packet.skipBytes(length);

		PacketCodec<RegistryByteBuf, ? extends CustomPayload> codec = switch (path) {
			case "snapshot" -> InspectorPayloads.Snapshot.CODEC;
			case "slots" -> InspectorPayloads.Slots.CODEC;
			case "container" -> InspectorPayloads.ContainerEvent.CODEC;
			default -> null;
		};
		if (codec == null) {
			return null;
		}
		try {
			return codec.decode(new RegistryByteBuf(packet, registries));
		} catch (Exception e) {
			FlashbackInspector.LOGGER.warn("Skipping an unreadable {} payload", path, e);
			return null;
		}
	}

	private static int readVarInt(ByteBuf buf) {
		int value = 0;
		int shift = 0;
		byte b;
		do {
			b = buf.readByte();
			value |= (b & 0x7F) << shift;
			shift += 7;
			if (shift > 35) {
				throw new IllegalStateException("VarInt too long");
			}
		} while ((b & 0x80) != 0);
		return value;
	}
}
