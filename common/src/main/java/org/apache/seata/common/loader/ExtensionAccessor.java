package org.apache.seata.common.loader;

public interface ExtensionAccessor {
    ExtensionDirector getExtensionDirector();

    default <T> EnhancedServiceLoader<T> getExtensionLoader(Class<T> type) {
        return this.getExtensionDirector().getExtensionLoader(type);
    }

    default <T> T getExtension(Class<T> type, String name) {
        EnhancedServiceLoader<T> extensionLoader = getExtensionLoader(type);
        return extensionLoader != null ? EnhancedServiceLoader.load(type, name) : null;
    }

    default <T> T getCachedExtension(String activateName, Class<?>[] argsType, Object[] args, ClassLoader loader, boolean includeCompatible, Class<T> type) {
        EnhancedServiceLoader<T> extensionLoader = getExtensionLoader(type);
        if (extensionLoader != null && activateName != null && argsType != null && args != null) {
            return EnhancedServiceLoader.load(type, activateName, argsType, args);
        } else if (extensionLoader != null && activateName != null && args != null) {
            return EnhancedServiceLoader.load(type, activateName, args);
        } else if (extensionLoader != null && activateName != null && loader != null) {
            return EnhancedServiceLoader.load(type, activateName, loader);
        } else if (extensionLoader != null && activateName != null) {
            return EnhancedServiceLoader.load(type, activateName, includeCompatible);
        } else {
            return null;
        }
    }

    default <T> T getDefaultExtension(Class<T> type, boolean includeCompatible) {
        EnhancedServiceLoader<T> extensionLoader = getExtensionLoader(type);
        return extensionLoader != null ? EnhancedServiceLoader.load(type, includeCompatible) : null;
    }
}
