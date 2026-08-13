package io.github.qwertyhgb.knowflow.common.exception;

import io.github.qwertyhgb.knowflow.common.response.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class)
@Import(GlobalExceptionHandlerTest.TestController.class)
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
    void shouldReturnValidationMessageForInvalidMethodParameter() throws Exception {
        mockMvc.perform(get("/_test/page").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("页码不能小于 1"));
    }

    @Test
    void shouldHandleParameterTypeMismatch() throws Exception {
        mockMvc.perform(get("/_test/page").param("page", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("请求参数类型错误"));
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
    void shouldIdentifyMissingRequestParameter() throws Exception {
        mockMvc.perform(get("/_test/page"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("缺少请求参数：page"));
    }

    @Test
    void shouldHandleMissingRequestHeader() throws Exception {
        mockMvc.perform(get("/_test/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("请求参数缺失或无效"));
    }

    @Test
    void shouldKeepNotFoundStatusForUnknownResource() throws Exception {
        mockMvc.perform(get("/_test/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void shouldKeepMethodNotAllowedStatus() throws Exception {
        mockMvc.perform(post("/_test/header"))
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

    @Test
    void shouldKeepNotAcceptableStatusWithoutForcingJsonBody() throws Exception {
        mockMvc.perform(get("/_test/header")
                        .header("X-Tenant-Id", "tenant-1")
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable());
    }

    @RestController
    public static class TestController {

        @GetMapping("/_test/business")
        public Result<Void> businessError() {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }

        @GetMapping("/_test/unexpected")
        public Result<Void> unexpectedError() {
            throw new IllegalStateException("不应暴露给客户端的内部信息");
        }

        @PostMapping(value = "/_test/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        public Result<Void> validateBody(@Valid @RequestBody TestRequest request) {
            return Result.success();
        }

        @GetMapping("/_test/page")
        public Result<Void> validatePage(
                @RequestParam @Min(value = 1, message = "页码不能小于 1") Integer page) {
            return Result.success();
        }

        @GetMapping("/_test/header")
        public Result<Void> requireHeader(@RequestHeader("X-Tenant-Id") String tenantId) {
            return Result.success();
        }
    }

    public record TestRequest(@NotBlank(message = "名称不能为空") String name) {
    }
}
