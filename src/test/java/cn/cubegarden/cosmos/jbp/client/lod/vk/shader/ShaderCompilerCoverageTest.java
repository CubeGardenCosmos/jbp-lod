package cn.cubegarden.cosmos.jbp.client.lod.vk.shader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderCompilerCoverageTest {
    private final ShaderCompiler compiler = new ShaderCompiler();

    @Test
    void compilesAndReflectsEveryInScopeLodShaderVariant() throws IOException {
        List<ShaderCase> coverage = coverage();
        List<ShaderCompiler.CompiledShader> compiled = new ArrayList<>(coverage.size());
        for (ShaderCase shaderCase : coverage) {
            ShaderCompiler.CompiledShader shader;
            if (shaderCase.sourceSuffix().isEmpty()) {
                shader = compiler.compile(shaderCase.path(), shaderCase.defines());
            } else {
                String resource = "/assets/jbp-lod/shaders/" + shaderCase.path();
                try (InputStream input = ShaderCompilerCoverageTest.class.getResourceAsStream(resource)) {
                    if (input == null) throw new IOException("Missing shader resource " + resource);
                    String source = new String(input.readAllBytes(), StandardCharsets.UTF_8) + shaderCase.sourceSuffix();
                    shader = compiler.compile(shaderCase.path(), source, shaderCase.stage(), shaderCase.defines());
                }
            }
            assertTrue(shader.spirv().length >= 20, shaderCase.path() + " emitted an empty SPIR-V module");
            assertEquals(shader.spirv().length, shader.trace().spirvBytes(), shaderCase.path());
            assertTrue(shader.trace().spirvSha256().matches("[0-9a-f]{64}"), shaderCase.path());
            assertTrue(shader.trace().sourceSha256().containsKey(shaderCase.path()), shaderCase.path());
            assertNotNull(shader.reflection(), shaderCase.path());
            if (!shaderCase.path().equals("hiz/blit.vert") && !shaderCase.path().equals("lod/depth_convert.vert")
                    && !shaderCase.path().equals("model/bake.vert")) {
                assertFalse(shader.reflection().descriptors().isEmpty(),
                        shaderCase.path() + " should reflect Vulkan descriptors");
            }
            assertTrue(shader.reflection().descriptors().stream()
                            .allMatch(descriptor -> descriptor.set() == 0 && descriptor.binding() >= 0),
                    shaderCase.path() + " must use explicit Vulkan set-0 descriptor bindings");
            if (shaderCase.path().endsWith("/quads3.vert") || shaderCase.path().endsWith("/quads.frag")) {
                assertTrue(shader.reflection().descriptors().stream()
                                .anyMatch(descriptor -> descriptor.name().equals("LodFogBlock")
                                        && descriptor.binding() == 8),
                        shaderCase.path() + " must reflect the LodFogBlock descriptor at binding 8");
            }

            compiled.add(shader);
        }
        writeManifest(compiled);
    }

    private static List<ShaderCase> coverage() {
        List<ShaderCase> cases = new ArrayList<>();
        Map<String, String> traversalDefines = Map.ofEntries(
                Map.entry("SCENE_UNIFORM_BINDING", "0"), Map.entry("NODE_DATA_BINDING", "1"),
                Map.entry("NODE_QUEUE_META_BINDING", "2"), Map.entry("NODE_QUEUE_SOURCE_BINDING", "3"),
                Map.entry("NODE_QUEUE_SINK_BINDING", "4"), Map.entry("MAX_ITERATIONS", "8"),
                Map.entry("LOCAL_SIZE_BITS", "8"), Map.entry("HIZ_BINDING", "5"),
                Map.entry("REQUEST_QUEUE_BINDING", "6"), Map.entry("RENDER_QUEUE_BINDING", "7"),
                Map.entry("RENDER_TRACKER_BINDING", "8"), Map.entry("MAX_REQUEST_QUEUE_SIZE", "65536"));
        cases.add(new ShaderCase("lod/hierarchical/traversal_dev.comp", traversalDefines));
        cases.add(new ShaderCase("lod/hierarchical/traversal_dev.comp",
                withDefines(traversalDefines, Map.of("HAS_STATISTICS", "1", "STATISTICS_BUFFER_BINDING", "9"))));
        cases.add(new ShaderCase("lod/hierarchical/traversal_dev.comp",
                withDefines(traversalDefines, Map.of("USE_REVERSE_Z", "1", "USE_ZERO_ONE_DEPTH", "1"))));
        cases.add(new ShaderCase("lod/hierarchical/traversal_dev.comp",
                withDefines(traversalDefines, Map.of("USE_REVERSE_Z", "1"))));
        Map<String, String> reverseZ = Map.of("USE_REVERSE_Z", "1", "USE_ZERO_ONE_DEPTH", "1");
        cases.add(new ShaderCase("hiz/hiz.comp", Map.of()));
        cases.add(new ShaderCase("hiz/hiz.comp", reverseZ));
        cases.add(new ShaderCase("hiz/hiz.comp", Map.of("USE_SUBGROUP", "1")));
        cases.add(new ShaderCase("hiz/hiz.comp", withDefines(Map.of("USE_SUBGROUP", "1"), reverseZ)));
        cases.add(new ShaderCase("hiz/blit.vert", Map.of()));
        cases.add(new ShaderCase("hiz/blit.vert", reverseZ));
        cases.add(new ShaderCase("hiz/blit.frag", Map.of()));
        cases.add(new ShaderCase("hiz/blit.frag", reverseZ));
        cases.add(new ShaderCase("hiz/blit.frag", Map.of("OUTPUT_COLOUR", "1")));
        cases.add(new ShaderCase("lod/depth_convert.vert", Map.of()));
        cases.add(new ShaderCase("lod/depth_convert.frag", Map.of()));
        cases.add(new ShaderCase("lod/vanilla_depth_snapshot.frag", Map.of()));
        cases.add(new ShaderCase("lod/offscreen_composite.frag", Map.of()));
        cases.add(new ShaderCase("hiz/blit.frag",
                withDefines(Map.of("OUTPUT_COLOUR", "1"), reverseZ)));

        cases.add(new ShaderCase("lod/hierarchical/cleaner/batch_visibility_set.comp", Map.of(
                "VISIBILITY_BUFFER_BINDING", "0", "LIST_BUFFER_BINDING", "1")));
        cases.add(new ShaderCase("lod/hierarchical/cleaner/result_transformer.comp", Map.of(
                "OUTPUT_SIZE", "128", "MIN_ID_BUFFER_BINDING", "0", "NODE_BUFFER_BINDING", "1",
                "OUTPUT_BUFFER_BINDING", "2", "VISIBILITY_BUFFER_BINDING", "3")));
        cases.add(new ShaderCase("lod/hierarchical/cleaner/sort_visibility.comp", Map.of(
                "WORK_SIZE", "128", "OUTPUT_SIZE", "128", "ELEMS_PER_THREAD", "1",
                "NODE_DATA_BINDING", "0", "VISIBILITY_BUFFER_BINDING", "1", "OUTPUT_BUFFER_BINDING", "2")));

        cases.add(new ShaderCase("lod/gl46/prep.comp", Map.of()));
        Map<String, String> commandDefines = Map.of(
                "TRANSLUCENT_DISTANCE_BUFFER_BINDING", "7", "TEMPORAL_OFFSET", "500000",
                "TRANSLUCENT_WRITE_BASE", "1024", "OPAQUE_CAPACITY", "400000",
                "TRANSLUCENT_CAPACITY", "100000", "TEMPORAL_CAPACITY", "100000");
        cases.add(new ShaderCase("lod/gl46/cmdgen.comp", commandDefines));
        cases.add(new ShaderCase("lod/gl46/cmdgen.comp",
                withDefines(commandDefines, Map.of("HAS_STATISTICS", "1", "STATISTICS_BUFFER_BINDING", "8"))));
        cases.add(new ShaderCase("lod/gl46/buildtranslucents.comp", Map.of(
                "TRANSLUCENT_DISTANCE_BUFFER_BINDING", "7", "TRANSLUCENT_WRITE_BASE", "1024",
                "TRANSLUCENT_OFFSET", "400000", "TRANSLUCENT_CAPACITY", "100000")));

        cases.add(new ShaderCase("util/prefixsum/inital3.comp", Map.of("IO_BUFFER", "0")));
        cases.add(new ShaderCase("util/prefixsum/inital3.comp", Map.of("IO_BUFFER", "0", "USE_SUBGROUP", "1")));
        cases.add(new ShaderCase("util/prefixsum/inital3.comp",
                Map.of("IO_BUFFER", "0", "USE_SUBGROUP", "1", "IS_INTEL", "1")));
        cases.add(new ShaderCase("util/prefixsum/inital3.comp",
                Map.of("IO_BUFFER", "0", "USE_SUBGROUP", "1", "IS_WINDOWS", "1")));
        cases.add(new ShaderCase("util/prefixsum/simple.comp", Map.of("IO_BUFFER", "0")));
        cases.add(new ShaderCase("util/memcpy.comp", Map.of(
                "INPUT_HEADER_BUFFER_BINDING", "0", "INPUT_DATA_BUFFER_BINDING", "1", "OUTPUT_BUFFER_BINDING", "2")));
        cases.add(new ShaderCase("util/scatter.comp", Map.of(
                "INPUT_BUFFER_BINDING", "0", "OUTPUT_BUFFER1_BINDING", "1", "OUTPUT_BUFFER2_BINDING", "2")));

        cases.add(new ShaderCase("lod/gl46/quads3.vert", Map.of()));
        cases.add(new ShaderCase("lod/gl46/quads3.vert", Map.of("USE_SHADER_INT64", "1")));
        cases.add(new ShaderCase("lod/gl46/quads3.vert", Map.of("TAA_PATCH", "1"),
                "\nvec2 taaShift() { return vec2(float(gl_VertexIndex & 1), 0.0) * 0.125; }\n"));

        Map<String, String> fragmentDefines = Map.of("BLOCK_MODEL_TEXTURE_BINDING", "2");
        cases.add(new ShaderCase("lod/gl46/quads.frag", fragmentDefines));
        cases.add(new ShaderCase("lod/gl46/quads.frag",
                withDefines(fragmentDefines, Map.of("TRANSLUCENT", "1"))));
        Map<String, String> patchedDefines = withDefines(fragmentDefines, Map.of("PATCHED_SHADER", "1"));
        String fragmentCallback = "\nlayout(location=0) out vec4 injectedColour;\n"
                + "void voxy_emitFragment(VoxyFragmentParameters parameters) { injectedColour = parameters.sampledColour; }\n";
        cases.add(new ShaderCase("lod/gl46/quads.frag", patchedDefines, fragmentCallback));
        cases.add(new ShaderCase("lod/gl46/quads.frag",
                withDefines(patchedDefines, Map.of("PATCHED_SHADER_ALLOW_DERIVATIVES", "1")), fragmentCallback));
        cases.add(new ShaderCase("lod/gl46/quads.frag",
                withDefines(patchedDefines, Map.of("TRANSLUCENT", "1")), fragmentCallback));
        cases.add(new ShaderCase("lod/gl46/quads.frag",
                withDefines(patchedDefines, Map.of("TRANSLUCENT", "1", "PATCHED_SHADER_ALLOW_DERIVATIVES", "1")),
                fragmentCallback));

        cases.add(new ShaderCase("lod/gl46/cull/raster.vert", Map.of()));
        cases.add(new ShaderCase("lod/gl46/cull/raster.vert", Map.of("TAA", "1"),
                "\nvec2 getTAA() { return vec2(float(gl_VertexIndex & 1), 0.0) * 0.125; }\n"));
        cases.add(new ShaderCase("lod/gl46/cull/raster.frag", Map.of()));
        cases.add(new ShaderCase("lod/gl46/cull/raster.vert", Map.of("USE_REVERSE_Z", "1")));
        cases.add(new ShaderCase("lod/gl46/cull/raster.frag", Map.of("USE_REVERSE_Z", "1")));
        cases.add(new ShaderCase("model/bake.vert", Map.of()));
        cases.add(new ShaderCase("model/bake.frag", Map.of()));
        return List.copyOf(cases);
    }

    private static Map<String, String> withDefines(Map<String, String> base, Map<String, String> additions) {
        Map<String, String> combined = new TreeMap<>(base);
        combined.putAll(additions);
        return Map.copyOf(combined);
    }


    private static void writeManifest(List<ShaderCompiler.CompiledShader> compiled) throws IOException {
        StringBuilder json = new StringBuilder("{\n  \"entries\": [\n");
        for (int i = 0; i < compiled.size(); i++) {
            ShaderCompiler.CompiledShader shader = compiled.get(i);
            ShaderCompiler.CompilationTrace trace = shader.trace();
            if (i != 0) json.append(",\n");
            json.append("    {\n")
                    .append("      \"source\": ").append(quote(trace.sourcePath())).append(",\n")
                    .append("      \"stage\": ").append(quote(trace.stage().name().toLowerCase())).append(",\n")
                    .append("      \"defines\": ").append(stringMap(trace.defines())).append(",\n")
                    .append("      \"sourceSha256\": ").append(stringMap(trace.sourceSha256())).append(",\n")
                    .append("      \"spirvSha256\": ").append(quote(trace.spirvSha256())).append(",\n")
                    .append("      \"spirvBytes\": ").append(trace.spirvBytes()).append(",\n")
                    .append("      \"descriptors\": [");
            for (int descriptorIndex = 0; descriptorIndex < shader.reflection().descriptors().size(); descriptorIndex++) {
                ShaderCompiler.Descriptor descriptor = shader.reflection().descriptors().get(descriptorIndex);
                if (descriptorIndex != 0) json.append(", ");
                json.append("{\"name\":").append(quote(descriptor.name()))
                        .append(",\"kind\":").append(quote(descriptor.kind()))
                        .append(",\"set\":").append(descriptor.set())
                        .append(",\"binding\":").append(descriptor.binding()).append('}');
            }
            json.append("],\n      \"stageInputs\": ")
                    .append(stageVariables(shader.reflection().inputs())).append(",\n")
                    .append("      \"stageOutputs\": ")
                    .append(stageVariables(shader.reflection().outputs())).append(",\n")
                    .append("      \"pushConstantSize\": ")
                    .append(shader.reflection().pushConstantSize()).append("\n    }");
        }
        json.append("\n  ]\n}\n");
        Path manifest = Path.of("build", "reports", "jbp-lod-spv-manifest.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, json);
    }

    private static String stageVariables(List<ShaderCompiler.StageVariable> variables) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < variables.size(); i++) {
            ShaderCompiler.StageVariable variable = variables.get(i);
            if (i != 0) json.append(", ");
            json.append("{\"name\":").append(quote(variable.name()))
                    .append(",\"location\":").append(variable.location()).append('}');
        }
        return json.append(']').toString();
    }

    private static String stringMap(Map<String, String> values) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
            if (!first) json.append(',');
            json.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
            first = false;
        }
        return json.append('}').toString();
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> result.append(character);
            }
        }
        return result.append('"').toString();
    }

    private record ShaderCase(String path, Map<String, String> defines, String sourceSuffix) {
        private ShaderCase(String path, Map<String, String> defines) {
            this(path, defines, "");
        }

        private ShaderCompiler.Stage stage() {
            if (path.endsWith(".vert")) return ShaderCompiler.Stage.VERTEX;
            if (path.endsWith(".frag")) return ShaderCompiler.Stage.FRAGMENT;
            throw new IllegalStateException("Injected source stage not supported for " + path);
        }
    }

}
