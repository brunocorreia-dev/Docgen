package com.docgen.launcher;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlatJsonTest {
    @Test
    void parsesFlatStringObjects() {
        assertEquals(Map.of("protocol", "docgen-desktop/1", "proof", "abc"),
                FlatJson.parseObject(" {\"protocol\" : \"docgen-desktop/1\",\n\"proof\":\"abc\"} "));
        assertEquals(Map.of(), FlatJson.parseObject("{}"));
        assertEquals(Map.of("k", "a\"b\\c/\u00e9\n"), FlatJson.parseObject("{\"k\":\"a\\\"b\\\\c\\/\\u00e9\\n\"}"));
    }

    @Test
    void rejectsEverythingElse() {
        for (String json : new String[]{
                null, "", "[]", "{", "{\"a\":1}", "{\"a\":true}", "{\"a\":null}", "{\"a\":{\"b\":\"c\"}}",
                "{\"a\":\"b\"} trailing", "{\"a\":\"b\",}", "{\"a\":\"b\" \"c\":\"d\"}", "{\"a\":\"b\",\"a\":\"c\"}",
                "{\"a\":\"\\x\"}", "{\"a\":\"\\u12\"}", "{\"a\":\"line\nbreak\"}", "{a:\"b\"}", "{\"a\":\"b\"}{}",
                "{\"a\":\"" + "x".repeat(FlatJson.MAX_LENGTH) + "\"}"}) {
            assertThrows(IllegalArgumentException.class, () -> FlatJson.parseObject(json), String.valueOf(json));
        }
    }
}
