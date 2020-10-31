package tw.framework.michaelcore.core;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import net.sf.cglib.proxy.Enhancer;
import tw.framework.michaelcore.aop.MichaelCoreAopHandler;
import tw.framework.michaelcore.aop.annotation.AopHere;
import tw.framework.michaelcore.async.annotation.Async;
import tw.framework.michaelcore.core.annotation.Configuration;
import tw.framework.michaelcore.core.annotation.ExecuteAfterContainerStartup;
import tw.framework.michaelcore.data.annotation.Transactional;
import tw.framework.michaelcore.ioc.Components;
import tw.framework.michaelcore.ioc.CoreContext;
import tw.framework.michaelcore.ioc.annotation.Autowired;
import tw.framework.michaelcore.ioc.annotation.Bean;
import tw.framework.michaelcore.ioc.annotation.Value;
import tw.framework.michaelcore.mvc.MvcCore;

public class Core {

    static {
        try {
            readPropertiesToContainer();
            readFqcnsToContainer();
        } catch (Exception e) {
            System.err.println("Core Initial Error!");
            e.printStackTrace();
        }
    }

    public static void start() {
        initializeCore();
        CoreContext.getBean(MvcCore.class).startServer();
    }

    private static void initializeCore() {
        try {
            initializeIoC();
            initializeValueProperties();
            initializeIoCForBean();
            initializeAOP();
            initializeAutowired();
            executeStartupCode();
        } catch (Exception e) {
            System.err.println("initializeCore() Error!");
            e.printStackTrace();
        }
    }

    private static void readPropertiesToContainer() throws IOException {
        List<String> properties = Files.readAllLines(Paths.get("resources/application.properties"));
        properties.forEach(property -> {
            if (property.trim().length() > 0) {
                String[] keyValue = property.split("=");
                CoreContext.addProperties(keyValue[0], keyValue[1]);
            }
        });
    }

    private static void readFqcnsToContainer() throws IOException {
        String applicationPath = getApplicationPath();
        List<String> fqcns = Files.walk(Paths.get(applicationPath))
                .filter(Files::isRegularFile)
                .filter(file -> {
                    return notInDefaultPackage(file, applicationPath) && isClassFile(file);
                }).map(classFile -> {
                    return toFqcn(classFile, applicationPath);
                }).collect(Collectors.toList());
        CoreContext.setFqcns(fqcns);
    }

    private static String getApplicationPath() {
        String applicationPath = Core.class.getResource("/").getPath();
        boolean isWindowsSystem = Boolean.parseBoolean(CoreContext.getProperties("isWindowsSystem"));
        return isWindowsSystem ? applicationPath.substring(1) : applicationPath;
    }

    private static boolean notInDefaultPackage(Path file, String applicationPath) {
        return file.getParent().toString().replace("\\", "/").split(applicationPath).length == 2;
    }

    private static boolean isClassFile(Path file) {
        return file.getFileName().toString().endsWith(".class");
    }

    private static String toFqcn(Path classFile, String applicationPath) {
        String packageName = classFile.getParent().toString().replace("\\", "/").split(applicationPath)[1].replace("/", ".");
        String className = classFile.getFileName().toString().split("\\.class")[0];
        return String.format("%s.%s", packageName, className);
    }

    private static Class<?> getClassByFqcn(String fqcn) throws Exception {
        return Class.forName(fqcn);
    }

    private static void initializeIoC() throws Exception {
        for (String fqcn : CoreContext.getFqcns()) {
            Class<?> clazz = getClassByFqcn(fqcn);
            if (isManagedBean(clazz)) {
                addBeanToContainer(clazz);
            }
        }
    }
    
    private static boolean isManagedBean(Class<?> clazz) {
        return isConfigurationClass(clazz) || Components.isComponentClass(clazz);
    }

    private static boolean isConfigurationClass(Class<?> clazz) {
        return clazz.isAnnotationPresent(Configuration.class);
    }
    
    private static void addBeanToContainer(Class<?> clazz) throws Exception {
        CoreContext.addBean(clazz.getName(), clazz.getDeclaredConstructor().newInstance());
    }
    
