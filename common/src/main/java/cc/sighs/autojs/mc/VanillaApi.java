package cc.sighs.autojs.mc;

import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;

/**
 * The vanilla Minecraft API that moved across the versions AutoJS supports.
 *
 * <p>Every method is one call the shared scripts — {@code AutoJSTools}, {@code PlayerOps}, {@code AreaScan} —
 * used to make directly, and every one of them is spelled differently in a neighbouring version. Between
 * 1.18.2 and 1.21.1 {@code BuiltInRegistries} appears where each registry used to be a field of
 * {@code Registry}; {@code Entity.level()} replaces {@code getLevel()}; {@code BlockState.blocksMotion()}
 * becomes the state's own answer; {@code RecipeHolder} replaces a bare {@code Recipe}; {@code MobEffect}
 * becomes {@code Holder<MobEffect>}; saving an item stack or a block entity starts taking the level's
 * registry access; and vanilla gains menu kinds that older versions have never heard of.
 *
 * <p>That is the whole axis, and it is why these methods belong together rather than in one interface per
 * registry: a target implements this file by reading what its own Minecraft has, and a shared file asks for
 * what it means ("the level this entity is in") instead of what it is called this generation. Nothing here
 * names a type that only some versions have — a value is identified by its id as text, and anything
 * version-shaped (a registry, a recipe manager's entries) is reached through the nested types below.
 *
 * <p>No method here is client-only and none of them is loader-specific: the client's own additions are in
 * {@link ClientApi}, and the loader's in {@link LoaderApi}. A dedicated server implements this one too.
 */
public interface VanillaApi {
    /**
     * One vanilla registry, as far as the shared code needs it: look a value up by id, list every id, name a
     * value, and read the tags it belongs to.
     *
     * <p>{@code Registry} itself is the same type on every version — what moved is how one reaches a registry,
     * and that lookup never leaked into the shared helpers anyway. Those helpers do the same thing on every
     * version: parse an id, try it directly, fall back to listing candidates, and word the failure. Sharing
     * them behind this interface is what keeps the search and its error messages in one place.
     *
     * <p>An id crosses this interface as text — {@code "namespace:path"} — and never as a registry key type:
     * that type was renamed between the Minecraft generations AutoJS supports, so no shared signature can name
     * it. A target turns the text into its own id type at the call, and an id its version cannot parse is
     * treated as unregistered: {@link #get(String)} answers null for it, which is also the answer for a
     * well-formed id nothing is registered under.
     */
    interface Lookup<T> {
        /** The value registered under an id, or null when nothing is. */
        T get(String id);

        /** Every registered id, as text. */
        List<String> ids();

        /** The id a value is registered under, as text, or null when it is not registered. */
        String idOf(T value);

        /** The ids of every tag the value belongs to, as text, in no particular order. */
        List<String> tagIds(T value);
    }

    /**
     * The id a resource key names, as text: {@code "namespace:path"}.
     *
     * <p>This is the same rename {@link Lookup} carries, one level out. A key names a value in a registry
     * rather than being one, so it cannot go through {@code Lookup}, but the accessor that reads its id moved
     * with the id type itself — {@code location()} up to 1.21.1, {@code identifier()} at 26.1 — and a shared
     * signature can neither name that accessor nor be written without one. Dimensions and biomes are the two
     * keys the shared scripts print, and both print the same text on every version.
     */
    String keyId(ResourceKey<?> key);

    Lookup<Item> items();

    Lookup<Block> blocks();

    Lookup<EntityType<?>> entityTypes();

    Lookup<BlockEntityType<?>> blockEntityTypes();

    /**
     * The item stack as NBT with full metadata, or null when there is no level to save with.
     *
     * <p>The level is what supplies the registry access the save needs, and the save call takes it as a
     * registry-access provider from 1.20.5 on and as a plain tag before that, so only a target can write it.
     */
    Tag saveStack(Level level, ItemStack stack);

    /** The block entity as NBT with full metadata, or null when there is no level to save with. */
    Tag saveBlockEntity(Level level, BlockEntity blockEntity);

    /** The level an entity is in: {@code level()} here, {@code getLevel()} before 1.20. */
    Level level(Entity entity);

    /** Whether an entity is on the ground: {@code onGround()} here, {@code isOnGround()} before 1.20. */
    boolean onGround(Entity entity);

    /** Whether a block state blocks motion: the state answers this itself from 1.20.5 on. */
    boolean blocksMotion(BlockState state);

    /** Whether an ingredient is empty: {@code Ingredient.isEmpty()} only exists from 1.21 on. */
    boolean ingredientEmpty(Ingredient ingredient);

    /**
     * The level's day time, in ticks.
     *
     * <p>{@code Level.getDayTime()} is the call every version up to 1.21.1 has; 26.1 deletes it along with the
     * level data field behind it and answers the same question from the overworld world clock. Both are the
     * number of ticks since the world started, so a script sees one number.
     */
    long dayTime(Level level);

    /**
     * Sets the day time of a server level, in ticks.
     *
     * <p>The setter is {@code ServerLevel.setDayTime} up to 1.21.1; 26.1 deletes it and sets the overworld
     * clock instead, which is why the level handed in has to be the server's own.
     */
    void setDayTime(ServerLevel level, long ticks);

