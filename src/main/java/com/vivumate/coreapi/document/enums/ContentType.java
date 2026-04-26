package com.vivumate.coreapi.document.enums;

/**
 * Polymorphic content type for messages.
 * Determines the shape of the embedded {@code content} sub-document.
 */
public enum ContentType {
    TEXT,
    IMAGE,
    FILE,
    VIDEO,
    AUDIO,
    LINK_PREVIEW,
    SYSTEM
}
