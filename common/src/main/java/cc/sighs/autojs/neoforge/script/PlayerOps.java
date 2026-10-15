package cc.sighs.autojs.neoforge.script;

import cc.sighs.autojs.mc.McApi;
import cc.sighs.autojs.mc.VanillaApi;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The primitives a script needs to actually play the game rather than observe it: what the player can see,
 * what is within reach, and the actions a player performs.
 *
 * <p>Two rules shape this class:
 *
 * <ul>
 *     <li><b>Actions go through the game's own machinery.</b> Breaking a block calls
 *     {@code ServerPlayerGameMode.destroyBlock}, using an item calls {@code useItem}, placing calls
 *     {@code useItemOn}. That means tool checks, durability, drops, statistics and the packets a client would
 *     have sent all happen normally: a script that plays this way is playing the game, not editing it.</li>
 *     <li><b>Perception is bounded and reduced.</b> Asking "what is around me" must not walk millions of
 *     positions, so block searches read only already loaded chunks and stop at a limit, exactly like
 *     {@link AreaScan}.</li>
 * </ul>
 *
 * <p>Everything here is written for the side that owns the state: the server runs these against the real
 * player (the client is a viewer and will follow), so a flow can be driven from {@code side=server} while a
 * human watches the client.
 */
public final class PlayerOps {
    /** How far a block search may look before it is refused. */
    private static final double MAX_SEARCH_RADIUS = 64;
    /** How many positions a block search may visit. */
    private static final int MAX_SEARCH_VOLUME = 4_000_000;

    private PlayerOps() {
    }

    // ----------------------------------------------------------------- state

    /** What the player is: vitals, state, facing, effects and what it holds. */
    public static JsonObject state(Player player) {
        var json = new JsonObject();
        json.addProperty("name", player.getName().getString());
        json.addProperty("health", player.getHealth());
        json.addProperty("maxHealth", player.getMaxHealth());
        json.addProperty("absorption", player.getAbsorptionAmount());
        json.addProperty("air", player.getAirSupply() + "/" + player.getMaxAirSupply());

        var food = player.getFoodData();
        json.addProperty("food", food.getFoodLevel());
        json.addProperty("saturation", food.getSaturationLevel());
        json.addProperty("canEat", player.canEat(false));

        json.addProperty("xpLevel", player.experienceLevel);
        json.addProperty("xpProgress", player.experienceProgress);
        json.addProperty("gameMode", player instanceof ServerPlayer server
                ? server.gameMode.getGameModeForPlayer().getName()
                : "client");
        json.addProperty("dimension", McApi.vanilla().keyId(McApi.vanilla().level(player).dimension()));
        json.add("pos", arrayOf(player.getX(), player.getY(), player.getZ()));
        json.addProperty("standingOn", AutoJSTools.formatPos(player.blockPosition()));

        var look = new JsonObject();
        look.addProperty("yaw", player.getYRot());
        look.addProperty("pitch", player.getXRot());
        var vector = player.getLookAngle();
        look.add("vector", arrayOf(vector.x, vector.y, vector.z));
        json.add("look", look);

        json.addProperty("onGround", McApi.vanilla().onGround(player));
        json.addProperty("inWater", player.isInWater());
        json.addProperty("inLava", player.isInLava());
        json.addProperty("onFire", player.isOnFire());
        json.addProperty("sprinting", player.isSprinting());
        json.addProperty("crouching", player.isCrouching());
        json.addProperty("fallDistance", player.fallDistance);
        json.addProperty("hurtTime", player.hurtTime);
        json.addProperty("dead", player.isDeadOrDying());
        json.addProperty("held", String.valueOf(McApi.vanilla().selectedStack(player)));
        json.addProperty("armor", player.getArmorValue());

        var effects = new JsonArray();
        for (var effect : player.getActiveEffects()) {
            var entry = new JsonObject();
            var id = McApi.vanilla().effectId(effect);
            entry.addProperty("id", id == null ? "unknown" : id);
            entry.addProperty("amplifier", effect.getAmplifier());
            entry.addProperty("ticks", effect.getDuration());
            effects.add(entry);
        }
        if (!effects.isEmpty()) {
            json.add("effects", effects);
        }
        return json;
    }

