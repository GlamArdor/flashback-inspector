package com.glamardor.flashbackinspector;

import net.minecraft.world.entity.EquipmentSlot;

/**
 * The fixed order the inventory is written in.
 *
 * <p>Deliberately not {@code PlayerInventory}'s own indices. Those have been rearranged more than
 * once – 1.21.5 moved the worn items out of the inventory and into the entity's equipment – and a
 * replay is a file that outlives the version it was recorded on. This layout is ours, it is written
 * into the file, and it is read back the same way for as long as the mod exists.
 */
public final class InventoryLayout {
	public static final int HOTBAR_START = 0;
	public static final int HOTBAR_SIZE = 9;
	public static final int MAIN_START = 9;
	public static final int MAIN_SIZE = 27;
	public static final int HEAD = 36;
	public static final int CHEST = 37;
	public static final int LEGS = 38;
	public static final int FEET = 39;
	public static final int OFFHAND = 40;
	public static final int SIZE = 41;

	/** The worn slots, in the order they are drawn from the head down. */
	public static final EquipmentSlot[] ARMOUR = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	private InventoryLayout() {
	}

	/** The equipment slot an index stands for, or null when the index is a plain inventory slot. */
	public static EquipmentSlot equipmentFor(int index) {
		return switch (index) {
			case HEAD -> EquipmentSlot.HEAD;
			case CHEST -> EquipmentSlot.CHEST;
			case LEGS -> EquipmentSlot.LEGS;
			case FEET -> EquipmentSlot.FEET;
			case OFFHAND -> EquipmentSlot.OFFHAND;
			default -> null;
		};
	}
}
