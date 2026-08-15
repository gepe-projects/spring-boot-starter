package com.gepe.app.platform.web.pagination;

import com.gepe.app.platform.exception.PlatformError;
import com.gepe.app.platform.exception.ServiceException;
import java.time.Instant;
import java.util.UUID;

/**
 * Batas keyset hasil resolve cursor pagination: pasangan {@code (sortValue, id)} yang
 * dipakai sebagai "setelah baris ini" pada query {@code where (sort_col < :sortValue
 * or (sort_col = :sortValue and id < :id))} — lihat AGENTS.md §4.
 *
 * <p>Kontrak pembanding:
 * <ul>
 *   <li><b>sortValue</b> — selalu {@link Instant} (kolom {@code createdAt} /
 *       {@code updatedAt} / dst, bebas dipilih service lewat getter). Wajib non-null
 *       di entity, karena keyset comparison meng-exclude NULL.</li>
 *   <li><b>id</b> — tiebreaker deterministik, tipe bebas sesuai {@code idType}:
 *       {@link UUID}, {@link Long} (bigint), {@link Integer}, {@link String}.</li>
 * </ul>
 *
 * <p>Cursor bersifat opaque: di-encode base64url oleh {@link CursorEncoder},
 * di-decode kembali di sini. Cursor kosong/blank = halaman pertama → sentinel
 * {@code Instant.now()} + nilai id maksimum untuk tipe-nya.
 */
public record CursorBounds<ID>(Instant sortValue, ID id) {

	private static final UUID MAX_UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
	private static final String MAX_STRING = "\uFFFF\uFFFF";

	/**
	 * Resolve cursor opaque menjadi bounds keyset bertipe {@code idType}.
	 *
	 * <p>Cursor null/blank → bounds halaman pertama. Cursor rusak (base64 invalid,
	 * jumlah sort value ≠ 1, angka/UUID tidak valid) → {@link ServiceException}
	 * dengan {@link PlatformError#INVALID_CURSOR}.
	 *
	 * @param cursor cursor opaque dari request; null/blank = halaman pertama
	 * @param idType tipe id (UUID, Long, Integer, atau String)
	 * @throws IllegalArgumentException bila {@code idType} tidak didukung
	 */
	public static <ID> CursorBounds<ID> resolve(String cursor, Class<ID> idType) {
		if (cursor == null || cursor.isBlank()) {
			return new CursorBounds<>(Instant.now(), maxId(idType));
		}
		CursorEncoder.DecodedCursor decoded;
		try {
			decoded = CursorEncoder.decode(cursor);
		} catch (RuntimeException e) {
			throw invalidCursor();
		}
		if (decoded == null || decoded.sortValues().length != 1) {
			throw invalidCursor();
		}
		try {
			Instant sortValue = Instant.ofEpochMilli(Long.parseLong(decoded.sortValues()[0]));
			return new CursorBounds<>(sortValue, parseId(decoded.id(), idType));
		} catch (RuntimeException e) {
			throw invalidCursor();
		}
	}

	private static ServiceException invalidCursor() {
		return new ServiceException(PlatformError.INVALID_CURSOR);
	}

	private static <ID> ID maxId(Class<ID> idType) {
		if (idType == UUID.class) {
			return idType.cast(MAX_UUID);
		}
		if (idType == Long.class) {
			return idType.cast(Long.MAX_VALUE);
		}
		if (idType == Integer.class) {
			return idType.cast(Integer.MAX_VALUE);
		}
		if (idType == String.class) {
			return idType.cast(MAX_STRING);
		}
		throw new IllegalArgumentException("Unsupported id type for cursor bounds: " + idType.getName());
	}

	private static <ID> ID parseId(String raw, Class<ID> idType) {
		if (idType == UUID.class) {
			return idType.cast(UUID.fromString(raw));
		}
		if (idType == Long.class) {
			return idType.cast(Long.parseLong(raw));
		}
		if (idType == Integer.class) {
			return idType.cast(Integer.parseInt(raw));
		}
		if (idType == String.class) {
			return idType.cast(raw);
		}
		throw new IllegalArgumentException("Unsupported id type for cursor bounds: " + idType.getName());
	}
}
