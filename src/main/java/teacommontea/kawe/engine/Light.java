package teacommontea.kawe.engine;

import ca.spottedleaf.moonrise.patches.starlight.light.StarLightLightingProvider;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.function.IntConsumer;

public final class Light {

    private Light() {
    }

    public static void relight(KWorld world, Collection<ChunkPos> chunks, IntConsumer done) {
        ((StarLightLightingProvider) world.level().getChunkSource().getLightEngine()).starlight$serverRelightChunks(chunks, pos -> {
        }, done);
    }
}
