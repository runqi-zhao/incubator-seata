package org.apache.seata.common.loader;

public interface ExtensionPostProcessor {

    default Object postProcessBeforeInitialization(Object instance, String name) throws Exception {
        return instance;
    }

    default Object postProcessAfterInitialization(Object instance, String name) throws Exception {
        return instance;
    }
}
