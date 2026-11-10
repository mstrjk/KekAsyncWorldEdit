package teacommontea.kawe.op;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import java.util.function.Predicate;

final class WorldGen {

    private static final ResourceKey<Registry<Object>> CONFIGURED = ResourceKey.createRegistryKey(Identifier.withDefaultNamespace("worldgen/configured_feature"));
    private static final ResourceKey<Registry<Object>> FEATURE = ResourceKey.createRegistryKey(Identifier.withDefaultNamespace("worldgen/feature"));
    private static final MethodType PLACE = MethodType.methodType(boolean.class, WorldGenLevel.class, ChunkGenerator.class, RandomSource.class, BlockPos.class);
    private static final MethodHandle TEMPLATES = first(MinecraftServer.class, MethodType.methodType(StructureTemplateManager.class),
            "getStructureTemplateManager", "getStructureManager");
    private static final MethodHandle GENERATE_WITH_SAMPLER = virtual(Structure.class, "generate", MethodType.methodType(StructureStart.class, Holder.class,
            ResourceKey.class, RegistryAccess.class, ChunkGenerator.class, BiomeSource.class, Climate.Sampler.class, RandomState.class,
            StructureTemplateManager.class, long.class, ChunkPos.class, int.class, LevelHeightAccessor.class, Predicate.class));
    private static final MethodHandle GENERATE = GENERATE_WITH_SAMPLER != null ? null : virtual(Structure.class, "generate",
            MethodType.methodType(StructureStart.class, Holder.class, ResourceKey.class, RegistryAccess.class, ChunkGenerator.class, BiomeSource.class,
                    RandomState.class, StructureTemplateManager.class, long.class, ChunkPos.class, int.class, LevelHeightAccessor.class, Predicate.class));
    private static final MethodHandle SAMPLER = sampler();

    static {
        if (GENERATE_WITH_SAMPLER == null && GENERATE == null) {
            throw new ExceptionInInitializerError("No known Structure.generate signature");
        }
    }

    private WorldGen() {
    }

    private static MethodHandle virtual(Class<?> owner, String name, MethodType type) {
        try {
            return MethodHandles.publicLookup().findVirtual(owner, name, type);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static MethodHandle first(Class<?> owner, MethodType type, String... names) {
        for (String name : names) {
            MethodHandle found = virtual(owner, name, type);
            if (found != null) {
                return found;
            }
        }
        throw new ExceptionInInitializerError("No " + String.join(" or ", names) + " on " + owner.getName());
    }

    private static MethodHandle sampler() {
        MethodHandle legacy = virtual(RandomState.class, "sampler", MethodType.methodType(Climate.Sampler.class));
        if (legacy != null) {
            return legacy;
        }
        try {
            Class<?> context = Class.forName("net.minecraft.world.level.levelgen.densityfunction.SamplerContext");
            Class<?> builder = Class.forName("net.minecraft.world.level.levelgen.densityfunction.SamplerContext$Builder");
            Object built = builder.getMethod("build").invoke(builder.getMethod("enableCaches").invoke(context.getMethod("builder").invoke(null)));
            MethodHandle create = MethodHandles.publicLookup().findVirtual(RandomState.class, "createClimateSampler", MethodType.methodType(Climate.Sampler.class, context));
            return MethodHandles.insertArguments(create, 1, built);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static Registry<Object> features(RegistryAccess access) {
        Optional<Registry<Object>> configured = access.lookup(CONFIGURED);
        return configured.orElseGet(() -> access.lookupOrThrow(FEATURE));
    }

    static Registry<Structure> structures(RegistryAccess access) {
        return access.lookupOrThrow(Registries.STRUCTURE);
    }

    static boolean place(Object feature, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos pos) {
        MethodHandle handle = virtual(feature.getClass(), "place", PLACE);
        if (handle == null) {
            throw new IllegalStateException("Feature " + feature.getClass().getName() + " has no place method");
        }
        try {
            return (boolean) handle.invoke(feature, level, generator, random, pos);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    static StructureTemplateManager templates(MinecraftServer server) {
        try {
            return (StructureTemplateManager) TEMPLATES.invoke(server);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    static StructureStart generate(ServerLevel level, Registry<Structure> registry, Structure structure, ChunkPos chunk, LevelHeightAccessor height) {
        ServerChunkCache source = level.getChunkSource();
        Predicate<Holder<Biome>> anyBiome = biome -> true;
        try {
            StructureTemplateManager templates = templates(level.getServer());
            if (GENERATE_WITH_SAMPLER != null) {
                Climate.Sampler sampler = (Climate.Sampler) SAMPLER.invoke(source.randomState());
                return (StructureStart) GENERATE_WITH_SAMPLER.invoke(structure, registry.wrapAsHolder(structure), level.dimension(), level.registryAccess(),
                        source.getGenerator(), source.getGenerator().getBiomeSource(), sampler, source.randomState(), templates, level.getSeed(), chunk, 0, height, anyBiome);
            }
            return (StructureStart) GENERATE.invoke(structure, registry.wrapAsHolder(structure), level.dimension(), level.registryAccess(),
                    source.getGenerator(), source.getGenerator().getBiomeSource(), source.randomState(), templates, level.getSeed(), chunk, 0, height, anyBiome);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
