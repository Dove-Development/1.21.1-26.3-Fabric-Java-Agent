# Dove Agent

A Java agent for **Minecraft 1.21.1, 1.21.11 and 26.3 on Fabric**, running on **Java 25**, for Dove Client.

One codebase covers both versions: the only patch targets Fabric Loader, which both versions run
(0.19.5), not Minecraft, so it doesn't care that 1.21.1 and 1.21.11 are obfuscated and 26.3 isn't.

## What it does

| Patch | Where | Effect |
|---|---|---|
| `JarFileSystemPatch` | Fabric Loader (`FileSystemUtil`) | Mod jars, and jars nested in them, are opened read-only where Fabric only reads them. The JDK's zip file system otherwise first asks Windows whether each jar is writable, about 6-12 ms a jar. Fabric opens about 60 at startup: 1.21.1 730 -> 290 ms, 1.21.11 610 -> 255 ms, 26.3 650 -> 243 ms, so **about 0.35-0.45 s less to the title screen**. Fabric's one writer (the runtime mod remapper) and any call that creates a file keep their original arguments. |

Off switch: `fastStartup=false`.

Two Windows settings from the old shared agent's Fabric mode are kept as options, **off by
default because they measured no gain** (1.21.11 with FPS Boost, 3 alternating rounds each):
`timer=true` (1 ms timer resolution) and `renderPriority=true` (render thread above normal).
Uncapped, average FPS moved +1.5 still and -1.9 turning (medians); capped at 60, averages were
identical and the 0.1% lows went -8.8 still and +1.4 turning: noise either way.

## What was checked

**1.21.1 on Java 25 needs no fixes either**, checked the same way as 1.21.11 below: the jar scan
(42,354 classes) found only the same unused ImmediatelyFast library path; 11 sessions with and
without FPS Boost on Java 21 and 25 all exit 0 with the same warnings and errors on both; average
FPS is the same (with FPS Boost 160.0 vs 153.7 still, 147.7 vs 146.3 turning; without it 104.2 vs
110.4 and 74.7 vs 72.7, medians, Java 21 vs 25) and Java 25's 1% lows are 4 to 17 FPS better.
ZGC over a whole session: 62 pauses, the longest 0.06 ms, no allocation stalls.

**1.21.11 on Java 25 needs no fixes.** Compared with Java 21 (both with the launcher's flags and
garbage collector: ZGC on 25, G1 on 21):

- Every class in Minecraft, its libraries, Fabric Loader, Mixin, Fabric API, Dove and the FPS Boost
  mods (45,688 classes, nested jars included) was checked against both JDKs with `apiScan`.
  Nothing they call was removed in Java 22-25 except in code that never runs on 25 (ImmediatelyFast's
  bundled Reflect library has a Java 24+ path; its one call ImmediatelyFast makes, `Objects.allocate`
  for maps, was run on 21 and 25: same result). Nobody calls `System.setSecurityManager`,
  `Thread.suspend/resume/stop` or `Subject.getSubject`. `File.delete` (Java 25 no longer deletes
  read-only files on Windows) is only used on the game's and mods' own temporary and config files.
- 8 full sessions (create a world, play, quit, rejoin, play, close the window), with and without
  FPS Boost, Java 21 and 25: all exit 0, and the warnings and errors are the same set on both.
- Startup and frame rate, alternating runs, medians: title screen 31.8 s (21) vs 31.5 s (25);
  average FPS 126.7 vs 125.9 with FPS Boost and 88.2 vs 87.8 without; Java 25's 1% lows are 6 to
  12 FPS better (ZGC).
- `--enable-native-access=ALL-UNNAMED` and `--sun-misc-unsafe-memory-access=allow` only silence
  two warnings on 25 (JNA loading its library, JOML using `sun.misc.Unsafe`); both are right to keep.

**Looked at and not done**, because it measured no gain or could not be proven:
- Java 25's AOT cache (`-XX:AOTCache`): 28.2 vs 28.6 s to the title. Fabric's class loader defines
  nearly every game and mod class, and the cache only covers the JDK's own class loaders.
- Skipping bytecode verification: no gain, and it is a diagnostic-only switch on 25.
- Frame rate: in a world the render thread spends its time in the graphics driver (waiting for the
  GPU, draw calls); the Java side (Sodium, ImmediatelyFast, Dove's HUD) is a few percent, and nothing
  runs per frame that an agent could remove safely.
- Startup otherwise goes to class loading and Mixin, Minecraft's bootstrap, the driver compiling
  shaders, and a resource reload that waits on its own steps rather than on the CPU.

## Building

Requires nothing but a JDK to run Gradle; the build downloads a JDK 25 toolchain if one isn't installed.

```bash
./gradlew build
```

The agent is `build/libs/dove-agent.jar`. (The `-slim` jar is the same code without ASM shaded
in; don't use it as the agent.)

## Using

```
-javaagent:/path/to/dove-agent.jar=log=<minecraft dir>/logs/dove-agent.log
--enable-native-access=ALL-UNNAMED
--sun-misc-unsafe-memory-access=allow
```

Agent options (comma-separated after `=`):

| Option  | Default                       | Meaning                                       |
|---------|-------------------------------|-----------------------------------------------|
| `log`   | console only                  | file to also write the agent log to           |
| `cache` | `<java.io.tmpdir>/dove-agent` | directory for cached work                     |
| `debug` | `false`                       | verbose logging (also `-Ddove.debug=true`)    |
| `fastStartup` | `true`                  | `false` turns off the startup changes         |
| `timer` | `false`                       | `true` asks Windows for a 1 ms timer          |
| `renderPriority` | `false`              | `true` raises the render thread's priority    |

## Testing tools

- `./gradlew apiScan --args="--jdk21 <JDK 21 home> [--where java.io.File.delete] [--out report.txt] <jar or folder>..."`
  lists every Java API the given jars (and the jars inside them) use that was removed, is marked for
  removal, or behaves differently on Java 25 than on Java 21, checked against both JDKs' own class files.
- `python tools/mcrun.py --version 1.21.1|1.21.11|26.3 --java <home> --dove <jar> --fabric-api <jar> --game-dir <dir> [--agent build/libs/dove-agent.jar[=options]] [--pack <folder>] [--mode bench] [--rejoin] [--jfr <file>]`
  starts the game the way the Dove launcher does (its .minecraft, libraries and Java 25 flags) in a
  separate game folder, and prints the time to the title screen, into the world, and the bench's
  frame rates as one JSON line. `--rejoin` joins the newest world again, plays, and closes the window.
  For a version the launcher hasn't installed, set `DOVE_MC_DIR` to a folder laid out like `.minecraft`.

## Project layout

```
src/main/java/dev/dove/agent/
├── DoveAgent.java        premain entry point; registers patches
├── AgentOptions.java     log= / cache= / debug= / fastStartup= options
├── transform/            ClassPatch interface, registry (by name or constant-pool marker), transformer
├── patches/              bytecode patches
├── hooks/                runtime code that patched bytecode (or premain) calls into
└── util/                 console + file logger, constant-pool helpers
src/scan/java/            the jar scanner (not part of the agent jar)
tools/mcrun.py            the launch and benchmark harness
```

**Gotcha:** the shaded jar relocates every string literal that looks like `org/objectweb/asm/...`.
To name the game's own ASM classes, build the string at runtime.

**1.21.1 and 1.21.11 are obfuscated:** in a real launch its classes are named `net/minecraft/class_1234`
(Fabric's intermediary names), not the Mojang names seen in the dev environment. A patch on a
Minecraft class must target the intermediary name on 1.21.1 and 1.21.11 and the plain name on 26.3.
