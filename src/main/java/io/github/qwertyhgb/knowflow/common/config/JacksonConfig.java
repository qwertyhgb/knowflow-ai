package io.github.qwertyhgb.knowflow.common.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.DateTimeFeature;

/**
 * Jackson 序列化配置。
 *
 * <p>将日期时间统一截断为毫秒精度输出，满足 API 时间规范中
 * 「默认精度为毫秒」的要求（Spring Boot 4 默认使用 Jackson 3，
 * 其时间相关特性由 {@link DateTimeFeature} 控制）。</p>
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer jsonMapperBuilderCustomizer() {
        return builder -> builder.enable(DateTimeFeature.TRUNCATE_TO_MSECS_ON_WRITE);
    }
}