    // ------------------------------------------------------------- perception

    /** What the crosshair is on: a block, an entity, or nothing within reach. */
    public static JsonObject look(Player player, double reach) {
        var from = player.getEyePosition();
        var to = from.add(player.getViewVector(1.0F).scale(reach));

        var blockHit = McApi.vanilla().level(player).clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
        var box = player.getBoundingBox().expandTowards(to.subtract(from)).inflate(1.0D);
        var entityHit = ProjectileUtil.getEntityHitResult(player, from, to, box,
                target -> !target.isSpectator() && target.isPickable(), reach * reach);

        var closest = pickNearest(blockHit, entityHit, from);
        var json = new JsonObject();
        json.addProperty("reach", reach);

        if (closest == null) {
            json.addProperty("type", "miss");
            return json;
        }
        if (closest instanceof EntityHitResult entity) {
            json.addProperty("type", "entity");
            json.add("entity", describe(player, entity.getEntity()));
            return json;
        }

        var block = (BlockHitResult) closest;
        json.addProperty("type", "block");
        json.addProperty("block", blockId(McApi.vanilla().level(player).getBlockState(block.getBlockPos())));
        json.add("pos", arrayOf(block.getBlockPos().getX(), block.getBlockPos().getY(), block.getBlockPos().getZ()));
        json.addProperty("face", block.getDirection().getName());
        json.addProperty("distance", round(from.distanceTo(block.getLocation())));
        return json;
    }

    /** Everything in the view cone, nearest first: the structured "what is in front of me". */
    public static JsonArray view(Player player, double angleDegrees, double range, int limit) {
        var half = Math.max(1.0D, Math.min(180.0D, angleDegrees)) / 2.0D;
        var cos = Math.cos(Math.toRadians(half));
        var eye = player.getEyePosition();
        var direction = player.getViewVector(1.0F).normalize();

        var candidates = new ArrayList<Entity>();
        for (var entity : McApi.vanilla().level(player).getEntities(player, player.getBoundingBox().inflate(range))) {
            var offset = entity.getEyePosition().subtract(eye);
            if (offset.length() > range || offset.lengthSqr() < 1.0E-4D) {
                continue;
            }
            if (offset.normalize().dot(direction) < cos) {
                continue;
            }
            candidates.add(entity);
        }
        candidates.sort(Comparator.comparingDouble(player::distanceTo));

        var array = new JsonArray();
        for (var entity : candidates) {
            if (limit > 0 && array.size() >= limit) {
                break;
            }
            array.add(describe(player, entity));
        }
        return array;
    }

    /** Where a kind of block is, nearest first. Reads only already loaded chunks. */
    public static JsonArray findBlocks(Level level, BlockPos centre, String query, double radius, int limit) {
        var needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            throw new IllegalArgumentException("no block to search for");
        }

