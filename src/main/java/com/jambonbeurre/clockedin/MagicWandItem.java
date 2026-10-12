package com.jambonbeurre.clockedin;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * Pick a villager once, then hand him his bed and his workstation with one click each -
 * the selection sticks, so setting both for the same villager is three clicks total and
 * never needs re-picking him. Sneak-click him to clear what he has.
 *
 * <p>Both interactions run from NeoForge's interaction events rather than the item's own
 * useOn/interactLivingEntity, because vanilla lets the target act first: a villager would
 * open its trades and a bed would put the player to sleep before the item got a say.
 */
@EventBusSubscriber(modid = ClockedIn.MODID)
public class MagicWandItem extends Item {

    /** Assignments are local, so only look for a rival in the neighbourhood of the spot. */
    private static final double OCCUPANT_SEARCH_RADIUS = 64.0;

    public MagicWandItem(Properties properties) {
        super(properties);
    }

    // Highest priority, and cancelled events too: lecterns holding a Patchouli, Modonomicon or
    // CyclopsCore book have those mods cancel the click first, and the wand must still win.
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        ItemStack stack = event.getItemStack();
        if (!stack.is(ClockedIn.MAGIC_WAND.get())) return;
        if (!(event.getTarget() instanceof Villager villager)) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);
        if (event.getLevel().isClientSide) return;

        Player player = event.getEntity();
        if (player.isShiftKeyDown()) {
            villager.setData(ClockedIn.VILLAGER_ASSIGNMENT.get(), VillagerAssignment.EMPTY);
            player.displayClientMessage(Component.translatable("message.clockedin.wand.cleared"), true);
            return;
        }

        stack.set(ClockedIn.WAND_VILLAGER.get(), villager.getUUID());
        player.displayClientMessage(Component.translatable("message.clockedin.wand.selected",
                villager.getName()), true);
        reportRegistry(player, villager);
    }

    /**
     * What this villager is tied to right now, whether we assigned it or he found it himself -
     * the brain memories are the truth either way, so a villager that latched onto a decorative
     * block shows up here before anyone wonders why he is a fletcher.
     */
    private static void reportRegistry(Player player, Villager villager) {
        VillagerAssignment assignment = villager.getData(ClockedIn.VILLAGER_ASSIGNMENT.get());
        player.sendSystemMessage(Component.translatable("message.clockedin.registry.header", villager.getName())
                .withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.translatable("message.clockedin.registry.profession",
                Component.translatable(villager.getVillagerData().getProfession().name())));
        player.sendSystemMessage(line("message.clockedin.registry.bed",
                villager.getBrain().getMemory(MemoryModuleType.HOME).orElse(null), assignment.home().isPresent()));
        player.sendSystemMessage(line("message.clockedin.registry.job",
                villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null), assignment.job().isPresent()));
    }

    private static Component line(String key, GlobalPos pos, boolean manual) {
        if (pos == null) {
            return Component.translatable(key, Component.translatable("message.clockedin.registry.none"))
                    .withStyle(ChatFormatting.GRAY);
        }
        Component where = Component.literal(pos.pos().getX() + ", " + pos.pos().getY() + ", " + pos.pos().getZ());
        Component source = Component.translatable(manual
                ? "message.clockedin.registry.manual"
                : "message.clockedin.registry.auto");
        return Component.translatable(key, Component.empty().append(where).append(" ").append(source))
                .withStyle(manual ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
    }

    // Highest priority, and cancelled events too: lecterns holding a Patchouli, Modonomicon or
    // CyclopsCore book have those mods cancel the click first, and the wand must still win.
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        if (!stack.is(ClockedIn.MAGIC_WAND.get())) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        Player player = event.getEntity();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        boolean isBed = state.getBlock() instanceof BedBlock;
        Optional<Holder<PoiType>> poi = PoiTypes.forState(state);
        if (!isBed && poi.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.clockedin.wand.invalid_target"), true);
            return;
        }

        // For a double bed always store the head half, the position vanilla uses as the HOME poi.
        BlockPos target = isBed && state.getValue(BedBlock.PART) == BedPart.FOOT
                ? pos.relative(state.getValue(BedBlock.FACING))
                : pos;
        GlobalPos globalTarget = GlobalPos.of(level.dimension(), target);

        Villager villager = selectedVillager(stack, level);
        if (villager == null) {
            // No villager in hand: use the wand as a lookup instead, and say who holds this spot.
            Villager holder = currentOccupant(level, globalTarget, null, isBed);
            player.displayClientMessage(holder == null
                    ? Component.translatable("message.clockedin.wand.free")
                    : Component.translatable(isBed
                            ? "message.clockedin.wand.bed_taken"
                            : "message.clockedin.wand.job_taken", holder.getName()), true);
            return;
        }

        // The wand is absolute: whoever had this spot, vanilla or a previous assignment, loses it.
        MemoryModuleType<GlobalPos> memory = isBed ? MemoryModuleType.HOME : MemoryModuleType.JOB_SITE;
        Villager owner = currentOccupant(level, globalTarget, villager, isBed);
        if (owner != null) {
            VillagerAssignmentHandler.evict(level, owner, memory, globalTarget);
            player.sendSystemMessage(Component.translatable(
                    isBed ? "message.clockedin.wand.bed_taken_over" : "message.clockedin.wand.job_taken_over",
                    owner.getName(), villager.getName()).withStyle(ChatFormatting.YELLOW));
        }

        // One bed and one job only: the one he had before is let go.
        VillagerAssignment current = villager.getData(ClockedIn.VILLAGER_ASSIGNMENT.get());
        Optional<GlobalPos> previous = isBed ? current.home() : current.job();
        previous.filter(old -> !old.equals(globalTarget)).ifPresent(old -> VillagerAssignmentHandler.release(level, old));
        villager.getBrain().getMemory(memory).filter(old -> !old.equals(globalTarget))
                .ifPresent(old -> VillagerAssignmentHandler.release(level, old));
        villager.getBrain().eraseMemory(memory);

        if (isBed) {
            villager.setData(ClockedIn.VILLAGER_ASSIGNMENT.get(), current.withHome(globalTarget));
            player.displayClientMessage(Component.translatable("message.clockedin.wand.assigned_bed",
                    villager.getName()), true);
        } else {
            villager.setData(ClockedIn.VILLAGER_ASSIGNMENT.get(), current.withJob(globalTarget));
            player.displayClientMessage(Component.translatable("message.clockedin.wand.assigned_job",
                    villager.getName()), true);
        }

        VillagerAssignmentHandler.applyAssignment(villager, level);
    }

    /**
     * The villager already living or working here, if any - checked both against our own
     * assignments and against vanilla's brain memories, so a spot vanilla handed out on its
     * own is not silently stolen.
     */
    private static Villager currentOccupant(ServerLevel level, GlobalPos target, Villager except, boolean bed) {
        AABB searchArea = new AABB(target.pos()).inflate(OCCUPANT_SEARCH_RADIUS);
        MemoryModuleType<GlobalPos> memory = bed ? MemoryModuleType.HOME : MemoryModuleType.JOB_SITE;
        for (Villager other : level.getEntitiesOfClass(Villager.class, searchArea)) {
            if (other == except) continue;
            VillagerAssignment assignment = other.getData(ClockedIn.VILLAGER_ASSIGNMENT.get());
            Optional<GlobalPos> assigned = bed ? assignment.home() : assignment.job();
            if (assigned.filter(target::equals).isPresent()) return other;
            if (other.getBrain().getMemory(memory).filter(target::equals).isPresent()) return other;
        }
        return null;
    }

    private static Villager selectedVillager(ItemStack stack, ServerLevel level) {
        UUID id = stack.get(ClockedIn.WAND_VILLAGER.get());
        if (id == null) return null;
        return level.getEntity(id) instanceof Villager villager && villager.isAlive() ? villager : null;
    }

    /** The profession whose job site is this block, so assigning a job also sets the trade. */
    static Optional<VillagerProfession> professionFor(BlockState state) {
        return PoiTypes.forState(state).flatMap(MagicWandItem::professionFor);
    }

    private static Optional<VillagerProfession> professionFor(Holder<PoiType> poi) {
        for (VillagerProfession profession : BuiltInRegistries.VILLAGER_PROFESSION) {
            if (profession.heldJobSite().test(poi)) return Optional.of(profession);
        }
        return Optional.empty();
    }
}
