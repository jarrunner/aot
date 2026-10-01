package jarrunner.aot;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Loading enough of an application that an AOT cache trained on this run is worth having.
 *
 * <p><b>Why this is needed at all.</b> The JVM assembles an AOT cache out of what the training run actually
 * loaded. A cache trained on {@code mytool --help} — or on any other one-line path a user happened to type —
 * is close to useless, and measurably <b>worse than no cache</b>: naming a cache also suppresses the default
 * CDS archive, so a thin cache costs you that as well. Measured on a CLI tool's {@code --help}: 1645ms with no
 * flags, 1837ms with a cache trained on a trivial path, 844ms with a cache from a full warmup pass.
 *
 * <p><b>It force-LOADS rather than executes.</b> Class loading and linking is the expensive part and it is
 * what the cache stores. Executing the application instead would mean opening databases, starting toolkits
 * and touching daemons — slow, and side-effecting, on a run the user did not ask for. Classes are loaded
 * with {@code initialize=false}, so static initialisers do not run: nothing starts a thread, opens a file or
 * pops a window. Anything genuinely worth running goes in {@link #exercise}, where the application decides.
 *
 * <p><b>An {@code exercise} block must leave the process usable.</b> It runs in the SAME JVM that then
 * serves the operator's real command, so anything it shuts down stays shut down. Starting a UI toolkit is a
 * fine thing to warm up; calling that toolkit's {@code exit()} afterwards cost a desktop app a silent,
 * windowless hang on the first run after every rebuild. Warm things up; do not tidy them away.
 *
 * <p><b>Both classpath shapes are handled.</b> A scan of the running jar alone is fine for
 * {@code java -jar}. Plenty of tools launch as {@code java -cp target\classes;lib\*} instead, and there a
 * jar-only scan silently finds nothing and warms up zero classes. This walks directories and jars alike,
 * across the whole classpath.
 *
 * <p>Typical use — three lines at the top of {@code main}:
 * <pre>{@code
 * if (AotWarmup.packages("com.example.myapp", "javafx")
 *         .exercise(MyApp::touchTheExpensiveBits)
 *         .runIfTraining()) return;      // trained; nothing else to do this run
 * }</pre>
 */
public final class AotWarmup {

    private final List<String> prefixes = new ArrayList<>();
    private int maxClasses = 12_000;
    private long budgetMs = 45_000;
    private Runnable exercise;
    private int iterations = 1;
    private PrintStream log = System.err;
    private boolean scanClasspath = true;
    private boolean wholeApp;

    private AotWarmup() { }

    /**
     * Load <b>every</b> class in the application's own artifact, whatever its package.
     *
     * <p><b>Measure before choosing this over {@link #packages}.</b> The intuition is that a shaded jar
     * ships all of it anyway, so loading all of it costs nothing — and that is true of disk and false of
     * startup. Tried on a CLI tool: an 81MB shaded jar produced a <b>143MB</b> cache and moved {@code --help}
     * from a ~1.12s median to ~2.8s, and made it erratic (1.1s–3.1s) where the curated build was steady.
     * Every startup has to map and validate the whole cache, so classes trained in but never used are a
     * standing tax. Curated prefixes won by 2.5x.
     *
     * <p>So this suits an application whose jar is mostly code it actually runs, and a small one especially.
     * An app carrying a database, a search engine and a UI toolkit to use a slice of each is the case where naming
     * the packages wins. When in doubt, build both and time the command you actually type.
     *
     * <p>Combine with {@link #alsoPackages} to reach into dependencies that lazy linking would otherwise
     * skip, and with {@link #exercise} to record method profiles as well as loaded classes.
     */
    public static AotWarmup wholeApp() {
        var w = new AotWarmup();
        w.wholeApp = true;
        w.scanClasspath = false;   // the app's own artifact only; the rest arrives transitively
        return w;
    }

    /**
     * The packages worth loading — everything startup-critical and expensive. Accepts either form,
     * {@code "com.example.myapp"} or {@code "com/example/myapp/"}.
     *
     * <p>Be generous: third-party frameworks are usually the expensive half. Be a <em>little</em> careful
     * too — naming {@code ""} would load every class on the classpath, which blows the time budget on
     * things the app never touches.
     */
    public static AotWarmup packages(String... packagePrefixes) {
        var w = new AotWarmup();
        for (var p : packagePrefixes) {
            if (p == null || p.isBlank()) continue;
            var slashed = p.trim().replace('.', '/');
            w.prefixes.add(slashed.endsWith("/") ? slashed : slashed + "/");
        }
        return w;
    }

    /**
     * Additionally reach into packages outside the application's own artifact — the dependency internals
     * that nothing in the app references directly, so loading the app would never pull them in. Frameworks
     * that resolve implementations reflectively (logging backends, JDBC drivers, service loaders) live here.
     * Re-enables the classpath scan that {@link #wholeApp()} switches off.
     */
    public AotWarmup alsoPackages(String... packagePrefixes) {
        var w = packages(packagePrefixes);
        this.prefixes.addAll(w.prefixes);
        this.scanClasspath = true;
        return this;
    }

    /** Ceiling on classes loaded. Default 12,000. */
    public AotWarmup maxClasses(int n) { this.maxClasses = n; return this; }

    /** Wall-clock ceiling. Default 45s. A training run is allowed to be slow, but not unbounded. */
    public AotWarmup budget(Duration d) { this.budgetMs = d.toMillis(); return this; }

    /**
     * The few things worth genuinely RUNNING because they dominate a real startup — building a CLI tree,
     * starting a UI toolkit, parsing a config. Must be side-effect-free enough to run on a training pass:
     * no database locks, no windows, no network, nothing the user did not ask for. Failures are caught.
     */
    public AotWarmup exercise(Runnable r) { this.exercise = r; return this; }

    /**
     * How many times to run {@link #exercise}. Default 1.
     *
     * <p><b>This is the half of AOT that class loading cannot buy.</b> A JDK 25 cache stores loaded and
     * linked classes (JEP 483) <i>and</i> method profiles (JEP 515) — but a profile only exists for a method
     * that actually RAN, and only becomes interesting once it has run enough times for the JIT to notice it.
     * One pass through a code path records that it was entered; a few thousand records how it behaves, which
     * is what lets a later run skip the slow interpreted warm-up.
     *
     * <p>This is the JVM's equivalent of GraalVM's profile-guided optimisation: the training run is the
     * instrumented run, and the workload you choose is the profile you get. Pick iteration counts that
     * exercise the real hot loops, not the whole application — a hot path run 10,000 times is worth more
     * than every path run once.
     *
     * <p><b>Train with C2 enabled.</b> {@code -XX:TieredStopAtLevel=1} during training suppresses exactly
     * the profiling this records. Measured across the four combinations, the best is: train
     * without tier1, consume the cache with it.
     */
    public AotWarmup iterations(int n) { this.iterations = Math.max(1, n); return this; }

    /** Where progress goes. Default {@code System.err}, so a tool whose stdout carries data stays clean. */
    public AotWarmup log(PrintStream out) { this.log = out == null ? System.err : out; return this; }

    /** Scan every classpath entry, not just this class's own. Default true. */
    public AotWarmup scanClasspath(boolean b) { this.scanClasspath = b; return this; }

    /**
     * Warm up only if this is the run that is building the cache.
     *
     * @return true if the warmup ran — the caller may then exit, or carry on and let the user's command
     *         run in the same process (the JVM assembles the cache at exit either way)
     */
    public boolean runIfTraining() {
        if (!AotState.creating()) return false;
        log.println("[aot] first run since the code changed — warming up to build the startup cache.");
        log.println("[aot] this run is slower; the next ones are much faster.");
        var blocker = AotState.classpathBlocker();
        if (blocker != null) {
            log.println("[aot] WARNING: no cache will be produced. The JVM refuses to dump one when the");
            log.println("[aot] classpath contains a directory, and this one has: " + blocker);
            log.println("[aot] Launch from a jar (or a classpath of jars only) if you want AOT.");
        }
        run();
        return true;
    }

    /** Force-load the configured packages and run {@link #exercise}. Never throws. */
    public int run() {
        var started = System.currentTimeMillis();
        var loaded = 0;
        var deadline = started + budgetMs;
        var loader = AotWarmup.class.getClassLoader();
        var sources = sources();
        if (sources.isEmpty()) log.println("[aot] no classpath sources found; nothing to preload");

        var own = ownCodeSource();
        outer:
        for (var source : sources) {
            for (var cls : classesIn(source, source.equals(own))) {
                if (loaded >= maxClasses) { log.println("[aot] class limit reached"); break outer; }
                if (System.currentTimeMillis() > deadline) { log.println("[aot] time budget reached"); break outer; }
                try {
                    // initialize=false: link it, do not run static initialisers.
                    Class.forName(cls, false, loader);
                    loaded++;
                } catch (Throwable t) {
                    // A class that will not load is exactly the sort that must not fail the run.
                }
            }
        }

        if (exercise != null) {
            var exerciseStart = System.currentTimeMillis();
            var ran = 0;
            for (var i = 0; i < iterations; i++) {
                try { exercise.run(); ran++; }
                catch (Throwable t) {
                    log.println("[aot] exercise stopped after " + ran + " iteration(s): " + t);
                    break;
                }
            }
            if (ran > 0) log.println("[aot] exercised " + ran + " iteration(s) in "
                + (System.currentTimeMillis() - exerciseStart) + "ms"
                + (iterations > 1 ? " (recording method profiles)" : ""));
        }

        log.println("[aot] preloaded " + loaded + " classes from " + sources.size()
            + " source(s) in " + (System.currentTimeMillis() - started) + "ms");
        return loaded;
    }

    // ---- classpath walking ---------------------------------------------------

    /** This class's own code source plus, unless disabled, every entry of {@code java.class.path}. */
    List<Path> sources() {
        var out = new LinkedHashSet<Path>();
        var own = ownCodeSource();
        if (own != null) out.add(own);
        if (scanClasspath) {
            var cp = System.getProperty("java.class.path", "");
            // The launcher has already expanded any `lib\*` wildcard into real entries by this point.
            for (var entry : cp.split(java.io.File.pathSeparator)) {
                if (entry.isBlank()) continue;
                try {
                    var p = Path.of(entry).toAbsolutePath().normalize();
                    if (Files.exists(p)) out.add(p);
                } catch (Exception ignore) {
                    // An unparseable classpath entry is the JVM's problem, not the warmup's.
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Where this library was loaded from — a jar or a directory. Null when it cannot be determined. */
    public static Path ownCodeSource() {
        try {
            var src = AotWarmup.class.getProtectionDomain().getCodeSource();
            if (src == null) return null;
            return Path.of(src.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    /** Binary names of the classes in one source that this warmup wants loaded. */
    List<String> classesIn(Path source, boolean isOwnArtifact) {
        try {
            if (Files.isDirectory(source)) return fromDirectory(source, isOwnArtifact);
            if (source.toString().toLowerCase().endsWith(".jar")) return fromJar(source, isOwnArtifact);
        } catch (Exception e) {
            log.println("[aot] could not read " + source + ": " + e);
        }
        return List.of();
    }

    List<String> fromJar(Path jar, boolean isOwnArtifact) throws Exception {
        var out = new ArrayList<String>();
        try (var jf = new JarFile(jar.toFile())) {
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                var name = entries.nextElement().getName();
                if (wanted(name, isOwnArtifact)) out.add(binaryName(name));
            }
        }
        return out;
    }

    List<String> fromDirectory(Path dir, boolean isOwnArtifact) throws Exception {
        var out = new ArrayList<String>();
        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                var rel = dir.relativize(p).toString().replace('\\', '/');
                if (wanted(rel, isOwnArtifact)) out.add(binaryName(rel));
            });
        }
        return out;
    }

    boolean wanted(String resourceName, boolean isOwnArtifact) {
        if (!resourceName.endsWith(".class")) return false;
        if (resourceName.contains("module-info") || resourceName.contains("package-info")) return false;
        // wholeApp takes everything out of the application's OWN artifact — everything in there ships
        // anyway, so there is nothing to save by being selective, and nothing to predict wrongly.
        if (wholeApp && isOwnArtifact) return true;
        for (var prefix : prefixes) if (resourceName.startsWith(prefix)) return true;
        return false;
    }

    static String binaryName(String resourceName) {
        return resourceName.substring(0, resourceName.length() - ".class".length()).replace('/', '.');
    }
}
