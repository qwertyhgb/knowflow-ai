package io.github.qwertyhgb.knowflow.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档基础信息。
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI knowflowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("KnowFlow API")
                        .description("KnowFlow 企业级 AI 知识库 SaaS 服务接口文档")
                        .version("v1"));
    }
}
