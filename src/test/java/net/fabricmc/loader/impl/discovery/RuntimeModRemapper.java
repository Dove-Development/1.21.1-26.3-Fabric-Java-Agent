package net.fabricmc.loader.impl.discovery;

import dev.dove.agent.hooks.JarFileSystemHooks;

import java.net.URI;
import java.nio.file.FileSystem;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Stands in for Fabric's runtime mod remapper, the one caller that writes through a jar file
 * system. Test classes come before Fabric Loader on the test class path, so this one is used in
 * place of Fabric's.
 */
public final class RuntimeModRemapper {

    private RuntimeModRemapper() {
    }

    public static <T> T call(Callable<T> action) throws Exception {
        return action.call();
    }

    public static FileSystem open(URI uri) throws Exception {
        return call(() -> JarFileSystemHooks.newFileSystem(uri, Map.of()));
    }
}
