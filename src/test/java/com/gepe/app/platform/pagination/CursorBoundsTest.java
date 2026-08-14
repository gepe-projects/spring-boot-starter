package com.gepe.app.platform.pagination;

import com.gepe.app.platform.exception.PlatformError;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.support.Uuidv7;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorBoundsTest {

    @Test
    void blankCursorResolvesToFirstPageBounds() {
        CursorBounds<UUID> bounds = CursorBounds.resolve(null, UUID.class);

        assertThat(bounds.sortValue()).isNotNull();
        assertThat(bounds.id()).isEqualTo(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
    }

    @Test
    void encodedCursorRoundTrips() {
        Instant sortValue = Instant.parse("2025-01-02T03:04:05.123Z");
        UUID id = Uuidv7.generate();

        String cursor = CursorEncoder.encode(sortValue, id);
        CursorBounds<UUID> bounds = CursorBounds.resolve(cursor, UUID.class);

        assertThat(bounds.sortValue()).isEqualTo(sortValue);
        assertThat(bounds.id()).isEqualTo(id);
    }

    @Test
    void supportsLongId() {
        String cursor = CursorEncoder.encode(Instant.parse("2025-01-02T03:04:05Z"), 42L);

        CursorBounds<Long> bounds = CursorBounds.resolve(cursor, Long.class);

        assertThat(bounds.sortValue()).isEqualTo(Instant.parse("2025-01-02T03:04:05Z"));
        assertThat(bounds.id()).isEqualTo(42L);
    }

    @Test
    void supportsIntegerId() {
        String cursor = CursorEncoder.encode(Instant.parse("2025-01-02T03:04:05Z"), 7);

        CursorBounds<Integer> bounds = CursorBounds.resolve(cursor, Integer.class);

        assertThat(bounds.id()).isEqualTo(7);
    }

    @Test
    void rejectsGarbageCursor() {
        assertThatThrownBy(() -> CursorBounds.resolve("!!!not-base64!!!", UUID.class))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PlatformError.INVALID_CURSOR));
    }

    @Test
    void rejectsCursorWithoutSortValue() {
        String raw = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Uuidv7.generate().toString().getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> CursorBounds.resolve(raw, UUID.class))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PlatformError.INVALID_CURSOR));
    }

    @Test
    void rejectsCursorWithBadUuid() {
        String raw = "1756771200000\nnot-a-uuid";
        String bad = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> CursorBounds.resolve(bad, UUID.class))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PlatformError.INVALID_CURSOR));
    }

    @Test
    void rejectsCursorWithNonNumericSortValue() {
        String raw = "not-a-number\n" + Uuidv7.generate();
        String bad = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> CursorBounds.resolve(bad, UUID.class))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PlatformError.INVALID_CURSOR));
    }
}
