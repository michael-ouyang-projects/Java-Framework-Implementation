package tw.framework.michaelcore.ioc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.sf.cglib.proxy.Enhancer;
import tw.framework.michaelcore.aop.MichaelCoreAopHandler;
import tw.framework.michaelcore.ioc.annotation.components.Component;

public class CoreContext {

    private static Map<String, String> properties = new HashMap<>();
    private static List<Class<?>> classes;

    private Map<String, Object> beanFactory = new HashMap<>();
    private Map<String, Object> realBeanFactory = new HashMap<>();

    public CoreContext() {
        beanFactory.put(this.getClass().getName(), this);
    }

    public static String getProperty(String key) {
        return properties.get(key);
    }

    static void addProperty(String key, String value) {
        if (value != null) {
            properties.put(key, value);
        }
    }

    public static List<Class<?>> getClasses() {
        return classes;
    }

    static void setClasses(List<Class<?>> componentClasses) {
        CoreContext.classes = componentClasses;
    }

    Map<String, Object> getBeanFactory() {
        return beanFactory;
    }

    public void clearBeanFactory() {
        beanFactory.clear();
        realBeanFactory.clear();
    }

    public <T> T getBean(String name, Class<T> clazz) {
        return clazz.cast(getBean(name));
    }

    @SuppressWarnings("unchecked")
    public Object getBean(String name) {
        Object bean = beanFactory.get(name);
        if (bean == null) {
            return null;
        }
        try {
            if (bean.getClass().equals(Constructor.class)) {
                bean = getPrototypeComponentByConstructor((Constructor<Object>) bean, name);
            } else if (bean.getClass().equals(Method.class)) {
                Method method = (Method) bean;
                bean = method.invoke(beanFactory.get(method.getDeclaringClass().getName()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return bean;
    }

    private Object getPrototypeComponentByConstructor(Constructor<Object> constructor, String beanName) throws Exception {
        Object component = constructor.newInstance();
        Class<?> componentClazz = component.getClass();
        Core.insertPropertiesToBean(component, componentClazz);
        Core.autowireDependencies(this, component);
        if (Core.aopOnClass(componentClazz) || Core.aopOnMethod(componentClazz)) {
            return getProxyAndAddRealBeanToContainer(componentClazz, component);
        }
        return component;
    }

    private Object getProxyAndAddRealBeanToContainer(Class<?> clazz, Object realBean) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(clazz);
        enhancer.setCallback((MichaelCoreAopHandler) getBean(MichaelCoreAopHandler.class.getName()));
        Object proxy = enhancer.create();
        realBeanFactory.put(proxy.getClass().getName(), realBean);
        return proxy;
    }

    public Object getRealBean(String name) {
        Object bean = beanFactory.get(name);
        boolean isProxy = bean.getClass().getName().contains("$$EnhancerByCGLIB$$");
        return isProxy ? getRealBean(bean) : bean;
    }

    public Object getRealBean(Object proxy) {
        return realBeanFactory.get(proxy.getClass().getName());
    }

    public void addBean(String name, Object object) {
        if (object != null) {
            if (!containsBean(name)) {
                beanFactory.put(name, object);
            } else {
                throw new RuntimeException("Duplicate Bean Name: " + name);
            }
        }
    }

    public void addProxyBean(String name, Object proxy) {
        Object realBean = beanFactory.put(name, proxy);
        realBeanFactory.put(proxy.getClass().getName(), realBean);
    }

    public boolean containsBean(String name) {
        return beanFactory.containsKey(name);
    }

    public <T> T executeInnerMethodWithAop(Class<T> clazz) {
        String beanName = clazz.getName();
        if (clazz.isAnnotationPresent(Component.class)) {
            beanName = Core.getComponentName(clazz.getAnnotation(Component.class), clazz);
        }
        return getBean(beanName, clazz);
    }

}