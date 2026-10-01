# aot — the AOT training route for jr

The training payload that makes a `jr.exe` AOT cache worth having, so every tool can have it in three lines.

`jr` builds an AOT cache for any tool with `aot=true` in its `.jrc`. What it cannot do is decide **what goes
in it**: the JVM assembles the cache out of the classes the training run happened to load. If that run was
`mytool --help`, the cache holds the help path and nothing else — and that is **worse than no cache at
all**, because naming a cache also switches off the default CDS archive.

    mytool --help: 1645ms  no AOT flags
                   1837ms  cache trained on a trivial path   <-- slower than none
                    844ms  cache trained on a full warmup pass

## Use

```xml
<dependency>
    <groupId>io.github.jarrunner</groupId>
    <artifactId>aot</artifactId>
    <version>1.0</version>
</dependency>
```

At the very top of `main`, before anything else runs:

```java
public static void main(String[] args) {
    if (AotSetup.handled(args)) return;      // the whole hook; see below
    ...
}
```

where `AotSetup` is yours, and holds the only part that is ever application-specific — *what* to load:

```java
static AotWarmup warmup() {
    return AotWarmup.packages("com.example.myapp", "javafx", "com.fasterxml.jackson")
        .maxClasses(20_000)
        .budget(Duration.ofMinutes(4))
        .exercise(AotSetup::exercise);       // the few things worth genuinely RUNNING
}

public static boolean handled(String[] args) {
    if (args.length > 0 && AotTrainer.WARMUP_FLAG.equals(args[0])) { warmup().run(); return true; }
    warmup().runIfTraining();                // jr says "creating" but a real command was typed
    return false;
}
```

`runIfTraining()` is a no-op unless jr says this run is writing the cache, so it costs nothing on every
other run. After it warms up you may exit or let the user's command run in the same process — the JVM
assembles the cache at exit either way, so one run can both train and work. Rejecting the user's command,
or re-running it in a second process, would both be worse.

**Put the hook in every entry point.** An app with two mains, say a CLI and a GUI launcher, needs it in
both: if jr launches the GUI and only the CLI is hooked, the cache is trained on a popup and the app is left
cold.

## The rule that decides everything: train for the command you actually run

An AOT cache has a **fixed cost paid at every startup** (mapping and validating the whole file) and a
benefit **proportional to how much the command loads**. So a bigger cache is not a better cache — it is a
better cache *for a heavier command*, and a worse one for a light one. Measured here, both directions:

| application | training | cache | command | result |
|---|---|---|---|---|
| CLI tool | whole shaded jar | 143 MB | `--help` | 2819 ms, erratic 1.1–3.1 s |
| CLI tool | curated packages | 82 MB | `--help` | **1123 ms**, stable |
| desktop app | curated packages | 84 MB | full class-load path | **6968 → 2126 ms (3.3x)** |
| desktop app | curated packages | 84 MB | a trivial headless command | 765 → 834 ms (slower) |

So the tempting intuition — *"it all ships in the shaded jar anyway, so loading it all costs nothing"* — is
true of disk and false of startup. The CLI tool carries a database, a search engine and a UI toolkit to use a slice of each;
training on all of it filled the cache with classes every startup then had to map, and lost 2.5x.

Pick by what your real workload does:

- **Heavy startup** (a UI, a database, a framework container) → load broadly. The 3.3x row is that case.
- **Light, short commands** → curate. Name the startup-critical packages, leave the rest to lazy loading.
- **When unsure, build both and time the command you actually type.** It takes ten minutes and the answer
  is not guessable.

`AotWarmup.wholeApp()` exists for the first case and takes everything in the application's own artifact;
`packages(...)` is the curated form; `alsoPackages(...)` combines them.

## Loading is only half of it — profiles are the other half

A JDK 25 cache stores **two** things:

- **loaded and linked classes** (JEP 483) — what force-loading gets you, and it helps startup;
- **method profiles** (JEP 515) — which only exist for methods that actually *ran*, and only become useful
  once they have run enough times for the JIT to take an interest.

Force-loading alone therefore leaves half the benefit on the table. `exercise(...)` is where real code runs,
and `iterations(n)` runs it repeatedly — this is the JVM's equivalent of GraalVM's profile-guided
optimisation, where the training run is the instrumented run and the workload you pick *is* the profile you
get. Pick the hot loops, not the whole app: one path run 10,000 times is worth more than every path once.

