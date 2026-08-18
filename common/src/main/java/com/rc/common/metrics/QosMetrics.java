package com.rc.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.util.function.Supplier;

/**
 * 全局 QoS 指标门面：基于 Micrometer {@link Metrics#globalRegistry}，供 client / relay /
 * signaling 三端统一上报标准化指标，屏蔽「是否依赖 Spring / Micrometer 注册表注入」的差异。
 *
 * <p>指标名统一取自 {@link QosMetricNames}；tag 键约定 {@code path}、{@code protocol}、
 * {@code from}、{@code to}。client 为纯 Netty + JavaFX 进程，经本门面写全局注册表即可；
 * server-relay 额外挂 Prometheus 抓取端点；server-signaling 由 Spring Boot actuator 暴露
 * {@code /actuator/prometheus}，其自动装配的 {@link io.micrometer.prometheus.PrometheusMeterRegistry}
 * 会被 Spring 加入全局注册表，故三方指标最终统一被 Prometheus 抓取。</p>
 */
public final class QosMetrics {

    private QosMetrics() {
    }

    /** 全局注册表（Spring Boot 环境为复合注册表，含 Prometheus；纯 Java 进程为默认简单注册表）。 */
    public static MeterRegistry registry() {
        return Metrics.globalRegistry;
    }

    /** 计数指标。 */
    public static Counter counter(String name, String... keyValueTags) {
        return Metrics.counter(name, keyValueTags);
    }

    /** 计时指标。 */
    public static Timer timer(String name, String... keyValueTags) {
        return Metrics.timer(name, keyValueTags);
    }

    /** 即时值指标（以 {@code supplier} 惰性求值，每次抓取反映当前值），写入全局注册表。 */
    public static void gauge(String name, Supplier<Number> supplier, String... keyValueTags) {
        Gauge.builder(name, supplier).tags(keyValueTags).register(Metrics.globalRegistry);
    }

    /** 即时值指标，写入指定注册表（Spring Boot 环境注入其复合注册表，保证被 actuator 抓取）。 */
    public static void gauge(MeterRegistry registry, String name, Supplier<Number> supplier, String... keyValueTags) {
        Gauge.builder(name, supplier).tags(keyValueTags).register(registry);
    }

    /** 计数 +1。 */
    public static void increment(String name, String... keyValueTags) {
        counter(name, keyValueTags).increment();
    }

    /** 计数 +delta。 */
    public static void increment(String name, double delta, String... keyValueTags) {
        counter(name, keyValueTags).increment(delta);
    }
}
