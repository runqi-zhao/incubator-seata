package org.apache.seata.common.loader;


import org.apache.seata.common.rpc.model.ScopeModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ExtensionDirector implements ExtensionAccessor {
    private final ConcurrentMap<Class<?>, EnhancedServiceLoader<?>> enhancedServiceLoaderMap = new ConcurrentHashMap<>(64);
    private final ConcurrentMap<Class<?>, Scope> extensionScopeMap = new ConcurrentHashMap<>(64);
    private final ExtensionDirector parent;
    private final Scope scope;
    private final List<ExtensionPostProcessor> extensionPostProcessors = new ArrayList<>();
    private final ScopeModel scopeModel;
    private final AtomicBoolean destroyed = new AtomicBoolean();

    public ExtensionDirector(ExtensionDirector parent, Scope scope, ScopeModel scopeModel) {
        this.parent = parent;
        this.scope = scope;
        this.scopeModel = scopeModel;
    }

    public void addExtensionPostProcessor(ExtensionPostProcessor processor) {
        if (!this.extensionPostProcessors.contains(processor)) {
            this.extensionPostProcessors.add(processor);
        }
    }

    public List<ExtensionPostProcessor> getExtensionPostProcessors() {
        return extensionPostProcessors;
    }

    @Override
    public ExtensionDirector getExtensionDirector() {
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> EnhancedServiceLoader<T> getExtensionLoader(Class<T> type) {
        checkDestroyed();
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }

        // 1. find in local cache
        EnhancedServiceLoader<T> loader = (EnhancedServiceLoader<T>) enhancedServiceLoaderMap.get(type);

        Scope scope = extensionScopeMap.get(type);
        if (scope == null) {
            LoadLevel loadLevel = type.getAnnotation(LoadLevel.class);
            scope = loadLevel.getExtensionScope();
            extensionScopeMap.put(type, scope);
        }

        if (loader == null && scope == Scope.SELF) {
            // create an instance in self scope
            loader = createExtensionLoader0(type);
        }

        // 2. find in parent
        if (loader == null) {
            if (this.parent != null) {
                loader = this.parent.getExtensionLoader(type);
            }
        }

        // 3. create it
        if (loader == null) {
            loader = createExtensionLoader(type);
        }

        return loader;
    }

    private <T> EnhancedServiceLoader<T> createExtensionLoader(Class<T> type) {
        EnhancedServiceLoader<T> loader = null;
        if (isScopeMatched(type)) {
            // if scope is matched, just create it
            loader = createExtensionLoader0(type);
        }
        return loader;
    }

    @SuppressWarnings("unchecked")
    private <T> EnhancedServiceLoader<T> createExtensionLoader0(Class<T> type) {
        checkDestroyed();
        EnhancedServiceLoader<T> loader;
        //TODO:这里待确定
        enhancedServiceLoaderMap.putIfAbsent(type, new EnhancedServiceLoader<T>());
        loader = (EnhancedServiceLoader<T>) enhancedServiceLoaderMap.get(type);
        return loader;
    }

    private boolean isScopeMatched(Class<?> type) {
        final LoadLevel defaultLoadLevel = type.getAnnotation(LoadLevel.class);
        return defaultLoadLevel.scope().equals(scope);
    }

    private static boolean withExtensionLoadLevel(Class<?> type) {
        return type.isAnnotationPresent(LoadLevel.class);
    }

    public ExtensionDirector getParent() {
        return parent;
    }

    public void removeAllCachedLoader() {}

    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            for (EnhancedServiceLoader<?> extensionLoader : enhancedServiceLoaderMap.values()) {
                extensionLoader.destroy();
            }
            enhancedServiceLoaderMap.clear();
            extensionScopeMap.clear();
            extensionPostProcessors.clear();
        }
    }

    private void checkDestroyed() {
        if (destroyed.get()) {
            throw new IllegalStateException("ExtensionDirector is destroyed");
        }
    }
}
