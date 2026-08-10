package com.gepe.app.platform.config.i18n;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MessageHelper {

	private final MessageSource messageSource;

	public String get(String key, Object... args) {
		return messageSource.getMessage(
				key, args, key, LocaleContextHolder.getLocale());
	}
}
