package com.gepe.app.platform.pagination;

public record CursorPageRequest(String cursor, int limit) {

	private static final int DEFAULT_LIMIT = 20;
	private static final int MAX_LIMIT = 100;

	public CursorPageRequest {
		if (limit <= 0) {
			limit = DEFAULT_LIMIT;
		}
		if (limit > MAX_LIMIT) {
			limit = MAX_LIMIT;
		}
	}

	public static CursorPageRequest of(String cursor, int limit) {
		return new CursorPageRequest(cursor, limit);
	}

	public CursorEncoder.DecodedCursor decodedCursor() {
		return CursorEncoder.decode(cursor);
	}

}
