package dev.dove.agent.tools;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lists the Java APIs a set of jars uses that were removed, are marked for removal, or behave
 * differently on Java 25 than on Java 21.
 *
 * <p>Every reference from a class into the JDK (a call, a field, a type, or a JDK class named in a
 * string, as reflection does) is looked up in both JDKs' own class files, so "removed" and "for
 * removal" come from the JDKs themselves. Jars inside jars (Fabric's {@code META-INF/jars}) are
 * read too. Run with {@code ./gradlew apiScan --args="--jdk21 <home> [--out report.txt] <jar or dir>..."}.
 */
public final class ApiScan {

    private static final Pattern JDK_CLASS_STRING = Pattern.compile("(java|javax|jdk|sun|com\\.sun)\\.[A-Za-z0-9_.$]+");
    /** Strings reflection uses to reach JDK internals; reported where they appear. */
    private static final Set<String> REFLECTION_HINTS = Set.of(
            "theUnsafe", "modifiers", "IMPL_LOOKUP", "implAddOpens", "implAddOpensToAllUnnamed",
            "setSecurityManager", "getSecurityManager", "addURL", "ucp", "trustedLookup");

    /** Calls whose behaviour changed between Java 21 and 25, by owner.name (any descriptor). */
    private static final Map<String, String> CHANGED = new TreeMap<>(Map.ofEntries(
            Map.entry("java/lang/System.setSecurityManager", "throws UnsupportedOperationException since Java 24 (JEP 486)"),
            Map.entry("java/lang/System.getSecurityManager", "always null since Java 24 (JEP 486)"),
            Map.entry("java/lang/SecurityManager.<init>", "a SecurityManager can be built but never installed since Java 24"),
            Map.entry("javax/security/auth/Subject.getSubject", "throws UnsupportedOperationException since Java 23"),
            Map.entry("javax/security/auth/Subject.doAs", "no longer ties the Subject to the AccessControlContext since Java 23"),
            Map.entry("java/lang/Thread.stop", "throws UnsupportedOperationException"),
            Map.entry("java/lang/Thread.suspend", "removed in Java 23"),
            Map.entry("java/lang/Thread.resume", "removed in Java 23"),
            Map.entry("java/lang/ThreadGroup.stop", "removed in Java 23"),
            Map.entry("java/lang/ThreadGroup.suspend", "removed in Java 23"),
            Map.entry("java/lang/ThreadGroup.resume", "removed in Java 23"),
            Map.entry("java/lang/System.runFinalization", "finalization deprecated for removal (JEP 421)"),
            Map.entry("java/lang/Runtime.runFinalization", "finalization deprecated for removal (JEP 421)"),
            Map.entry("java/io/File.delete", "on Windows no longer deletes read-only files since Java 25"),
            Map.entry("java/lang/System.load", "JNI library loading: native access warning without --enable-native-access (JEP 472)"),
            Map.entry("java/lang/System.loadLibrary", "JNI library loading: native access warning without --enable-native-access (JEP 472)"),
            Map.entry("java/lang/Runtime.load", "JNI library loading: native access warning without --enable-native-access (JEP 472)"),
            Map.entry("java/lang/Runtime.loadLibrary", "JNI library loading: native access warning without --enable-native-access (JEP 472)"),
            Map.entry("java/lang/foreign/Linker.downcallHandle", "restricted method (JEP 472)"),
            Map.entry("java/lang/foreign/Linker.upcallStub", "restricted method (JEP 472)"),
            Map.entry("java/lang/foreign/SymbolLookup.libraryLookup", "restricted method (JEP 472)"),
            Map.entry("java/lang/foreign/MemorySegment.reinterpret", "restricted method (JEP 472)"),
            Map.entry("java/lang/invoke/MethodHandles.privateLookupIn", "needs the target package opened"),
            Map.entry("java/lang/reflect/AccessibleObject.setAccessible", "into JDK internals needs the package opened"),
            Map.entry("java/lang/reflect/Field.setAccessible", "into JDK internals needs the package opened"),
            Map.entry("java/lang/reflect/Method.setAccessible", "into JDK internals needs the package opened"),
            Map.entry("java/lang/reflect/Constructor.setAccessible", "into JDK internals needs the package opened")));

    private final Jdk jdk21;
    private final Jdk jdk25;
    /** category -> finding -> jars */
    private final Map<String, Map<String, Set<String>>> findings = new TreeMap<>();
    private final Map<String, Integer> classCounts = new TreeMap<>();
    private final Map<String, Integer> classVersions = new TreeMap<>();
    /** --where owner.name: every call site of these members, as jar: class.method */
    private final Set<String> where = new TreeSet<>();

