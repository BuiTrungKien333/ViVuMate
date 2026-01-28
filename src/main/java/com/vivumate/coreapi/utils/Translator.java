package com.vivumate.coreapi.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class Translator {

    private final MessageSource messageSource;

    public String toLocale(String msgCode) {
        return messageSource.getMessage(
                msgCode,
                null,
                LocaleContextHolder.getLocale()
        );
    }
}
