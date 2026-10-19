package com.sighs.autojs.neoforge.rhino;

import com.sighs.autojs.neoforge.script.AutoJSTools;
import com.sighs.autojs.neoforge.script.ScriptOutput;
import com.sighs.autojs.side.EvalSide;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.NativeJavaClass;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.SharedContextData;
import dev.latvian.mods.rhino.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The globals AutoJS hangs on a Rhino scope.
 *
 * <p>Deliberately short: this mode reproduces what AutoJS itself needs — the {@code AutoJS} helpers, a place
 * for a snippet to print, and a way to reach a class by name — not KubeJS's pre-registered surface
 * ({@code Item}, {@code Text}, {@code NBT}, the event groups). A snippet written against those falls back to
 * {@code Java.loadClass} here.
 *
 * <p>How a global is attached is the one thing 1802 changes about this file. There is no
 * {@code Context.addToScope} yet: on this generation the conversion from a Java object to a script value happens
 * on the {@link SharedContextData} the scope carries, and that is also what KubeJS's own binding registry uses
 * — {@code BindingsEvent.add} is one call to {@link SharedContextData#addToTopLevelScope}. The class-level
 * wrappers are attached the same way, minus the conversion, which is the distinction the two branches of that
 * method make.
 */
public final class RhinoBindings {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoJS/Rhino");

    private RhinoBindings() {
    }

    /**
     * Hangs the globals on a freshly built scope. Runs on the game thread that owns the scope, because the
     * globals below hold on to the live context.
     */
    public static void install(Context context, ScriptableObject scope, EvalSide side, List<String> diagnostics) {
        var console = new Console(diagnostics);
        var java = new JavaNamespace(scope);

        // The scope's own context data rather than the context's: on this generation the context carries one
        // too, but it is whichever scope called initStandardObjects last, while this lookup is tied to the
        // scope it is asked about.
        var data = SharedContextData.get(scope);
        data.addToTopLevelScope("AutoJS", new AutoJSTools(side, console));
        data.addToTopLevelScope("console", console);
        data.addToTopLevelScope("Java", java);

        // KubeJS scripts know JSON as `Json`, so the name is kept. It is the engine's own JSON object rather
        // than a gson-backed one: gson would hand a snippet Java maps and lists wrapped as foreign objects,
        // while this is the parser and stringifier a plain script already expects.
        data.addToTopLevelScope("Json", scope.get("JSON", scope));
    }

    /**
     * Where a snippet's output goes.
     *
     * <p>It is both the {@code console} global and the outlet {@link AutoJSTools} logs through, so
     * {@code console.info(...)} and {@code AutoJS.log(...)} land in the same place: the diagnostics of the eval
     * that is running, plus the game log, which is the only place left to look once that eval is over.
     */
    public static final class Console implements ScriptOutput {
        private final List<String> diagnostics;

        Console(List<String> diagnostics) {
            this.diagnostics = diagnostics;
        }

        /** What {@code AutoJS.log(...)} ends up calling. */
        @Override
        public void info(Object message) {
            var text = String.valueOf(message);
            diagnostics.add(text);
            LOGGER.info("{}", text);
        }

        public void log(Object... messages) {
            info(join(messages));
        }

        public void warn(Object... messages) {
            var text = join(messages);
            diagnostics.add(text);
            LOGGER.warn("{}", text);
        }

        public void error(Object... messages) {
            var text = join(messages);
            diagnostics.add(text);
            LOGGER.error("{}", text);
        }

        private static String join(Object[] messages) {
            var builder = new StringBuilder();
            for (var index = 0; index < messages.length; index++) {
                if (index > 0) {
                    builder.append(' ');
                }
                builder.append(messages[index]);
            }
            return builder.toString();
        }
    }

    /**
     * The {@code Java} global, the one piece of KubeJS's surface worth reproducing: without it a snippet has no
     * way to reach a class by name, because this mode registers no wrappers at all.
     */
    public static final class JavaNamespace {
        private final Scriptable scope;

        JavaNamespace(Scriptable scope) {
            this.scope = scope;
        }

        /**
         * Loads a class and hands back its script wrapper, the way KubeJS's {@code Java.loadClass} does.
         *
         * <p>On this generation the wrapper takes no context: {@code NativeJavaClass} reads the classes it
         * needs off the scope it is built for.
         */
        public Object loadClass(String name) {
            try {
                return new NativeJavaClass(scope, Class.forName(name));
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("no such class: " + name);
            }
        }
    }
}
