package cc.sighs.autojs.neoforge;

import dev.latvian.mods.kubejs.script.ScriptManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the KubeJS console of one side from its log file.
 *
 * <p>The log file is the source of truth rather than {@code ConsoleJS.errors}: those in-memory queues are
 * only filled while KubeJS has error capturing switched on, while every line is appended to
 * {@code logs/kubejs/<side>.log} regardless. That file is also what a human reads, and KubeJS truncates it
 * when a side reloads, so reading it right after a reload shows exactly what that load reported.
 *
 * <p>KubeJS queues log lines and flushes them asynchronously, so every read flushes synchronously first.
 * Writes and reads both happen on the game thread, which keeps the two from interleaving.
 *
 * <p>A single Rhino parse error produces one message followed by dozens of JVM stack frames, which would
 * drown everything else. Each run of frames is therefore folded into its first frame plus a count, and one
 * error counts as one entry.
 */
public final class KubeJSConsole {
    /** Past this a per-eval delta stops being affordable, and the result is dropped instead. */
    private static final long MAX_SNAPSHOT_BYTES = 1_000_000L;
    private static final int MAX_DELTA_ENTRIES = 50;
    private static final String ERROR_TAG = "[ERROR]";
    private static final String WARNING_TAG = "[WARN]";
    private static final Pattern LOG_PREFIX = Pattern.compile("^\\[[^\\]]*]\\s*\\[[^\\]]*]\\s*(?:!\\s*)?");

    private KubeJSConsole() {
    }

    public static Path logFile(ScriptManager manager) {
        return manager.scriptType.getLogFile();
    }

    public static String relativeLogFile(ScriptManager manager) {
        return "logs/kubejs/" + manager.scriptType.name + ".log";
    }

    /** An entry count to diff against later, or -1 when the log is missing or too large to snapshot. */
    public static int mark(ScriptManager manager) {
        flush(manager);
        try {
            var path = logFile(manager);
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_SNAPSHOT_BYTES) {
                return -1;
            }
            return readLines(path).size();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** Entries appended after {@code mark}, or null when no usable snapshot was taken. */
    public static List<String> since(ScriptManager manager, int mark) {
        if (mark < 0) {
            return null;
        }
        flush(manager);

        List<String> lines;
        try {
            var path = logFile(manager);
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_SNAPSHOT_BYTES) {
                return null;
            }
            lines = readLines(path);
        } catch (IOException | RuntimeException e) {
            return null;
        }

        // A reload in between truncates the file, in which case everything still there is new.
        var added = lines.size() < mark ? lines : new ArrayList<>(lines.subList(mark, lines.size()));
        var entries = collapse(added);
        if (entries.size() <= MAX_DELTA_ENTRIES) {
            return entries;
        }

        var dropped = entries.size() - MAX_DELTA_ENTRIES;
        var tail = new ArrayList<String>();
        tail.add("... " + dropped + " earlier entries omitted");
        tail.addAll(entries.subList(dropped, entries.size()));
        return tail;
    }

    /** Which lines a read wants. {@code ALL} means every line, including a script's own console output. */
    public enum Selection {
        ERRORS,
        WARNINGS,
        ALL
    }

    /** What one read of a side's log found. {@code limit <= 0} means every matching entry. */
    public record Readout(int totalLines, int errorCount, int warningCount, List<String> entries) {
    }

    public static Readout read(ScriptManager manager, Selection selection, int limit) {
        flush(manager);

        List<String> all;
        try {
            var path = logFile(manager);
            all = Files.isRegularFile(path) ? readLines(path) : List.of();
        } catch (IOException | RuntimeException e) {
            return new Readout(0, 0, 0, List.of("Could not read " + relativeLogFile(manager) + ": " + e));
        }

        var errorRows = new ArrayList<String>();
        var warningRows = new ArrayList<String>();
        var errorCount = 0;
        var warningCount = 0;
        for (var line : all) {
            if (line.contains(ERROR_TAG)) {
                errorRows.add(line);
                if (!isStackFrame(line)) {
                    errorCount++;
                }
            } else if (line.contains(WARNING_TAG)) {
                warningRows.add(line);
                if (!isStackFrame(line)) {
                    warningCount++;
                }
            }
        }

        var entries = switch (selection) {
            case ERRORS -> collapse(errorRows);
            case WARNINGS -> collapse(warningRows);
            case ALL -> collapse(all);
        };

        if (limit > 0 && entries.size() > limit) {
            entries = new ArrayList<>(entries.subList(entries.size() - limit, entries.size()));
        }
        return new Readout(all.size(), errorCount, warningCount, entries);
    }

    /** Folds each run of JVM stack frames into its first frame plus a count. */
    private static List<String> collapse(List<String> lines) {
        var out = new ArrayList<String>();
        var frames = 0;
        for (var line : lines) {
            if (isStackFrame(line)) {
                if (frames == 0) {
                    out.add(line);
                }
                frames++;
                continue;
            }
            appendFrameCount(out, frames);
            frames = 0;
            out.add(line);
        }
        appendFrameCount(out, frames);
        return out;
    }

    private static void appendFrameCount(List<String> out, int frames) {
        if (frames > 1) {
            out.add("    ... " + (frames - 1) + " more stack frames omitted");
        }
    }

    private static boolean isStackFrame(String line) {
        return LOG_PREFIX.matcher(line).replaceFirst("").stripLeading().startsWith("at ");
    }

    private static List<String> readLines(Path path) throws IOException {
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    private static void flush(ScriptManager manager) {
        try {
            manager.scriptType.console.flush(true);
        } catch (RuntimeException e) {
            // No console yet: an empty log is a valid answer.
        }
    }
}
