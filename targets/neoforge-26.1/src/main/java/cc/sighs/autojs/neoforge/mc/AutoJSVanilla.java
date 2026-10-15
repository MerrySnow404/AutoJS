package cc.sighs.autojs.neoforge.mc;

import cc.sighs.autojs.mc.VanillaApi;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
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
 * {@link VanillaApi} for Minecraft 26.1.
 *
 * <p>Each method is the call the shared scripts used to make, kept here because the name, the argument list or
 * the type of this version's is. 26.1 is the biggest set of drifts AutoJS has had to absorb, and almost all of
 * them are renames or re-homings rather than new behaviour:
 *
 * <ul>
 *     <li>the registry key type is {@code Identifier} rather than {@code ResourceLocation}. Every id the seam
 *     carries is text, so that is one line of conversion per lookup here, which is where the interface's
 *     {@code "namespace:path"} contract is fulfilled;</li>
 *     <li>{@code ResourceKey.location()} becomes {@code identifier()}, which is the same conversion one level
 *     out: a key names a value instead of being one;</li>
 *     <li>{@code ItemStack.save(provider)} is gone: a stack encodes through {@code ItemStack.CODEC} against the
 *     level's registry-keyed {@code NbtOps}, which is what the old call did internally, empty stack and all;</li>
 *     <li>{@code Level.getDayTime()} and {@code ServerLevel.setDayTime} are gone with the level data field
 *     behind them: the day is the overworld world clock now, read through {@code getOverworldClockTime()} and
 *     written through the server's {@code ClockManager}; {@code setWeatherParameters} moves to the server,
 *     which finds its own weather data, with its argument list unchanged;</li>
 *     <li>the height accessor is renamed — {@code getMinY()} and {@code getMaxY()}, both inclusive, where the
 *     old pair was {@code getMinBuildHeight()} and a one-past-the-top {@code getMaxBuildHeight()} — and
 *     {@code ChunkPos} is a record, so its coordinates are accessors;</li>
 *     <li>{@code Inventory.getSelected()} becomes {@code getSelectedItem()}, {@code Direction.getNormal()}
 *     becomes {@code getUnitVec3i()}, and {@code EntityType.create(Level)} has to state an
 *     {@code EntitySpawnReason} — a script summoning an entity is the command case;</li>
 *     <li>{@code ServerPlayer.getServer()} is gone as well, so the server behind a player is reached through
 *     the level the player is in, which for a server player is a {@code ServerLevel};</li>
 *     <li>a recipe no longer exposes its ingredients or its result: the ingredients live in its
 *     {@code PlacementInfo}, and the result is the {@code SlotDisplay} of the crafting displays the recipe
 *     announces. Reading the result off the display is the same answer the old {@code getResultItem} gave for
 *     shaped and shapeless recipes and the same empty answer it gave for every other crafting recipe, because
 *     only those two ever produced a display;</li>
 *     <li>{@code Ingredient.getItems()} becomes {@code items()}, a stream of item holders rather than of
 *     stacks; and</li>
 *     <li>{@code CrafterMenu} is still the one menu kind 1.20.1 does not have.</li>
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
     * {@code Identifier} and back. An id that will not parse is not registered, which is exactly the answer
     * {@link Lookup#get(String)} documents for a malformed id.
     */
    private record RegistryLookup<T>(Registry<T> registry) implements Lookup<T> {
        @Override
        public T get(String id) {
            var key = Identifier.tryParse(id);
            return key == null ? null : registry.getOptional(key).orElse(null);
        }

        @Override
        public List<String> ids() {
            return registry.keySet().stream().map(Identifier::toString).toList();
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
    public String keyId(ResourceKey<?> key) {
        return key.identifier().toString();
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

    /**
     * The stack as NBT, encoded through its codec against the level's registries.
     *
     * <p>This is what {@code ItemStack.save(provider)} did on the versions that have it: the codec, the level's
     * registry-keyed {@code NbtOps}, and a failure rather than a silent empty tag when the stack is empty. The
     * message is the one those versions threw, so a script that hands {@code AutoJS.nbt} an empty stack reads
     * the same failure it always did.
     */
    @Override
    public Tag saveStack(Level level, ItemStack stack) {
        if (level == null) {
            return null;
        }
        if (stack.isEmpty()) {
            throw new IllegalStateException("Cannot encode empty ItemStack");
        }
        return ItemStack.CODEC
                .encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack)
                .getOrThrow();
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

    /**
     * The level's day, read off the overworld world clock.
     *
     * <p>{@code Level.getDayTime()} is gone here, and the number behind it is now a world clock's tick count.
     * The overworld clock is the one the old field tracked, and {@code getOverworldClockTime()} asks for it
     * whatever dimension the level is.
     */
    @Override
    public long dayTime(Level level) {
        return level.getOverworldClockTime();
    }

    /**
     * Sets the overworld clock, which is what setting the day time means here.
     *
     * <p>{@code ServerLevel.setDayTime} is gone; the clock lives on the server's {@code ClockManager}, keyed by
     * the overworld {@code WorldClock}, and the holder for it comes out of the level's own registries. This is
     * the same place vanilla's own time commands write, and it broadcasts the new time the same way.
     */
    @Override
    public void setDayTime(ServerLevel level, long ticks) {
        level.getServer().clockManager()
                .setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), ticks);
    }

    /** The weather data moved to the server; the four arguments did not. */
    @Override
    public void setWeather(ServerLevel level, int clearTime, int rainTime, boolean raining, boolean thundering) {
        level.getServer().setWeatherParameters(clearTime, rainTime, raining, thundering);
    }

    /** The renamed height accessor, which is inclusive on both ends here. */
    @Override
    public int minY(Level level) {
        return level.getMinY();
    }

    /** The inclusive top, where the older targets have to subtract one from {@code getMaxBuildHeight()}. */
    @Override
    public int maxY(Level level) {
        return level.getMaxY();
    }

    /** A record accessor here, a public field on the older targets. */
    @Override
    public int chunkX(ChunkPos pos) {
        return pos.x();
    }

    /** The other record accessor. */
    @Override
    public int chunkZ(ChunkPos pos) {
        return pos.z();
    }

    @Override
    public ItemStack selectedStack(Player player) {
        return player.getInventory().getSelectedItem();
    }

    @Override
    public Vec3i directionStep(Direction direction) {
        return direction.getUnitVec3i();
    }

    /** The spawn reason is the one vanilla's own {@code /summon} states. */
    @Override
    public Entity createEntity(EntityType<?> type, Level level) {
        return type.create(level, EntitySpawnReason.COMMAND);
    }

    /** The move itself is renamed {@code snapTo} here, argument list and effect unchanged. */
    @Override
    public void moveEntity(Entity entity, double x, double y, double z) {
        entity.snapTo(x, y, z);
    }

    @Override
    public String effectId(MobEffectInstance effect) {
        return effect.getEffect().unwrapKey().map(this::keyId).orElse(null);
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
                crafting.add(new RecipeEntry(keyId(holder.id()), recipe));
            }
        }
        return crafting;
    }

    /**
     * The recipe's result, read off the crafting display it announces.
     *
     * <p>A recipe no longer answers "what do I produce": the result of a shaped or shapeless recipe is the
     * stack the display carries, and a recipe with no display — every overridden crafting recipe, and every
     * smelting-style one — produces nothing a crafting grid can show, which is what {@code getResultItem}
     * answered for those on the versions that have it. The count comes with it, because the display holds the
     * same stack template the recipe crafts.
     */
    @Override
    public ItemStack craftingResult(Player player, CraftingRecipe recipe) {
        var context = SlotDisplayContext.fromLevel(player.level());
        for (var display : recipe.display()) {
            if (display instanceof ShapedCraftingRecipeDisplay shaped) {
                return shaped.result().resolveForFirstStack(context);
            }
            if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
                return shapeless.result().resolveForFirstStack(context);
            }
        }
        return ItemStack.EMPTY;
    }

    /** {@code RegistryAccess.registry} became {@code lookup} here; the answer means the same. */
    @Override
    public boolean hasStructureRegistry(Level level) {
        return level.registryAccess().lookup(Registries.STRUCTURE).isPresent();
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
        var structureRegistry = level.registryAccess().lookup(Registries.STRUCTURE).orElse(null);
        if (structureRegistry == null) {
            return List.of();
        }

        var ids = new ArrayList<String>();
        for (Structure structure : chunk.getAllStarts().keySet()) {
            ids.add(registryId(structureRegistry, structure));
        }
        return ids;
    }

    /** The recipe's own ingredient list: the ingredients sit in its placement info here. */
    @Override
    public List<Ingredient> craftingIngredients(CraftingRecipe recipe) {
        return recipe.placementInfo().ingredients();
    }

    /** One item per alternative, where {@code Ingredient.getItems()} used to hand back one stack per one. */
    @Override
    public List<Item> ingredientItems(Ingredient ingredient) {
        return ingredient.items().map(Holder::value).toList();
    }

    /**
     * Every recipe the server knows. Empty on a client, which has no recipe manager.
     *
     * <p>{@code ServerPlayer.getServer()} is gone here, but a player's level is: on the server that level is a
     * {@code ServerLevel}, which still knows the server it belongs to.
     */
    private static List<RecipeHolder<?>> recipes(Player player) {
        if (player.level() instanceof ServerLevel serverLevel) {
            return new ArrayList<>(serverLevel.getServer().getRecipeManager().getRecipes());
        }
        return List.of();
    }

    private static <T> String registryId(Registry<T> registry, T value) {
        var key = registry.getKey(value);
        return key == null ? "unregistered" : key.toString();
    }
}
