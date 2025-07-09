package io.arex.agent.instrumentation;

import io.arex.foundation.logger.AgentLoggerFactory;
import io.arex.foundation.logger.AgentLogger;
import io.arex.inst.extension.ExtensionTransformer;
import io.arex.inst.extension.ModuleInstrumentation;
import io.arex.inst.extension.MethodInstrumentation;
import io.arex.inst.extension.TypeInstrumentation;
import io.arex.agent.bootstrap.InstrumentationHolder;
import io.arex.foundation.config.ConfigManager;
import io.arex.agent.bootstrap.util.CollectionUtil;

import io.arex.inst.extension.matcher.IgnoredRawMatcher;
import io.arex.inst.runtime.model.DynamicClassEntity;
import io.arex.inst.runtime.model.DynamicClassStatusEnum;
import io.arex.agent.bootstrap.util.ServiceLoader;
import java.util.stream.Collectors;

import io.arex.inst.runtime.util.IgnoreUtils;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.dynamic.scaffold.MethodGraph;
import net.bytebuddy.matcher.ElementMatcher;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.*;

@SuppressWarnings("unused")
public class InstrumentationInstaller extends BaseAgentInstaller {
    private static final AgentLogger LOGGER = AgentLoggerFactory.getAgentLogger(InstrumentationInstaller.class);
    private ModuleInstrumentation dynamicModule;
    private ResettableClassFileTransformer resettableClassFileTransformer;

    public InstrumentationInstaller(Instrumentation inst, File agentFile, String agentArgs) {
        super(inst, agentFile, agentArgs);
    }

    /**
     * 首次插桩主流程：
     * 1. 通过 SPI 加载并注册所有 ModuleInstrumentation 插桩模块（如 httpclient、redis、dubbo 等），
     *    对目标类和方法进行字节码增强（Advice）。
     * 2. 通过 SPI 加载并注册所有 ExtensionTransformer 扩展插件，增强扩展性。
     * 3. 打印日志，标记首次插桩完成，便于排查和监控。
     */
    @Override
    protected void transform() {
        // 1. 注册所有 ModuleInstrumentation 插桩模块，返回可重置的 transformer
        resettableClassFileTransformer = install(getAgentBuilder(), false);
        // 2. 注册所有 ExtensionTransformer 扩展插桩
        extensionTransform();
        // 3. 打印日志，标记首次插桩完成
        LOGGER.info("[AREX] Agent first transform class successfully.");
    }

    /**
     * 动态 retransform 主流程：
     * 1. 先重置所有需要还原的类，确保后续增强不会叠加冲突。
     * 2. 获取所有需要 retransform 的动态类（如配置变更、插件热更新等）。
     * 3. 清理无效操作记录，保证增强环境干净。
     * 4. 移除旧的 transformer，避免重复增强。
     * 5. 以 retransform 模式重新注册所有插桩模块，对目标类重新做字节码增强。
     * 6. 打印日志，标记 retransform 完成，便于排查和监控。
     */
    @Override
    protected void retransform() {
        resetClass();

        List<DynamicClassEntity> retransformList = ConfigManager.INSTANCE.getDynamicClassList().stream()
            .filter(item -> DynamicClassStatusEnum.RETRANSFORM == item.getStatus()).collect(Collectors.toList());
        if (CollectionUtil.isEmpty(retransformList)) {
            LOGGER.info("[AREX] No dynamic class need to retransform.");
            return;
        }
        IgnoreUtils.clearInvalidOperation();
        if (resettableClassFileTransformer != null) {
            instrumentation.removeTransformer(resettableClassFileTransformer);
        }
        resettableClassFileTransformer = install(getAgentBuilder(), true);
        LOGGER.info("[AREX] Agent retransform class successfully.");
    }

    private void resetClass() {
        Set<String> resetClassSet = ConfigManager.INSTANCE.getResetClassSet();
        if (CollectionUtil.isEmpty(resetClassSet)) {
            return;
        }
        IgnoreUtils.clearInvalidOperation();
        if (resettableClassFileTransformer != null) {
            // The transformer must be removed before reset will take effect.
            instrumentation.removeTransformer(resettableClassFileTransformer);
        }
        // TODO: optimize reset abstract class
        for (Class<?> clazz : this.instrumentation.getAllLoadedClasses()) {
            if (resetClassSet.contains(clazz.getName())) {
                try {
                    ClassReloadingStrategy.of(this.instrumentation).reset(clazz);
                    LOGGER.info("[arex] retransform reset class successfully, name: {}", clazz.getName());
                } catch (Exception e) {
                    LOGGER.warn("[arex] retransform reset class failed, name: {}", clazz.getName(), e);
                }
            }
        }
    }

    private void extensionTransform() {
        List<ExtensionTransformer> transformers = ServiceLoader.load(ExtensionTransformer.class, getClassLoader());
        for (ExtensionTransformer transformer : transformers) {
            if (disabledModule(transformer.getName())) {
                LOGGER.warn("[arex] filtered disabled instrumentation module: {}", transformer.getName());
                continue;
            }

            if (!transformer.validate()) {
                LOGGER.warn("[arex] filtered invalid instrumentation module: {}", transformer.getName());
                continue;
            }

            LOGGER.info("[arex] first transform instrumentation module: {}", transformer.getName());
            instrumentation.addTransformer(transformer, true);
        }
    }

