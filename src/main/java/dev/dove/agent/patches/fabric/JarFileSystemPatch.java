package dev.dove.agent.patches.fabric;

import dev.dove.agent.hooks.JarFileSystemHooks;
import dev.dove.agent.transform.Bytecode;
import dev.dove.agent.transform.ClassPatch;
import org.objectweb.asm.tree.ClassNode;

import java.util.Set;

/**
 * Fabric Loader's jar file systems open read-only where Fabric only reads them; see
 * {@link JarFileSystemHooks}.
 */
public final class JarFileSystemPatch implements ClassPatch {

    static final String TARGET = "net/fabricmc/loader/impl/util/FileSystemUtil";

    @Override
    public String name() {
        return "JarFileSystem";
    }

    @Override
    public Set<String> targets() {
        return Set.of(TARGET);
    }

    @Override
    public boolean apply(ClassNode node) {
        return Bytecode.redirectCall(node, "java/nio/file/FileSystems", "newFileSystem",
                "(Ljava/net/URI;Ljava/util/Map;)Ljava/nio/file/FileSystem;",
                JarFileSystemHooks.OWNER, "newFileSystem", "(Ljava/net/URI;Ljava/util/Map;)Ljava/nio/file/FileSystem;") > 0;
    }
}
