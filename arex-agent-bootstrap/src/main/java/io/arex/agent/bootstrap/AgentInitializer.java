package io.arex.agent.bootstrap;

import io.arex.agent.bootstrap.constants.ConfigConstants;
import io.arex.agent.bootstrap.util.AdviceClassesCollector;
import io.arex.agent.bootstrap.util.StringUtil;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * AgentInitializer 负责 agent 启动时的核心初始化流程。
 * 主要职责：
 * 1. 初始化日志配置，保证 agent 日志能正确输出。
 * 2. 获取 extensions 目录下的扩展 jar（插件式扩展）。
 * 3. 创建 agent 专用的类加载器，加载 agent 及扩展 jar，保证与业务代码隔离。
 * 4. 保存 agent 相关信息到全局 holder，便于后续使用。
 * 5. 创建并初始化 AgentInstaller（实际为 InstrumentationInstaller），负责注册所有插桩。
 * 6. 将 agent 及扩展 jar 加入 AdviceClassesCollector 的搜索路径，便于字节码增强时查找。
 * 7. 安装所有 instrumentation 插桩，正式生效。
 * 
 * 该类是 agent 启动的核心枢纽，负责环境准备、类加载隔离、插桩注册等所有关键步骤。
 */
public class AgentInitializer {

    public static final String SIMPLE_DATE_FORMAT_MILLIS = "yyyy-MM-dd HH:mm:ss.SSS";

    private static ClassLoader classLoader;

    /**
     * @param parentClassLoader Normally, the parentClassLoader should be ClassLoaders.AppClassLoader.
     */
    public static void initialize(Instrumentation inst, File agentFile, String agentArgs, ClassLoader parentClassLoader)
            throws Exception {
        if (classLoader != null) {
            return;
        }
        // 1. 初始化日志配置，保证 agent 日志能正确输出到文件，便于排查问题
        initializeSimpleLoggerConfig(agentFile.getParent());
        // 2. 获取 extensions 目录下的所有扩展 jar（插件式扩展，便于后续功能增强）
        File[] extensionFiles = getExtensionJarFiles(agentFile);
        // 为什么要创建 agent 专用的类加载器？
        // 1. 避免 agent 依赖与业务应用依赖冲突，实现类隔离，防止版本冲突和奇怪的 ClassNotFound/ClassCast 问题。
        // 2. 插桩增强时，Advice 等增强逻辑的类需要灵活控制可见性，专用类加载器更安全。
        // 3. 支持插件式扩展，extensions 目录下的插件可动态加载且互不干扰。
        // 4. 遵循 Java Agent 领域最佳实践，主流探针如 SkyWalking、OpenTelemetry 也都采用类似机制。
        // 5. 便于后续卸载和重加载，防止内存泄漏。
        classLoader = new AgentClassLoader(agentFile, parentClassLoader, extensionFiles);
        // 4. 保存 agent 相关信息到全局 holder，便于后续任意地方获取 agent 的关键信息
        InstrumentationHolder.setAgentClassLoader(classLoader);
        InstrumentationHolder.setInstrumentation(inst);
        InstrumentationHolder.setAgentFile(agentFile);
        // 5. 创建并初始化 AgentInstaller（实际为 InstrumentationInstaller），负责注册所有插桩
        AgentInstaller installer = createAgentInstaller(inst, agentFile, agentArgs);
        // 6. 将 agent 及扩展 jar 加入 AdviceClassesCollector 的搜索路径，便于字节码增强时查找 Advice 类
        addJarToLoaderSearch(agentFile, extensionFiles);
        // 7. 安装所有 instrumentation 插桩，agent 正式生效，开始字节码增强
        installer.install();
    }

    private static void addJarToLoaderSearch(File agentFile, File[] extensionFiles) {
        AdviceClassesCollector.INSTANCE.addJarToLoaderSearch(agentFile);

        if (extensionFiles == null) {
            return;
        }

        for (File file : extensionFiles) {
            AdviceClassesCollector.INSTANCE.addJarToLoaderSearch(file);
        }
    }

    private static File[] getExtensionJarFiles(File jarFile) {
        String extensionDir = jarFile.getParent() + "/extensions/";
        return new File(extensionDir).listFiles(AgentInitializer::isJar);
    }

    private static boolean isJar(File f) {
        return f.isFile() && f.getName().endsWith(".jar");
    }

    private static AgentInstaller createAgentInstaller(Instrumentation inst, File file, String agentArgs) throws Exception {
        Class<?> clazz = classLoader.loadClass("io.arex.agent.instrumentation.InstrumentationInstaller");
        Constructor<?> constructor = clazz.getDeclaredConstructor(Instrumentation.class, File.class, String.class);
        return (AgentInstaller) constructor.newInstance(inst, file, agentArgs);
    }

    private static void initializeSimpleLoggerConfig(String agentFileParent) {
        System.setProperty(ConfigConstants.SIMPLE_LOGGER_SHOW_DATE_TIME, Boolean.TRUE.toString());
        System.setProperty(ConfigConstants.SIMPLE_LOGGER_DATE_TIME_FORMAT, SIMPLE_DATE_FORMAT_MILLIS);

        String logPath = System.getProperty(ConfigConstants.LOG_PATH);
        if (StringUtil.isEmpty(logPath)) {
            logPath = agentFileParent + "/logs";
            System.setProperty(ConfigConstants.LOG_PATH, logPath);
        }

        Path filePath = Paths.get(logPath);
        if (Files.notExists(filePath)) {
            try {
                Files.createDirectories(filePath);
            } catch (IOException e) {
                System.err.printf("%s [AREX] Failed to create log directory: %s%n",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern(SIMPLE_DATE_FORMAT_MILLIS)), logPath);
                return;
            }
        }
        String logFilePath = logPath + "/arex." + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".log";
        System.setProperty(ConfigConstants.SIMPLE_LOGGER_FILE, logFilePath);
    }
}
