package cc.sighs.autojs.neoforge.mc;

import cc.sighs.autojs.mc.VanillaApi;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link VanillaApi} for Minecraft 1.21.1.
 *
 * <p>Each method is the call the shared scripts used to make, kept here because the name or the argument list is
 * this version's: {@code BuiltInRegistries} holds every registry, {@code Entity.level()} is the accessor,
 * {@code BlockState.blocksMotion()} is the state's own answer, a recipe arrives as a {@code RecipeHolder} and its
 * result needs the level's registry access, and {@code CrafterMenu} is a menu kind 1.20.1 does not have.
 *
 * <p>The tag lookup answers with {@code wrapAsHolder} rather than {@code builtInRegistryHolder}, because the
 * latter is deprecated in this version.
 */
public final class AutoJSVanilla implements VanillaApi {
    /** Stateless: every answer is a read of a registry or of the level passed in. */
    public static final AutoJSVanilla INSTANCE = new AutoJSVanilla();

    private AutoJSVanilla() {
    }

    /**
     * One of the built-in registries, with the four questions the shared code asks of every one of them.
     *
     * <p>Ids cross the interface as text, so this is where the text becomes this version's
     * {@code ResourceLocation} and back. An id that will not parse is not registered, which is exactly the
     * answer {@link Lookup#get(String)} documents for a malformed id.
     */
    private record RegistryLookup<T>(Registry<T> registry) implements Lookup<T> {
        @Override
        public T get(String id) {
            var key = ResourceLocation.tryParse(id);
            return key == null ? null : registry.getOptional(key).orElse(null);
        }

        @Override
        public List<String> ids() {
            return registry.keySet().stream().map(ResourceLocation::toString).toList();
        }

        @Override
        public String idOf(T value) {
            var key = registry.getKey(value);
            return key == null ? null : key.toString();
        }

        @Override
        public List<String> tagIds(T value) {
            return registry.wrapAsHolder(value).tags().map(tag -> tag.location().toString()).toList();
        }
    }

    @Override
    public Lookup<Item> items() {
        return new RegistryLookup<>(BuiltInRegistries.ITEM);
    }

    @Override
    public Lookup<Block> blocks() {
        return new RegistryLookup<>(BuiltInRegistries.BLOCK);
    }

    @Override
    public Lookup<EntityType<?>> entityTypes() {
        return new RegistryLookup<>(BuiltInRegistries.ENTITY_TYPE);
    }

    @Override
    public Lookup<BlockEntityType<?>> blockEntityTypes() {
        return new RegistryLookup<>(BuiltInRegistries.BLOCK_ENTITY_TYPE);
    }

    @Override
    public Tag saveStack(Level level, ItemStack stack) {
        return level == null ? null : stack.save(level.registryAccess());
    }

    @Override
    public Tag saveBlockEntity(Level level, BlockEntity blockEntity) {
        return level == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
    }

    @Override
    public Level level(Entity entity) {
        return entity.level();
    }

    @Override
    public boolean onGround(Entity entity) {
        return entity.onGround();
    }

    @Override
    public boolean blocksMotion(BlockState state) {
        return state.blocksMotion();
    }

    @Override
    public boolean ingredientEmpty(Ingredient ingredient) {
        return ingredient.isEmpty();
    }

    /** The level's own day time: {@code Level.getDayTime()} throughout this version's life. */
    @Override
    public long dayTime(Level level) {
        return level.getDayTime();
    }

    /** The setter sits on the {@code ServerLevel} itself in this version. */
    @Override
    public void setDayTime(ServerLevel level, long ticks) {
        level.setDayTime(ticks);
    }

    /** The weather setter sits on the {@code ServerLevel} itself in this version. */
    @Override
    public void setWeather(ServerLevel level, int clearTime, int rainTime, boolean raining, boolean thundering) {
        level.setWeatherParameters(clearTime, rainTime, raining, thundering);
    }

    /** {@code ResourceKey.location()} is this version's spelling of the id a key names. */
    @Override
    public String keyId(ResourceKey<?> key) {
        return key.location().toString();
    }

    /** {@code getMinBuildHeight()} is the lowest block of the world in this version. */
    @Override
    public int minY(Level level) {
        return level.getMinBuildHeight();
    }

