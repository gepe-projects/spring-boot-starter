package com.gepe.app.platform.web.pagination;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.PageRequest;

/**
 * Perakitan halaman keyset — bagian yang sama di setiap service list, dipindah ke sini
 * supaya service tinggal: resolve bounds → query repository → {@link #fromRows}.
 *
 * <p>Kontrak (AGENTS.md §4): query memakai {@link #lookaheadPageable} (fetch
 * {@code pageSize+1} untuk deteksi {@code hasMore}), dan {@code nextCursor} dibangun
 * dari baris terakhir halaman lewat {@link CursorEncoder} (opaque, base64url).
 */
public final class CursorPages {

	/** Batas maksimum ukuran halaman yang boleh diminta client — satu-satunya sumber kebenaran. */
	public static final int MAX_PAGE_SIZE = 100;

	private CursorPages() {}

	/**
	 * Clamp {@code limit} dari client ke rentang {@code [1, MAX_PAGE_SIZE]}.
	 * Setiap service list wajib lewat sini — jangan menulis konstanta sendiri.
	 */
	public static int clampPageSize(int limit) {
		return Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
	}

	/**
	 * Pageable yang mengambil {@code pageSize + 1} baris — baris ekstra dipakai
	 * hanya untuk tahu apakah masih ada halaman berikutnya.
	 */
	public static PageRequest lookaheadPageable(int pageSize) {
		return PageRequest.of(0, pageSize + 1);
	}

	/**
	 * Rakit {@link CursorPage} dari hasil query keyset.
	 *
	 * @param rows      baris hasil repository (sudah difetch {@code pageSize + 1})
	 * @param pageSize  ukuran halaman sebenarnya (tanpa baris ekstra)
	 * @param sortValue ekstraktor kolom sort ({@code createdAt}/{@code updatedAt}/dst — bebas)
	 * @param id        ekstraktor id (UUID/bigint/int — bebas)
	 * @param toDto     mapping entity → DTO
	 */
	public static <T, D, ID> CursorPage<D> fromRows(
			List<T> rows,
			int pageSize,
			Function<T, Instant> sortValue,
			Function<T, ID> id,
			Function<T, D> toDto) {
		boolean hasMore = rows.size() > pageSize;
		List<T> page = hasMore ? rows.subList(0, pageSize) : rows;
		List<D> items = page.stream().map(toDto).toList();

		String nextCursor = null;
		if (hasMore) {
			T last = page.get(page.size() - 1);
			nextCursor = CursorEncoder.encode(sortValue.apply(last), id.apply(last));
		}
		return new CursorPage<>(items, nextCursor, hasMore);
	}
}
