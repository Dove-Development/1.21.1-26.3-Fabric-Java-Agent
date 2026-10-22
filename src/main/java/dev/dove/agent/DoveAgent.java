package dev.dove.agent;

import dev.dove.agent.hooks.JarFileSystemHooks;
import dev.dove.agent.hooks.WindowsRuntime;
import dev.dove.agent.patches.fabric.JarFileSystemPatch;
import dev.dove.agent.transform.DoveTransformer;
import dev.dove.agent.transform.PatchRegistry;
import dev.dove.agent.util.AgentLog;

import java.lang.instrument.Instrumentation;

/**
 * Entry point for the Dove agent.
 *
 * <p>Started with {@code -javaagent:dove-agent.jar} before Fabric's Knot class loader starts, so
 * every class Minecraft and mods define afterwards passes through {@link DoveTransformer}.
 */
public final class DoveAgent {

    private static volatile Instrumentation instrumentation;
    private static volatile AgentOptions options = AgentOptions.parse(null);

    private DoveAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        start(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        // Attaching late is only useful for debugging: the classes these patches target are
        // loaded during startup.
        AgentLog.warn("Attached after startup; patches for already-loaded classes will not apply.");
        start(args, inst);
    }

    private static synchronized void start(String args, Instrumentation inst) {
        if (instrumentation != null) {
            AgentLog.warn("Agent already started, ignoring second start.");
            return;
        }
        long startNanos = System.nanoTime();
        instrumentation = inst;
        options = AgentOptions.parse(args);
        AgentLog.init(options.logFile(), options.debug());

        AgentLog.info("Dove agent " + version() + " on Java " + Runtime.version() + " (" + System.getProperty("java.vendor") + ")");
        if (Runtime.version().feature() < 25) {
            AgentLog.warn("Dove targets Java 25; running on " + Runtime.version().feature() + " is untested.");
        }

        if (WindowsRuntime.isWindows()) {
            if (options.timer()) {
                WindowsRuntime.apply("1 ms timer resolution", WindowsRuntime::raiseTimerResolution);
            }
            // premain runs on the thread that becomes Minecraft's render thread.
            if (options.renderPriority()) {
                WindowsRuntime.apply("render thread priority above normal", WindowsRuntime::raiseThreadPriority);
            }
        }

        PatchRegistry registry = new PatchRegistry();
        registerPatches(registry);
        if (registry.size() > 0) {
            inst.addTransformer(new DoveTransformer(registry), false);
        }

        AgentLog.info("Registered " + registry.size() + " patches in " + (System.nanoTime() - startNanos) / 1_000_000 + " ms");
    }

    private static void registerPatches(PatchRegistry registry) {
        // Faster loading: skip work that cannot change the result
        if (options.fastStartup()) {
            registry.register(new JarFileSystemPatch());
            if (options.debug()) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> AgentLog.info(JarFileSystemHooks.stats()), "Dove agent stats"));
            }
        } else {
            AgentLog.info("Startup changes off (fastStartup=false)");
        }
    }

    public static Instrumentation instrumentation() {
        return instrumentation;
    }

    public static AgentOptions options() {
        return options;
    }

    public static String version() {
        String version = DoveAgent.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
