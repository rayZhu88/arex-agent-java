package io.arex.agent.bootstrap;

import io.arex.agent.bootstrap.util.StringUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * AgentClassLoader 是 arex-agent 的专用类加载器，核心作用：
 * 1. 实现 agent 及其扩展 jar 的类隔离，避免与业务代码冲突。
 * 2. 支持插件式扩展，extensions 目录下的 jar 会自动加载。
 * 3. 优先自身加载，最后才委托父加载器，保证 agent 依赖的独立性。
 * 4. 提升多线程加载类的性能和安全性。
 * 5. 是 Java Agent 领域的通用最佳实践。
 */
public class AgentClassLoader extends URLClassLoader {

    static {
        // 注册为并行 capable，提升多线程加载类时的安全性和性能
        ClassLoader.registerAsParallelCapable();
    }

    // agent jar 的信息
    private JarInfo agentJarInfo;
    private JarFile agentJarFile;
    // 扩展 jar 的信息列表
    private List<JarInfo> extensionJarFiles;

    // 构造方法，接收 agent jar、父加载器、扩展 jar
    public AgentClassLoader(File jarFile, ClassLoader parent, File[] extensionJars) {
        super(new URL[]{}, parent);

        try {
            this.agentJarFile = new JarFile(jarFile, false);
            agentJarInfo = new JarInfo(agentJarFile, jarFile);
            extensionJarFiles = getExtensionJarFiles(extensionJars);
            // 将扩展 jar 的 URL 加入到 URLClassLoader 路径
            for (JarInfo jarInfo : extensionJarFiles) {
                super.addURL(jarInfo.getSourceFile().toURI().toURL());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to open agent jar", e);
        }
    }

    // 加载扩展 jar 的信息
    private List<JarInfo> getExtensionJarFiles(File[] extensionFiles) {
        if (extensionFiles == null) {
            return Collections.emptyList();
        }
        List<JarInfo> jarFiles = new ArrayList<>(extensionFiles.length);
        for (File file : extensionFiles) {
            try {
                JarInfo jarInfo = new JarInfo(new JarFile(file, false), file);
                jarFiles.add(jarInfo);
            } catch (IOException e) {
                System.err.printf("Add extension file failed, file: %s%n", file.getAbsolutePath());
            }
        }
        return jarFiles;
    }

    // 重写 loadClass，优先自身加载，找不到再委托父加载器
    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // 1. 先查找已加载类
            Class<?> clazz = findLoadedClass(name);
            if (clazz == null) {
                // 2. 再尝试自身 findClass
                clazz = findClass(name);
            }
            if (clazz == null) {
                // 3. 最后委托父加载器
                clazz = super.loadClass(name, false);
            }
            if (resolve) {
                resolveClass(clazz);
            }
            return clazz;
        }
    }

    // 自定义查找 class 的逻辑
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // 某些 runtime 包名直接跳过
        if (StringUtil.startWithFrom(name, "runtime", 13)) {
            return null;
        }
        // 查找 agent jar 和扩展 jar 里的 class
        JarEntryInfo jarEntryInfo = findJarEntry(name.replace('.', '/') + ".class");
        if (jarEntryInfo != null && jarEntryInfo.getJarEntry() != null) {
            byte[] bytes;
            try {
                bytes = getJarEntryBytes(jarEntryInfo);
            } catch (IOException exception) {
                throw new ClassNotFoundException(name, exception);
            }
            // 定义 package 信息
            definePackageIfNeeded(jarEntryInfo, name);
            // 定义 class
            return defineClass(name, bytes);
        }
        return null;
    }

    public Class<?> defineClass(String name, byte[] bytes) {
        return defineClass(name, bytes, 0, bytes.length);
    }

    private void definePackageIfNeeded(JarEntryInfo jarEntryInfo, String className) {
        String packageName = getPackageName(className);
        if (packageName == null) {
            return;
        }
        if (getPackage(packageName) == null) {
            try {
                definePackage(packageName, jarEntryInfo.getJarInfo().getJarFile().getManifest(),
                    jarEntryInfo.getJarInfo().getSourceFile().toURI().toURL());
            } catch (Exception exception) {
                if (getPackage(packageName) == null) {
                    throw new IllegalStateException("Failed to define package", exception);
                }
            }
        }
    }

    private static String getPackageName(String className) {
        int index = className.lastIndexOf('.');
        return index == -1 ? null : className.substring(0, index);
    }

    private byte[] getJarEntryBytes(JarEntryInfo jarEntryInfo) throws IOException {
        int size = (int) jarEntryInfo.getJarEntry().getSize();
        byte[] buffer = new byte[size];
        try (InputStream is = jarEntryInfo.getJarInfo().getJarFile().getInputStream(jarEntryInfo.getJarEntry())) {
            int offset = 0;
            int read;

            while (offset < size && (read = is.read(buffer, offset, size - offset)) != -1) {
                offset += read;
            }
        }

        return buffer;
    }

    // need to cache
    private JarEntryInfo findJarEntry(String name) {
        JarEntry jarEntry = agentJarInfo.getJarFile().getJarEntry(name);
        if (jarEntry != null) {
            return new JarEntryInfo(name, jarEntry, agentJarInfo);
        }

        for (JarInfo jarInfo : extensionJarFiles) {
            jarEntry = jarInfo.getJarFile().getJarEntry(name);
            if (jarEntry != null) {
                return new JarEntryInfo(name, jarEntry, jarInfo);
            }
        }

        return null;
    }

    private URL getJarEntryUrl(JarEntryInfo jarInfo) {
        if (jarInfo != null && jarInfo.getJarEntry() != null) {
            try {
                return new URL(
                    "jar:" + jarInfo.getJarInfo().getSourceFile().toURI().toURL() + "!/" + jarInfo.getJarEntry()
                        .getName());
            } catch (MalformedURLException e) {
                throw new IllegalStateException(jarInfo.getJarEntry().getName(), e);
            }
        }
        return null;
    }

    @Override
    public URL findResource(String name) {
        URL url = getJarEntryUrl(findJarEntry(name));
        if (url != null) {
            return url;
        }
        return super.findResource(name);
    }

    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        Enumeration<URL> superResource = super.findResources(name);
        URL url = getJarEntryUrl(findJarEntry(name));
        if (url == null) {
            return superResource;
        }

        List<URL> resources = new LinkedList<>();
        resources.add(url);
        while (superResource.hasMoreElements()) {
            resources.add(superResource.nextElement());
        }
        final Iterator<URL> iterator = resources.iterator();
        return new Enumeration<URL>() {
            @Override
            public boolean hasMoreElements() {
                return iterator.hasNext();
            }

            @Override
            public URL nextElement() {
                return iterator.next();
            }
        };
    }

    private static class JarEntryInfo {

        private final String className;
        private final JarEntry jarEntry;
        private final JarInfo jarInfo;

        private JarEntryInfo(String className, JarEntry jarEntry, JarInfo jarInfo) {
            this.className = className;
            this.jarEntry = jarEntry;
            this.jarInfo = jarInfo;
        }

        public JarEntry getJarEntry() {
            return jarEntry;
        }

        public JarInfo getJarInfo() {
            return jarInfo;
        }
    }

    private static class JarInfo {

        private final JarFile jarFile;
        private final File sourceFile;

        private JarInfo(JarFile jarFile, File sourceFile) {
            this.jarFile = jarFile;
            this.sourceFile = sourceFile;
        }

        public JarFile getJarFile() {
            return jarFile;
        }

        public File getSourceFile() {
            return sourceFile;
        }
    }
}
