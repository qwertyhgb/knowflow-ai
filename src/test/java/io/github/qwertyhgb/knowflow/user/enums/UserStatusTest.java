package io.github.qwertyhgb.knowflow.user.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserStatusTest {

    @Test
    void shouldMatchDatabaseStatusCodes() {
        assertEquals(1, UserStatus.NORMAL.getCode());
        assertEquals(0, UserStatus.DISABLED.getCode());
    }
}