    /** {@code getMaxBuildHeight()} is one past the highest block here, so the last block is one less. */
    @Override
    public int maxY(Level level) {
        return level.getMaxBuildHeight() - 1;
    }

    /** A public field in this version. */
    @Override
    public int chunkX(ChunkPos pos) {
        return pos.x;
    }

    /** The other public field. */
    @Override
    public int chunkZ(ChunkPos pos) {
        return pos.z;
    }

    /** {@code Inventory.getSelected()} is this version's accessor for the hotbar selection. */
    @Override
    public ItemStack selectedStack(Player player) {
        return player.getInventory().getSelected();
    }

    /** {@code Direction.getNormal()} is this version's name for the block step. */
    @Override
    public Vec3i directionStep(Direction direction) {
        return direction.getNormal();
    }

    /** One call in this version: the spawn reason {@code EntityType.create} later takes does not exist yet. */
    @Override
    public Entity createEntity(EntityType<?> type, Level level) {
        return type.create(level);
    }

    /** {@code Entity.moveTo} is this version's name for the move that keeps the rotation. */
    @Override
    public void moveEntity(Entity entity, double x, double y, double z) {
        entity.moveTo(x, y, z);
    }

    /** {@code Recipe.getIngredients()} is this version's accessor, and its list is already the recipe's order. */
    @Override
    public List<Ingredient> craftingIngredients(CraftingRecipe recipe) {
        return List.copyOf(recipe.getIngredients());
    }

    /** {@code Ingredient.getItems()} is this version's accessor: one stack per alternative. */
    @Override
    public List<Item> ingredientItems(Ingredient ingredient) {
        var items = new ArrayList<Item>();
        for (var option : ingredient.getItems()) {
            items.add(option.getItem());
        }
        return items;
    }

    @Override
    public String effectId(MobEffectInstance effect) {
        return effect.getEffect().unwrapKey().map(key -> key.location().toString()).orElse(null);
    }

    @Override
    public void runCommand(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    @Override
    public String addedMenuKind(AbstractContainerMenu menu) {
        return menu instanceof CrafterMenu ? "crafter" : null;
    }

    @Override
    public List<RecipeEntry> craftingRecipes(Player player) {
        var crafting = new ArrayList<RecipeEntry>();
        for (var holder : recipes(player)) {
            if (holder.value() instanceof CraftingRecipe recipe) {
                crafting.add(new RecipeEntry(holder.id().toString(), recipe));
            }
        }
        return crafting;
    }

    @Override
    public ItemStack craftingResult(Player player, CraftingRecipe recipe) {
        return recipe.getResultItem(player.level().registryAccess());
    }

    @Override
    public boolean hasStructureRegistry(Level level) {
        return !level.registryAccess().registry(Registries.STRUCTURE).isEmpty();
    }

    /**
     * The structures of this chunk, named through the level's structure registry.
     *
     * <p>Structures are a datapack registry, so they are read from the level rather than from
     * {@code BuiltInRegistries}, and a client may not have that registry synced at all: then there is simply
     * nothing to report.
     */
    @Override
    public List<String> structureIds(Level level, LevelChunk chunk) {
        var structureRegistry = level.registryAccess().registry(Registries.STRUCTURE).orElse(null);
        if (structureRegistry == null) {
            return List.of();
        }

        var ids = new ArrayList<String>();
        for (Structure structure : chunk.getAllStarts().keySet()) {
            ids.add(registryId(structureRegistry, structure));
        }
        return ids;
    }

    /** Every recipe the server knows. Empty on a client, which has no recipe manager. */
    private static List<RecipeHolder<?>> recipes(Player player) {
        if (player instanceof ServerPlayer server) {
            var minecraftServer = server.getServer();
            if (minecraftServer != null) {
                return new ArrayList<>(minecraftServer.getRecipeManager().getRecipes());
            }
        }
        var level = player.level();
        if (level instanceof ServerLevel serverLevel) {
            var minecraftServer = serverLevel.getServer();
            return new ArrayList<>(minecraftServer.getRecipeManager().getRecipes());
        }
        return List.of();
    }

    private static <T> String registryId(Registry<T> registry, T value) {
        var key = registry.getKey(value);
        return key == null ? "unregistered" : key.toString();
    }
}
