package com.gepe.app.platform.pagination;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * Codec cursor pagination (opaque, base64url). Format wire:
 * {@code base64url("<sortValue>\n<id>")} — sort value terlebih dulu, id di akhir
 * (tiebreaker). {@link CursorBounds} memakai ini untuk resolve, {@link CursorPages}
 * untuk membangun {@code nextCursor}.
 */
public final class CursorEncoder {

	private static final String SEPARATOR = "\n";

	private CursorEncoder() {}

	/** Encode pasangan (sortValue, id) → cursor opaque. Wire-format sama dengan lama. */
	public static String encode(Instant sortValue, Object id) {
		return encode(stringify(id), String.valueOf(sortValue.toEpochMilli()));
	}

	/**
	 * Encode rendah-level: {@code sortValues} dulu, {@code id} terakhir.
	 * Package-private — pemakaian luar harus lewat {@link #encode(Instant, Object)}.
	 */
	static String encode(String id, String... sortValues) {
		StringBuilder sb = new StringBuilder();
		for (String sv : sortValues) {
			sb.append(sv).append(SEPARATOR);
		}
		sb.append(id);
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Decode cursor opaque → bagian mentahnya. Mengembalikan {@code null} untuk
	 * input blank; melempar {@link IllegalArgumentException} untuk base64 rusak.
	 * Package-private — dipakai {@link CursorBounds}.
	 */
	static DecodedCursor decode(String opaque) {
		if (opaque == null || opaque.isBlank()) {
			return null;
		}
		String decoded = new String(
				Base64.getUrlDecoder().decode(opaque), StandardCharsets.UTF_8);
		String[] parts = decoded.split(SEPARATOR);
		String id = parts[parts.length - 1];
		String[] sortValues = Arrays.copyOf(parts, parts.length - 1);
		return new DecodedCursor(id, sortValues);
	}

	private static String stringify(Object value) {
		if (value instanceof Instant instant) {
			return String.valueOf(instant.toEpochMilli());
		}
		return String.valueOf(value);
	}

	record DecodedCursor(String id, String[] sortValues) {}

}
