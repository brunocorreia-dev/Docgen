package com.docgen;

import java.util.Locale;
import java.util.Objects;

/** One message of a conversation sent to {@code /api/chat}. */
public record ChatMessage(Role role, String content) {
    public enum Role {
        SYSTEM, USER, ASSISTANT;

        String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }
}
