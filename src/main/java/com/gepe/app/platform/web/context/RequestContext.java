package com.gepe.app.platform.web.context;

import com.gepe.app.platform.web.filter.RequestIdFilter;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class RequestContext {

	private RequestContext() {}

	public static UUID getRequestId() {
		RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
		if (!(attrs instanceof ServletRequestAttributes sra)) return null;
		return (UUID) sra.getRequest().getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
	}

	public static UUID getCurrentUserId() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated()) return null;
		if (auth.getPrincipal() instanceof UUID userId) return userId;
		return null;
	}
}
