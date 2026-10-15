package cc.sighs.autojs.neoforge.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The area query behind {@code AutoJS.area(...)}.
 *
 * <p>Reading a region naively is a trap in three ways, and this class is shaped around all three:
 *
 * <ol>
 *     <li><b>Size.</b> A radius of 128 is 17 million positions. Every distribution is therefore reduced to its
 *     top entries, and the two full-scan steps (columns, and the volume census) are sampled with a stride that
 *     keeps them under a fixed budget. What was reduced is reported in {@code cost} and {@code notes} rather
 *     than hidden.</li>
 *     <li><b>Side effects.</b> Asking a {@code Level} about a position outside a loaded chunk loads, and at
 *     world generation time even generates, that chunk. A debug query must not do that, so the scan takes a
 *     snapshot of the already loaded chunks with {@code getChunkNow} and reads nothing else. A box that is
 *     mostly unloaded reports little, and says so.</li>
 *     <li><b>Main thread cost.</b> Everything here runs on the game thread because it reads live state, so the
 *     budgets are deliberately modest.</li>
 * </ol>
 *
 * <p>The answer is JSON: {@code dimension}, {@code center}, {@code box}, {@code cost}, {@code height},
 * {@code biomes}, {@code surface}, {@code blocks}, {@code blockEntities}, {@code blockEntityList},
 * {@code entities}, {@code entityList}, {@code structures} and {@code notes}. Empty sections are left out.
 */
public final class AreaScan {
    private static final int DEFAULT_RADIUS = 32;
    private static final int DEFAULT_TOP = 10;
    private static final double MAX_RADIUS = 128;
    private static final int MAX_COLUMNS = 16384;
    private static final int MAX_VOLUME_SAMPLES = 200_000;

    private AreaScan() {
    }

    /** A point to centre on, without depending on any coordinate type. */
    private record Point(double x, double y, double z) {
    }

    public static String scan(Level level, Object centre, double radius, JsonElement options) {
        var point = resolve(centre);
        if (point == null) {
            throw new IllegalArgumentException("no centre: pass an entity, a BlockPos, a {x,y,z} object or an "
                    + "[x,y,z] array, or be on a side that has a player");
        }

        var opts = options != null && options.isJsonObject() ? options.getAsJsonObject() : new JsonObject();
        var half = clamp(radius <= 0 ? DEFAULT_RADIUS : radius, 1, MAX_RADIUS);
        var halfY = clamp(number(opts, "vertical", half), 1, MAX_RADIUS * 2);
        var top = (int) clamp(number(opts, "top", DEFAULT_TOP), 1, 200);
        var entityList = (int) clamp(number(opts, "entities", 0), 0, 100);
        var withVolume = bool(opts, "volume", true);
        var withStructures = bool(opts, "structures", true);

        var started = System.nanoTime();
        var notes = new ArrayList<String>();

        var minX = (int) Math.floor(point.x - half);
        var minY = (int) Math.floor(point.y - halfY);
        var minZ = (int) Math.floor(point.z - half);
        var maxX = (int) Math.ceil(point.x + half);
        var maxY = (int) Math.ceil(point.y + halfY);
        var maxZ = (int) Math.ceil(point.z + half);

        // Snapshot of what the game already has. Nothing below loads or generates a chunk.
        var chunks = loadedChunks(level, minX, minZ, maxX, maxZ);
        var loaded = loadedKeys(chunks);
        var chunksInBox = ((maxX >> 4) - (minX >> 4) + 1) * ((maxZ >> 4) - (minZ >> 4) + 1);

        notes.add("only chunks that were already loaded are read; nothing was loaded or generated");
        if (chunks.isEmpty()) {
            notes.add("no loaded chunk in the box, so there is nothing to describe");
        } else if (chunks.size() < chunksInBox) {
            notes.add(chunks.size() + " of " + chunksInBox + " chunks in the box are loaded; the rest reported nothing");
        }

        var columnStride = strideFor((long) (maxX - minX + 1) * (maxZ - minZ + 1), MAX_COLUMNS);
        var biomes = new HashMap<String, Integer>();
        var surface = new HashMap<String, Integer>();
        var heights = new IntSummaryStatistics();
        var columns = scanColumns(level, loaded, minX, minZ, maxX, maxZ, columnStride, biomes, surface, heights);

        Map<String, Integer> blocks = Map.of();
        var volumeSamples = 0;
        var volumeStride = 0;
        if (withVolume && !chunks.isEmpty()) {
            volumeStride = strideFor((long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1),
                    MAX_VOLUME_SAMPLES);
            var counted = new HashMap<String, Integer>();
            volumeSamples = scanVolume(level, loaded, minX, minY, minZ, maxX, maxY, maxZ, volumeStride, counted);
            blocks = counted;
        }

        var blockEntities = new HashMap<String, Integer>();
        var blockEntityList = new JsonArray();
        var structures = new LinkedHashSet<String>();
        scanChunks(level, chunks, minX, minY, minZ, maxX, maxY, maxZ, blockEntities, blockEntityList, top,
                structures, withStructures);

        var json = new JsonObject();
        json.addProperty("dimension", level.dimension().location().toString());
        json.add("center", arrayOf(round(point.x), round(point.y), round(point.z)));
        json.add("box", box(minX, minY, minZ, maxX, maxY, maxZ));

        var cost = new JsonObject();
        cost.addProperty("ms", (System.nanoTime() - started) / 1_000_000L);
        cost.addProperty("chunksInBox", chunksInBox);
        cost.addProperty("chunksLoaded", chunks.size());
        cost.addProperty("columnsScanned", columns);
        cost.addProperty("columnStride", columnStride);
        cost.addProperty("volumeSamples", volumeSamples);
        cost.addProperty("volumeStride", volumeStride);
        json.add("cost", cost);

        if (heights.getCount() > 0) {
            var height = new JsonObject();
            height.addProperty("min", heights.getMin());
            height.addProperty("max", heights.getMax());
            height.addProperty("avg", round(heights.getAverage()));
            json.add("height", height);
        }
        if (!biomes.isEmpty()) {
            json.add("biomes", topEntries(biomes, top, "biomes", notes));
        }
        if (!surface.isEmpty()) {
            json.add("surface", topEntries(surface, top, "surface blocks", notes));
        }
        if (!blocks.isEmpty()) {
            json.add("blocks", topEntries(blocks, top, "block types", notes));
        }
        if (!blockEntities.isEmpty()) {
            json.add("blockEntities", topEntries(blockEntities, top, "block entity types", notes));
            json.add("blockEntityList", blockEntityList);
        }
        scanEntities(level, minX, minY, minZ, maxX, maxY, maxZ, entityList, json, notes);
        if (withStructures && !structures.isEmpty()) {
            var array = new JsonArray();
            structures.forEach(array::add);
            json.add("structures", array);
        }

        if (withStructures && level.registryAccess().registry(Registries.STRUCTURE).isEmpty()) {
            notes.add("structures are not listed: this side has no structure registry, which is normal on a client");
        }
        if (columnStride > 1) {
            notes.add("columns sampled every " + columnStride + " blocks");
        }
        if (volumeStride > 1) {
            notes.add("blocks sampled every " + volumeStride + " blocks");
        }
        if (!withVolume) {
            notes.add("volume census skipped (volume=false)");
        }

        var noteArray = new JsonArray();
        notes.forEach(noteArray::add);
        json.add("notes", noteArray);
        return json.toString();
    }