        var r = (int) clamp(radius <= 0 ? 16 : radius, 1, MAX_SEARCH_RADIUS);
        var minX = centre.getX() - r;
        var minY = Math.max(McApi.vanilla().minY(level), centre.getY() - r);
        var minZ = centre.getZ() - r;
        var maxX = centre.getX() + r;
        var maxY = Math.min(McApi.vanilla().maxY(level), centre.getY() + r);
        var maxZ = centre.getZ() + r;
        if ((long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > MAX_SEARCH_VOLUME) {
            throw new IllegalArgumentException("search box is too large; a radius of " + r
                    + " covers " + (maxX - minX + 1) + "x" + (maxY - minY + 1) + "x" + (maxZ - minZ + 1)
                    + " positions, the limit is " + MAX_SEARCH_VOLUME);
        }

        var loaded = loadedChunkKeys(level, minX, minZ, maxX, maxZ);
        var found = new ArrayList<BlockPos>();
        for (var pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (!loaded.contains(chunkKey(pos.getX() >> 4, pos.getZ() >> 4))) {
                continue;
            }
            if (blockId(level.getBlockState(pos)).toLowerCase(Locale.ROOT).contains(needle)) {
                found.add(pos.immutable());
            }
        }
        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(centre)));

        var array = new JsonArray();
        for (var pos : found) {
            if (limit > 0 && array.size() >= limit) {
                break;
            }
            var entry = new JsonObject();
            entry.addProperty("block", blockId(level.getBlockState(pos)));
            entry.add("pos", arrayOf(pos.getX(), pos.getY(), pos.getZ()));
            entry.addProperty("distance", round(Math.sqrt(pos.distSqr(centre))));
            array.add(entry);
        }
        return array;
    }

    /** What a block is: identity, properties, light, hardness and what it holds. */
    public static JsonObject blockInfo(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        var json = new JsonObject();
        json.addProperty("block", blockId(state));
        json.add("pos", arrayOf(pos.getX(), pos.getY(), pos.getZ()));

        var properties = new JsonObject();
        for (var property : state.getProperties()) {
            properties.addProperty(property.getName(), String.valueOf(state.getValue(property)));
        }
        // size() rather than isEmpty(): the two oldest targets ship a gson whose JsonObject has no isEmpty().
        if (properties.size() > 0) {
            json.add("properties", properties);
        }

        json.addProperty("hardness", state.getDestroySpeed(level, pos));
        json.addProperty("lightEmission", state.getLightEmission());
        json.addProperty("air", state.isAir());
        json.addProperty("blocksMotion", McApi.vanilla().blocksMotion(state));
        json.addProperty("liquid", !state.getFluidState().isEmpty());
        json.addProperty("biome", biomeId(level.getBiome(pos)));
        json.addProperty("above", blockId(level.getBlockState(pos.above())));
        json.addProperty("below", blockId(level.getBlockState(pos.below())));

        var blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            json.addProperty("blockEntity", registryId(McApi.vanilla().blockEntityTypes(), blockEntity.getType()));
        }
        return json;
    }

    /** Distance from the player to an entity, a block position or a point. */
    public static double distanceTo(Player player, Object target) {
        var to = resolvePoint(target);
        if (to == null) {
            throw new IllegalArgumentException("needs an entity, a BlockPos or {x,y,z}");
        }
        return round(player.position().distanceTo(to));
    }

    // ----------------------------------------------------------------- action

    /** Aim the player at an entity, a block position or a point. */
    public static boolean lookAt(Player player, Object target) {
        var to = resolvePoint(target);
        if (to == null) {
            throw new IllegalArgumentException("needs an entity, a BlockPos or {x,y,z}");
        }

        var eye = player.getEyePosition();
        var offset = to.subtract(eye);
        var flat = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
        var yaw = (float) (Math.toDegrees(Math.atan2(offset.z, offset.x)) - 90.0D);
        var pitch = (float) (-Math.toDegrees(Math.atan2(offset.y, flat)));

        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);

        // The client only learns a server-side rotation from a packet, and this is the one vanilla uses.
        if (player instanceof ServerPlayer server && server.connection != null) {
            server.connection.teleport(server.getX(), server.getY(), server.getZ(), yaw, pitch);
        }
        return true;
    }

    /** An entity, a BlockPos, a {@code {x,y,z}} object or an {@code [x,y,z]} array, as a point. */
    public static Vec3 resolvePoint(Object target) {
        if (target == null) {
            return null;
        }
        if (target instanceof Entity entity) {
            return entity.position();
        }
        if (target instanceof BlockPos pos) {
            return Vec3.atBottomCenterOf(pos);
        }
        if (target instanceof Vec3 vec) {
            return vec;
        }
        if (target instanceof JsonElement json) {
            return pointFromJson(json);
        }
        return null;
    }

    private static Vec3 pointFromJson(JsonElement json) {
        if (json.isJsonArray() && json.getAsJsonArray().size() >= 3) {
            var array = json.getAsJsonArray();
            return new Vec3(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
        }
        if (json.isJsonObject()) {
            var object = json.getAsJsonObject();
            if (object.has("x") && object.has("y") && object.has("z")) {
                return new Vec3(object.get("x").getAsDouble(), object.get("y").getAsDouble(),
                        object.get("z").getAsDouble());
            }
        }
        return null;
    }

    /**
     * One tick of movement intent, the way a key press would produce it.
     *
     * <p>{@code forward} and {@code strafe} are in blocks per tick, so 1 is "hold W for a tick" and a flow
     * that walks simply calls this every tick. {@code hurtMarked} is what makes the client accept a velocity
     * that came from the server, exactly as knockback does.
     */
    public static void move(Player player, double forward, double strafe, boolean jump, boolean sprint) {
        var yaw = Math.toRadians(player.getYRot());
        var sin = Math.sin(yaw);
        var cos = Math.cos(yaw);

        var speed = sprint ? 0.26D : 0.2D;
        var x = (-sin * forward - cos * strafe) * speed;
        var z = (cos * forward - sin * strafe) * speed;

        if (jump && McApi.vanilla().onGround(player)) {
            player.setDeltaMovement(x, 0.42D, z);
        } else {
            player.setDeltaMovement(x, player.getDeltaMovement().y, z);
        }
        player.setSprinting(sprint);
        player.hurtMarked = true;
    }

    /** Crouch or stand, as a shift key would. */
    public static void crouch(Player player, boolean crouching) {
        player.setShiftKeyDown(crouching);
    }

    /**
     * Break a block the way a player does: the server's game mode applies tool rules, durability, drops and
     * the packets, so the world reacts exactly as it would to a real client.
     */
    public static boolean mine(Player player, BlockPos pos) {
        if (!(player instanceof ServerPlayer server)) {
            throw new IllegalStateException("mining must be driven on the server side: the client cannot break "
                    + "a block on its own, so use side=server");
        }
        return server.gameMode.destroyBlock(pos);
    }

    /**
     * Pick up what is lying on the ground nearby, exactly the way walking over it does: the vanilla pickup
     * runs its own rules, so a pickup delay or a full inventory still applies.
     *
     * <p>This is the other half of {@link #mine}: breaking a block leaves its drops on the ground, as it
     * does for a real player.
     */
    public static JsonArray collect(Player player, double radius) {
        var picked = new JsonArray();
        for (var entity : McApi.vanilla().level(player).getEntities(player, player.getBoundingBox().inflate(radius))) {
            if (!(entity instanceof net.minecraft.world.entity.item.ItemEntity drop) || drop.isRemoved()) {
                continue;
            }
            var before = drop.getItem().copy();
            drop.playerTouch(player);
            if (drop.isRemoved() || drop.getItem().getCount() < before.getCount()) {
                picked.add(before.getCount() + "x " + itemId(before.getItem()));
            }
        }
        return picked;
    }

    /**
     * Hit a living entity. Returns false when it is out of reach, or when the attack is not charged yet.
     *
     * <p>Damage is scaled by the attack cooldown, so a script has to pace itself like a player does: attack
     * from a tick event rather than looping, and let the charge build up between hits.
     */
    public static boolean attack(Player player, Object target) {
        if (!(target instanceof LivingEntity living)) {
            throw new IllegalArgumentException("can only attack a living entity");
        }
        if (player.distanceTo(living) > 6.0D) {
            return false;
        }
        if (player.getAttackStrengthScale(0.0F) < 0.9F) {
            return false;
        }

        player.attack(living);
        return true;
    }

    /** Use the held item, the way a right click would: eat, drink, shoot, throw. */
    public static boolean use(Player player) {
        if (!(player instanceof ServerPlayer server)) {
            throw new IllegalStateException("using an item must be driven on the server side; use side=server");
        }
        return server.gameMode.useItem(server, McApi.vanilla().level(server), McApi.vanilla().selectedStack(server),
                InteractionHand.MAIN_HAND).consumesAction();
    }

    /**
     * Right click the given face of a block, which is how a player places the held block next to it, opens a
     * chest, plants a seed or uses a machine.
     */
    public static boolean place(Player player, BlockPos pos, String face) {
        if (!(player instanceof ServerPlayer server)) {
            throw new IllegalStateException("placing must be driven on the server side; use side=server");
        }

        var direction = Direction.byName(face == null ? "up" : face.trim().toLowerCase(Locale.ROOT));
        if (direction == null) {
            throw new IllegalArgumentException("face must be one of up, down, north, south, east, west");
        }

        var level = McApi.vanilla().level(server);
        var hit = new BlockHitResult(Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(McApi.vanilla().directionStep(direction)).scale(0.5D)),
                direction, pos, false);
        return server.gameMode.useItemOn(server, level, McApi.vanilla().selectedStack(server),
                InteractionHand.MAIN_HAND, hit).consumesAction();
    }

    // ---------------------------------------------------------------- crafting

    /** What the inventory could craft right now, using the real recipes. */
    public static JsonArray craftable(Player player, int limit) {
        var inventory = player.getInventory();
        var array = new JsonArray();

        for (var recipeEntry : McApi.vanilla().craftingRecipes(player)) {
            if (limit > 0 && array.size() >= limit) {
                break;
            }

            var recipe = recipeEntry.recipe();
            var plan = plan(recipe, inventory);
            if (plan == null) {
                continue;
            }

            var result = McApi.vanilla().craftingResult(player, recipe);
            if (result.isEmpty()) {
                continue;
            }

            var entry = new JsonObject();
            entry.addProperty("recipe", recipeEntry.id());
            entry.addProperty("output", result.getCount() + "x " + itemId(result.getItem()));
            entry.addProperty("consumes", describe(plan));
            array.add(entry);
        }
        return array;
    }

    /**
     * Craft a recipe by id, so long as the inventory holds the ingredients.
     *
     * <p>The ingredients and the output are the real recipe. The step that is skipped is the one a client
     * performs by hand, moving stacks into a grid and taking the result, because that is UI work rather than
     * game rules.
     */
    public static JsonObject craft(Player player, String recipeId, int times) {
        var wanted = recipeId == null ? "" : recipeId.trim().toLowerCase(Locale.ROOT);
        var exact = new ArrayList<VanillaApi.RecipeEntry>();
        var partial = new ArrayList<VanillaApi.RecipeEntry>();
        for (var recipeEntry : McApi.vanilla().craftingRecipes(player)) {
            var id = recipeEntry.id().toLowerCase(Locale.ROOT);
            if (id.equals(wanted) || id.equals("minecraft:" + wanted)) {
                exact.add(recipeEntry);
            } else if (id.contains(wanted)) {
                partial.add(recipeEntry);
            }
        }
        // A full id wins outright: "minecraft:stick" must not be beaten by "minecraft:sticky_piston".
        var matches = exact.isEmpty() ? partial : exact;
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("no crafting recipe matches '" + recipeId + "'");
        }
        if (matches.size() > 1) {
            var ids = new ArrayList<String>();
            for (var recipeEntry : matches) {
                ids.add(recipeEntry.id());
                if (ids.size() >= 8) {
                    break;
                }
            }
            throw new IllegalArgumentException("'" + recipeId + "' matches " + matches.size() + " recipes: "
                    + String.join(", ", ids) + "; use the full id");
        }

        var recipeEntry = matches.get(0);
        var recipe = recipeEntry.recipe();
        var inventory = player.getInventory();
        var rounds = (int) clamp(times <= 0 ? 1 : times, 1, 64);
        var crafted = 0;

        for (var round = 0; round < rounds; round++) {
            var plan = plan(recipe, inventory);
            if (plan == null) {
                break;
            }
            for (var item : plan) {
                consume(inventory, item);
            }

            var result = McApi.vanilla().craftingResult(player, recipe).copy();
            if (!inventory.add(result)) {
                player.drop(result, false);
            }
            crafted++;
        }

        if (crafted == 0) {
            throw new IllegalStateException("the inventory does not hold the ingredients for " + recipeEntry.id());
        }
        if (player instanceof ServerPlayer server) {
            server.containerMenu.broadcastChanges();
        }

        var json = new JsonObject();
        json.addProperty("recipe", recipeEntry.id());
        json.addProperty("crafted", crafted);
        json.addProperty("output", McApi.vanilla().craftingResult(player, recipe).getCount()
                + "x " + itemId(McApi.vanilla().craftingResult(player, recipe).getItem()));
        return json;
    }

    // ---------------------------------------------------------------- helpers

    /** The items one craft would consume, or null when the inventory cannot cover the recipe. */
    private static List<Item> plan(CraftingRecipe recipe, Inventory inventory) {
        var available = new HashMap<Item, Integer>();
        for (var slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                available.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }

        var needed = new ArrayList<Ingredient>();
        for (var ingredient : McApi.vanilla().craftingIngredients(recipe)) {
            if (!McApi.vanilla().ingredientEmpty(ingredient)) {
                needed.add(ingredient);
            }
        }
        // A recipe whose ingredients are all dynamic would otherwise look craftable out of nothing.
        if (needed.isEmpty()) {
            return null;
        }
        // Rarest first, which is what makes a greedy assignment correct for normal recipes.
        needed.sort(Comparator.comparingInt(ingredient -> McApi.vanilla().ingredientItems(ingredient).size()));

        var plan = new ArrayList<Item>();
        for (var ingredient : needed) {
            Item chosen = null;
            var best = 0;
            for (var option : McApi.vanilla().ingredientItems(ingredient)) {
                var have = available.getOrDefault(option, 0);
                if (have > best) {
                    best = have;
                    chosen = option;
                }
            }
            if (chosen == null) {
                return null;
            }
            available.merge(chosen, -1, Integer::sum);
            plan.add(chosen);
        }
        return plan;
    }

    private static void consume(Inventory inventory, Item item) {
        for (var slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.is(item) && !stack.isEmpty()) {
                stack.shrink(1);
                return;
            }
        }
    }

    private static String describe(List<Item> items) {
        var counts = new HashMap<Item, Integer>();
        for (var item : items) {
            counts.merge(item, 1, Integer::sum);
        }
        var parts = new ArrayList<String>();
        counts.forEach((item, count) -> parts.add(count + "x " + itemId(item)));
        parts.sort(null);
        return String.join(", ", parts);
    }

    /** Everything a script needs to know about an entity before deciding what to do with it. */
    private static JsonObject describe(Player viewer, Entity entity) {
        var json = new JsonObject();
        json.addProperty("type", registryId(McApi.vanilla().entityTypes(), entity.getType()));
        json.addProperty("name", entity.getName().getString());
        json.add("pos", arrayOf(entity.getX(), entity.getY(), entity.getZ()));
        json.addProperty("distance", round(viewer.distanceTo(entity)));
        json.addProperty("alive", entity.isAlive());

        if (entity instanceof LivingEntity living) {
            json.addProperty("health", living.getHealth());
            json.addProperty("maxHealth", living.getMaxHealth());
            json.addProperty("onFire", living.isOnFire());
            json.addProperty("lineOfSight", viewer.hasLineOfSight(living));
        }
        if (entity instanceof Enemy) {
            json.addProperty("hostile", true);
        }
        if (entity instanceof Mob mob) {
            json.addProperty("aggressive", mob.isAggressive());
            var target = mob.getTarget();
            if (target != null) {
                json.addProperty("target", target.getName().getString());
                json.addProperty("targetingMe", target == viewer);
            }
        }
        return json;
    }

    private static HitResult pickNearest(HitResult blockHit, EntityHitResult entityHit, Vec3 from) {
        if (blockHit.getType() == HitResult.Type.MISS) {
            return entityHit;
        }
        if (entityHit == null) {
            return blockHit;
        }
        var blockDistance = from.distanceToSqr(blockHit.getLocation());
        return from.distanceToSqr(entityHit.getLocation()) <= blockDistance ? entityHit : blockHit;
    }

    /** The same rule AreaScan uses: only chunks the game already has, so a query never generates terrain. */
    private static Set<Long> loadedChunkKeys(Level level, int minX, int minZ, int maxX, int maxZ) {
        var keys = new HashSet<Long>();
        for (var cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (var cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) != null) {
                    keys.add(chunkKey(cx, cz));
                }
            }
        }
        return keys;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static String blockId(BlockState state) {
        return registryId(McApi.vanilla().blocks(), state.getBlock());
    }

    private static String itemId(Item item) {
        return registryId(McApi.vanilla().items(), item);
    }

    private static String biomeId(Holder<net.minecraft.world.level.biome.Biome> biome) {
        return biome.unwrapKey().map(McApi.vanilla()::keyId).orElse("unknown");
    }

    private static <T> String registryId(VanillaApi.Lookup<T> registry, T value) {
        var key = registry.idOf(value);
        return key == null ? "unregistered" : key;
    }

    private static JsonArray arrayOf(double... values) {
        var array = new JsonArray();
        for (var value : values) {
            array.add(round(value));
        }
        return array;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
