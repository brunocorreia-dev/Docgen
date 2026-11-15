package com.docgen.desktop;

import java.util.List;

/**
 * One authenticated protocol line: an event (API to launcher, on stdout) or a
 * command (launcher to API, on stdin).
 */
public record DesktopMessage(Direction direction, long sequence, String type, List<String> fields) {
    public enum Direction {
        EVENT("E"),
        COMMAND("C");

        private final String code;

        Direction(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    public DesktopMessage {
        fields = List.copyOf(fields);
        if (direction == null || sequence <= 0 || type == null || !DesktopProtocol.TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("invalid message header");
        }
        for (String field : fields) {
            if (!DesktopProtocol.FIELD.matcher(field).matches()) {
                throw new IllegalArgumentException("invalid message field");
            }
        }
    }

    public String field(int index) {
        return fields.get(index);
    }
}
