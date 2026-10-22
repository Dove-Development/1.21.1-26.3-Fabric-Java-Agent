package dev.dove.agent.patches.fabric;

import dev.dove.agent.transform.DoveTransformer;
import dev.dove.agent.transform.PatchRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Patches Fabric Loader 0.19.5's own FileSystemUtil, as the agent does at startup, and uses it. */
class JarFileSystemPatchTest {

    @TempDir
    Path dir;

    @Test
    void fabricOpensModJarsReadOnlyAndCreatesWritableOnes() throws Exception {
        Method open = patchedFileSystemUtil().getMethod("getJarFileSystem", Path.class, boolean.class);

        Path mod = dir.resolve("mod.jar");
        try (OutputStream out = Files.newOutputStream(mod); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write("{}".getBytes());
        }
        try (AutoCloseable delegate = (AutoCloseable) open.invoke(null, mod, false)) {
            FileSystem fs = fileSystem(delegate);
            assertTrue(fs.isReadOnly());
            assertEquals("{}", Files.readString(fs.getPath("fabric.mod.json")));
        }
        try (AutoCloseable delegate = (AutoCloseable) open.invoke(null, dir.resolve("new.jar"), true)) {
            assertFalse(fileSystem(delegate).isReadOnly());
        }
    }

    private static FileSystem fileSystem(Object delegate) throws ReflectiveOperationException {
        return (FileSystem) delegate.getClass().getMethod("get").invoke(delegate);
    }

    private static Class<?> patchedFileSystemUtil() throws IOException, ClassNotFoundException {
        String name = JarFileSystemPatch.TARGET;
        ClassLoader parent = JarFileSystemPatchTest.class.getClassLoader();
        byte[] original;
        try (InputStream in = parent.getResourceAsStream(name + ".class")) {
            assertNotNull(in, "Fabric Loader is on the test class path");
            original = in.readAllBytes();
        }
        PatchRegistry registry = new PatchRegistry();
        registry.register(new JarFileSystemPatch());
        byte[] patched = new DoveTransformer(registry).transform(parent, name, null, null, original);
        assertNotNull(patched, "the patch applies to Fabric's FileSystemUtil");
        String className = name.replace('/', '.');
        return new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                if (!n.equals(className)) {
                    return super.loadClass(n, resolve);
                }
                synchronized (getClassLoadingLock(n)) {
                    Class<?> c = findLoadedClass(n);
                    return c != null ? c : defineClass(n, patched, 0, patched.length);
                }
            }
        }.loadClass(className);
    }
}