    private ApiScan(Jdk jdk21, Jdk jdk25) {
        this.jdk21 = jdk21;
        this.jdk25 = jdk25;
    }

    public static void main(String[] args) throws IOException {
        Path jdk21Home = null;
        Path jdk25Home = Path.of(System.getProperty("java.home"));
        Path out = null;
        List<String> where = new ArrayList<>();
        List<Path> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--jdk21" -> jdk21Home = Path.of(args[++i]);
                case "--jdk25" -> jdk25Home = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--where" -> where.add(args[++i].replace('.', '/').replaceFirst("/([^/]+)$", ".$1"));
                // @list.txt: one jar or folder a line
                default -> {
                    if (args[i].startsWith("@")) {
                        Files.readAllLines(Path.of(args[i].substring(1))).stream()
                                .filter(line -> !line.isBlank()).map(String::strip).map(Path::of).forEach(inputs::add);
                    } else {
                        inputs.add(Path.of(args[i]));
                    }
                }
            }
        }
        if (jdk21Home == null || inputs.isEmpty()) {
            System.err.println("Usage: ApiScan --jdk21 <JDK 21 home> [--jdk25 <JDK 25 home>] [--out <file>] <jar or dir>...");
            System.exit(2);
        }
        ApiScan scan = new ApiScan(Jdk.open(jdk21Home), Jdk.open(jdk25Home));
        scan.where.addAll(where);
        for (Path input : inputs) {
            List<Path> jars;
            try (Stream<Path> walk = Files.isDirectory(input) ? Files.walk(input) : Stream.of(input)) {
                jars = walk.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
            }
            for (Path jar : jars) {
                try (InputStream in = Files.newInputStream(jar)) {
                    scan.scanJar(jar.getFileName().toString(), in);
                }
            }
        }
        if (out != null) {
            try (PrintStream file = new PrintStream(Files.newOutputStream(out), true, StandardCharsets.UTF_8)) {
                scan.report(file);
            }
            System.out.println("Report written to " + out);
        } else {
            scan.report(System.out);
        }
    }

    private void scanJar(String label, InputStream in) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
            String name = entry.getName();
            if (name.endsWith(".jar")) {
                byte[] nested = zip.readAllBytes();
                scanJar(label + "!" + name.substring(name.lastIndexOf('/') + 1), new ByteArrayInputStream(nested));
            } else if (name.endsWith(".class") && !name.endsWith("module-info.class")) {
                byte[] bytes = zip.readAllBytes();
                try {
                    scanClass(label, bytes);
                } catch (RuntimeException e) {
                    add("Unreadable class files", name + ": " + e, label);
                }
            }
        }
    }

    private void scanClass(String jar, byte[] bytes) {
        classCounts.merge(jar, 1, Integer::sum);
        int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        classVersions.merge("Java " + (major - 44) + " class files", 1, Integer::sum);
        if (major - 44 > 21) {
            add("Class files newer than Java 21", "Java " + (major - 44), jar);
        }
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            String self;

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                self = name;
                if (superName != null) {
                    type(superName);
                }
                if (interfaces != null) {
                    for (String itf : interfaces) {
                        type(itf);
                    }
                }
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                descriptorTypes(descriptor);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (name.equals("finalize") && descriptor.equals("()V") && (access & Opcodes.ACC_STATIC) == 0) {
                    add("Behaviour changed", "overrides Object.finalize(): finalization deprecated for removal (JEP 421)", jar);
                }
                if ((access & Opcodes.ACC_NATIVE) != 0) {
                    add("JNI native methods (need native access)", self.substring(0, Math.max(0, self.lastIndexOf('/'))), jar);
                }
                descriptorTypes(descriptor);
                String method = name;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        if (where.contains(owner + "." + name)) {
                            add("Call sites of " + owner.replace('/', '.') + "." + name, self + "." + method, jar);
                        }
                        member(owner, name, descriptor, true, jar);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        member(owner, name, descriptor, false, jar);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        type(type);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... bsmArgs) {
                        handle(bsm);
                        for (Object arg : bsmArgs) {
                            if (arg instanceof Handle h) {
                                handle(h);
                            }
                        }
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type t && t.getSort() == Type.OBJECT) {
                            type(t.getInternalName());
                        } else if (value instanceof Handle h) {
                            handle(h);
                        } else if (value instanceof String s) {
                            string(s);
                        }
                    }

                    private void handle(Handle h) {
                        member(h.getOwner(), h.getName(), h.getDesc(), h.getTag() >= Opcodes.H_INVOKEVIRTUAL, jar);
                    }
                };
            }

            private void descriptorTypes(String descriptor) {
                Type type = Type.getType(descriptor);
                if (type.getSort() == Type.METHOD) {
                    for (Type arg : type.getArgumentTypes()) {
                        typeOf(arg);
                    }
                    typeOf(type.getReturnType());
                } else {
                    typeOf(type);
                }
            }

            private void typeOf(Type type) {
                if (type.getSort() == Type.ARRAY) {
                    type = type.getElementType();
                }
                if (type.getSort() == Type.OBJECT) {
                    type(type.getInternalName());
                }
            }

            private void type(String internalName) {
                if (internalName.startsWith("[")) {
                    typeOf(Type.getType(internalName));
                    return;
                }
                if (isJdk(internalName)) {
                    checkClass(internalName, jar, "");
                }
            }

            private void string(String s) {
                if (REFLECTION_HINTS.contains(s)) {
                    add("Reflection hints (string constants)", '"' + s + '"', jar);
                }
                if (JDK_CLASS_STRING.matcher(s).matches()) {
                    String internal = s.replace('.', '/');
                    if (jdk21.hasClass(internal) || jdk25.hasClass(internal)) {
                        checkClass(internal, jar, " (named in a string)");
                    }
                }
            }
        }, ClassReader.SKIP_FRAMES);
    }

    private void checkClass(String name, String jar, String how) {
        boolean in21 = jdk21.hasClass(name);
        boolean in25 = jdk25.hasClass(name);
        if (in21 && !in25) {
            add("Removed in Java 22-25", name + how, jar);
        } else if (!in21 && !in25 && isPublicJdk(name)) {
            add("Missing in both JDKs (guarded or for other Java versions)", name + how, jar);
        } else if (in25 && jdk25.classForRemoval(name)) {
            add(jdk21.hasClass(name) && jdk21.classForRemoval(name)
                    ? "Deprecated for removal (already in Java 21)"
                    : "Deprecated for removal (new since Java 21)", name + how, jar);
        }
        if (name.equals("sun/misc/Unsafe") && how.isEmpty()) {
            add("sun.misc.Unsafe users", "uses the type", jar);
        }
    }

    private void member(String owner, String name, String descriptor, boolean method, String jar) {
        if (!isJdk(owner) || owner.startsWith("[")) {
            return;
        }
        checkClass(owner, jar, "");
        String changed = CHANGED.get(owner + "." + name);
        if (changed != null) {
            add("Behaviour changed", owner.replace('/', '.') + "." + name + ": " + changed, jar);
        }
        if (owner.equals("sun/misc/Unsafe")) {
            add("sun.misc.Unsafe users", name, jar);
        }
        Jdk.Member in21 = jdk21.resolve(owner, name, descriptor, method);
        Jdk.Member in25 = jdk25.resolve(owner, name, descriptor, method);
        String what = owner.replace('/', '.') + "." + name + (method ? descriptor : " : " + descriptor);
        if (in21 != null && in25 == null) {
            add("Removed in Java 22-25", what, jar);
        } else if (in21 == null && in25 == null && jdk25.hasClass(owner)) {
            add("Missing in both JDKs (guarded or for other Java versions)", what, jar);
        } else if (in25 != null && in25.forRemoval()) {
            add(in21 != null && in21.forRemoval()
                    ? "Deprecated for removal (already in Java 21)"
                    : "Deprecated for removal (new since Java 21)", what, jar);
        }
    }

    private static boolean isJdk(String internalName) {
        return internalName.startsWith("java/") || internalName.startsWith("javax/") || internalName.startsWith("jdk/")
                || internalName.startsWith("sun/") || internalName.startsWith("com/sun/");
    }

    private static boolean isPublicJdk(String internalName) {
        return internalName.startsWith("java/") || internalName.startsWith("javax/");
    }

    private void add(String category, String finding, String jar) {
        findings.computeIfAbsent(category, k -> new TreeMap<>()).computeIfAbsent(finding, k -> new TreeSet<>()).add(jar);
    }

    private void report(PrintStream out) {
        out.println("Scanned " + classCounts.values().stream().mapToInt(Integer::intValue).sum() + " classes in "
                + classCounts.size() + " jars (nested jars counted separately).");
        out.println("Class file versions: " + classVersions);
        for (var category : findings.entrySet()) {
            out.println();
            out.println("== " + category.getKey() + " (" + category.getValue().size() + ")");
            for (var finding : category.getValue().entrySet()) {
                out.println("  " + finding.getKey());
                out.println("      in " + String.join(", ", finding.getValue()));
            }
        }
    }

    /** One JDK's class library, read from its {@code lib/modules} image. */
    static final class Jdk {
        private final FileSystem jrt;
        /** Package -> the modules the image lists for it (java.awt lists java.datatransfer too). */
        private final Map<String, List<Path>> packages = new HashMap<>();
        private final Map<String, Model> models = new HashMap<>();

        private Jdk(FileSystem jrt) throws IOException {
            this.jrt = jrt;
            try (Stream<Path> pkgs = Files.list(jrt.getPath("/packages"))) {
                for (Path pkg : pkgs.toList()) {
                    try (Stream<Path> modules = Files.list(pkg)) {
                        packages.put(pkg.getFileName().toString().replace('.', '/'),
                                modules.map(module -> jrt.getPath("/modules", module.getFileName().toString())).toList());
                    }
                }
            }
        }

        static Jdk open(Path home) throws IOException {
            return new Jdk(FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", home.toString())));
        }

        boolean hasClass(String name) {
            return model(name) != null;
        }

        boolean classForRemoval(String name) {
            Model model = model(name);
            return model != null && model.forRemoval;
        }

        /** The member as the JVM would find it: in the class, its superclasses, then its interfaces. */
        Member resolve(String owner, String name, String descriptor, boolean method) {
            Model model = model(owner);
            if (model == null) {
                return null;
            }
            String key = name + (method ? descriptor : "");
            Member found = model.members.get(key);
            if (found != null) {
                return found;
            }
            // MethodHandle.invokeExact, VarHandle.compareAndSet and friends accept any descriptor.
            if (method && model.polymorphic.contains(name)) {
                return model.members.values().stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
            }
            if (model.superName != null) {
                found = resolve(model.superName, name, descriptor, method);
                if (found != null) {
                    return found;
                }
            }
            for (String itf : model.interfaces) {
                found = resolve(itf, name, descriptor, method);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        private Model model(String name) {
            if (models.containsKey(name)) {
                return models.get(name);
            }
            Model model = null;
            int slash = name.lastIndexOf('/');
            for (Path module : slash > 0 ? packages.getOrDefault(name.substring(0, slash), List.of()) : List.<Path>of()) {
                Path file = module.resolve(name + ".class");
                if (Files.exists(file)) {
                    try {
                        model = Model.read(Files.readAllBytes(file));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    break;
                }
            }
            models.put(name, model);
            return model;
        }

        record Member(String name, boolean forRemoval) {
        }

        static final class Model {
            String superName;
            String[] interfaces = new String[0];
            boolean forRemoval;
            final Map<String, Member> members = new HashMap<>();
            final Set<String> polymorphic = new TreeSet<>();

            static Model read(byte[] bytes) {
                Model model = new Model();
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                        model.superName = superName;
                        model.interfaces = interfaces == null ? new String[0] : interfaces;
                        model.polymorphic.clear();
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        return forRemoval(descriptor, () -> model.forRemoval = true);
                    }

                    @Override
                    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                        boolean[] removal = {false};
                        model.members.put(name, new Member(name, false));
                        return new FieldVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                                return forRemoval(desc, () -> removal[0] = true);
                            }

                            @Override
                            public void visitEnd() {
                                model.members.put(name, new Member(name, removal[0]));
                            }
                        };
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        if ((access & Opcodes.ACC_NATIVE) != 0 && (access & Opcodes.ACC_VARARGS) != 0
                                && descriptor.startsWith("([Ljava/lang/Object;)")) { // MethodHandle.invoke, VarHandle.set, ...
                            model.polymorphic.add(name);
                        }
                        boolean[] removal = {false};
                        model.members.put(name + descriptor, new Member(name, false));
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                                return forRemoval(desc, () -> removal[0] = true);
                            }

                            @Override
                            public void visitEnd() {
                                model.members.put(name + descriptor, new Member(name, removal[0]));
                            }
                        };
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                return model;
            }

            private static AnnotationVisitor forRemoval(String descriptor, Runnable onForRemoval) {
                if (!descriptor.equals("Ljava/lang/Deprecated;")) {
                    return null;
                }
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if (name.equals("forRemoval") && Boolean.TRUE.equals(value)) {
                            onForRemoval.run();
                        }
                    }
                };
            }
        }
    }
}
