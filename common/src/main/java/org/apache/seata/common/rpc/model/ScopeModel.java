package org.apache.seata.common.rpc.model;

import org.apache.seata.common.loader.ExtensionAccessor;
import org.apache.seata.common.loader.ExtensionDirector;
import org.apache.seata.common.loader.Scope;
import org.apache.seata.common.util.StringUtils;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;

public abstract class ScopeModel implements ExtensionAccessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScopeModel.class);

    /**
     * The internal id is used to represent the hierarchy of the model tree, such as:
     * <ol>
     *     <li>1</li>
     *     FrameworkModel (index=1)
     *     <li>1.2</li>
     *     FrameworkModel (index=1) -> ApplicationModel (index=2)
     *     <li>1.2.0</li>
     *     FrameworkModel (index=1) -> ApplicationModel (index=2) -> ModuleModel (index=0, internal module)
     *     <li>1.2.1</li>
     *     FrameworkModel (index=1) -> ApplicationModel (index=2) -> ModuleModel (index=1, first user module)
     * </ol>
     */
    private String internalId;

    /**
     * Public Model Name, can be set from user
     */
    private String modelName;

    private String desc;

    private final Set<ClassLoader> classLoaders = new ConcurrentHashSet<>();

    private final ScopeModel parent;
    private final Scope scope;

    private volatile ExtensionDirector extensionDirector;

    private volatile ScopeBeanFactory beanFactory;
    private final List<ScopeModelDestroyListener> destroyListeners = new CopyOnWriteArrayList<>();

    private final List<ScopeClassLoaderListener> classLoaderListeners = new CopyOnWriteArrayList<>();

    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final boolean internalScope;

    protected final Object instLock = new Object();

    protected ScopeModel(ScopeModel parent, Scope scope, boolean isInternal) {
        this.parent = parent;
        this.scope = scope;
        this.internalScope = isInternal;
    }

    /**
     * NOTE:
     * <ol>
     *  <li>The initialize method only be called in subclass.</li>
     * <li>
     * In subclass, the extensionDirector and beanFactory are available in initialize but not available in constructor.
     * </li>
     * </ol>
     */
    protected void initialize() {
        synchronized (instLock) {
            this.extensionDirector =
                    new ExtensionDirector(parent != null ? parent.getExtensionDirector() : null, scope, this);
            this.extensionDirector.addExtensionPostProcessor(new ScopeModelAwareExtensionProcessor(this));
            this.beanFactory = new ScopeBeanFactory(parent != null ? parent.getBeanFactory() : null, extensionDirector);

            // Add Framework's ClassLoader by default
            ClassLoader dubboClassLoader = ScopeModel.class.getClassLoader();
            if (dubboClassLoader != null) {
                this.addClassLoader(dubboClassLoader);
            }
        }
    }

    protected abstract Lock acquireDestroyLock();

    public void destroy() {
        Lock lock = acquireDestroyLock();
        try {
            lock.lock();
            if (destroyed.compareAndSet(false, true)) {
                try {
                    onDestroy();
                    HashSet<ClassLoader> copyOfClassLoaders = new HashSet<>(classLoaders);
                    for (ClassLoader classLoader : copyOfClassLoaders) {
                        removeClassLoader(classLoader);
                    }
                    if (beanFactory != null) {
                        beanFactory.destroy();
                    }
                    if (extensionDirector != null) {
                        extensionDirector.destroy();
                    }
                } catch (Throwable t) {
                    LOGGER.error(CONFIG_UNABLE_DESTROY_MODEL, "", "", "Error happened when destroying ScopeModel.", t);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean isDestroyed() {
        return destroyed.get();
    }

    protected void notifyDestroy() {
        for (ScopeModelDestroyListener destroyListener : destroyListeners) {
            destroyListener.onDestroy(this);
        }
    }

    protected void notifyProtocolDestroy() {
        for (ScopeModelDestroyListener destroyListener : destroyListeners) {
            if (destroyListener.isProtocol()) {
                destroyListener.onDestroy(this);
            }
        }
    }

    protected void notifyClassLoaderAdd(ClassLoader classLoader) {
        for (ScopeClassLoaderListener classLoaderListener : classLoaderListeners) {
            classLoaderListener.onAddClassLoader(this, classLoader);
        }
    }

    protected void notifyClassLoaderDestroy(ClassLoader classLoader) {
        for (ScopeClassLoaderListener classLoaderListener : classLoaderListeners) {
            classLoaderListener.onRemoveClassLoader(this, classLoader);
        }
    }

    protected abstract void onDestroy();

    public final void addDestroyListener(ScopeModelDestroyListener listener) {
        destroyListeners.add(listener);
    }

    public final void addClassLoaderListener(ScopeClassLoaderListener listener) {
        classLoaderListeners.add(listener);
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public <T> T getAttribute(String key, Class<T> type) {
        return (T) attributes.get(key);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @Override
    public ExtensionDirector getExtensionDirector() {
        return extensionDirector;
    }

    public ScopeBeanFactory getBeanFactory() {
        return beanFactory;
    }

    public ScopeModel getParent() {
        return parent;
    }

    public ExtensionScope getScope() {
        return scope;
    }

    public void addClassLoader(ClassLoader classLoader) {
        synchronized (instLock) {
            this.classLoaders.add(classLoader);
            if (parent != null) {
                parent.addClassLoader(classLoader);
            }
            extensionDirector.removeAllCachedLoader();
            notifyClassLoaderAdd(classLoader);
        }
    }

    public void removeClassLoader(ClassLoader classLoader) {
        synchronized (instLock) {
            if (checkIfClassLoaderCanRemoved(classLoader)) {
                this.classLoaders.remove(classLoader);
                if (parent != null) {
                    parent.removeClassLoader(classLoader);
                }
                extensionDirector.removeAllCachedLoader();
                notifyClassLoaderDestroy(classLoader);
            }
        }
    }

    protected boolean checkIfClassLoaderCanRemoved(ClassLoader classLoader) {
        return classLoader != null && !classLoader.equals(ScopeModel.class.getClassLoader());
    }

    public Set<ClassLoader> getClassLoaders() {
        return Collections.unmodifiableSet(classLoaders);
    }

    /**
     * Get current model's environment.
     * </br>
     * Note: This method should not start with `get` or it would be invoked due to Spring boot refresh.
     * @see <a href="https://github.com/apache/dubbo/issues/12542">Configuration refresh issue</a>
     */
    public abstract Environment modelEnvironment();

    /**
     * Get current model's environment.
     *
     * @see <a href="https://github.com/apache/dubbo/issues/12542">Configuration refresh issue</a>
     * @deprecated use modelEnvironment() instead
     */
    @Deprecated
    public final Environment getModelEnvironment() {
        try {
            return modelEnvironment();
        } catch (Exception ex) {
            return null;
        }
    }

    public String getInternalId() {
        return this.internalId;
    }

    void setInternalId(String internalId) {
        this.internalId = internalId;
    }

    protected String buildInternalId(String parentInternalId, long childIndex) {
        // FrameworkModel    1
        // ApplicationModel  1.1
        // ModuleModel       1.1.1
        if (StringUtils.hasText(parentInternalId)) {
            return parentInternalId + "." + childIndex;
        } else {
            return "" + childIndex;
        }
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
        this.desc = buildDesc();
    }

    public boolean isInternal() {
        return internalScope;
    }

    /**
     * @return to describe string of this scope model
     */
    public String getDesc() {
        if (this.desc == null) {
            this.desc = buildDesc();
        }
        return this.desc;
    }

    private String buildDesc() {
        // Dubbo Framework[1]
        // Dubbo Application[1.1](appName)
        // Dubbo Module[1.1.1](appName/moduleName)
        String type = this.getClass().getSimpleName().replace("Model", "");
        String desc = "Dubbo " + type + "[" + this.getInternalId() + "]";

        // append model name path
        String modelNamePath = this.getModelNamePath();
        if (StringUtils.hasText(modelNamePath)) {
            desc += "(" + modelNamePath + ")";
        }
        return desc;
    }

    private String getModelNamePath() {
        if (this instanceof ApplicationModel) {
            return safeGetAppName((ApplicationModel) this);
        } else if (this instanceof ModuleModel) {
            String modelName = this.getModelName();
            if (StringUtils.hasText(modelName)) {
                // appName/moduleName
                return safeGetAppName(((ModuleModel) this).getApplicationModel()) + "/" + modelName;
            }
        }
        return null;
    }

    private static String safeGetAppName(ApplicationModel applicationModel) {
        String modelName = applicationModel.getModelName();
        if (StringUtils.isBlank(modelName)) {
            modelName = "unknown"; // unknown application
        }
        return modelName;
    }

    @Override
    public String toString() {
        return getDesc();
    }
}
