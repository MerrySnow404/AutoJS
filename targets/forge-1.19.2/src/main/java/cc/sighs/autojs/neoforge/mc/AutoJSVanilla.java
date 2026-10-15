package cc.sighs.autojs.neoforge.mc;

import cc.sighs.autojs.mc.VanillaApi;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
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
 * {@link VanillaApi} for Minecraft 1.19.2.
 *
 * <p>Each method is the call the shared scripts used to make, kept here because the name or the argument list is
 * this version's. This is the earliest generation AutoJS has a target for, so it is the far side of every change
 * the interface documents:
 *
 * <ul>
 *     <li>there is no {@code BuiltInRegistries} and no {@code Registries} yet — both arrive at 1.19.3 — so each
 *     registry is the matching field of {@code Registry} itself, and a datapack registry is named by its
 *     {@code Registry.*_REGISTRY} key;</li>
 *     <li>{@code Entity.getLevel()} and {@code Entity.isOnGround()} are the pre-1.20 spellings;</li>
 *     <li>{@code BlockState} has no {@code blocksMotion()} yet: the answer still comes off the {@code Material}
 *     the state carries;</li>
 *     <li>a save takes neither registry access nor the plain two-argument forms the interface describes:
 *     {@code ItemStack.save(CompoundTag)} and {@code BlockEntity.saveWithFullMetadata()};</li>
 *     <li>a recipe is a bare {@code CraftingRecipe} with its own {@code getId()}, not a {@code RecipeHolder}, and
 *     its result is computed with no argument at all — {@code getResultItem()} only starts taking the level's
 *     registry access at 1.20.5;</li>
 *     <li>{@code Ingredient.isEmpty()} exists here and is asked directly: it disappears again in later
 *     generations until 1.21 brings it back, which is why the 1.20.1 target has to count the item list
 *     instead;</li>
 *     <li>{@code MobEffectInstance.getEffect()} hands back a bare {@code MobEffect}, not a {@code Holder};</li>
 *     <li>{@code Level} has no registry access at all before 1.19.3 — only {@code ServerLevel} and
 *     {@code ClientLevel} declare it, so the two are asked one after the other; and</li>
 *     <li>vanilla has no crafter menu yet, so {@link #addedMenuKind} has nothing to recognise.</li>
 * </ul>
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

        /**
         * The tags a value belongs to, read off its holder.
         *
         * <p>There is no {@code wrapAsHolder} to do this in one call yet (1.21.1 added it), so the value is
         * turned into a holder through the registry's own key lookup — the same route without the convenience
         * method.
         */
        @Override
        public List<String> tagIds(T value) {
            ResourceKey<T> key = registry.getResourceKey(value).orElse(null);
            if (key == null) {
                return List.of();
            }
            return registry.getHolder(key)
                    .map(holder -> holder.tags().map(tag -> tag.location().toString()).toList())
                    .orElse(List.of());
        }
    }

    @Override
    public Lookup<Item> items() {
        return new RegistryLookup<>(Registry.ITEM);
    }

    @Override
    public Lookup<Block> blocks() {
        return new RegistryLookup<>(Registry.BLOCK);
    }

    @Override
    public Lookup<EntityType<?>> entityTypes() {
        return new RegistryLookup<>(Registry.ENTITY_TYPE);
    }

    @Override
    public Lookup<BlockEntityType<?>> blockEntityTypes() {
        return new RegistryLookup<>(Registry.BLOCK_ENTITY_TYPE);
    }

    /** This version saves into a plain tag: the registry access it would later take does not exist yet. */
    @Override
    public Tag saveStack(Level level, ItemStack stack) {
        return level == null ? null : stack.save(new CompoundTag());
    }

    @Override
    public Tag saveBlockEntity(Level level, BlockEntity blockEntity) {
        return level == null ? null : blockEntity.saveWithFullMetadata();
    }

    @Override
    public Level level(Entity entity) {
        return entity.getLevel();
    }

    @Override
    public boolean onGround(Entity entity) {
        return entity.isOnGround();
    }

    /** The old route: 1.19.2's {@code BlockState} answers this through the material it still carries. */
    @Override
    public boolean blocksMotion(BlockState state) {
        return state.getMaterial().blocksMotion();
    }

    /** {@code Ingredient.isEmpty()} exists in this version, so emptiness is the ingredient's own answer. */
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

    /** A bare {@code MobEffect}, so its registry id is the registry's own answer. */
    @Override
    public String effectId(MobEffectInstance effect) {
        var key = Registry.MOB_EFFECT.getKey(effect.getEffect());
        return key == null ? null : key.toString();
    }

    @Override
    public void runCommand(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    /** Null: the crafter menu this answers for is a 1.21 block, and 1.19.2 has no such menu. */
    @Override
    public String addedMenuKind(AbstractContainerMenu menu) {
        return null;
    }

    @Override
    public List<RecipeEntry> craftingRecipes(Player player) {
        var crafting = new ArrayList<RecipeEntry>();
        for (var recipe : recipes(player)) {
            crafting.add(new RecipeEntry(recipe.getId().toString(), recipe));
        }
        return crafting;
    }

    /** No argument yet: the registry access {@code getResultItem} later takes does not exist in this version. */
    @Override
    public ItemStack craftingResult(Player player, CraftingRecipe recipe) {
        return recipe.getResultItem();
    }

    @Override
    public boolean hasStructureRegistry(Level level) {
        var registryAccess = registryAccessOf(level);
        return registryAccess != null && registryAccess.registry(Registry.STRUCTURE_REGISTRY).isPresent();
    }

    /**
     * The structures of this chunk, named through the level's structure registry.
     *
     * <p>Structures are a datapack registry, so they are read from the level rather than from
     * {@code Registry}, and a side that never received it has nothing to report: {@link #registryAccessOf} is what
     * turns "this level" into the registries it actually holds.
     */
    @Override
    public List<String> structureIds(Level level, LevelChunk chunk) {
        var registryAccess = registryAccessOf(level);
        var structureRegistry = registryAccess == null
                ? null
                : registryAccess.registry(Registry.STRUCTURE_REGISTRY).orElse(null);
        if (structureRegistry == null) {
            return List.of();
        }

        var ids = new ArrayList<String>();
        for (Structure structure : chunk.getAllStarts().keySet()) {
            ids.add(registryId(structureRegistry, structure));
        }
        return ids;
    }

    /**
     * The registries a level holds, or null when it holds none worth reading.
     *
     * <p>This is the one place where asking 1.19.2 is a two-branch question rather than one call:
     * {@code Level.registryAccess()} does not exist until 1.19.3, and only the two concrete levels declare it —
     * the dedicated or integrated server's level keeps the authoritative registries, and a client level keeps the
     * datapack registries the server synced to it. Both are asked, so a script scanning a singleplayer or
     * multiplayer client sees the same structures it would on the server.
     *
     * <p>Naming {@code ClientLevel} in a class a dedicated server loads is safe for the same reason
     * {@code ClientScriptBridge} is: the reference sits in a branch a server never takes, and a class is only
     * loaded when the instruction that names it first executes.
     */
    private static RegistryAccess registryAccessOf(Level level) {
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel.registryAccess();
        }
        if (level instanceof ClientLevel clientLevel) {
            return clientLevel.registryAccess();
        }
        return null;
    }

    /** Every crafting recipe the server knows. Empty on a client, which has no recipe manager. */
    private static List<CraftingRecipe> recipes(Player player) {
        var minecraftServer = serverOf(player);
        return minecraftServer == null
                ? List.of()
                : new ArrayList<>(minecraftServer.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING));
    }

    private static MinecraftServer serverOf(Player player) {
        if (player instanceof ServerPlayer server) {
            return server.getServer();
        }
        if (player.getLevel() instanceof ServerLevel serverLevel) {
            return serverLevel.getServer();
        }
        return null;
    }

    private static <T> String registryId(Registry<T> registry, T value) {
        var key = registry.getKey(value);
        return key == null ? "unregistered" : key.toString();
    }
}
