package com.gepe.app.platform.support;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

/**
 * Central factory for UUID generation. The project mandate is: ALL UUIDs are UUID v7
 * (time-ordered, so database index-friendly). Modules MUST NOT call {@link UUID#randomUUID()}
 * (v4) directly — use this helper instead.
 */
public final class Uuidv7 {

	private Uuidv7() {}

	public static UUID generate() {
		return UuidCreator.getTimeOrderedEpoch();
	}
}
