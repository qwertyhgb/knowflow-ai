package io.github.qwertyhgb.knowflow.common.response;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageResultTest {

    @Test
    void shouldCreatePageResultWithNavigationMetadata() {
        PageResult<String> result = PageResult.of(List.of("a", "b"), 25, 2, 10);

        assertEquals(List.of("a", "b"), result.getRecords());
        assertEquals(25, result.getTotal());
        assertEquals(2, result.getPageNum());
        assertEquals(10, result.getPageSize());
        assertEquals(3, result.getTotalPages());
        assertTrue(result.isHasNext());
        assertTrue(result.isHasPrevious());
    }

    @Test
    void shouldCreateEmptyFirstPage() {
        PageResult<Object> result = PageResult.empty(1, 20);

        assertTrue(result.getRecords().isEmpty());
        assertEquals(0, result.getTotal());
        assertEquals(0, result.getTotalPages());
        assertFalse(result.isHasNext());
        assertFalse(result.isHasPrevious());
    }

    @Test
    void shouldDefensivelyCopyRecords() {
        List<String> records = new ArrayList<>(List.of("first"));
        PageResult<String> result = PageResult.of(records, 1, 1, 10);
        records.add("later");

        assertEquals(List.of("first"), result.getRecords());
        assertThrows(UnsupportedOperationException.class, () -> result.getRecords().add("other"));
    }

    @Test
    void shouldRejectInvalidPaginationMetadata() {
        assertThrows(IllegalArgumentException.class, () -> PageResult.of(List.of(), -1, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> PageResult.of(List.of(), 0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> PageResult.of(List.of(), 0, 1, 0));
        assertThrows(NullPointerException.class, () -> PageResult.of(null, 0, 1, 10));
    }
}