**Train with C2 enabled.** `-XX:TieredStopAtLevel=1` during training suppresses exactly the profiling this
records. Measured across all four combinations, the best pairing is *train without tier1, consume the cache
with it*.

`exercise` must stay side-effect-free enough for a run the user did not ask for: no database locks, no
windows, no network. Classes elsewhere are loaded with `initialize=false`, so no static initialiser runs.

### Choosing the hot paths

Two inputs, and they complement each other:

1. **The classpath scan** gives breadth — every class in the artifact, no judgement required, nothing
   forgotten.
2. **A human or an AI reading the code** gives depth — which handful of methods run thousands of times per
   session. For a chat client that is the per-message path (parsing, decoding, retry policy), not the
   once-per-launch path. That list belongs in `exercise` with an iteration count, and is worth
   revisiting when the hot path moves.

## Building the cache in the Maven lifecycle

Train the **shaded jar**, after `shade`, so the cache matches the classpath it will be read under — and
because the JVM refuses to dump a cache for a classpath containing a directory (see the traps below), the
shaded jar is the only thing that can be trained anyway. Opt-in, since it adds ~30 s:

```xml
<profile>
  <id>aot</id>
  <build><plugins><plugin>
    <groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId><version>3.5.0</version>
    <executions><execution>
      <id>train-aot-cache</id><phase>verify</phase><goals><goal>exec</goal></goals>
      <configuration>
        <executable>${java.home}/bin/java</executable>
        <arguments>
          <argument>-XX:AOTCacheOutput=${project.build.directory}/${project.build.finalName}-all.aot</argument>
          <argument>-cp</argument>
          <argument>${project.build.directory}/${project.build.finalName}-all.jar</argument>
          <argument>com.example.Main</argument>
          <argument>--aot-warmup</argument>
        </arguments>
      </configuration>
    </execution></executions>
  </plugin></plugins></build>
</profile>
```

    mvn -Paot package

Ship the `.aot` beside the jar and run with `-XX:AOTCache=app.aot`. A cache the JVM cannot use is ignored
with a warning, never fatally, so a stale one can never break a run.

## Two traps, both measured, both detected for you

**1. AOT needs a jar-only classpath.** The JVM refuses to dump a cache when the classpath contains a
non-empty directory — `Cannot have non-empty directory in paths` — and writes nothing. This is why `jr`
wires AOT up only for `-jar` launches: a JVM restriction, not a `jr` limitation. A `.jrc` saying `aot=true`
while launching `-cp target\classes;lib\*` has silently never had a cache, however long it has claimed
otherwise. `AotState.classpathBlocker()` names the offending entry, `describe()` prints it, and
`runIfTraining()` warns instead of leaving you to a cryptic VM error.

**2. Budget for dump mode, not for a normal run.** Class loading is roughly 4x slower while the JVM records
a cache. Measured: the same pass loads 9,794 classes in ~11 s normally and hit the default 45 s budget at
6,166 while dumping — silently producing a *partial* cache, the exact failure this library exists to
prevent. Set `budget(...)` and `maxClasses(...)` for the dumping run.

## API

- `AotState` — what jr decided: `state()`, `creating()`, `using()`, `cache()`, `cacheExists()`,
  `classpathBlocker()`, `describe()`. Reads `JR_AOT_STATE` / `JR_AOT_CACHE`, falling back to the VM
  arguments for a run started by hand. Memoized, because the fallback pulls in `java.lang.management` and
  every user asks on its startup path.
- `AotWarmup` — `packages(...)` or `wholeApp()`, then `alsoPackages` / `maxClasses` / `budget` / `exercise`
  / `iterations` / `log` / `scanClasspath`, then `run()` or `runIfTraining()`.
- `AotTrainer` — `rebuild(cache, log)` retrains deliberately in a child process (a process cannot create a
  cache and then use it — the JVM writes it at exit). Handles both `-jar` and `-cp … MainClass` relaunches.
  `WARMUP_FLAG` is the argument the child gets.

## No dependencies, permanently

This sits on the startup path of every tool that uses it and its whole job is to make startup cheap; a
dependency here would be loaded by all of them before they do anything. It also has to drop into apps with
incompatible logging and JSON stacks without an argument, which it can only do by having none.
