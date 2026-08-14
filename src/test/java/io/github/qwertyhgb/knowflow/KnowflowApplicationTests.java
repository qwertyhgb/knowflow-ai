package io.github.qwertyhgb.knowflow;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
class KnowflowApplicationTests {

    @Autowired
    private OpenAPI openApi;

    @Test
    void contextLoads() {
    }

    @Test
    void shouldConfigureOpenApiBasicInformation() {
        assertNotNull(openApi.getInfo());
        assertEquals("KnowFlow API", openApi.getInfo().getTitle());
        assertEquals("KnowFlow Java 后端学习项目接口文档", openApi.getInfo().getDescription());
        assertEquals("v1", openApi.getInfo().getVersion());
    }

}
