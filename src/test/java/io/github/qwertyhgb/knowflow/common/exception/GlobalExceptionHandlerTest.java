package io.github.qwertyhgb.knowflow.common.exception;

import io.github.qwertyhgb.knowflow.common.response.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandlerTest.TestController.class)
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturnBusinessErrorWithConfiguredStatus() throws Exception {
        mockMvc.perform(get("/_test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("知识库不存在"));
    }

    @Test
    void shouldReturnForbiddenWhenAccessDenied() throws Exception {
        // @PreAuthorize 校验失败抛出的 AccessDeniedException 子类，应转为 403 + FORBIDDEN。
        mockMvc.perform(get("/_test/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("没有操作权限"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void shouldHideUnexpectedExceptionDetails() throws Exception {
        mockMvc.perform(get("/_test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务器内部错误"));
    }

    @Test
    void shouldReturnValidationMessageForInvalidRequestBody() throws Exception {
        mockMvc.perform(post("/_test/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("名称不能为空"));
    }

    @Test
    void shouldHandleMalformedRequestBody() throws Exception {
        mockMvc.perform(post("/_test/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("请求体格式错误或缺失"));
    }

    @Test
    void shouldKeepNotFoundStatusForUnknownResource() throws Exception {
        mockMvc.perform(get("/_test/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void shouldKeepMethodNotAllowedStatus() throws Exception {
        mockMvc.perform(post("/_test/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void shouldKeepUnsupportedMediaTypeStatus() throws Exception {
        mockMvc.perform(post("/_test/body")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name=knowflow"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @RestController
    public static class TestController {

        @GetMapping("/_test/business")
        public Result<Void> businessError() {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }

        @GetMapping("/_test/denied")
        public Result<Void> denied() {
            throw new AccessDeniedException("权限不足");
        }

        @GetMapping("/_test/unexpected")
        public Result<Void> unexpectedError() {
            throw new IllegalStateException("不应暴露给客户端的内部信息");
        }

        @PostMapping(value = "/_test/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        public Result<Void> validateBody(@Valid @RequestBody TestRequest request) {
            return Result.success();
        }

    }

    public record TestRequest(@NotBlank(message = "名称不能为空") String name) {
    }
}
