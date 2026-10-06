package com.jambonbeurre.clockedin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;

import java.util.Optional;

/**
 * A manual, permanent bed/workstation assignment for one villager, stored as a
 * NeoForge data attachment so it survives saving and chunk reloads - unlike the
 * villager's brain memories, which vanilla clears and reassigns on its own.
 */
public record VillagerAssignment(Optional<GlobalPos> home, Optional<GlobalPos> job) {

    public static final VillagerAssignment EMPTY = new VillagerAssignment(Optional.empty(), Optional.empty());

    public static final Codec<VillagerAssignment> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            GlobalPos.CODEC.optionalFieldOf("home").forGetter(VillagerAssignment::home),
            GlobalPos.CODEC.optionalFieldOf("job").forGetter(VillagerAssignment::job)
    ).apply(instance, VillagerAssignment::new));

    public VillagerAssignment withHome(GlobalPos pos) {
        return new VillagerAssignment(Optional.of(pos), job);
    }

    public VillagerAssignment withJob(GlobalPos pos) {
        return new VillagerAssignment(home, Optional.of(pos));
    }

    public VillagerAssignment withoutHome() {
        return new VillagerAssignment(Optional.empty(), job);
    }

    public VillagerAssignment withoutJob() {
        return new VillagerAssignment(home, Optional.empty());
    }

    public boolean isEmpty() {
        return home.isEmpty() && job.isEmpty();
    }
}
