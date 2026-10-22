package dev.dove.agent.hooks;

import dev.dove.agent.util.AgentLog;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fabric Loader opens every mod jar, and every jar nested in one, as a zip file system
 * ({@code FileSystemUtil.getJarFileSystem}) to read classes, resources and metadata. The JDK's zip
 * file system first asks Windows whether the jar is writable ({@code Files.isWritable}), which on
 * Windows checks the volume and the file's access rights: about 12 ms a jar on the test laptop, on
 * Java 21 as on 25. Opening the jar read-only ({@code accessMode=readOnly}) skips that question;
 * the zip file system then reads the same entries in the same way.
 *
 * <p>Fabric writes through such a file system in one place, the runtime mod remapper (it rewrites a
 * remapped jar's class tweaker), so calls from there keep the original arguments. So does any call
 * that asks to create the file.
 */
public final class JarFileSystemHooks {

    public static final String OWNER = "dev/dove/agent/hooks/JarFileSystemHooks";

    static final Map<String, String> READ_ONLY = Map.of("accessMode", "readOnly");
    static final String REMAPPER = "net.fabricmc.loader.impl.discovery.RuntimeModRemapper";

    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final AtomicInteger OPENED = new AtomicInteger();
    private static final AtomicInteger READ_ONLY_OPENED = new AtomicInteger();
    private static final AtomicLong NANOS = new AtomicLong();

    private JarFileSystemHooks() {
    }

    /** Replaces {@code FileSystems.newFileSystem(uri, env)} in Fabric's {@code FileSystemUtil}. */
    public static FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        long start = System.nanoTime();
        try {
            if (env.isEmpty() && !fromRemapper()) {
                READ_ONLY_OPENED.incrementAndGet();
                return FileSystems.newFileSystem(uri, READ_ONLY);
            }
            return FileSystems.newFileSystem(uri, env);
        } finally {
            NANOS.addAndGet(System.nanoTime() - start);
            int opened = OPENED.incrementAndGet();
            if (AgentLog.isDebug()) {
                AgentLog.debug("Jar file system " + opened + " (" + READ_ONLY_OPENED.get() + " read-only, "
                        + NANOS.get() / 1_000_000 + " ms so far): " + uri);
            }
        }
    }

    static boolean fromRemapper() {
        return WALKER.walk(frames -> frames.anyMatch(frame -> frame.getClassName().equals(REMAPPER)));
    }

    /** Opened so far, read-only, and the time spent opening them, for the log. */
    public static String stats() {
        return OPENED.get() + " jar file systems (" + READ_ONLY_OPENED.get() + " read-only) in " + NANOS.get() / 1_000_000 + " ms";
    }
}
