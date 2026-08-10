package com.gepe.app.auth.internal.dto;

import java.util.List;

public record SessionPage(List<SessionInfo> items, String nextCursor) {
}