    // 遍历所有 ModuleInstrumentation 插桩模块，依次注册到 AgentBuilder
    private ResettableClassFileTransformer install(AgentBuilder builder, boolean retransform) {
        List<ModuleInstrumentation> list = ServiceLoader.load(ModuleInstrumentation.class);

        for (ModuleInstrumentation module : list) {
            builder = installModule(builder, module, retransform);
        }

        // 安装到 JVM，返回可重置的 transformer
        return builder.installOn(this.instrumentation);
    }

    // 注册单个模块的所有类型插桩
    private AgentBuilder installModule(AgentBuilder builder, ModuleInstrumentation module, boolean retransform) {
        String moduleName = module.getName();
        // 过滤禁用模块
        if (disabledModule(moduleName)) {
            LOGGER.warn("[arex] filtered disabled instrumentation module: {}", moduleName);
            return builder;
        }

        // 过滤无内容模块
        if (CollectionUtil.isEmpty(module.instrumentationTypes())) {
            LOGGER.warn("[arex] filtered empty instrumentation module: {}", moduleName);
            return builder;
        }

        // 首次注册或 retransformation 场景，注册所有类型插桩
        if (!retransform) {
            LOGGER.info("[arex] first transform instrumentation module: {}", moduleName);
            return installTypes(builder, module, module.instrumentationTypes());
        }

        if (retranformModule(moduleName)) {
            LOGGER.info("[arex] retransform instrumentation module: {}", moduleName);
            return installTypes(builder, module, module.instrumentationTypes());
        }
        return builder;
    }

    // 注册模块下所有类型的插桩
    private AgentBuilder installTypes(AgentBuilder builder, ModuleInstrumentation module, List<TypeInstrumentation> types) {
        for (TypeInstrumentation inst : types) {
            builder = installType(builder, module.matcher(), inst);
        }
        return builder;
    }

    // 注册单个类型的插桩（如某个类的所有方法增强）
    private AgentBuilder installType(AgentBuilder builder, ElementMatcher<ClassLoader> moduleMatcher,
        TypeInstrumentation type) {
        // 1. 通过 matcher 匹配目标类
        AgentBuilder.Identified identified = builder.type(type.matcher(), moduleMatcher);
        // 2. 如果有类级别的 transformer，先应用
        AgentBuilder.Transformer transformer = type.transformer();
        if (transformer != null) {
            identified = identified.transform(transformer);
        }
        // 3. 注册所有方法级别的 Advice
        List<MethodInstrumentation> methodAdvices = type.methodAdvices();
        if (CollectionUtil.isEmpty(methodAdvices)) {
            return (AgentBuilder) identified;
        }
        AgentBuilder.Identified.Extendable extBuilder = installMethod(identified, methodAdvices.get(0));
        for (int i = 1; i < methodAdvices.size(); i++) {
            extBuilder = installMethod(extBuilder, methodAdvices.get(i));
        }
        return extBuilder;
    }

    // 注册方法级别的 Advice（增强逻辑）
    private AgentBuilder.Identified.Extendable installMethod(AgentBuilder.Identified builder,
        MethodInstrumentation method) {
        // 注意：
        // 1. 虽然 AgentClassLoader 的父加载器是 AppClassLoader，按双亲委派模型 AppClassLoader 加载不到 agent 的类。
        // 2. 但在注册 Advice 时，agent/ByteBuddy 会通过 include(AgentClassLoader) 显式指定 Advice 类的加载器。
        // 3. 这样，JVM 在执行 Advice 相关字节码时，会直接用 AgentClassLoader 去加载 Advice 类，
        //    而不是只依赖目标类的 classloader（如 AppClassLoader）。
        // 4. 这打破了传统的父子委派限制，实现了 agent 逻辑和业务代码的解耦与隔离。
        return builder.transform(new AgentBuilder.Transformer.ForAdvice()
                        .include(InstrumentationHolder.getAgentClassLoader())
                        .advice(method.getMethodMatcher(), method.getAdviceClassName())
                        .withExceptionHandler(Advice.ExceptionHandler.Default.PRINTING));
    }


    private AgentBuilder getAgentBuilder() {
        // config may use to add some classes to be ignored in future
        long buildBegin = System.currentTimeMillis();

        return new AgentBuilder.Default(
                new ByteBuddy().with(MethodGraph.Compiler.ForDeclaredMethods.INSTANCE))
            .enableNativeMethodPrefix("arex_")
            .disableClassFormatChanges()
            .ignore(new IgnoredRawMatcher(ConfigManager.INSTANCE.getIgnoreTypePrefixes(),
                ConfigManager.INSTANCE.getIgnoreClassLoaderPrefixes()))
            .with(new TransformListener())
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            .with(AgentBuilder.TypeStrategy.Default.REBASE)
             // https://github.com/raphw/byte-buddy/issues/1441
            .with(AgentBuilder.DescriptionStrategy.Default.POOL_FIRST)
            .with(AgentBuilder.LocationStrategy.ForClassLoader.STRONG
                .withFallbackTo(ClassFileLocator.ForClassLoader.ofSystemLoader()));
    }

    private boolean disabledModule(String moduleName) {
        return ConfigManager.INSTANCE.getDisabledModules().contains(moduleName);
    }

    private boolean retranformModule(String moduleName) {
        return ConfigManager.INSTANCE.getRetransformModules().contains(moduleName);
    }
}
