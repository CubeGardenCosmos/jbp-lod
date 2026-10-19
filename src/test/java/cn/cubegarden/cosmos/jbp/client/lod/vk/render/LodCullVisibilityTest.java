package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class LodCullVisibilityTest {
    @Test void temporalClassificationReadsSnapshotNotConcurrentFragmentOutput() throws IOException {
        String vertex = source("raster.vert");
        String fragment = source("raster.frag");
        assertTrue(vertex.contains("restrict readonly buffer PreviousVisibilityBuffer"));
        assertTrue(vertex.contains("previousVisibilityData[sid]&0x7fffffffu"));
        assertFalse(vertex.contains("previous = visibilityData[sid]"));
        assertTrue(fragment.contains("visibilityData[id] = value"));
    }

    @Test void compiledStagesUseDisjointVisibilityBindings() {
        ShaderCompiler compiler = new ShaderCompiler();
        for (Map<String, String> defines : java.util.List.of(Map.<String, String>of(),
                Map.of("USE_REVERSE_Z", "1"))) {
            var vertex = compiler.compile("lod/gl46/cull/raster.vert", defines);
            var fragment = compiler.compile("lod/gl46/cull/raster.frag", defines);
            Set<Integer> vertexBindings = vertex.reflection().descriptors().stream()
                    .map(ShaderCompiler.Descriptor::binding).collect(Collectors.toSet());
            Set<Integer> fragmentBindings = fragment.reflection().descriptors().stream()
                    .map(ShaderCompiler.Descriptor::binding).collect(Collectors.toSet());
            assertTrue(vertexBindings.contains(LodCullRasterPass.PREVIOUS_VISIBILITY_BINDING));
            // binding 2 may be listed by reflection because bindings.glsl declares it; the vertex source never reads it
            // (checked textually in temporalClassificationReadsSnapshotNotConcurrentFragmentOutput).
            assertEquals(Set.of(LodCullRasterPass.VISIBILITY_BINDING), fragmentBindings);
        }
    }

    private static String source(String name) throws IOException {
        try (var input = LodCullVisibilityTest.class.getResourceAsStream(
                "/assets/jbp-lod/shaders/lod/gl46/cull/" + name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
