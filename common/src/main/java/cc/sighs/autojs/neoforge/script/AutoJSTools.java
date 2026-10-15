package cc.sighs.autojs.neoforge.script;

import cc.sighs.autojs.side.EvalSide;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Locale;

/**
 * The {@code AutoJS} global of either script context — KubeJS's when it is installed, AutoJS's own Rhino kernel
 * when it is not — and the body of the same helpers when they are reached from the MCP endpoint's
 * {@code eval_js}.
 *
 * <p>It exists so that debugging a mod is not a sequence of guesses about registry lookups, coordinate
 * plumbing, screen classes and version-specific method names: {@code AutoJS.where()} says whether the game
 * is on the main menu, in a world or inside a machine's screen; {@code AutoJS.item("jetpack")} either finds
 * the item or tells you the candidates; {@code AutoJS.nbt(...)} prints a block entity's data without anyone
 * having to look up how to save one in this Minecraft version.
 *
 * <p>Conventions:
 * <ul>
 *     <li>Ids are registry ids; a bare {@code diamond} means {@code minecraft:diamond}. Where a name is
 *     ambiguous the call throws with the candidates instead of picking one.</li>
 *     <li>Methods that change the world need a server. On the client they throw with that message, because
 *     client-side writes are phantom state the server never sees.</li>
 *     <li>File paths are relative to the game directory unless absolute.</li>
 *     <li>Coordinates are always three numbers; the position-taking calls also have an overload that accepts
 *     a {@code BlockPos}.</li>
 *     <li>This class deliberately mentions no client-only type: the client queries go through
 *     {@link ClientBridge}, which only a client ever installs.</li>
 * </ul>
 */
public class AutoJSTools {
    /** Client-only queries. The implementation is installed by the client entry point. */
    public interface ClientBridge {
        Level level();

        Player player();

        JsonObject snapshot();

        /** Where the client is right now, in words. */
        String where();

        /** The open screen, if any, as JSON. */
        JsonObject screen();

        /**
         * Drive the local player's movement, the way a keyboard would. Only a client can do this: a player's
         * position is decided by the client, so the server-side equivalent barely moves it.
         */
        void move(double forward, double strafe, boolean jump, boolean sprint, boolean crouch);

        /**
         * Let the mouse out of the window without opening a screen, and keep the world ticking. This is what
         * makes unattended testing possible: an open screen is the only thing that pauses a singleplayer world,
         * and it also swallows the input a script is driving.
         */
        void background(boolean on);
    }

    private static final String HELP = """
            AutoJS helpers (side=<side>)

             where
               where() ui() menu() snapshot() side() mods() modVersion(modId) help()
             area  what a region looks like
               area()                        player centre, radius 32
               area(radius)                  player centre
               area(centre, radius)          centre: entity | BlockPos | {x,y,z} | [x,y,z]
               area(x, y, z, radius)
               options: { vertical, top, entities, volume, structures }
             state  what the player is
               state() look(reach?) view(range) view(angle, range, limit)
             sense  what is around
               findBlocks(query, radius, limit) nearestBlock(query, radius) blockInfo(pos|x,y,z)
               distanceTo(thing) entitiesNear(radius) inventory(player) held(player)
             play  what a player does  (mine/use/place need a server)
               lookAt(thing) move(forward, strafe, jump, sprint) crouch(on) background(on)
               mine(pos|x,y,z) attack(entity) use() place(pos, face)
               craftable(limit) craft(recipeId, times)
             lookup
               item(idOrName) block(idOrName) entityType(idOrName) id(thing) tags(thing)
               findItems(query, limit) findBlocks(query, limit) findEntities(query, limit)
             read
               server() level() player() players() pos(x,y,z) posOf(thing)
               blockAt(x,y,z) blockAt(pos) blockEntity(x,y,z) blockEntity(pos) nbt(thing)
               entitiesNear(radius) entitiesNear(x,y,z,radius) inventory(player) held(player)
             act  (needs a server)
               command(cmd) give(item,count) setBlock(x,y,z,block) setBlock(pos,block)
               summon(type,x,y,z) setTime(ticks) setWeather(clear|rain|thunder,ticks) heal(player) tp(entity,x,y,z)
             files  (relative to the game directory, or absolute)
               read(path) write(path,text) append(path,text) exists(path) readJson(path) writeJson(path,value)
             misc
               stack(item,count) log(...)

            'where' answers what the game is doing: main menu, loading, playing in a world, paused, or which
            screen is open. On a client the open screen is a real GUI; on a server it is the container menu the
            player has open.

            'area' answers what a region contains, as JSON: dimension, center, box, cost, height, biomes,
            surface, blocks, blockEntities, entities, structures, notes. It reads only chunks that are already
            loaded (it never loads or generates one), caps every distribution at 'top' entries, samples the
            column and volume scans when the box is large, and reports what it reduced in 'cost' and 'notes'.
            """;

