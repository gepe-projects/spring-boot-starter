package com.gepe.app.platform.pagination;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CursorPagesTest {

    private record Row(String id, Instant createdAt, String name) {}

    private static Row row(String id, Instant createdAt, String name) {
        return new Row(id, createdAt, name);
    }

    @Test
    void pageBuildsNextCursorFromLastRowWhenHasMore() {
        List<Row> rows = List.of(
                row("a", Instant.parse("2025-01-03T00:00:00Z"), "A"),
                row("b", Instant.parse("2025-01-02T00:00:00Z"), "B"),
                row("c", Instant.parse("2025-01-01T00:00:00Z"), "C"));

        CursorPage<String> page = CursorPages.page(rows, 2, Row::createdAt, Row::id, Row::name);

        assertThat(page.items()).containsExactly("A", "B");
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();

        // nextCursor harus menunjuk ke baris terakhir halaman (keyset "setelah" baris ini)
        CursorBounds<String> bounds = CursorBounds.resolve(page.nextCursor(), String.class);
        assertThat(bounds.sortValue()).isEqualTo(Instant.parse("2025-01-02T00:00:00Z"));
        assertThat(bounds.id()).isEqualTo("b");
    }

    @Test
    void pageWithoutMoreHasNoNextCursor() {
        List<Row> rows = List.of(
                row("a", Instant.parse("2025-01-03T00:00:00Z"), "A"),
                row("b", Instant.parse("2025-01-02T00:00:00Z"), "B"));

        CursorPage<String> page = CursorPages.page(rows, 2, Row::createdAt, Row::id, Row::name);

        assertThat(page.items()).containsExactly("A", "B");
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void emptyPageHasNoNextCursor() {
        CursorPage<String> page = CursorPages.page(List.of(), 2, Row::createdAt, Row::id, Row::name);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void pageableFetchesOneExtraRow() {
        assertThat(CursorPages.pageable(2).getPageSize()).isEqualTo(3);
        assertThat(CursorPages.pageable(2).getPageNumber()).isZero();
    }
}
