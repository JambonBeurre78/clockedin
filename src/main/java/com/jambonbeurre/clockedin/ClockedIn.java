package com.jambonbeurre.clockedin;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.UUID;

/**
 * Clocked In: bind a villager to a bed and a workstation with the Magic Wand, and keep him on
 * schedule - at work during the day, in bed at night.
 */
@Mod(ClockedIn.MODID)
public class ClockedIn {

    public static final String MODID = "clockedin";

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, MODID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MODID);

    public static final DeferredItem<Item> MAGIC_WAND = ITEMS.registerItem("magic_wand",
            props -> new MagicWandItem(props.stacksTo(1)));

    /** The villager the magic wand currently has selected. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> WAND_VILLAGER =
            DATA_COMPONENTS.register("wand_villager", () -> DataComponentType.<UUID>builder()
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC)
                    .build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<VillagerAssignment>> VILLAGER_ASSIGNMENT =
            ATTACHMENT_TYPES.register("villager_assignment", () -> AttachmentType
                    .builder(() -> VillagerAssignment.EMPTY)
                    .serialize(VillagerAssignment.CODEC)
                    .build());

    public ClockedIn(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
        ATTACHMENT_TYPES.register(modEventBus);
        modEventBus.addListener(this::addToCreativeTab);
    }

    private void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(MAGIC_WAND.get());
        }
    }
}
