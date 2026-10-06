package com.jambonbeurre.clockedin;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Keeps a villager on the one bed and the one workstation the wand gave him, whatever vanilla
 * decides. Three things make that hold:
 * <ul>
 *   <li>The spots are claimed in the POI manager (their "tickets"), like vanilla claims them, so
 *   no other villager can take them, and anything he grabbed on his own is released at once.</li>
 *   <li>The profession follows the workstation, and the brain is rebuilt after a profession change
 *   as vanilla does: the brain's job-site check is built from the profession, so without the
 *   rebuild it judged the new workstation invalid, dropped it, and the villager lost his job
 *   again in a loop.</li>
 *   <li>Where he should be is decided from the time of day: walked there first, teleported if he
 *   is still not there a few seconds later.</li>
 * </ul>
 */
@EventBusSubscriber(modid = ClockedIn.MODID)
public final class VillagerAssignmentHandler {

    private static final int CHECK_INTERVAL_TICKS = 20;
    /** Vanilla's villager schedule works from 2000 and starts heading home at 12000. */
    private static final long WORK_START = 2000L;
    private static final long WORK_END = 9000L;
    private static final long REST_START = 12000L;
    /** Close enough to count as "there". */
    private static final double NEAR_DISTANCE = 3.0;
    private static final float WALK_SPEED = 0.6F;
    /** 10 checks at 1 s each: he gets 10 seconds to walk there before being put there. */
    private static final int CHECKS_BEFORE_TELEPORT = 10;
    /** Assignments are local, so rivals for a spot are only looked for around it. */
    static final double OWNER_SEARCH_RADIUS = 64.0;

    private static final Map<Villager, Integer> STRAY_CHECKS = new WeakHashMap<>();

    private VillagerAssignmentHandler() {
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        if (!(villager.level() instanceof ServerLevel level)) return;
        if (villager.tickCount % CHECK_INTERVAL_TICKS != 0) return;

        VillagerAssignment assignment = villager.getData(ClockedIn.VILLAGER_ASSIGNMENT.get());
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

    /** Puts the assignment back in force: memories, POI claims and profession. */
    static void applyAssignment(Villager villager, ServerLevel level) {
        VillagerAssignment assignment = villager.getData(ClockedIn.VILLAGER_ASSIGNMENT.get());
        assignment.home()
                .filter(pos -> pos.dimension().equals(level.dimension()))
                .ifPresent(pos -> hold(villager, level, MemoryModuleType.HOME, pos));
        assignment.job()
                .filter(pos -> pos.dimension().equals(level.dimension()))
                .ifPresent(pos -> {
                    // A villager with a job never looks for another one; drop any he had in view.
                    villager.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE)
                            .filter(potential -> !potential.equals(pos))
                            .ifPresent(potential -> release(level, potential));
                    villager.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
                    hold(villager, level, MemoryModuleType.JOB_SITE, pos);
                    enforceProfession(villager, level, pos);
                });
    }

    /**
     * Makes {@code target} the villager's one and only bed or workstation. Fast path when nothing
     * changed; otherwise releases what he holds instead, claims the target and takes it from
     * whoever had it.
     */
    private static void hold(Villager villager, ServerLevel level, MemoryModuleType<GlobalPos> memory, GlobalPos target) {
        Brain<Villager> brain = villager.getBrain();
        Optional<GlobalPos> current = brain.getMemory(memory);
        if (current.filter(target::equals).isPresent()) return;
        current.ifPresent(pos -> release(level, pos));
        if (level.isLoaded(target.pos())) claim(level, villager, memory, target);
        brain.setMemory(memory, target);
    }

    /** Takes a ticket on the spot, evicting any other villager that holds it first. */
    static void claim(ServerLevel level, Villager owner, MemoryModuleType<GlobalPos> memory, GlobalPos target) {
        PoiManager poi = level.getPoiManager();
        if (poi.getType(target.pos()).isEmpty()) return; // the block is gone; nothing to claim
        if (poi.getFreeTickets(target.pos()) == 0) {
            AABB area = new AABB(target.pos()).inflate(OWNER_SEARCH_RADIUS);
            for (Villager other : level.getEntitiesOfClass(Villager.class, area)) {
                if (other != owner && other.getBrain().getMemory(memory).filter(target::equals).isPresent()) {
                    evict(level, other, memory, target);
                }
            }
        }
        if (poi.getFreeTickets(target.pos()) > 0) {
            poi.take(type -> true, (type, pos) -> pos.equals(target.pos()), target.pos(), 1);
        }
    }

    /** Takes a spot away from a villager: memory, POI ticket and, if the wand gave it to him, his assignment. */
    static void evict(ServerLevel level, Villager villager, MemoryModuleType<GlobalPos> memory, GlobalPos target) {
        villager.getBrain().eraseMemory(memory);
        release(level, target);
        VillagerAssignment assignment = villager.getData(ClockedIn.VILLAGER_ASSIGNMENT.get());
        if (memory == MemoryModuleType.HOME && assignment.home().filter(target::equals).isPresent()) {
            villager.setData(ClockedIn.VILLAGER_ASSIGNMENT.get(), assignment.withoutHome());
        } else if (memory == MemoryModuleType.JOB_SITE && assignment.job().filter(target::equals).isPresent()) {
            villager.setData(ClockedIn.VILLAGER_ASSIGNMENT.get(), assignment.withoutJob());
        }
    }

    static void release(ServerLevel level, GlobalPos pos) {
        if (pos.dimension().equals(level.dimension()) && level.isLoaded(pos.pos())) {
            level.getPoiManager().release(pos.pos());
        }
    }

    /**
     * The wand is absolute: the profession always follows the workstation, even for a villager who
     * has traded - he starts again at level 1, and his old offers go with his old job (vanilla
     * drops them on a profession change). The brain is then rebuilt, exactly as vanilla does after
     * giving a profession.
     */
    private static void enforceProfession(Villager villager, ServerLevel level, GlobalPos job) {
        if (!level.isLoaded(job.pos())) return;
        MagicWandItem.professionFor(level.getBlockState(job.pos())).ifPresent(profession -> {
            VillagerData data = villager.getVillagerData();
            if (data.getProfession() == profession) return;
            villager.setVillagerData(data.setProfession(profession).setLevel(1));
            villager.setVillagerXp(0);
            villager.refreshBrain(level);
        });
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
