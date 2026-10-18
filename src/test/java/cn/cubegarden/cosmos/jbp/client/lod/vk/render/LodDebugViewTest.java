package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LodDebugViewTest {
    @Test
    void nullBlankAndOffMeanNoDebugView() {
        assertEquals(LodDebugView.OFF, LodDebugView.parse(null).orElseThrow());
        assertEquals(LodDebugView.OFF, LodDebugView.parse("").orElseThrow());
        assertEquals(LodDebugView.OFF, LodDebugView.parse("   ").orElseThrow());
        assertEquals(LodDebugView.OFF, LodDebugView.parse("off").orElseThrow());
        assertEquals(LodDebugView.OFF, LodDebugView.parse(" OFF ").orElseThrow());
    }

    @Test
    void namesParseCaseInsensitively() {
        assertEquals(LodDebugView.NORMAL, LodDebugView.parse("normal").orElseThrow());
        assertEquals(LodDebugView.UV, LodDebugView.parse("UV").orElseThrow());
        assertEquals(LodDebugView.FACE, LodDebugView.parse("Face").orElseThrow());
        assertEquals(LodDebugView.MODEL, LodDebugView.parse("model").orElseThrow());
        assertEquals(LodDebugView.DEPTH, LodDebugView.parse(" depth ").orElseThrow());
    }

    @Test
    void unknownNamesAreReportedNotSilentlyAccepted() {
        assertFalse(LodDebugView.parse("colour").isPresent());
        assertFalse(LodDebugView.parse("normals").isPresent());
    }

    @Test
    void shaderCodesAreDistinctAndMatchGlslComments() {
        // quads.frag documents codes 1..5 as normal, uv, face, model, depth; keep these in sync.
        assertEquals(0, LodDebugView.OFF.shaderCode());
        assertEquals(1, LodDebugView.NORMAL.shaderCode());
        assertEquals(2, LodDebugView.UV.shaderCode());
        assertEquals(3, LodDebugView.FACE.shaderCode());
        assertEquals(4, LodDebugView.MODEL.shaderCode());
        assertEquals(5, LodDebugView.DEPTH.shaderCode());
        assertEquals("jbp.lod.debugView", LodDebugView.PROPERTY);
    }
}
