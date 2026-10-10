package com.glamardor.flashbackinspector.mixin;

import com.moulberry.flashback.playback.PlayableChunk;
import com.moulberry.flashback.playback.ReplayServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.TreeMap;

/**
 * The chunk files of the replay being watched, keyed by the tick each one starts on. The inspector
 * reads its own payloads straight out of them rather than waiting for Flashback to play them.
 */
@Mixin(value = ReplayServer.class, remap = false)
public interface ReplayServerAccessor {
	@Accessor("playableChunksByStart")
	TreeMap<Integer, PlayableChunk> flashbackinspector$chunks();
}
