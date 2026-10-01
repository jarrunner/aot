package jarrunner.aot;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilding the AOT cache deliberately, instead of hoping the next run happens to be a good training run.
 *
 * <p><b>It has to be a child process.</b> The JVM assembles the cache when the training process EXITS, so a
 * process cannot create a cache and then use it. The child inherits this process's VM arguments so the cache
 * is trained under the configuration it will later be read under — a cache built with different flags is one
 * the JVM will quietly decline to use.
 *
 * <p>Both launch shapes are handled: {@code java -jar app.jar} and
 * {@code java -cp target\classes;lib\* MainClass} are each relaunched the way they were started.
 */
public final class AotTrainer {

    private AotTrainer() { }

    /** The argument the child is started with; the app must route it to {@link AotWarmup} and exit. */
    public static final String WARMUP_FLAG = "--aot-warmup";

    /**
     * Train a fresh cache at {@code cache} by relaunching this application with
     * {@code -XX:AOTCacheOutput} and {@link #WARMUP_FLAG}.
     *
     * @param cache where to write it — normally {@link AotState#cache()}, i.e. the file jr manages
     * @return the child's exit code
     */
    public static int rebuild(Path cache, PrintStream log) throws IOException {
        if (cache == null) throw new IOException(
            "no cache path known. Launch via jr (it exports JR_AOT_CACHE), or pass an explicit path.");

        if (Files.exists(cache)) {
            Files.delete(cache);
            log.println("[aot] removed old cache " + cache);
        }
        Files.createDirectories(cache.toAbsolutePath().getParent());

        var command = new ArrayList<String>();
        command.add(javaBinary());
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .filter(AotTrainer::keepForTraining)
            .forEach(command::add);
        command.add("-XX:AOTCacheOutput=" + cache);
        command.addAll(relaunchArgs());
        command.add(WARMUP_FLAG);

        log.println("[aot] training: " + String.join(" ", command));
        var pb = new ProcessBuilder(command);
        // jr hands its flags to the JVM through JAVA_TOOL_OPTIONS, and a child JVM inherits it. Left in
        // place, a rebuild launched from a normal run would inherit that run's -XX:AOTCache=<same file>
        // alongside the -XX:AOTCacheOutput=<same file> added above — reading and writing one cache in one
        // JVM. Every flag needed is already on the command line, so clear it.
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().put("JR_AOT_STATE", AotState.CREATING);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        try {
            var exit = pb.start().waitFor();
            if (exit != 0) log.println("[aot] training process exited with " + exit);
            return exit;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while training", e);
        }
    }

    /**
     * How to start this application again: {@code -jar app.jar}, or {@code -cp <classpath> MainClass}.
     * Read from {@code sun.java.command}, which carries whichever form was used.
     */
    static List<String> relaunchArgs() throws IOException {
        var command = System.getProperty("sun.java.command", "").trim();
        var first = command.isEmpty() ? "" : command.split("\\s+")[0];

        if (first.toLowerCase().endsWith(".jar")) return List.of("-jar", first);

        if (!first.isEmpty())
            return List.of("-cp", System.getProperty("java.class.path", "."), first);

        // No sun.java.command (a stripped or embedded VM): fall back to our own jar, if that is what we are.
        var own = AotWarmup.ownCodeSource();
        if (own != null && own.toString().endsWith(".jar")) return List.of("-jar", own.toString());
        throw new IOException("cannot work out how to relaunch this application for training "
            + "(no sun.java.command and not running from a jar)");
    }

    static boolean keepForTraining(String arg) {
        return !arg.startsWith("-agentlib")
            && !arg.startsWith("-javaagent")
            && !arg.startsWith("-XX:AOTCache");
    }

    static String javaBinary() {
        var win = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", win ? "java.exe" : "java").toString();
    }
}
