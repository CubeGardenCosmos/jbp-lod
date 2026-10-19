package cn.cubegarden.cosmos.jbp.client.lod.api;

/**
 * Supplies the borrowed G-buffer targets and matrices for one LOD draw. Implementations are
 * invoked on the render thread while the current CUTOUT dynamic-rendering scope is open.
 *
 * <p>Returned images must already be in the declared layouts, be compatible with the active
 * render scope's extent/sample count, and have producer writes visible to the supplied command
 * buffer. The provider owns any barriers, queue-family ownership transfers, and external graph
 * hand-off needed before this callback. JBP-LOD does not transition provider-owned images while
 * rendering is active. Handles and matrices remain valid through CUTOUT close: JBP-LOD then
 * samples vanilla depth and opens an owned LOAD/STORE composite scope using the same attachments
 * and command buffer. The provider must allow that hand-off; nothing is retained across frames.</p>
 *
 * <p>The initial implementation installs only the vanilla Sodium provider. Implementing this
 * interface does not imply shaderpack support.</p>
 */
@FunctionalInterface
public interface LodExternalTargetsProvider {
    LodFrameTargets targetsFor(LodFrameContext frame);
}