    /**
     * Sets the weather of a server level: {@code clearTime} ticks of clear skies once it stops raining,
     * {@code rainTime} ticks of rain, and whether it is raining and thundering at all.
     *
     * <p>This is exactly the argument list of {@code ServerLevel.setWeatherParameters}, the call every version
     * up to 1.21.1 has. 26.1 moves it to {@code MinecraftServer}, which needs the server and finds the weather
     * data there, with the same four arguments.
     */
    void setWeather(ServerLevel level, int clearTime, int rainTime, boolean raining, boolean thundering);

    /**
     * The lowest block Y a level can hold, inclusive: {@code getMinBuildHeight()} up to 1.21.1, where the name
     * says what it means, and {@code getMinY()} at 26.1, where the height accessor was renamed wholesale.
     */
    int minY(Level level);

    /**
     * The highest block Y a level can hold, inclusive.
     *
     * <p>This one moved sideways rather than being renamed: up to 1.21.1 the level answers
     * {@code getMaxBuildHeight()}, which is one past the top, and 26.1 answers {@code getMaxY()}, which is the
     * top itself. Both mean "the last block of the world", so both are that here.
     */
    int maxY(Level level);

    /** The chunk's X coordinate: a public field up to 1.21.1, an accessor on the 26.1 record. */
    int chunkX(ChunkPos pos);

    /** The chunk's Z coordinate, spelled the same way as {@link #chunkX(ChunkPos)}. */
    int chunkZ(ChunkPos pos);

    /**
     * The stack a player has selected in the hotbar: {@code Inventory.getSelected()} up to 1.21.1, which 26.1
     * renames to {@code getSelectedItem()}.
     */
    ItemStack selectedStack(Player player);

    /**
     * The block step from one block to its neighbour in a direction: {@code Direction.getNormal()} up to
     * 1.21.1, {@code getUnitVec3i()} at 26.1.
     */
    Vec3i directionStep(Direction direction);

    /**
     * A new entity of a type in a level, or null when the level cannot make one.
     *
     * <p>{@code EntityType.create(Level)} is the whole call up to 1.21.1; 26.1 makes the spawn reason an
     * argument the caller has to state. A script summoning an entity is the command case, the same one
     * vanilla's own {@code /summon} uses.
     */
    Entity createEntity(EntityType<?> type, Level level);

    /**
     * Moves an entity to a position, keeping the rotation it has: {@code Entity.moveTo(x, y, z)} up to 1.21.1,
     * which 26.1 renames to {@code snapTo(x, y, z)}. The two do the same five things in the same order — raw
     * position, both rotations set to what they already are, then the old position and the collision box — so a
     * summoned entity lands where it always did.
     */
    void moveEntity(Entity entity, double x, double y, double z);

    /**
     * The ingredients a crafting recipe needs, in the recipe's own order.
     *
     * <p>{@code Recipe.getIngredients()} is the accessor up to 1.21.1. 26.1 moves them into the recipe's
     * {@code PlacementInfo}, which carries the slots each one goes in as well; the ingredients themselves are
     * the part the shared code reads, and it reads them in the same order.
     */
    List<Ingredient> craftingIngredients(CraftingRecipe recipe);

    /**
     * The items an ingredient accepts, one entry per alternative, in no particular order.
     *
     * <p>{@code Ingredient.getItems()} hands back one stack per alternative up to 1.21.1; 26.1 replaces it with
     * {@code items()}, a stream of item holders. The shared code reads the item of each option and counts the
     * options, so the holders are reduced to the items here rather than leaked through the seam.
     */
    List<Item> ingredientItems(Ingredient ingredient);

    /** The effect's registry id as text, or null when it is not registered. */
    String effectId(MobEffectInstance effect);

    /** Runs a command as the server's own command source. */
    void runCommand(MinecraftServer server, String command);

    /**
     * The word for a container menu kind vanilla only gained after 1.18.2, or null when the menu is not one of
     * them: the crafter is the only one today.
     *
     * <p>One branch of {@code menuKind} rather than the whole chain, because the chain is identical on every
     * version and a second copy of it per target is how two targets start disagreeing about what a furnace is
     * called.
     */
    String addedMenuKind(AbstractContainerMenu menu);

    /** One crafting recipe as the shared code reads it: the id a script names it by, as text, and the recipe itself. */
    record RecipeEntry(String id, CraftingRecipe recipe) {
    }

    /** Every crafting recipe this player can see: the server's list, and empty on a client, which has none. */
    List<RecipeEntry> craftingRecipes(Player player);

    /**
     * One craft's result, as this version computes it.
     *
     * <p>The player supplies the level whose registry access {@code getResultItem} wants. That argument is what
     * changed: it is absent before 1.20.5 and a registry-access provider after.
     */
    ItemStack craftingResult(Player player, CraftingRecipe recipe);

    /** Whether this level has the structure datapack registry, which a client may not have synced. */
    boolean hasStructureRegistry(Level level);

    /**
     * Registry ids of the structures that start in this chunk, in the chunk's own order, and empty when there is
     * no structure registry.
     *
     * <p>Structures are a datapack registry, so they come from the level rather than from a built-in one, and
     * what a chunk holds them under is a type that moved packages between 1.18.2 and 1.19.
     */
    List<String> structureIds(Level level, LevelChunk chunk);
}
