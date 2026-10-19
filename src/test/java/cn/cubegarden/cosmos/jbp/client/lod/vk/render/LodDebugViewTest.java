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
        assertEquals(LodDebugView.LEVEL, LodDebugView.parse(" level ").orElseThrow());
        assertEquals(LodDebugView.PASS, LodDebugView.parse("PASS").orElseThrow());
        assertEquals(LodDebugView.BOUND, LodDebugView.parse("Bound").orElseThrow());
        assertEquals(LodDebugView.STENCIL, LodDebugView.parse("stencil").orElseThrow());
        assertEquals(LodDebugView.LIGHT, LodDebugView.parse("light").orElseThrow());
        assertEquals(LodDebugView.TEXTURE, LodDebugView.parse("Texture").orElseThrow());
    }

    @Test
    void unknownNamesAreReportedNotSilentlyAccepted() {
        assertFalse(LodDebugView.parse("colour").isPresent());
        assertFalse(LodDebugView.parse("normals").isPresent());
    }

    @Test
    void shaderCodesAreDistinctAndMatchGlslComments() {
        // quads.frag documents codes 1..11; keep these in sync.
        assertEquals(0, LodDebugView.OFF.shaderCode());
        assertEquals(1, LodDebugView.NORMAL.shaderCode());
        assertEquals(2, LodDebugView.UV.shaderCode());
        assertEquals(3, LodDebugView.FACE.shaderCode());
        assertEquals(4, LodDebugView.MODEL.shaderCode());
        assertEquals(5, LodDebugView.DEPTH.shaderCode());
        assertEquals(6, LodDebugView.LEVEL.shaderCode());
        assertEquals(7, LodDebugView.PASS.shaderCode());
        assertEquals(8, LodDebugView.BOUND.shaderCode());
        assertEquals(9, LodDebugView.STENCIL.shaderCode());
        assertEquals(10, LodDebugView.LIGHT.shaderCode());
        assertEquals(11, LodDebugView.TEXTURE.shaderCode());
        assertEquals("jbp.lod.debugView", LodDebugView.PROPERTY);
    }
}
