package dev.dove.agent.hooks;

import net.fabricmc.loader.impl.discovery.RuntimeModRemapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarFileSystemHooksTest {

    @TempDir
    Path dir;

    @Test
    void readsTheSameEntriesReadOnly() throws IOException {
        URI uri = jar("mod.jar");
        try (FileSystem fs = JarFileSystemHooks.newFileSystem(uri, Map.of())) {
            assertTrue(fs.isReadOnly());
            assertEquals("{\"id\":\"mod\"}", Files.readString(fs.getPath("fabric.mod.json")));
            assertEquals("class bytes", Files.readString(fs.getPath("a/b/C.class")));
            assertThrows(ReadOnlyFileSystemException.class, () -> Files.writeString(fs.getPath("new.txt"), "x"));
        }
    }

    @Test
    void keepsWhatTheCallerAskedFor() throws IOException {
        URI uri = URI.create("jar:" + dir.resolve("created.jar").toUri());
        try (FileSystem fs = JarFileSystemHooks.newFileSystem(uri, Map.of("create", "true"))) {
            assertFalse(fs.isReadOnly());
            Files.writeString(fs.getPath("written.txt"), "x");
        }
    }

    @Test
    void theRemapperStillWrites() throws Exception {
        URI uri = jar("remapped.jar");
        try (FileSystem fs = RuntimeModRemapper.open(uri)) {
            assertFalse(fs.isReadOnly());
            Files.delete(fs.getPath("fabric.mod.json"));
            Files.writeString(fs.getPath("fabric.mod.json"), "rewritten");
        }
        try (FileSystem fs = JarFileSystemHooks.newFileSystem(uri, Map.of())) {
            assertEquals("rewritten", Files.readString(fs.getPath("fabric.mod.json")));
        }
    }

    @Test
    void knowsTheRemapper() throws Exception {
        assertFalse(JarFileSystemHooks.fromRemapper());
        assertTrue(RuntimeModRemapper.call(JarFileSystemHooks::fromRemapper));
    }

    private URI jar(String name) throws IOException {
        Path jar = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write("{\"id\":\"mod\"}".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("a/b/C.class"));
            zip.write("class bytes".getBytes(StandardCharsets.UTF_8));
        }
        return URI.create("jar:" + jar.toUri());
    }
}
