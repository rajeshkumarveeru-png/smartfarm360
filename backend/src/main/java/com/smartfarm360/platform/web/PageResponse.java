package com.smartfarm360.platform.web;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
    public static final int MAX_SIZE = 100;

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        return new PageResponse<>(items, page, size, total, size == 0 ? 0 : (int) ((total + size - 1) / size));
    }

    /** Validates paging parameters; page is zero-based. */
    public static void check(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be ≥ 0 and size between 1 and " + MAX_SIZE);
        }
    }
}
