package cc.sighs.autojs.neoforge.client;

import cc.sighs.autojs.mc.McApi;
import cc.sighs.autojs.neoforge.script.AutoJSTools;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * The client half of the {@code AutoJS} global.
 *
 * <p>Client-only: the shared {@link AutoJSTools} reaches these answers through its bridge interface
 * precisely so that a dedicated server never loads this class.
 *
 * <p>{@link #where()} is where the human-facing vocabulary lives. A client knows exactly one thing about
 * what the game is doing, the open screen, so this class turns that screen into words: the main menu, a
 * loading or connecting screen, paused, dead, the player inventory, a crafting table, a furnace, or a generic
 * container screen that names the menu it is showing. The vanilla checks come first and the container check
 * last, so a screen that is both is reported as the specific one.
 *
 * <p>This file is shared with every other target except 26.1, which replaces it for two reasons, both of them
 * names that only that version has: {@code ReceivingLevelScreen} is gone — a world that is loading now shows
 * {@code LevelLoadingScreen} and nothing else — and the input a script drives is no longer the
 * {@code Input} this bridge used to write field by field. The words themselves, and every other answer here,
 * are unchanged, so a script reads the same strings on every target.
 */
public final class ClientScriptBridge implements AutoJSTools.ClientBridge {
    @Override
    public Level level() {
        return Minecraft.getInstance().level;
    }

    @Override
    public Player player() {
        return Minecraft.getInstance().player;
    }

    @Override
    public JsonObject snapshot() {
        var minecraft = Minecraft.getInstance();
        var json = new JsonObject();
        json.addProperty("fps", McApi.client().fps());
        json.addProperty("window", minecraft.getWindow().getWidth() + "x" + minecraft.getWindow().getHeight());
        json.addProperty("hostingWorld", minecraft.hasSingleplayerServer());
        return json;
    }

    @Override
    public String where() {
        var minecraft = Minecraft.getInstance();
        var screen = minecraft.screen;

        if (screen == null) {
            return minecraft.level == null
                    ? "no world loaded and no screen open"
                    : "playing in " + standingPlace() + ", no screen open";
        }

        if (screen instanceof TitleScreen) {
            return "at the main menu";
        }
        if (McApi.client().isFirstRunScreen(screen)) {
            return "at the first-run accessibility screen";
        }
        if (screen instanceof LevelLoadingScreen) {
            return "loading a world";
        }
        if (screen instanceof ConnectScreen) {
            return "connecting to a server";
        }
        if (screen instanceof DisconnectedScreen) {
            return "disconnected: " + titleOf(screen);
        }
        if (screen instanceof DeathScreen) {
            return "on the death screen";
        }
        if (screen instanceof PauseScreen) {
            return minecraft.level == null ? "paused" : "paused in " + standingPlace();
        }
        if (screen instanceof InventoryScreen) {
            return "in the player inventory";
        }
        if (screen instanceof CraftingScreen) {
            return "at a crafting table";
        }
        if (screen instanceof AbstractFurnaceScreen<?>) {
            return "at a furnace screen: " + titleOf(screen);
        }
        if (screen instanceof AbstractContainerScreen<?> container) {
            return "container open: " + titleOf(screen) + " (" + AutoJSTools.menuKind(container.getMenu()) + ")";
        }
        return "in a GUI screen: " + titleOf(screen) + " (" + screen.getClass().getSimpleName() + ")";
    }

    @Override
    public JsonObject screen() {
        var screen = Minecraft.getInstance().screen;
        var json = new JsonObject();
        json.addProperty("open", screen != null);
        if (screen == null) {
            return json;
        }

        json.addProperty("class", screen.getClass().getName());
        json.addProperty("title", titleOf(screen));
        json.addProperty("pauseScreen", screen.isPauseScreen());
        json.addProperty("containerScreen", screen instanceof AbstractContainerScreen<?>);

        var mod = AutoJSTools.owningMod(screen.getClass());
        if (mod != null) {
            json.addProperty("mod", mod);
        }
        return json;
    }

    /** The script's own input object, or null until the first {@link #move} call installs one. */
    private AutoJSInput drivenInput;

    /**
     * Drive the local player's movement from a script, the way a keyboard would: the client's own physics
     * then moves the player and reports it, which is the only way a player actually walks.
     *
     * <p>The first call takes the input away from the keyboard, and also closes any open screen, because a
     * player who is in a GUI cannot walk. Values persist until they are changed, so a walking flow sets
     * forward once and sets it back to zero when it arrives.
     *
     * <p>Where the older targets wrote the impulse fields of the input object here, 26.1's input is split
     * between the movement vector its physics reads and the key record the server is told about, so the whole
     * assignment is one call into {@link AutoJSInput#drive}. The sprinting flag is still set on the player
     * rather than on an input key, exactly as it was.
     */
    @Override
    public void move(double forward, double strafe, boolean jump, boolean sprint, boolean crouch) {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null) {
            return;
        }
        if (minecraft.screen != null) {
            minecraft.setScreen(null);
        }

        if (drivenInput == null || player.input != drivenInput) {
            // The seam hands back this version's input class; what it builds is the script's own subclass,
            // which is the only kind that can be driven. See AutoJSInput.
            drivenInput = (AutoJSInput) McApi.client().scriptInput();
            player.input = drivenInput;
        }

        drivenInput.drive(forward > 0.0D, forward < 0.0D, strafe > 0.0D, strafe < 0.0D, jump, crouch,
                (float) strafe, (float) forward);
        player.setSprinting(sprint);
    }

    @Override
    public void background(boolean on) {
        var minecraft = Minecraft.getInstance();
        var mouse = minecraft.mouseHandler;

        if (on) {
            // Only a pause screen stops a singleplayer world from ticking, so close exactly that and nothing
            // else: any other screen would swallow the input a script is driving.
            if (minecraft.screen != null && minecraft.screen.isPauseScreen()) {
                minecraft.setScreen(null);
            }
            // Releasing the cursor is the whole trick: no screen involved, so nothing pauses and the mouse is
            // free to leave the window. Clicking the window later re-grabs it, as it always does.
            mouse.releaseMouse();
        } else {
            mouse.grabMouse();
        }
    }

    /** Which world and where in it, for the answers that are about being somewhere rather than a screen. */
    private static String standingPlace() {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return "a world";
        }
        return McApi.vanilla().keyId(minecraft.level.dimension()) + " at " + AutoJSTools.formatPos(player.blockPosition());
    }

    private static String titleOf(Screen screen) {
        var title = screen.getTitle();
        return title == null ? "" : title.getString();
    }
}
