package com.gepe.app.platform.pagination;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

public final class CursorEncoder {

	private static final String SEPARATOR = "\n";

	private CursorEncoder() {}

	public static String encode(String id, String... sortValues) {
		StringBuilder sb = new StringBuilder();
		for (String sv : sortValues) {
			sb.append(sv).append(SEPARATOR);
		}
		sb.append(id);
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
	}

	public static DecodedCursor decode(String opaque) {
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

	public record DecodedCursor(String id, String[] sortValues) {}

}
