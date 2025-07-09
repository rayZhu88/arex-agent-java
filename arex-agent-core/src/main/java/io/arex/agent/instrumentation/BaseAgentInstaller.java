package io.arex.agent.instrumentation;

import io.arex.agent.bootstrap.AgentInstaller;
import io.arex.agent.bootstrap.TraceContextManager;
import io.arex.agent.bootstrap.util.FileUtils;
import io.arex.foundation.config.ConfigManager;
import io.arex.foundation.healthy.HealthManager;
import io.arex.foundation.logger.AgentLoggerFactory;
import io.arex.foundation.logger.AgentLogger;
import io.arex.foundation.services.ConfigService;
import io.arex.foundation.services.TimerService;
import io.arex.foundation.util.NetUtils;
import io.arex.inst.runtime.context.RecordLimiter;
import io.arex.inst.runtime.service.DataCollector;
import io.arex.inst.runtime.service.DataService;

import io.arex.agent.bootstrap.util.ServiceLoader;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import java.io.File;
import java.lang.instrument.Instrumentation;

import net.bytebuddy.dynamic.scaffold.TypeWriter;

public abstract class BaseAgentInstaller implements AgentInstaller {
    private static final AgentLogger LOGGER = AgentLoggerFactory.getAgentLogger(BaseAgentInstaller.class);
    private static final String BYTECODE_DUMP_DIR = "/bytecode-dump";
    protected final Instrumentation instrumentation;
    protected final File agentFile;
    protected final String agentArgs;
    private ScheduledFuture<?> reportStatusTask;
    private ScheduledFuture<?> loadConfigTask;

    public BaseAgentInstaller(Instrumentation inst, File agentFile, String agentArgs) {
        this.instrumentation = inst;
        this.agentFile = agentFile;
        this.agentArgs = agentArgs;
    }

    /**
     * agent 安装主流程：
     * - 支持定时刷新配置和热更新，保证 agent 能动态适应环境变化。
     * - 首次 transform 时初始化所有依赖组件，后续支持 retransform 动态增强。
     * - debug 模式下自动 dump 增强后的字节码，便于开发排查。
     * - 通过 SPI 加载数据采集器等插件，增强扩展性。
     * - 关键节点自动上报 agent 状态，便于监控和健康管理。
     */
    @Override
    public void install() {
        // 保存当前线程的上下文类加载器
        ClassLoader savedContextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // 切换为 agent 的类加载器，保证后续 SPI 加载等都在 agent classloader 下
            Thread.currentThread().setContextClassLoader(getClassLoader());
            // 注册 JVM 关闭钩子，优雅关闭 agent
            Runtime.getRuntime().addShutdownHook(new Thread(ConfigService.INSTANCE::shutdown, "arex-agent-shutdown-hook"));
            // 加载 agent 配置，返回定时刷新配置的间隔（分钟）
            long delayMinutes = ConfigService.INSTANCE.loadAgentConfig(agentArgs);
            // 检查 agent 是否允许启动（如配置合法、开关打开等）
            if (!allowStartAgent()) {
                ConfigService.INSTANCE.reportStatus();
                if (!ConfigManager.FIRST_TRANSFORM.get()) {
                    LOGGER.warn("[AREX] Agent would not install due to {}.", getInvalidReason());
                }
                return;
            }

            // 如果需要定时刷新配置，启动定时任务
            if (delayMinutes > 0 && loadConfigTask == null) {
                loadConfigTask = TimerService.scheduleAtFixedRate(this::install, delayMinutes, delayMinutes, TimeUnit.MINUTES);
                timedReportStatus();
            }

            // 首次 transform，还是 retransform（动态增强）
            if (ConfigManager.FIRST_TRANSFORM.compareAndSet(false, true)) {
                // 初始化依赖组件（如 TraceContext、RecordLimiter、DataCollector 等）
                initDependentComponents();
                // 如果开启 debug，创建字节码 dump 目录
                createDumpDirectory();
                // 执行 transform（首次插桩，抽象方法由子类实现）
                transform();
            } else {
                // 非首次，执行 retransform（动态增强，抽象方法由子类实现）
                retransform();
            }

            // 上报 agent 状态
            ConfigService.INSTANCE.reportStatus();
        } finally {
            // 恢复线程原有的上下文类加载器
            Thread.currentThread().setContextClassLoader(savedContextClassLoader);
        }
    }

    boolean allowStartAgent() {
        if (ConfigManager.INSTANCE.isLocalStorage()) {
            return true;
        }
        return ConfigManager.INSTANCE.isAgentEnabled();
    }

    String getInvalidReason() {
        if (!ConfigManager.INSTANCE.isAgentEnabled()) {
            return ConfigManager.INSTANCE.getMessage();
        }

        return "invalid config";
    }

    /**
     * 定时上报 agent 状态，并检测配置变更，支持热更新。
     * 每分钟执行一次：
     * - 上报 agent 状态
     * - 检查配置文件是否有变更，有变更则重新 install
     */
    private void timedReportStatus() {
        if (reportStatusTask != null) {
            return;
        }
        reportStatusTask = TimerService.scheduleAtFixedRate(() -> {
            try {
                ConfigService.INSTANCE.reportStatus();
                // Load agent config according to last modified time
                if (ConfigService.INSTANCE.reloadConfig()) {
                    install();
                }
            } catch (Exception e) {
                LOGGER.error("[AREX] Report status error.", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * 初始化 agent 依赖的核心组件：
     * - TraceContextManager：链路追踪上下文，采集本机 IP
     * - RecordLimiter：采集限流器，结合健康管理
     * - DataCollector：通过 SPI 加载所有数据采集器，注册到 DataService
     */
    private void initDependentComponents() {
        TraceContextManager.init(NetUtils.getIpAddress());
        RecordLimiter.init(HealthManager::acquire);
        initDataCollector();
    }

    /**
     * 通过 SPI 加载所有数据采集器，并注册到 DataService，增强扩展性。
     */
    private void initDataCollector() {
        List<DataCollector> collectorList = ServiceLoader.load(DataCollector.class, getClassLoader());
        DataService.setDataCollector(collectorList);
    }

    /**
     * First transform class
     */
    protected abstract void transform();

    /**
     * Retransform class after dynamic class changed
     */
    protected abstract void retransform();

    @Override
    public ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }

    /**
     * 如果开启 debug，会将所有被增强的字节码 class 文件 dump 到指定目录，便于开发和排查问题。
     */
    private void createDumpDirectory() {
        if (!ConfigManager.INSTANCE.isEnableDebug()) {
            return;
        }

        try {
            File bytecodeDumpPath = new File(agentFile.getParent(), BYTECODE_DUMP_DIR);
            boolean exists = bytecodeDumpPath.exists();
            boolean mkdir = false;
            if (exists) {
                FileUtils.cleanDirectory(bytecodeDumpPath);
            } else {
                mkdir = bytecodeDumpPath.mkdirs();
            }
            if (exists || mkdir) {
                System.setProperty(TypeWriter.DUMP_PROPERTY, bytecodeDumpPath.getPath());
            }
            LOGGER.info("[arex] bytecode dump path exists: {}, mkdir: {}, path: {}", exists, mkdir, bytecodeDumpPath.getPath());
        } catch (Exception e) {
            LOGGER.warn("[arex] Failed to create directory to instrumented bytecode: ", e);
        }
    }
}
