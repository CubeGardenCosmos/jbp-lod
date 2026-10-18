package cn.cubegarden.cosmos.jbp.client.lod.scope;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Looks up the optional JBP node identity API once, then queries its value per session. */
public final class ReflectiveNodeIdProvider {
    public static final String PROVIDER_CLASS = "cn.cubegarden.cosmos.jbp.client.api.JbpNodeIdProvider";

    private final String providerClassName;
    private final ClassLoader classLoader;
    private volatile Lookup cachedLookup;

    public ReflectiveNodeIdProvider() {
        this(PROVIDER_CLASS, ReflectiveNodeIdProvider.class.getClassLoader());
    }

    ReflectiveNodeIdProvider(String providerClassName, ClassLoader classLoader) {
        this.providerClassName = providerClassName;
        this.classLoader = classLoader;
    }

    /** Returns null when the optional API is unavailable, blank, or fails during lookup/invocation. */
    public String currentNodeId() {
        Method method = lookup().method();
        if (method == null) {
            return null;
        }
        try {
            Object result = method.invoke(null);
            if (result instanceof String nodeId && !nodeId.isBlank()) {
                return nodeId;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // The node provider is optional; failure must keep this scope inactive.
        }
        return null;
    }

    private Lookup lookup() {
        Lookup result = cachedLookup;
        if (result == null) {
            synchronized (this) {
                result = cachedLookup;
                if (result == null) {
                    result = findProviderMethod();
                    cachedLookup = result;
                }
            }
        }
        return result;
    }

    private Lookup findProviderMethod() {
        try {
            Class<?> provider = Class.forName(providerClassName, false, classLoader);
            Method method = provider.getMethod("currentNodeId");
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) {
                return Lookup.MISSING;
            }
            return new Lookup(method);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return Lookup.MISSING;
        }
    }

    private record Lookup(Method method) {
        private static final Lookup MISSING = new Lookup(null);
    }
}