    // ---------------------------------------------------------------- columns

    private static int scanColumns(Level level, Set<Long> loaded, int minX, int minZ, int maxX, int maxZ, int stride,
                                   Map<String, Integer> biomes, Map<String, Integer> surface,
                                   IntSummaryStatistics heights) {
        var columns = 0;
        for (var x = minX; x <= maxX; x += stride) {
            for (var z = minZ; z <= maxZ; z += stride) {
                if (!loaded.contains(key(x >> 4, z >> 4))) {
                    continue;
                }

                var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) {
                    continue;
                }

                columns++;
                // getHeight returns the y of the topmost block of that heightmap, not the air above it.
                // MOTION_BLOCKING is the ground, so leaves, snow layers and the like do not hide the terrain.
                var height = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
                heights.accept(height);
                var surfacePos = new BlockPos(x, height, z);

                add(surface, registryId(BuiltInRegistries.BLOCK, level.getBlockState(surfacePos).getBlock()));
                add(biomes, biomeId(level.getBiome(surfacePos)));
            }
        }
        return columns;
    }

    private static int scanVolume(Level level, Set<Long> loaded, int minX, int minY, int minZ, int maxX, int maxY,
                                  int maxZ, int stride, Map<String, Integer> counted) {
        var samples = 0;
        for (var y = minY; y <= maxY; y += stride) {
            for (var x = minX; x <= maxX; x += stride) {
                for (var z = minZ; z <= maxZ; z += stride) {
                    if (!loaded.contains(key(x >> 4, z >> 4))) {
                        continue;
                    }
                    samples++;
                    add(counted, registryId(BuiltInRegistries.BLOCK, level.getBlockState(new BlockPos(x, y, z)).getBlock()));
                }
            }
        }
        return samples;
    }

    // ----------------------------------------------------------------- chunks

    private static void scanChunks(Level level, List<LevelChunk> chunks, int minX, int minY, int minZ, int maxX,
                                   int maxY, int maxZ, Map<String, Integer> blockEntities, JsonArray blockEntityList,
                                   int top, Set<String> structures, boolean withStructures) {
        // Structures are a datapack registry, so they are read from the level rather than BuiltInRegistries, and a
        // client may not have that registry synced at all: then there is simply nothing to report.
        var structureRegistry = level.registryAccess().registry(Registries.STRUCTURE).orElse(null);

        for (var chunk : chunks) {
            for (var entry : chunk.getBlockEntities().entrySet()) {
                var pos = entry.getKey();
                if (!inside(pos, minX, minY, minZ, maxX, maxY, maxZ)) {
                    continue;
                }

                BlockEntity blockEntity = entry.getValue();
                var id = registryId(BuiltInRegistries.BLOCK_ENTITY_TYPE, blockEntity.getType());
                add(blockEntities, id);
                if (blockEntityList.size() < top) {
                    var json = new JsonObject();
                    json.addProperty("id", id);
                    json.add("pos", arrayOf(pos.getX(), pos.getY(), pos.getZ()));
                    blockEntityList.add(json);
                }
            }

            if (!withStructures || structureRegistry == null) {
                continue;
            }
            for (Structure structure : chunk.getAllStarts().keySet()) {
                structures.add(registryId(structureRegistry, structure));
            }
        }
    }

    private static void scanEntities(Level level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                     int listLimit, JsonObject json, List<String> notes) {
        var box = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        var entities = level.getEntities((Entity) null, box);
        if (entities.isEmpty()) {
            return;
        }

        var byType = new HashMap<String, Integer>();
        for (var entity : entities) {
            add(byType, registryId(BuiltInRegistries.ENTITY_TYPE, entity.getType()));
        }

        var summary = new JsonObject();
        summary.addProperty("total", entities.size());
        summary.add("byType", topEntries(byType, DEFAULT_TOP, "entity types", notes));
        json.add("entities", summary);

        if (listLimit > 0) {
            var list = new JsonArray();
            for (var entity : entities) {
                if (list.size() >= listLimit) {
                    break;
                }
                var entry = new JsonObject();
                entry.addProperty("type", registryId(BuiltInRegistries.ENTITY_TYPE, entity.getType()));
                entry.addProperty("name", entity.getName().getString());
                entry.add("pos", arrayOf(round(entity.getX()), round(entity.getY()), round(entity.getZ())));
                entry.addProperty("alive", entity.isAlive());
                list.add(entry);
            }
            json.add("entityList", list);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The chunks the game already has in the box. */
    private static List<LevelChunk> loadedChunks(Level level, int minX, int minZ, int maxX, int maxZ) {
        var loaded = new ArrayList<LevelChunk>();
        for (var cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (var cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk != null) {
                    loaded.add(chunk);
                }
            }
        }
        return loaded;
    }

    private static Set<Long> loadedKeys(List<LevelChunk> chunks) {
        var keys = new HashSet<Long>();
        for (var chunk : chunks) {
            keys.add(key(chunk.getPos().x, chunk.getPos().z));
        }
        return keys;
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static boolean inside(BlockPos pos, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    /** How far apart to sample so that {@code total} positions fit in {@code budget}. */
    private static int strideFor(long total, long budget) {
        if (total <= budget) {
            return 1;
        }
        return (int) Math.ceil(Math.cbrt((double) total / budget));
    }

    private static JsonObject topEntries(Map<String, Integer> counts, int limit, String label, List<String> notes) {
        var sorted = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .toList();

        var json = new JsonObject();
        sorted.stream().limit(limit).forEach(entry -> json.addProperty(entry.getKey(), entry.getValue()));
        if (sorted.size() > limit) {
            notes.add(label + ": " + sorted.size() + " kinds, showing the top " + limit + " (raise 'top' for more)");
        }
        return json;
    }

    private static void add(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static <T> String registryId(Registry<T> registry, T value) {
        var key = registry.getKey(value);
        return key == null ? "unregistered" : key.toString();
    }

    private static String biomeId(Holder<net.minecraft.world.level.biome.Biome> biome) {
        return biome.unwrapKey().map(key -> key.location().toString()).orElse("unknown");
    }

    private static Point resolve(Object centre) {
        return switch (centre) {
            case null -> null;
            case Entity entity -> new Point(entity.getX(), entity.getY(), entity.getZ());
            case BlockPos pos -> new Point(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            case JsonElement json -> fromJson(json);
            default -> null;
        };
    }

    private static Point fromJson(JsonElement json) {
        if (json.isJsonArray() && json.getAsJsonArray().size() >= 3) {
            var array = json.getAsJsonArray();
            return new Point(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
        }
        if (json.isJsonObject()) {
            var object = json.getAsJsonObject();
            if (object.has("x") && object.has("y") && object.has("z")) {
                return new Point(object.get("x").getAsDouble(), object.get("y").getAsDouble(),
                        object.get("z").getAsDouble());
            }
        }
        return null;
    }

    private static JsonObject box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        var json = new JsonObject();
        json.add("min", arrayOf(minX, minY, minZ));
        json.add("max", arrayOf(maxX, maxY, maxZ));
        json.add("size", arrayOf(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1));
        return json;
    }

    private static JsonArray arrayOf(double... values) {
        var array = new JsonArray();
        for (var value : values) {
            array.add(value);
        }
        return array;
    }

    private static double number(JsonObject options, String key, double fallback) {
        return options.has(key) && options.get(key).isJsonPrimitive() ? options.get(key).getAsDouble() : fallback;
    }

    private static boolean bool(JsonObject options, String key, boolean fallback) {
        return options.has(key) && options.get(key).isJsonPrimitive() ? options.get(key).getAsBoolean() : fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Two decimals is plenty for averages and coordinates, and keeps the output readable. */
    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
