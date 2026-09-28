package com.twogether.villagers;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps a manually assigned villager bound to his bed and workstation. Vanilla keeps clearing
 * and reassigning those memories on its own, so they are written back periodically.
 *
 * <p>Where he should be is decided from the time of day rather than from the brain's current
 * activity: a villager that drifts into MEET or IDLE would otherwise never be pushed to his
 * job at all. He is first walked there, and only teleported if he is still not there a few
 * seconds later - which is also what stops the pathfinder from retrying forever.
 */
@EventBusSubscriber(modid = TwoGetherVillagers.MODID)
public final class VillagerAssignmentHandler {

    private static final int CHECK_INTERVAL_TICKS = 40;
    /** Vanilla's villager schedule works from 2000 and starts heading home at 12000. */
    private static final long WORK_START = 2000L;
    private static final long WORK_END = 9000L;
    private static final long REST_START = 12000L;
    /** Close enough to count as "there". */
    private static final double NEAR_DISTANCE = 3.0;
    private static final float WALK_SPEED = 0.6F;
    /** 5 checks at 2s each: he gets 10 seconds to walk there before being put there. */
    private static final int CHECKS_BEFORE_TELEPORT = 5;

    private static final Map<Villager, Integer> STRAY_CHECKS = new WeakHashMap<>();

    private VillagerAssignmentHandler() {
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        if (!(villager.level() instanceof ServerLevel level)) return;
        if (villager.tickCount % CHECK_INTERVAL_TICKS != 0) return;

        VillagerAssignment assignment = villager.getData(TwoGetherVillagers.VILLAGER_ASSIGNMENT.get());
        if (assignment.isEmpty()) {
            STRAY_CHECKS.remove(villager);
            return;
        }

        applyAssignment(villager, level);

        long dayTime = level.getDayTime() % 24000L;
        GlobalPos destination = null;
        if (dayTime >= WORK_START && dayTime < WORK_END) {
            destination = assignment.job().orElse(null);
        } else if (dayTime >= REST_START) {
            destination = assignment.home().orElse(null);
        }

        if (destination == null) {
            STRAY_CHECKS.remove(villager);
            return;
        }
        keepAt(villager, level, destination);
    }

    /** Writes the assignment back into the brain, overriding whatever vanilla decided. */
    static void applyAssignment(Villager villager, ServerLevel level) {
        VillagerAssignment assignment = villager.getData(TwoGetherVillagers.VILLAGER_ASSIGNMENT.get());
        Brain<Villager> brain = villager.getBrain();
        assignment.home()
                .filter(pos -> pos.dimension().equals(level.dimension()))
                .ifPresent(pos -> brain.setMemory(MemoryModuleType.HOME, pos));
        assignment.job()
                .filter(pos -> pos.dimension().equals(level.dimension()))
                .ifPresent(pos -> {
                    brain.setMemory(MemoryModuleType.JOB_SITE, pos);
                    enforceProfession(villager, level, pos);
                });
    }

    /**
     * Keeps the trade matching the workstation we gave him. Without this he can drift back to a
     * profession he picked up on his own - this pack has plenty of decorative blocks that are
     * registered as job sites, and vanilla is happy to claim them.
     */
    private static void enforceProfession(Villager villager, ServerLevel level, GlobalPos job) {
        if (!level.isLoaded(job.pos())) return;
        MagicWandItem.professionFor(level.getBlockState(job.pos())).ifPresent(profession -> {
            if (villager.getVillagerData().getProfession() == profession) return;
            if (!canRetrain(villager)) return;
            villager.setVillagerData(villager.getVillagerData().setProfession(profession));
        });
    }

    /**
     * A villager who has already traded keeps the job he earned: his offers were rolled for that
     * profession, and swapping it underneath him would leave him with trades he can no longer
     * level up. Only the jobless and the untraded get retrained.
     */
    static boolean canRetrain(Villager villager) {
        return villager.getVillagerXp() == 0
                || villager.getVillagerData().getProfession() == VillagerProfession.NONE;
    }

    private static void keepAt(Villager villager, ServerLevel level, GlobalPos target) {
        if (!target.dimension().equals(level.dimension()) || !level.isLoaded(target.pos())) {
            STRAY_CHECKS.remove(villager);
            return;
        }

        if (villager.blockPosition().distSqr(target.pos()) <= NEAR_DISTANCE * NEAR_DISTANCE) {
            STRAY_CHECKS.remove(villager);
            return;
        }

        int strayed = STRAY_CHECKS.merge(villager, 1, Integer::sum);
        if (strayed < CHECKS_BEFORE_TELEPORT) {
            // Nudge first: a walk target survives whatever activity he is currently in.
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(target.pos(), WALK_SPEED, (int) NEAR_DISTANCE));
            return;
        }

        villager.teleportTo(target.pos().getX() + 0.5, target.pos().getY(), target.pos().getZ() + 0.5);
        villager.getNavigation().stop();
        STRAY_CHECKS.remove(villager);
    }
}
