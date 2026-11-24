package com.glamardor.flashbackinspector.test;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Collects the checks of a game test and writes them to port26-test.log in the run directory. */
final class TestLog {
	private final List<String> lines = new ArrayList<>();
	private int failures;

	TestLog(String title) {
		lines.add("== " + title);
	}

	/** Mutes the client and applies every mixin of every mod at once, so a bad target shows up here. */
	void startup(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0));
		try {
			ctx.runOnClient(mc -> MixinEnvironment.getCurrentEnvironment().audit());
			check("mixin audit (all targets loaded and transformed)", true);
		} catch (Throwable e) {
			fail("mixin audit: " + e);
		}
	}

	void info(String s) {
		lines.add("     " + s);
		flush();
	}

	boolean check(String what, boolean ok) {
		lines.add((ok ? "ok   " : "FAIL ") + what);
		if (!ok) {
			failures++;
		}
		flush();
		return ok;
	}

	void eq(String what, Object actual, Object expected) {
		check(what + ": " + actual + (java.util.Objects.equals(actual, expected) ? "" : " (expected " + expected + ")"),
				java.util.Objects.equals(actual, expected));
	}

	void range(String what, double actual, double min, double max) {
		check(what + ": " + String.format(Locale.ROOT, "%.3f", actual) + " (expected " + min + ".." + max + ")",
				actual >= min && actual <= max);
	}

	void fail(String what) {
		check(what, false);
	}

	void finish() {
		lines.add(failures == 0 ? "PASSED" : failures + " FAILED");
		flush();
	}

	private void flush() {
		try {
			Files.write(Path.of("port26-test.log"), lines);
		} catch (IOException ignored) {
		}
	}
}
