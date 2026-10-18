package cn.cubegarden.cosmos.jbp.client.lod.scope;

import java.nio.file.Path;

/** Resolves the configured cache root. */
public final class LodPaths {
    public static final String BASE_DIRECTORY_PROPERTY = "jbp.lod.dir";
    public static final String DEVELOPMENT_SCOPE_PROPERTY = "jbp.lod.devScope";
    public static final String DEVELOPMENT_NODE_PROPERTY = "jbp.lod.devNode";

    private LodPaths() {
    }

    public static Path baseDirectory(String configuredBase, Path gameDirectory) {
        if (configuredBase == null || configuredBase.isBlank()) {
            return gameDirectory.resolve(".lod");
        }
        return Path.of(configuredBase);
    }

    /** Android supplies Context.getCacheDir()/lod as the root; it never uses the PC fallback. */
    public static Path androidBaseDirectory(Path applicationCacheDirectory) {
        return applicationCacheDirectory.resolve("lod");
    }

    /** Extracts the save-folder ID, normalizing LevelResource.ROOT's trailing {@code .}. */
    public static String worldFolderId(Path worldRoot) {
        if (worldRoot == null) {
            return null;
        }
        Path folder = worldRoot.normalize().getFileName();
        return folder == null ? null : folder.toString();
    }

}
