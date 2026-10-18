package cn.cubegarden.cosmos.jbp.client.lod.vk.shader;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;
import org.lwjgl.util.spvc.Spv;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runtime GLSL-to-SPIR-V compiler using the LWJGL modules supplied by Minecraft. */
public final class ShaderCompiler {
    private static final int SPIRV_MAGIC = 0x07230203;
    private static final Pattern IMPORT = Pattern.compile("^\\s*#import\\s+<([A-Za-z0-9_.-]+):([^>]+)>\\s*$");
    private static final String SHADER_RESOURCE_ROOT = "/assets/jbp-lod/shaders/";

    public CompiledShader compile(String resourcePath, Map<String, String> defines) {
        String path = normalizeShaderPath(resourcePath);
        Stage stage = Stage.fromPath(path);
        Map<String, String> dependencies = new LinkedHashMap<>();
        String source = loadAndExpand(path, dependencies, Set.of());
        return compile(path, source, stage, defines, dependencies);
    }

    /** Compiles a source string; imports are resolved from the mod's shader resources. */
    public CompiledShader compile(String sourceName, String source, Stage stage, Map<String, String> defines) {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(stage, "stage");
        Map<String, String> dependencies = new LinkedHashMap<>();
        String expanded = expand(sourceName, source, dependencies, Set.of(sourceName));
        return compile(sourceName, expanded, stage, defines, dependencies);
    }

    private CompiledShader compile(String sourceName, String expandedSource, Stage stage,
                                   Map<String, String> defines, Map<String, String> dependencies) {
        Objects.requireNonNull(defines, "defines");
        Map<String, String> stableDefines = new TreeMap<>();
        defines.forEach((key, value) -> {
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Invalid GLSL define name: " + key);
            }
            stableDefines.put(key, Objects.requireNonNull(value, "define value"));
        });

