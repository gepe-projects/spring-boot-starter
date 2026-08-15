package com.gepe.app.platform.web.pagination;

import java.util.Collections;
import java.util.List;

public record CursorPage<T>(List<T> items, String nextCursor, boolean hasNext) {

	public CursorPage {
		items = Collections.unmodifiableList(items);
	}

	public static <T> CursorPage<T> empty() {
		return new CursorPage<>(List.of(), null, false);
	}

}