    /**
     * gson, configured the way KubeJS's own JsonIO configured its instance: without HTML escaping, so a script
     * that writes a file still finds {@code <} as {@code <} rather than {@code \u003c}.
     */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static volatile ClientBridge clientBridge;

    /** Which side this global belongs to; the client-only answers go through the bridge below. */
    protected final EvalSide side;

    /** Where {@code log} and the other messages go; the engine behind the global supplies the outlet. */
    private final ScriptOutput out;

    public AutoJSTools(EvalSide side, ScriptOutput out) {
        this.side = side;
        this.out = out;
    }

    /** Called once by the client entry point. */
    public static void setClientBridge(ClientBridge bridge) {
        clientBridge = bridge;
    }

    // ------------------------------------------------------------------ meta

    public String side() {
        return side.id();
    }

    public String help() {
        return HELP.replace("<side>", side.id());
    }

    public String[] mods() {
        return ModList.get().getMods().stream().map(info -> info.getModId()).sorted().toArray(String[]::new);
    }

    public String modVersion(String modId) {
        for (var info : ModList.get().getMods()) {
            if (info.getModId().equals(modId)) {
                return String.valueOf(info.getVersion());
            }
        }
        return null;
    }

    /** One call that answers "where am I": side, dist, what the game is doing, world, time, weather, player. */
    public String snapshot() {
        var json = new JsonObject();
        json.addProperty("side", side());
        json.addProperty("dist", FMLEnvironment.dist.name());
        json.addProperty("where", where());

        var server = server();
        if (server != null) {
            json.addProperty("tick", server.getTickCount());
            json.addProperty("playerCount", server.getPlayerCount());
            json.addProperty("motd", server.getMotd());
        }

        var level = level();
        if (level != null) {
            json.addProperty("dimension", level.dimension().location().toString());
            json.addProperty("dayTime", level.getDayTime());
            json.addProperty("raining", level.isRaining());
            json.addProperty("thundering", level.isThundering());
            json.addProperty("difficulty", String.valueOf(level.getDifficulty()));
        } else {
            json.addProperty("dimension", "no level");
        }

        var player = player();
        if (player != null) {
            json.addProperty("player", player.getName().getString());
            json.addProperty("playerPos", formatPos(player.blockPosition()));
            json.addProperty("playerHealth", player.getHealth() + "/" + player.getMaxHealth());
        }

        var bridge = clientBridge;
        if (side == EvalSide.CLIENT && bridge != null) {
            json.add("client", bridge.snapshot());
        }
        return json.toString();
    }

    // ----------------------------------------------------------------- where

    /**
     * What the game is doing right now, in words.
     *
     * <p>On a client that is the open screen: the main menu, a loading screen, playing in a world with no
     * screen, paused, dead, or a container screen with the machine that owns it. On a server there is no
     * screen, so it describes the world and the container menu the player has open, which is the same thing
     * the server authorises.
     */
    public String where() {
        if (side == EvalSide.CLIENT) {
            var bridge = clientBridge;
            if (bridge != null) {
                return bridge.where();
            }
        }

        var level = level();
        if (level == null) {
            return "no level loaded on this side yet";
        }

        var player = player();
        var place = level.dimension().location() + " at "
                + (player == null ? "no player" : formatPos(player.blockPosition()));

        var menu = menu();
        if (menu == null || menu instanceof InventoryMenu) {
            return "in world " + place + ", no container open";
        }
        return "in world " + place + ", container open: " + menuKind(menu);
    }

