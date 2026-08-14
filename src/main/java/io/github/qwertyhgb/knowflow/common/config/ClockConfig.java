package io.github.qwertyhgb.knowflow.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时钟配置。
 *
 * <p>统一提供 UTC 时钟，业务层通过注入 {@link Clock} 获取当前时间，
 * 避免使用 JVM 默认时区，便于测试时冻结时间。</p>
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