    private static void initializeValueProperties() throws Exception {
        for (String fqcn : CoreContext.getFqcns()) {
            Class<?> clazz = getClassByFqcn(fqcn);
            if (isManagedBean(clazz)) {
                insertValue(clazz, CoreContext.getBean(clazz));
            }
        }
    }

    private static void insertValue(Class<?> clazz, Object bean) throws Exception {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Value.class)) {
            	field.setAccessible(true);
                field.set(bean, CoreContext.getProperties(field.getName()));
            }
        }
    }

    private static void initializeIoCForBean() throws Exception {
        for (String fqcn : CoreContext.getFqcns()) {
            Class<?> clazz = getClassByFqcn(fqcn);
            if (isConfigurationClass(clazz)) {
                processConfigurationClass(clazz);
            }
        }
    }

    private static void processConfigurationClass(Class<?> clazz) throws Exception {
        Object configurationBean = CoreContext.getBean(clazz.getName());
        for (Method method : clazz.getMethods()) {
            if (isBeanMethod(method)) {
                addBeanToContainer(method, configurationBean);
            }
        }
    }
    
    private static boolean isBeanMethod(Method method) {
        return method.isAnnotationPresent(Bean.class);
    }

    private static void addBeanToContainer(Method method, Object configurationBean) throws Exception {
        CoreContext.addBean(method.getReturnType().getName(), method.invoke(configurationBean));
    }

    private static void initializeAOP() throws Exception {
        for (String fqcn : CoreContext.getFqcns()) {
            Class<?> clazz = getClassByFqcn(fqcn);
            if (needToCreateProxy(clazz)) {
                addProxyBeanToContainer(clazz, createProxy(clazz));
            }
        }
    }

    private static boolean needToCreateProxy(Class<?> clazz) {
        return aopOnClass(clazz) || aopOnMethod(clazz);
    }

    private static boolean aopOnClass(Class<?> clazz) {
        if (clazz.isAnnotationPresent(AopHere.class) || clazz.isAnnotationPresent(Transactional.class) || clazz.isAnnotationPresent(Async.class)) {
            return true;
        }
        return false;
    }

    private static boolean aopOnMethod(Class<?> clazz) {
        for (Method method : clazz.getMethods()) {
            if (method.isAnnotationPresent(AopHere.class) || method.isAnnotationPresent(Transactional.class) || method.isAnnotationPresent(Async.class)) {
                return true;
            }
        }
        return false;
    }

    private static Object createProxy(Class<?> clazz) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(clazz);
        enhancer.setCallback(getAopHandler());
        return enhancer.create();
    }

    private static MichaelCoreAopHandler getAopHandler() {
        return CoreContext.getBean(MichaelCoreAopHandler.class);
    }

    private static void addProxyBeanToContainer(Class<?> clazz, Object proxy) throws Exception {
        CoreContext.addProxyBean(clazz.getName(), proxy);
    }

    private static void initializeAutowired() throws Exception {
        for (String fqcn : CoreContext.getFqcns()) {
            Class<?> clazz = getClassByFqcn(fqcn);
            if (isManagedBean(clazz)) {
                autowireDependency(clazz, CoreContext.getRealBean(clazz));
            }
        }
    }

    private static void autowireDependency(Class<?> clazz, Object realBean) throws Exception {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Autowired.class)) {
            	field.setAccessible(true);
            	Class<?> autowiredClazz = field.getAnnotation(Autowired.class).value();
            	if(autowiredClazz.equals(Object.class)) {
            		field.set(realBean, CoreContext.getBean(field.getType()));
            	} else {
            		field.set(realBean, CoreContext.getBean(autowiredClazz.getName()));
            	}
            }
        }
    }

    private static void executeStartupCode() throws Exception {
        for (String fqcn : CoreContext.getFqcns()) {
            Class<?> clazz = getClassByFqcn(fqcn);
            if (isConfigurationClass(clazz)) {
                executeMethodWithStartupAnnotation(clazz, CoreContext.getBean(clazz));
            }
        }
    }

    private static void executeMethodWithStartupAnnotation(Class<?> clazz, Object configurationBean) throws Exception {
        for (Method method : clazz.getMethods()) {
            if (method.isAnnotationPresent(ExecuteAfterContainerStartup.class)) {
                method.invoke(configurationBean);
            }
        }
    }

}