    /** The details behind {@link #where()}, as JSON: the screen on a client, the menu on either side. */
    public String ui() {
        var json = new JsonObject();
        json.addProperty("where", where());

        var bridge = clientBridge;
        if (side == EvalSide.CLIENT && bridge != null) {
            json.add("screen", bridge.screen());
        }

        var menu = menu();
        if (menu != null) {
            json.add("menu", describeMenu(menu));
        }
        return json.toString();
    }

    /** The container menu the relevant player has open, on either side. */
    public AbstractContainerMenu menu() {
        var player = player();
        return player == null ? null : player.containerMenu;
    }

    /** A word for a container menu, vanilla or not: "crafting table", "container menu: MixerMenu". */
    public static String menuKind(AbstractContainerMenu menu) {
        var kind = switch (menu) {
            case InventoryMenu ignored -> "player inventory";
            case CraftingMenu ignored -> "crafting table";
            case CrafterMenu ignored -> "crafter";
            case AbstractFurnaceMenu ignored -> "furnace";
            case ChestMenu ignored -> "chest";
            case ShulkerBoxMenu ignored -> "shulker box";
            case HopperMenu ignored -> "hopper";
            case DispenserMenu ignored -> "dispenser or dropper";
            case AnvilMenu ignored -> "anvil";
            case SmithingMenu ignored -> "smithing table";
            case GrindstoneMenu ignored -> "grindstone";
            case CartographyTableMenu ignored -> "cartography table";
            case LoomMenu ignored -> "loom";
            case StonecutterMenu ignored -> "stonecutter";
            case EnchantmentMenu ignored -> "enchanting table";
            case BrewingStandMenu ignored -> "brewing stand";
            case BeaconMenu ignored -> "beacon";
            case LecternMenu ignored -> "lectern";
            case MerchantMenu ignored -> "villager trade";
            case HorseInventoryMenu ignored -> "horse inventory";
            default -> null;
        };
        return kind != null ? kind : "container menu: " + menu.getClass().getSimpleName();
    }

    /** Structured menu state, with the owning mod when it can be told from the class's code source. */
    public static JsonObject describeMenu(AbstractContainerMenu menu) {
        var json = new JsonObject();
        json.addProperty("kind", menuKind(menu));
        json.addProperty("class", menu.getClass().getName());
        json.addProperty("slots", menu.slots.size());

        var mod = owningMod(menu.getClass());
        if (mod != null) {
            json.addProperty("mod", mod);
        }
        return json;
    }

