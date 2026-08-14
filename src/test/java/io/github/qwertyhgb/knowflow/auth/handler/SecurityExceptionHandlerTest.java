package io.github.qwertyhgb.knowflow.auth.handler;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityExceptionHandlerTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void shouldWriteUnifiedUnauthorizedResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAuthenticationEntryPoint(jsonMapper).commence(
                new MockHttpServletRequest(), response,
                new InsufficientAuthenticationException("authentication required"));

        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertEquals(401, response.getStatus());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(
                MediaType.parseMediaType(response.getContentType())));
        assertEquals("UNAUTHORIZED", body.get("code").asString());
        assertEquals("未认证或登录已过期", body.get("message").asString());
    }

    @Test
    void shouldWriteUnifiedForbiddenResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAccessDeniedHandler(jsonMapper).handle(
                new MockHttpServletRequest(), response,
                new AccessDeniedException("access denied"));

        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertEquals(403, response.getStatus());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(
                MediaType.parseMediaType(response.getContentType())));
        assertEquals("FORBIDDEN", body.get("code").asString());
        assertEquals("没有操作权限", body.get("message").asString());
    }
}
