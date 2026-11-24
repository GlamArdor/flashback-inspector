package com.glamardor.flashbackinspector.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Puts the settings button on our entry in the Mod Menu list. */
public class InspectorModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> com.glamardor.flashbackinspector.gui.ConfigScreenFactory.create(parent);
	}
}