    /**
     * Best effort at the mod that ships a class, by matching its code source against the mod files. Null for
     * vanilla classes and for anything loaded from a development workspace.
     */
    public static String owningMod(Class<?> type) {
        try {
            var source = type.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }

            var location = source.getLocation().toURI();
            for (var info : ModList.get().getMods()) {
                var file = info.getOwningFile().getFile().getFilePath();
                if (file != null && file.toUri().equals(location)) {
                    return info.getModId();
                }
            }
        } catch (Exception ignored) {
            // A missing or unreadable code source is normal in a development workspace.
        }
        return null;
    }

    // ------------------------------------------------------------------ area

    /** A region around the player. See {@link AreaScan} for the shape of the answer. */
    public String area() {
        return area(null, 0, null);
    }

    /** A region around the player, of the given radius. */
    public String area(double radius) {
        return area(null, radius, null);
    }

    /** A region around an entity, a {@code BlockPos}, a {@code {x,y,z}} object or an {@code [x,y,z]} array. */
    public String area(Object center, double radius) {
        return area(center, radius, null);
    }

    public String area(Object center, double radius, JsonElement options) {
        var level = requireLevel("scan an area");
        var resolved = center != null ? center : player();
        if (resolved == null) {
            throw new IllegalArgumentException(
                    "no centre to scan around: pass coordinates, or be on a side that has a player");
        }
        return AreaScan.scan(level, resolved, radius, options);
    }

    /** A region around a block position. */
    public String area(double x, double y, double z, double radius) {
        return area(x, y, z, radius, null);
    }

    public String area(double x, double y, double z, double radius, JsonElement options) {
        return area(new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)), radius, options);
    }

    // ------------------------------------------------------- play primitives

    /** Vitals, state, facing, effects and what the player holds. */
    public String state() {
        return PlayerOps.state(requirePlayer()).toString();
    }

    /** What the crosshair is on: a block, an entity, or nothing within reach. */
    public String look() {
        return look(5.0D);
    }

    public String look(double reach) {
        return PlayerOps.look(requirePlayer(), reach).toString();
    }

    /** Everything inside the view cone, nearest first: the structured "what is in front of me". */
    public String view(double angleDegrees, double range, int limit) {
        return PlayerOps.view(requirePlayer(), angleDegrees, range, limit).toString();
    }

    public String view(double range) {
        return view(60.0D, range, 20);
    }

    /** Where a kind of block is, nearest first. Reads only already loaded chunks. */
    public String findBlocks(String query, double radius, int limit) {
        return PlayerOps.findBlocks(requireLevel("search for blocks"), requirePlayer().blockPosition(), query,
                radius, limit).toString();
    }

    public String nearestBlock(String query, double radius) {
        var found = PlayerOps.findBlocks(requireLevel("search for blocks"), requirePlayer().blockPosition(), query,
                radius, 1);
        return found.isEmpty() ? null : found.get(0).toString();
    }

    /** What a block is: identity, properties, light, hardness and what it holds. */
    public String blockInfo(int x, int y, int z) {
        return blockInfo(new BlockPos(x, y, z));
    }

    public String blockInfo(Object pos) {
        var blockPos = posOf(pos);
        if (blockPos == null) {
            throw new IllegalArgumentException("needs a BlockPos");
        }
        return PlayerOps.blockInfo(requireLevel("inspect a block"), blockPos).toString();
    }

    public double distanceTo(Object target) {
        return PlayerOps.distanceTo(requirePlayer(), target);
    }

    /** Aim the player at an entity, a block position or a point. */
    public boolean lookAt(Object target) {
        return PlayerOps.lookAt(requirePlayer(), target);
    }

    /**
     * One tick of movement intent, as a key press would produce it: {@code forward} 1 is "hold W for a tick".
     * A walking flow calls this every tick, driven by a tick event rather than a loop.
     */
    public void move(double forward, double strafe, boolean jump, boolean sprint) {
        var bridge = clientBridge;
        if (side == EvalSide.CLIENT && bridge != null) {
            // A player's position belongs to the client, so this is the lever that actually walks.
            bridge.move(forward, strafe, jump, sprint, false);
            return;
        }
        PlayerOps.move(requirePlayer(), forward, strafe, jump, sprint);
    }

    public void crouch(boolean crouching) {
        PlayerOps.crouch(requirePlayer(), crouching);
    }

    /**
     * Background debug mode, for unattended testing.
     *
     * <p>On a client the window captures the mouse, and the obvious way to get it back — opening a screen —
     * both pauses a singleplayer world and eats the input a script is driving. This releases the cursor
     * directly instead: the mouse can leave the window, the world keeps ticking, so injected flows carry on
     * while you work somewhere else. Clicking the game window re-captures the mouse, as it always does; call
     * this with false to take it back deliberately.
     */
    public void background(boolean on) {
        var bridge = clientBridge;
        if (side != EvalSide.CLIENT || bridge == null) {
            throw new IllegalStateException("background debug mode is a client thing: only the client owns the mouse");
        }
        bridge.background(on);
        out.info("AutoJS background debug mode " + (on
                ? "on: mouse released, no screen opened, the world keeps ticking"
                : "off: mouse captured again"));
    }

    /** Break a block the way a player does, through the server's game mode: drops, durability, packets. */
    public boolean mine(int x, int y, int z) {
        return mine(new BlockPos(x, y, z));
    }

    public boolean mine(Object pos) {
        var blockPos = posOf(pos);
        if (blockPos == null) {
            throw new IllegalArgumentException("needs a BlockPos");
        }
        return PlayerOps.mine(requirePlayer(), blockPos);
    }

    /** Pick up what is on the ground nearby, the way walking over it would. This is the other half of mine. */
    public String collect() {
        return collect(2.5D);
    }

    public String collect(double radius) {
        return PlayerOps.collect(requirePlayer(), radius).toString();
    }

    /** Hit a living entity at full attack strength. Returns false when it is out of reach. */
    public boolean attack(Object target) {
        return PlayerOps.attack(requirePlayer(), target);
    }

    /** Use the held item, the way a right click would: eat, drink, shoot, throw. */
    public boolean use() {
        return PlayerOps.use(requirePlayer());
    }

    /** Right click a face of a block: place the held block next to it, open it, plant on it, use a machine. */
    public boolean place(int x, int y, int z, String face) {
        return place(new BlockPos(x, y, z), face);
    }

    public boolean place(Object pos, String face) {
        var blockPos = posOf(pos);
        if (blockPos == null) {
            throw new IllegalArgumentException("needs a BlockPos");
        }
        return PlayerOps.place(requirePlayer(), blockPos, face);
    }

    /** What the inventory could craft right now, using the real recipes. */
    public String craftable(int limit) {
        return PlayerOps.craftable(requirePlayer(), limit).toString();
    }

    /** Craft a recipe by id, so long as the inventory holds the ingredients. */
    public String craft(String recipeId, int times) {
        return PlayerOps.craft(requirePlayer(), recipeId, times).toString();
    }

    // ---------------------------------------------------------------- lookup

    public Item item(String idOrName) {
        return findOne(BuiltInRegistries.ITEM, idOrName, "item");
    }

    public Block block(String idOrName) {
        return findOne(BuiltInRegistries.BLOCK, idOrName, "block");
    }

    public EntityType<?> entityType(String idOrName) {
        return findOne(BuiltInRegistries.ENTITY_TYPE, idOrName, "entity type");
    }

    /** The registry id of an item, block, entity type, stack, block state, entity or resource location. */
    public String id(Object thing) {
        if (thing == null) {
            return null;
        }
        if (thing instanceof ItemStack stack) {
            return stack.isEmpty() ? null : String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }
        if (thing instanceof Item item) {
            return String.valueOf(BuiltInRegistries.ITEM.getKey(item));
        }
        if (thing instanceof Block block) {
            return String.valueOf(BuiltInRegistries.BLOCK.getKey(block));
        }
        if (thing instanceof BlockState state) {
            return String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }
        if (thing instanceof EntityType<?> entityType) {
            return String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entityType));
        }
        if (thing instanceof Entity entity) {
            return String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        }
        if (thing instanceof ResourceLocation location) {
            return location.toString();
        }
        return String.valueOf(thing);
    }

    /** Registry ids of every tag an item, block, stack or block state belongs to. */
    public String[] tags(Object thing) {
        Holder<?> holder = null;
        // wrapAsHolder rather than builtInRegistryHolder: the latter is deprecated in this version.
        if (thing instanceof Item item) {
            holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
        } else if (thing instanceof Block block) {
            holder = BuiltInRegistries.BLOCK.wrapAsHolder(block);
        } else if (thing instanceof BlockState state) {
            holder = BuiltInRegistries.BLOCK.wrapAsHolder(state.getBlock());
        } else if (thing instanceof ItemStack stack && !stack.isEmpty()) {
            holder = BuiltInRegistries.ITEM.wrapAsHolder(stack.getItem());
        }

        return holder == null
                ? new String[0]
                : holder.tags().map(tag -> tag.location().toString()).sorted().toArray(String[]::new);
    }

    public String[] findItems(String query, int limit) {
        return find(BuiltInRegistries.ITEM, query, limit);
    }

    public String[] findBlocks(String query, int limit) {
        return find(BuiltInRegistries.BLOCK, query, limit);
    }

    public String[] findEntities(String query, int limit) {
        return find(BuiltInRegistries.ENTITY_TYPE, query, limit);
    }

    // ------------------------------------------------------------------ read

    public MinecraftServer server() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    public Level level() {
        if (side == EvalSide.CLIENT) {
            var bridge = clientBridge;
            return bridge == null ? null : bridge.level();
        }
        var server = server();
        return server == null ? null : server.overworld();
    }

    /** The player this side would act on: the local player on a client, the first player on a server. */
    public Player player() {
        if (side == EvalSide.CLIENT) {
            var bridge = clientBridge;
            return bridge == null ? null : bridge.player();
        }
        var server = server();
        if (server == null) {
            return null;
        }
        var players = server.getPlayerList().getPlayers();
        return players.isEmpty() ? null : players.get(0);
    }

    public Player[] players() {
        if (side == EvalSide.CLIENT) {
            var local = player();
            return local == null ? new Player[0] : new Player[] {local};
        }
        var server = server();
        return server == null ? new Player[0] : server.getPlayerList().getPlayers().toArray(new Player[0]);
    }

    public BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    public BlockPos posOf(Object thing) {
        return switch (thing) {
            case BlockPos pos -> pos;
            case Entity entity -> entity.blockPosition();
            case BlockEntity blockEntity -> blockEntity.getBlockPos();
            case null, default -> null;
        };
    }

    public BlockState blockAt(int x, int y, int z) {
        return blockAt(new BlockPos(x, y, z));
    }

    public BlockState blockAt(Object pos) {
        var level = level();
        return level == null || !(pos instanceof BlockPos blockPos) ? null : level.getBlockState(blockPos);
    }

    public BlockEntity blockEntity(int x, int y, int z) {
        return blockEntity(new BlockPos(x, y, z));
    }

    public BlockEntity blockEntity(Object pos) {
        var level = level();
        return level == null || !(pos instanceof BlockPos blockPos) ? null : level.getBlockEntity(blockPos);
    }

    /** NBT as SNBT text, for tags, stacks, block entities and anything that prints itself. */
    public String nbt(Object thing) {
        if (thing == null) {
            return null;
        }
        if (thing instanceof Tag tag) {
            return tag.toString();
        }

        var registries = registries();
        if (registries != null) {
            if (thing instanceof ItemStack stack) {
                return String.valueOf(stack.save(registries));
            }
            if (thing instanceof BlockEntity blockEntity) {
                return String.valueOf(blockEntity.saveWithFullMetadata(registries));
            }
        }
        return String.valueOf(thing);
    }

    /** Entities around the player. */
    public Entity[] entitiesNear(double radius) {
        var self = player();
        if (self == null) {
            throw new IllegalStateException("no player to centre on; use entitiesNear(x, y, z, radius) instead");
        }
        return entitiesNear(self.blockPosition().getX(), self.blockPosition().getY(), self.blockPosition().getZ(), radius);
    }

    public Entity[] entitiesNear(int x, int y, int z, double radius) {
        var level = level();
        if (level == null) {
            return new Entity[0];
        }
        var box = new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        return level.getEntities((Entity) null, box).toArray(new Entity[0]);
    }

    /** Non-empty inventory stacks, so nobody has to walk 41 slots. */
    public ItemStack[] inventory(Object who) {
        var player = who instanceof Player given ? given : player();
        if (player == null) {
            return new ItemStack[0];
        }
        var inventory = player.getInventory();
        var stacks = new ArrayList<ItemStack>();
        for (var slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }
        return stacks.toArray(new ItemStack[0]);
    }

    public ItemStack held(Object who) {
        var player = who instanceof Player given ? given : player();
        return player == null ? null : player.getInventory().getSelected();
    }

    // ------------------------------------------------------------------- act

    /** Runs a vanilla command as the server. Every mod's command is in reach this way. */
    public void command(String command) {
        var server = requireServer("run commands");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    /** Gives to every player, the same way {@code /give @a} does. */
    public void give(Object item, int count) {
        command("give @a " + requireItemId(item) + " " + Math.max(1, count));
    }

    public boolean setBlock(int x, int y, int z, Object block) {
        return setBlock(new BlockPos(x, y, z), block);
    }

    public boolean setBlock(Object pos, Object block) {
        var level = level();
        if (level == null || !(pos instanceof BlockPos blockPos)) {
            return false;
        }
        var resolved = resolveBlock(block);
        return level.setBlockAndUpdate(blockPos, resolved.defaultBlockState());
    }

    public Entity summon(Object entityType, double x, double y, double z) {
        var level = requireLevel("summon an entity");
        var type = entityType instanceof EntityType<?> given ? given : entityType(String.valueOf(entityType));
        if (type == null) {
            throw new IllegalArgumentException("no such entity type: " + entityType);
        }
        var entity = type.create(level);
        if (entity == null) {
            throw new IllegalStateException(type + " cannot be created in this level");
        }
        entity.moveTo(x, y, z);
        level.addFreshEntity(entity);
        return entity;
    }

    /** Ticks since the world started, so 0 is sunrise, 6000 noon, 12000 sunset, 18000 midnight. */
    public void setTime(long ticks) {
        requireServerLevel("set the time").setDayTime(ticks);
    }

    public void setWeather(String kind, int ticks) {
        var level = requireServerLevel("set the weather");
        var duration = Math.max(1, ticks);
        switch (kind.toLowerCase(Locale.ROOT)) {
            case "clear" -> level.setWeatherParameters(duration, 0, false, false);
            case "rain" -> level.setWeatherParameters(0, duration, true, false);
            case "thunder" -> level.setWeatherParameters(0, duration, true, true);
            default -> throw new IllegalArgumentException("weather must be clear, rain or thunder, not " + kind);
        }
    }

    public void heal(Object who) {
        if (who instanceof LivingEntity living) {
            living.setHealth(living.getMaxHealth());
        }
    }

    public Entity tp(Object entity, double x, double y, double z) {
        if (entity instanceof Entity target) {
            target.teleportTo(x, y, z);
            return target;
        }
        return null;
    }

    // ----------------------------------------------------------------- files

    public String read(String path) {
        try {
            return Files.readString(file(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path + ": " + e.getMessage());
        }
    }

    public void write(String path, String text) {
        try {
            var file = file(path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + path + ": " + e.getMessage());
        }
    }

    public void append(String path, String text) {
        try {
            var file = file(path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("cannot append to " + path + ": " + e.getMessage());
        }
    }

    public boolean exists(String path) {
        return Files.exists(file(path));
    }

    public Object readJson(String path) {
        try {
            return GSON.fromJson(Files.readString(file(path), StandardCharsets.UTF_8), Object.class);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path + ": " + e.getMessage());
        }
    }

    /**
     * Writes JSON the way KubeJS's own JsonIO did, because a script can reach this in either mode: tab
     * indentation, parent directories created on demand, and a null value deleting the file instead of writing
     * {@code null}.
     */
    public void writeJson(String path, JsonElement value) {
        try {
            var target = file(path);
            if (value == null || value.isJsonNull()) {
                Files.deleteIfExists(target);
                return;
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, pretty(value), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + path + ": " + e.getMessage());
        }
    }

    /** Indented with tabs, which is the shape JsonIO wrote: this is text a person reads as often as a script. */
    private static String pretty(JsonElement value) throws IOException {
        var text = new StringWriter();
        try (var writer = new JsonWriter(text)) {
            writer.setIndent("\t");
            GSON.toJson(value, writer);
        }
        return text.toString();
    }

    // ------------------------------------------------------------------ misc

    public ItemStack stack(Object itemOrId, int count) {
        var resolved = switch (itemOrId) {
            case Item given -> given;
            case ItemStack existing -> existing.getItem();
            case String id -> item(id);
            case null, default -> null;
        };
        if (resolved == null) {
            throw new IllegalArgumentException("not an item: " + itemOrId);
        }
        return new ItemStack(resolved, Math.max(1, count));
    }

    public void log(Object... message) {
        for (var part : message) {
            out.info(part);
        }
    }

    // --------------------------------------------------------------- helpers

    private static <T> T findOne(Registry<T> registry, String idOrName, String what) {
        if (idOrName == null || idOrName.isBlank()) {
            throw new IllegalArgumentException("empty " + what + " name");
        }

        var query = idOrName.trim().toLowerCase(Locale.ROOT);
        var direct = byId(registry, query);
        if (direct != null) {
            return direct;
        }

        var matches = find(registry, query, 8);
        if (matches.length == 1) {
            return byId(registry, matches[0]);
        }
        if (matches.length == 0) {
            throw new IllegalArgumentException("no " + what + " matches '" + idOrName + "'");
        }
        throw new IllegalArgumentException("'" + idOrName + "' matches " + matches.length + " " + what
                + "s: " + String.join(", ", matches) + "; use the full id");
    }

    private static <T> T byId(Registry<T> registry, String id) {
        var location = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        return location == null ? null : registry.getOptional(location).orElse(null);
    }

    private static <T> String[] find(Registry<T> registry, String query, int limit) {
        var needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        var max = limit <= 0 ? 50 : limit;
        return registry.keySet().stream()
                .map(ResourceLocation::toString)
                .filter(id -> id.toLowerCase(Locale.ROOT).contains(needle))
                .sorted()
                .limit(max)
                .toArray(String[]::new);
    }

    private static Block resolveBlock(Object block) {
        if (block instanceof Block given) {
            return given;
        }
        if (block instanceof BlockState state) {
            return state.getBlock();
        }
        if (block instanceof String id) {
            var location = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
            var found = location == null ? null : BuiltInRegistries.BLOCK.getOptional(location).orElse(null);
            if (found != null) {
                return found;
            }
        }
        throw new IllegalArgumentException("not a block: " + block);
    }

    private String requireItemId(Object item) {
        var resolved = item instanceof Item given ? given : item instanceof ItemStack stack ? stack.getItem() : null;
        var id = resolved == null ? id(item) : String.valueOf(BuiltInRegistries.ITEM.getKey(resolved));
        if (id == null || BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id)).isEmpty()) {
            throw new IllegalArgumentException("not an item: " + item);
        }
        return id;
    }

    private MinecraftServer requireServer(String action) {
        var server = server();
        if (server == null) {
            throw new IllegalStateException("cannot " + action + " without a server: this side has none right now. "
                    + "Client-side writes are phantom state the server never sees, so do this with side=server.");
        }
        return server;
    }

    private Player requirePlayer() {
        var player = player();
        if (player == null) {
            throw new IllegalStateException("this side has no player to act on: a server needs someone online, a client "
                    + "needs a world loaded");
        }
        return player;
    }

    private Level requireLevel(String action) {
        var level = level();
        if (level == null) {
            throw new IllegalStateException("cannot " + action + " without a level: no world is loaded on this side.");
        }
        return level;
    }

    private ServerLevel requireServerLevel(String action) {
        if (level() instanceof ServerLevel serverLevel) {
            return serverLevel;
        }
        return requireServer(action).overworld();
    }

    private HolderLookup.Provider registries() {
        var level = level();
        return level == null ? null : level.registryAccess();
    }

    private Path file(String path) {
        var file = Path.of(path);
        return file.isAbsolute() ? file : net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve(path);
    }

    public static String formatPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
