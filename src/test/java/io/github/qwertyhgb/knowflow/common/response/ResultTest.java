package io.github.qwertyhgb.knowflow.common.response;

import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResultTest {

    @Test
    void shouldCreateSuccessResult() {
        Result<String> result = Result.success("knowledge");

        assertEquals("SUCCESS", result.getCode());
        assertEquals("success", result.getMessage());
        assertEquals("knowledge", result.getData());
    }

    @Test
    void shouldCreateFailureResult() {
        Result<Void> result = Result.failure(ErrorCode.NOT_FOUND, "知识库不存在");

        assertEquals("NOT_FOUND", result.getCode());
        assertEquals("知识库不存在", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void shouldRejectNullErrorCodeAndMessage() {
        assertThrows(NullPointerException.class, () -> Result.failure(null));
        assertThrows(NullPointerException.class, () -> Result.failure(ErrorCode.NOT_FOUND, null));
        assertThrows(NullPointerException.class, () -> new BusinessException(null));
        assertThrows(NullPointerException.class, () -> new BusinessException(ErrorCode.NOT_FOUND, null));
    }
}
