package cn.cubegarden.cosmos.jbp.client.lod.scope;

import java.util.Optional;

/** Converts a node or developer world ID into one safe path component. */
public final class NodeIdSanitizer {
    private NodeIdSanitizer() {
    }

    public static Optional<String> sanitize(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return Optional.empty();
        }

        StringBuilder builder = new StringBuilder(nodeId.length());
        for (int offset = 0; offset < nodeId.length();) {
            int codePoint = nodeId.codePointAt(offset);
            boolean allowed = codePoint >= 'A' && codePoint <= 'Z'
                    || codePoint >= 'a' && codePoint <= 'z'
                    || codePoint >= '0' && codePoint <= '9'
                    || codePoint == '.' || codePoint == '_' || codePoint == '-';
            builder.append(allowed ? (char) codePoint : '_');
            offset += Character.charCount(codePoint);
        }
        String sanitized = builder.toString();
        if (sanitized.isEmpty() || sanitized.equals(".") || sanitized.equals("..")) {
            return Optional.empty();
        }
        return Optional.of(sanitized);
    }
}
