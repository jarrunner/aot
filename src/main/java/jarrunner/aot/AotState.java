package jarrunner.aot;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What {@code jr} decided about the AOT cache for this run.
 *
 * <p>{@code jr.exe} exports {@code JR_AOT_STATE} ({@code using} / {@code creating} / {@code off}) and
 * {@code JR_AOT_CACHE} before starting the JVM. The environment is the cheap answer; the VM arguments are
 * the authoritative one, because a run launched by hand — or by an older {@code jr} — still carries
 * {@code -XX:AOTCacheOutput}, so that is the fallback.
 *
 * <p>The state that matters is {@link #creating()}: the JVM assembles an AOT cache out of what the training
 * process actually loaded, so that is the one run where an application should go out of its way to load
 * itself. See {@link AotWarmup} for why a thin cache is worse than none.
 */
public final class AotState {

    private AotState() { }

    public static final String CREATING = "creating", USING = "using", OFF = "off";

    private static volatile String cachedState;

    /**
     * Memoized, and that is not micro-optimisation: the VM-argument fallback pulls in
     * {@code java.lang.management}, and every tool using this library asks the question on its startup path
     * — the exact path the AOT cache exists to make cheap. Loading the management module repeatedly to find
     * out whether we are speeding up startup would be self-defeating. It cannot change within a process.
     */
    public static String state() {
        var s = cachedState;
        if (s != null) return s;
        var declared = System.getenv("JR_AOT_STATE");
        if (declared != null && !declared.isBlank()) return cachedState = declared.trim().toLowerCase();
        // Only now is the management module worth loading — a run jr did not launch.
        if (vmArg("-XX:AOTCacheOutput") != null) return cachedState = CREATING;
        if (vmArg("-XX:AOTCache=") != null) return cachedState = USING;
        return cachedState = OFF;
    }

    /** True on the one run that is WRITING the cache — the only run worth warming up. */
    public static boolean creating() { return CREATING.equals(state()); }

    /** True when this run is READING a cache, i.e. the fast path is in effect. */
    public static boolean using() { return USING.equals(state()); }

    /** The cache file jr is reading or about to write, or null when AOT is not in play. */
    public static Path cache() {
        var env = System.getenv("JR_AOT_CACHE");
        if (env != null && !env.isBlank()) return Path.of(env.trim());
        var arg = vmArg("-XX:AOTCacheOutput=");
        if (arg == null) arg = vmArg("-XX:AOTCache=");
        if (arg == null) return null;
        var value = arg.substring(arg.indexOf('=') + 1).replace("\"", "");
        return value.isBlank() ? null : Path.of(value);
    }

    public static boolean cacheExists() {
        var c = cache();
        return c != null && Files.isRegularFile(c);
    }

    public static long cacheSize() {
        try {
            var c = cache();
            return c == null ? -1 : Files.size(c);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The first classpath entry that makes an AOT cache impossible, or null if the classpath is fine.
     *
     * <p><b>The JVM will not dump a cache when the classpath contains a non-empty directory</b> — it fails
     * with {@code Cannot have non-empty directory in paths} and writes nothing. That single restriction is
     * why {@code jr} only wires AOT up for {@code -jar} launches, and why a {@code .jrc} that says
     * {@code aot=true} but launches {@code -cp target\classes;lib\*} has quietly never had a cache. It costs
     * an hour to rediscover, so it is detected and reported rather than left to a cryptic VM error.
     */
    public static String classpathBlocker() {
        for (var entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            try {
                var p = Path.of(entry);
                if (!Files.isDirectory(p)) continue;
                try (var any = Files.list(p)) {
                    if (any.findAny().isPresent()) return entry;
                }
            } catch (Exception ignore) {
                // Unreadable entries are the JVM's problem, not ours.
            }
        }
        return null;
    }

    /** Multi-line report for an {@code aot status} verb. */
    public static String describe() {
        var size = cacheSize();
        var blocker = classpathBlocker();
        return "JR_AOT_STATE  : " + state() + "\n"
             + "JR_LAUNCH_MODE: " + System.getenv("JR_LAUNCH_MODE") + "\n"
             + "cache path    : " + cache() + "\n"
             + "cache present : " + cacheExists() + (size > 0 ? " (" + (size / 1024 / 1024) + " MB)" : "")
             + (blocker == null ? ""
                : "\nBLOCKED       : the classpath contains a directory (" + blocker + "), and the JVM"
                + "\n                refuses to dump a cache for such a classpath. Launch from a jar.");
    }

    static String vmArg(String prefix) {
        return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> a.startsWith(prefix)).findFirst().orElse(null);
    }
}
