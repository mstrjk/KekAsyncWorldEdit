import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class LinkCheck implements AutoCloseable {

    private static final int REF_GET_STATIC = 2;
    private static final int REF_PUT_STATIC = 4;
    private static final int REF_INVOKE_STATIC = 6;
    private static final int REF_INVOKE_INTERFACE = 9;

    private record Located(JarFile jar, JarEntry entry) {
    }

    private final List<JarFile> jars = new ArrayList<>();
    private final Set<JarFile> serverJars = new HashSet<>();
    private final Map<String, String> conflicts = new HashMap<>();
    private final Map<String, Located> index = new HashMap<>();
    private final Map<String, Optional<ClassModel>> cache = new HashMap<>();
    private final Map<String, Boolean> resolved = new HashMap<>();
    private final Set<String> problems = new TreeSet<>();
    private final Set<String> warnings = new TreeSet<>();
    private final FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
    private final Map<String, List<Path>> jdkPackages = new HashMap<>();

    public static void main(String[] args) throws IOException {
        Path plugin = Path.of(args[0]);
        List<Path> classpath = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            classpath.addAll(ordered(Path.of(args[i])));
        }
        try (LinkCheck check = new LinkCheck(classpath)) {
            check.check(plugin);
            check.warnings.forEach(w -> System.out.println("warn: " + w));
            check.problems.forEach(System.out::println);
            System.exit(check.problems.isEmpty() ? 0 : 1);
        }
    }

    private static List<Path> ordered(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of(root);
        }
        List<Path> out = new ArrayList<>();
        try (Stream<Path> top = Files.list(root)) {
            top.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(out::add);
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".jar") && !p.getParent().equals(root)).sorted().forEach(out::add);
        }
        return out;
    }

    private LinkCheck(List<Path> classpath) throws IOException {
        for (Path path : classpath) {
            JarFile jar = new JarFile(path.toFile());
            jars.add(jar);
            if (!path.toString().contains("libraries")) {
                serverJars.add(jar);
            }
            for (JarEntry entry : jar.stream().toList()) {
                String name = entry.getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/")) {
                    continue;
                }
                String internal = name.substring(0, name.length() - 6);
                Located existing = index.putIfAbsent(internal, new Located(jar, entry));
                if (existing != null && existing.entry().getCrc() != entry.getCrc() && !serverJars.contains(existing.jar())) {
                    conflicts.put(internal, existing.jar().getName() + " and " + jar.getName());
                }
            }
        }
        Path packages = jrt.getPath("/packages");
        try (Stream<Path> list = Files.list(packages)) {
            for (Path pkg : list.toList()) {
                try (Stream<Path> modules = Files.list(pkg)) {
                    modules.forEach(module -> jdkPackages.computeIfAbsent(pkg.getFileName().toString().replace('.', '/'), key -> new ArrayList<>())
                            .add(jrt.getPath("/modules", module.getFileName().toString())));
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        for (JarFile jar : jars) {
            jar.close();
        }
    }

    private void check(Path plugin) throws IOException {
        Map<String, ClassModel> own = new HashMap<>();
        try (JarFile jar = new JarFile(plugin.toFile())) {
            for (JarEntry entry : jar.stream().toList()) {
                if (entry.getName().endsWith(".class")) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        ClassModel model = ClassFile.of().parse(in.readAllBytes());
                        own.put(model.thisClass().asInternalName(), model);
                    }
                }
            }
        }
        for (ClassModel model : own.values()) {
            String from = model.thisClass().asInternalName();
            for (PoolEntry entry : model.constantPool()) {
                if (entry instanceof ClassEntry ce) {
                    requireClass(from, ce.asInternalName(), own);
                } else if (entry instanceof MethodHandleEntry handle) {
                    MemberRefEntry ref = handle.reference();
                    int kind = handle.kind();
                    boolean isStatic = kind == REF_GET_STATIC || kind == REF_PUT_STATIC || kind == REF_INVOKE_STATIC;
                    boolean field = kind <= REF_PUT_STATIC;
                    member(from, ref.owner().asInternalName(), ref.nameAndType().name().stringValue(),
                            ref.nameAndType().type().stringValue(), field, isStatic, kind == REF_INVOKE_INTERFACE, own);
                }
            }
            for (MethodModel method : model.methods()) {
                requireDescriptor(from, method.methodType().stringValue(), false, own);
                Optional<CodeModel> code = method.code();
                if (code.isEmpty()) {
                    continue;
                }
                for (CodeElement element : code.get()) {
                    if (element instanceof FieldInstruction fi) {
                        boolean isStatic = fi.opcode() == Opcode.GETSTATIC || fi.opcode() == Opcode.PUTSTATIC;
                        member(from, fi.owner().asInternalName(), fi.name().stringValue(), fi.type().stringValue(), true, isStatic, false, own);
                    } else if (element instanceof InvokeInstruction ii) {
                        member(from, ii.owner().asInternalName(), ii.name().stringValue(), ii.type().stringValue(), false,
                                ii.opcode() == Opcode.INVOKESTATIC, ii.isInterface(), own);
                    }
                }
            }
            for (FieldModel field : model.fields()) {
                requireDescriptor(from, field.fieldType().stringValue(), true, own);
            }
        }
    }

    private static String element(String internal) {
        String name = internal;
        while (name.startsWith("[")) {
            name = name.substring(1);
        }
        if (name.startsWith("L") && name.endsWith(";")) {
            return name.substring(1, name.length() - 1);
        }
        return name.length() == 1 ? null : name;
    }

    private void requireClass(String from, String internal, Map<String, ClassModel> own) {
        String name = element(internal);
        if (name != null && !own.containsKey(name) && find(name) == null) {
            problems.add(from + " -> missing class " + name);
        }
    }

    private void requireDescriptor(String from, String descriptor, boolean field, Map<String, ClassModel> own) {
        List<ClassDesc> types = new ArrayList<>();
        if (field) {
            types.add(ClassDesc.ofDescriptor(descriptor));
        } else {
            MethodTypeDesc method = MethodTypeDesc.ofDescriptor(descriptor);
            types.add(method.returnType());
            types.addAll(method.parameterList());
        }
        for (ClassDesc type : types) {
            ClassDesc base = type;
            while (base.isArray()) {
                base = base.componentType();
            }
            if (base.isClassOrInterface()) {
                String d = base.descriptorString();
                requireClass(from, d.substring(1, d.length() - 1), own);
            }
        }
    }

    private void member(String from, String ownerInternal, String name, String type, boolean field, boolean isStatic, boolean interfaceCall, Map<String, ClassModel> own) {
        String owner = element(ownerInternal);
        if (owner == null) {
            return;
        }
        requireDescriptor(from, type, field, own);
        if (own.containsKey(owner)) {
            return;
        }
        String key = owner + "." + name + type + (field ? "#f" : "#m") + (isStatic ? "s" : "i") + (interfaceCall ? "I" : "");
        Boolean ok = resolved.get(key);
        if (ok == null) {
            ok = field ? resolveField(owner, name, type, isStatic, true) : resolveMethod(owner, name, type, isStatic, interfaceCall);
            resolved.put(key, ok);
        }
        if (!ok) {
            problems.add(from + " -> missing " + (isStatic ? "static " : "instance ") + (field ? "field " : "method ") + owner + "." + name + type);
        }
    }

    private boolean resolveField(String owner, String name, String type, boolean isStatic, boolean declaringClass) {
        ClassModel model = find(owner);
        if (model == null) {
            return false;
        }
        for (FieldModel f : model.fields()) {
            if (f.fieldName().equalsString(name) && f.fieldType().equalsString(type)) {
                boolean fieldStatic = f.flags().has(AccessFlag.STATIC);
                boolean visible = declaringClass || !f.flags().has(AccessFlag.PRIVATE);
                return fieldStatic == isStatic && visible;
            }
        }
        for (ClassEntry iface : model.interfaces()) {
            if (resolveField(iface.asInternalName(), name, type, isStatic, false)) {
                return true;
            }
        }
        return model.superclass().map(parent -> resolveField(parent.asInternalName(), name, type, isStatic, false)).orElse(false);
    }

    private boolean resolveMethod(String owner, String name, String type, boolean isStatic, boolean interfaceCall) {
        String current = owner;
        boolean declaring = true;
        while (current != null) {
            ClassModel model = find(current);
            if (model == null) {
                return false;
            }
            Optional<Boolean> hit = matchMethod(model, name, type, isStatic, declaring);
            if (hit.isPresent()) {
                return hit.get();
            }
            declaring = false;
            current = model.superclass().map(ClassEntry::asInternalName).orElse(null);
        }
        if (searchInterfaces(owner, name, type, isStatic, new TreeSet<>())) {
            return true;
        }
        if (interfaceCall) {
            ClassModel object = find("java/lang/Object");
            return object != null && matchMethod(object, name, type, isStatic, false).orElse(false);
        }
        return false;
    }

    private Optional<Boolean> matchMethod(ClassModel model, String name, String type, boolean isStatic, boolean declaring) {
        String className = model.thisClass().asInternalName();
        boolean polymorphicOwner = className.equals("java/lang/invoke/MethodHandle") || className.equals("java/lang/invoke/VarHandle");
        for (MethodModel m : model.methods()) {
            if (!m.methodName().equalsString(name)) {
                continue;
            }
            boolean signaturePolymorphic = polymorphicOwner && m.flags().has(AccessFlag.NATIVE) && m.flags().has(AccessFlag.VARARGS);
            if (signaturePolymorphic || m.methodType().equalsString(type)) {
                boolean methodStatic = m.flags().has(AccessFlag.STATIC);
                boolean visible = declaring || !m.flags().has(AccessFlag.PRIVATE);
                return Optional.of(methodStatic == isStatic && visible);
            }
        }
        return Optional.empty();
    }

    private boolean searchInterfaces(String owner, String name, String type, boolean isStatic, Set<String> seen) {
        ClassModel model = find(owner);
        if (model == null) {
            return false;
        }
        List<String> parents = new ArrayList<>();
        model.interfaces().forEach(i -> parents.add(i.asInternalName()));
        model.superclass().ifPresent(s -> parents.add(s.asInternalName()));
        for (String parent : parents) {
            if (!seen.add(parent)) {
                continue;
            }
            ClassModel parentModel = find(parent);
            if (parentModel == null) {
                continue;
            }
            if (parentModel.flags().has(AccessFlag.INTERFACE)) {
                Optional<Boolean> hit = matchMethod(parentModel, name, type, isStatic, false);
                if (hit.isPresent() && hit.get()) {
                    return true;
                }
            }
            if (searchInterfaces(parent, name, type, isStatic, seen)) {
                return true;
            }
        }
        return false;
    }

    private ClassModel find(String internal) {
        return cache.computeIfAbsent(internal, this::load).orElse(null);
    }

    private Optional<ClassModel> load(String internal) {
        try {
            Located located = index.get(internal);
            String conflict = conflicts.get(internal);
            if (conflict != null) {
                warnings.add("library conflict for " + internal + " between " + conflict + " (first wins)");
            }
            if (located != null) {
                try (InputStream in = located.jar().getInputStream(located.entry())) {
                    return Optional.of(ClassFile.of().parse(in.readAllBytes()));
                }
            }
            int slash = internal.lastIndexOf('/');
            List<Path> modules = slash < 0 ? List.of() : jdkPackages.getOrDefault(internal.substring(0, slash), List.of());
            for (Path module : modules) {
                Path candidate = module.resolve(internal + ".class");
                if (Files.exists(candidate)) {
                    return Optional.of(ClassFile.of().parse(Files.readAllBytes(candidate)));
                }
            }
            return Optional.empty();
        } catch (IOException | IllegalArgumentException e) {
            problems.add("unreadable class " + internal + ": " + e.getMessage());
            return Optional.empty();
        }
    }
}
