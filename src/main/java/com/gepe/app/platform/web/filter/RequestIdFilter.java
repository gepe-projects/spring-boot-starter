package com.gepe.app.platform.web.filter;

import com.gepe.app.platform.support.Uuidv7;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String REQUEST_ID_ATTR = "requestId";
	public static final String REQUEST_ID_HEADER = "X-Request-Id";

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		UUID requestId = parseOrGenerate(request.getHeader(REQUEST_ID_HEADER));

		request.setAttribute(REQUEST_ID_ATTR, requestId);
		MDC.put(REQUEST_ID_ATTR, requestId.toString());
		response.setHeader(REQUEST_ID_HEADER, requestId.toString());

		try {
			filterChain.doFilter(request, response);
		} finally {
			MDC.remove(REQUEST_ID_ATTR);
		}
	}

	private UUID parseOrGenerate(String value) {
		if (value == null || value.isBlank()) {
			return Uuidv7.generate();
		}
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			return Uuidv7.generate();
		}
	}
}