        String completeSource = "#version 460\n" + expandedSource;

        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new ShaderCompilationException(sourceName, "shaderc compiler initialization failed (LWJGL shaderc native unavailable)", null);
        }
        long options = 0L;
        try {
            options = Shaderc.shaderc_compile_options_initialize();
            if (options == 0L) {
                throw new ShaderCompilationException(sourceName, "shaderc compile-options initialization failed", null);
            }
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
                    Shaderc.shaderc_env_version_vulkan_1_3);
            Shaderc.shaderc_compile_options_set_target_spirv(options, Shaderc.shaderc_spirv_version_1_6);
            for (Map.Entry<String, String> define : stableDefines.entrySet()) {
                Shaderc.shaderc_compile_options_add_macro_definition(options, define.getKey(), define.getValue());
            }

            long result = Shaderc.shaderc_compile_into_spv(compiler, completeSource, stage.shadercKind,
                    sourceName, "main", options);
            if (result == 0L) {
                throw new ShaderCompilationException(sourceName, "shaderc returned a null compilation result", null);
            }
            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != Shaderc.shaderc_compilation_status_success) {
                    String diagnostics = Shaderc.shaderc_result_get_error_message(result);
                    throw new ShaderCompilationException(sourceName, diagnostics, null);
                }
                ByteBuffer compiledBytes = Shaderc.shaderc_result_get_bytes(result);
                byte[] spirv = new byte[compiledBytes.remaining()];
                compiledBytes.get(spirv);
                Reflection reflection = validateAndReflect(sourceName, spirv);
                Map<String, String> sourceHashes = new TreeMap<>();
                sourceHashes.put(sourceName, sha256(completeSource.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                dependencies.forEach((path, content) -> sourceHashes.put(path,
                        sha256(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                CompilationTrace trace = new CompilationTrace(sourceName, stage, Map.copyOf(stableDefines),
                        Map.copyOf(sourceHashes), sha256(spirv), spirv.length);
                return new CompiledShader(spirv, trace, reflection);
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            if (options != 0L) {
                Shaderc.shaderc_compile_options_release(options);
            }
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static Reflection validateAndReflect(String sourceName, byte[] spirv) {
        if (spirv.length < Integer.BYTES * 5 || spirv.length % Integer.BYTES != 0) {
            throw new ShaderCompilationException(sourceName, "SPIR-V output has an invalid byte length", null);
        }
        ByteBuffer wordsView = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        if (wordsView.getInt(0) != SPIRV_MAGIC) {
            throw new ShaderCompilationException(sourceName, "SPIR-V output has an invalid magic number", null);
        }
        validateSpirvStructure(sourceName, wordsView);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long context = 0L;
            try {
                PointerBuffer handle = stack.mallocPointer(1);
                if (Spvc.spvc_context_create(handle) != Spvc.SPVC_SUCCESS) {
                    throw new ShaderCompilationException(sourceName, "SPVC context creation failed", null);
                }
                context = handle.get(0);

                ByteBuffer directBytes = stack.malloc(spirv.length).order(ByteOrder.LITTLE_ENDIAN);
                directBytes.put(spirv).flip();
                IntBuffer words = directBytes.asIntBuffer();
                PointerBuffer parsedIrOut = stack.mallocPointer(1);
                int status = Spvc.spvc_context_parse_spirv(context, words, words.remaining(), parsedIrOut);
                if (status != Spvc.SPVC_SUCCESS) {
                    throw spvcFailure(sourceName, context, "SPIR-V parse/validation", status);
                }

                PointerBuffer compilerOut = stack.mallocPointer(1);
                status = Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_NONE,
                        parsedIrOut.get(0), Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, compilerOut);
                if (status != Spvc.SPVC_SUCCESS) {
                    throw spvcFailure(sourceName, context, "SPIR-V reflection compiler", status);
                }
                long compiler = compilerOut.get(0);
                PointerBuffer resourcesOut = stack.mallocPointer(1);
                status = Spvc.spvc_compiler_create_shader_resources(compiler, resourcesOut);
                if (status != Spvc.SPVC_SUCCESS) {
                    throw spvcFailure(sourceName, context, "SPIR-V resource reflection", status);
                }
                long resources = resourcesOut.get(0);

                List<Descriptor> descriptors = new ArrayList<>();
                reflectDescriptors(sourceName, stack, context, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER,
                        "uniform-buffer", descriptors);
                reflectDescriptors(sourceName, stack, context, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER,
                        "storage-buffer", descriptors);
                reflectDescriptors(sourceName, stack, context, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE,
                        "sampled-image", descriptors);
                reflectDescriptors(sourceName, stack, context, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE,
                        "separate-image", descriptors);
                reflectDescriptors(sourceName, stack, context, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS,
                        "sampler", descriptors);
                reflectDescriptors(sourceName, stack, context, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE,
                        "storage-image", descriptors);
                reflectDescriptors(sourceName, stack, context, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_SUBPASS_INPUT,
                        "input-attachment", descriptors);

                List<StageVariable> inputs = reflectStageVariables(sourceName, stack, context, compiler, resources,
                        Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT);
                List<StageVariable> outputs = reflectStageVariables(sourceName, stack, context, compiler, resources,
                        Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT);
                int pushConstantSize = reflectPushConstantSize(sourceName, stack, context, compiler, resources);
                return new Reflection(List.copyOf(descriptors), List.copyOf(inputs), List.copyOf(outputs), pushConstantSize);
            } finally {
                if (context != 0L) {
                    Spvc.spvc_context_destroy(context);
                }
            }
        } catch (ShaderCompilationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ShaderCompilationException(sourceName,
                    "SPIR-V validation/reflection failed: " + e.getMessage(), e);
        }
    }
    private static void validateSpirvStructure(String sourceName, ByteBuffer words) {
        int wordCount = words.remaining() / Integer.BYTES;
        // Header words: magic, version, generator, ID bound, reserved schema (zero).
        if (wordCount < 5 || words.getInt(3 * Integer.BYTES) == 0) {
            throw new ShaderCompilationException(sourceName, "SPIR-V header is truncated or has a zero ID bound", null);
        }
        int cursor = 5;
        boolean hasMemoryModel = false;
        boolean hasEntryPoint = false;
        while (cursor < wordCount) {
            int instruction = words.getInt(cursor * Integer.BYTES);
            int instructionWords = instruction >>> 16;
            int opcode = instruction & 0xffff;
            if (instructionWords == 0 || cursor + instructionWords > wordCount) {
                throw new ShaderCompilationException(sourceName,
                        "SPIR-V instruction at word " + cursor + " has an invalid word count", null);
            }
            hasMemoryModel |= opcode == 14;
            hasEntryPoint |= opcode == 15;
            cursor += instructionWords;
        }
        if (!hasMemoryModel || !hasEntryPoint) {
            throw new ShaderCompilationException(sourceName, "SPIR-V module is missing its memory model or entry point", null);
        }
    }


    private static void reflectDescriptors(String sourceName, MemoryStack stack, long context, long compiler, long resources,
                                           int resourceType, String kind, List<Descriptor> output) {
        PointerBuffer countOut = stack.mallocPointer(1);
        PointerBuffer listOut = stack.mallocPointer(1);
        int status = Spvc.spvc_resources_get_resource_list_for_type(resources, resourceType, listOut, countOut);
        if (status != Spvc.SPVC_SUCCESS) {
            throw spvcFailure(sourceName, context, "descriptor reflection", status);
        }
        int count = Math.toIntExact(countOut.get(0));
        if (count == 0) {
            return;
        }
        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(listOut.get(0), count);
 
        for (int i = 0; i < count; i++) {
            SpvcReflectedResource resource = list.get(i);
            int id = resource.id();
            boolean hasSet = Spvc.spvc_compiler_has_decoration(compiler, id, Spv.SpvDecorationDescriptorSet);
            boolean hasBinding = Spvc.spvc_compiler_has_decoration(compiler, id, Spv.SpvDecorationBinding);
            int set = hasSet ? Spvc.spvc_compiler_get_decoration(compiler, id, Spv.SpvDecorationDescriptorSet) : 0;
            int binding = hasBinding ? Spvc.spvc_compiler_get_decoration(compiler, id, Spv.SpvDecorationBinding) : -1;
            output.add(new Descriptor(resource.nameString(), kind, set, binding));
        }
    }

    private static List<StageVariable> reflectStageVariables(String sourceName, MemoryStack stack, long context, long compiler,
                                                              long resources, int resourceType) {
        PointerBuffer countOut = stack.mallocPointer(1);
        PointerBuffer listOut = stack.mallocPointer(1);
        int status = Spvc.spvc_resources_get_resource_list_for_type(resources, resourceType, listOut, countOut);
        if (status != Spvc.SPVC_SUCCESS) {
            throw spvcFailure(sourceName, context, "stage interface reflection", status);
        }
        int count = Math.toIntExact(countOut.get(0));
        if (count == 0) {
            return List.of();
        }
        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(listOut.get(0), count);
        List<StageVariable> variables = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            SpvcReflectedResource resource = list.get(i);
            int id = resource.id();
            boolean hasLocation = Spvc.spvc_compiler_has_decoration(compiler, id, Spv.SpvDecorationLocation);
            int location = hasLocation ? Spvc.spvc_compiler_get_decoration(compiler, id, Spv.SpvDecorationLocation) : -1;
            variables.add(new StageVariable(resource.nameString(), location));
        }
        return List.copyOf(variables);
    }

    private static int reflectPushConstantSize(String sourceName, MemoryStack stack, long context,
                                               long compiler, long resources) {
        PointerBuffer countOut = stack.mallocPointer(1);
        PointerBuffer listOut = stack.mallocPointer(1);
        int status = Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_PUSH_CONSTANT,
                listOut, countOut);
        if (status != Spvc.SPVC_SUCCESS) {
            throw spvcFailure(sourceName, context, "push-constant reflection", status);
        }
        int count = Math.toIntExact(countOut.get(0));
        if (count == 0) {
            return 0;
        }
        SpvcReflectedResource resource = SpvcReflectedResource.create(listOut.get(0));
        long type = Spvc.spvc_compiler_get_type_handle(compiler, resource.base_type_id());
        PointerBuffer sizeOut = stack.mallocPointer(1);
        status = Spvc.spvc_compiler_get_declared_struct_size(compiler, type, sizeOut);
        if (status != Spvc.SPVC_SUCCESS) {
            throw spvcFailure(sourceName, context, "push-constant size reflection", status);
        }
        return Math.toIntExact(sizeOut.get(0));
    }

    private static ShaderCompilationException spvcFailure(String sourceName, long context, String action, int status) {
        String detail = Spvc.spvc_context_get_last_error_string(context);
        return new ShaderCompilationException(sourceName,
                action + " failed with SPVC status " + status + (detail == null || detail.isBlank() ? "" : ": " + detail), null);
    }

    private static String loadAndExpand(String path, Map<String, String> dependencies, Set<String> ancestry) {
        String source = loadResource(path);
        return expand(path, source, dependencies, ancestry);
    }

    private static String expand(String sourceName, String source, Map<String, String> dependencies, Set<String> ancestry) {
        StringBuilder output = new StringBuilder(source.length());
        String[] lines = source.split("\\R", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#version")) {
                continue;
            }
            if (trimmed.startsWith("#import")) {
                Matcher match = IMPORT.matcher(line);
                if (!match.matches()) {
                    throw new IllegalArgumentException("Invalid shader import in " + sourceName + ": " + line);
                }
                String namespace = match.group(1);
                String importedPath = normalizeShaderPath(match.group(2));
                String importKey = namespace + ":" + importedPath;
                if (ancestry.contains(importKey) || ancestry.contains(importedPath)) {
                    throw new IllegalArgumentException("Cyclic shader import from " + sourceName + " to " + importKey);
                }
                String resourcePath = "/assets/" + namespace + "/shaders/" + importedPath;
                String importedSource = loadResourcePath(resourcePath);
                dependencies.put(resourcePath, importedSource);
                Set<String> nestedAncestry = new java.util.HashSet<>(ancestry);
                nestedAncestry.add(importKey);
                nestedAncestry.add(importedPath);
                output.append(expand(resourcePath, importedSource, dependencies, nestedAncestry));
                continue;
            }
            output.append(line).append('\n');
        }
        return output.toString();
    }

    private static String loadResource(String path) {
        return loadResourcePath(SHADER_RESOURCE_ROOT + path);
    }

    private static String loadResourcePath(String path) {
        try (InputStream input = ShaderCompiler.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalArgumentException("Shader resource not found: " + path);
            }
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read shader resource " + path, e);
        }
    }

    private static String normalizeShaderPath(String path) {
        Objects.requireNonNull(path, "resourcePath");
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/assets/jbp-lod/shaders/")) {
            normalized = normalized.substring("/assets/jbp-lod/shaders/".length());
        }
        if (normalized.startsWith("assets/jbp-lod/shaders/")) {
            normalized = normalized.substring("assets/jbp-lod/shaders/".length());
        }
        if (normalized.startsWith("/") || normalized.contains("..") || normalized.isBlank()) {
            throw new IllegalArgumentException("Invalid shader resource path: " + path);
        }
        return normalized;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is required by every Java runtime", e);
        }
    }

    public enum Stage {
        VERTEX(Shaderc.shaderc_vertex_shader),
        FRAGMENT(Shaderc.shaderc_fragment_shader),
        COMPUTE(Shaderc.shaderc_compute_shader);

        private final int shadercKind;

        Stage(int shadercKind) {
            this.shadercKind = shadercKind;
        }

        private static Stage fromPath(String path) {
            if (path.endsWith(".vert") || path.endsWith(".vsh")) return VERTEX;
            if (path.endsWith(".frag") || path.endsWith(".fsh")) return FRAGMENT;
            if (path.endsWith(".comp")) return COMPUTE;
            throw new IllegalArgumentException("Cannot infer Vulkan shader stage from resource path: " + path);
        }
    }

 

    public record Descriptor(String name, String kind, int set, int binding) {
    }

    public record StageVariable(String name, int location) {
    }

    public record Reflection(List<Descriptor> descriptors, List<StageVariable> inputs,
                             List<StageVariable> outputs, int pushConstantSize) {
    }

    /** Per-compilation source-to-artifact manifest, including recursively imported source hashes. */
    public record CompilationTrace(String sourcePath, Stage stage, Map<String, String> defines,
                                   Map<String, String> sourceSha256, String spirvSha256, int spirvBytes) {
    }

    public static final class CompiledShader {
        private final byte[] spirv;
        private final CompilationTrace trace;
        private final Reflection reflection;

        private CompiledShader(byte[] spirv, CompilationTrace trace, Reflection reflection) {
            this.spirv = spirv;
            this.trace = trace;
            this.reflection = reflection;
        }

        /** The returned buffer is compiler-owned immutable-by-contract; do not mutate it. */
        public byte[] spirv() {
            return spirv;
        }

        public CompilationTrace trace() {
            return trace;
        }

        public Reflection reflection() {
            return reflection;
        }
    }

    public static final class ShaderCompilationException extends RuntimeException {
        public ShaderCompilationException(String sourceName, String diagnostics, Throwable cause) {
            super("[JBP-LOD] Shader " + sourceName + " failed: " + diagnostics, cause);
        }
    }
}
