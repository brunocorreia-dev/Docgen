package com.docgen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reasoning written inline by the model is moved out of the answer (ROADMAP §6.4). */
class ThinkingFilterTest {
    private record Split(String answer, String reasoning) {
    }

    private static Split run(List<String> chunks) {
        StringBuilder answer = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ThinkingFilter filter = new ThinkingFilter(new ThinkingFilter.Sink() {
            @Override
            public void answer(String text) {
                answer.append(text);
            }

            @Override
            public void reasoning(String text) {
                reasoning.append(text);
            }
        });
        chunks.forEach(filter::accept);
        filter.finish();
        return new Split(answer.toString(), reasoning.toString());
    }

    @Test
    void answersWithoutReasoningPassUntouched() {
        assertEquals(new Split("  # Título\n\nTexto.", ""), run(List.of("  # Tí", "tulo\n\nTexto.")));
        assertEquals(new Split("<thought> is not a think tag", ""), run(List.of("<thought> is not a think tag")));
    }

    @Test
    void aLeadingBlockGoesToReasoningEvenWhenSplitCharacterByCharacter() {
        String text = "\n<think>\nPlanejando a resposta.\n</think>\n\n# README\n";
        List<String> characters = text.chars().mapToObj(code -> String.valueOf((char) code)).toList();

        assertEquals(new Split("# README\n", "\nPlanejando a resposta.\n"), run(characters));
        assertEquals(new Split("# README\n", "\nPlanejando a resposta.\n"), run(List.of(text)));
    }

    @Test
    void aBlockLaterInTheAnswerIsTheAnswersOwnText() {
        assertEquals(new Split("Use <think>...</think> tags.", ""), run(List.of("Use <think>...</think> tags.")));
    }

    @Test
    void anUnclosedBlockIsAllReasoning() {
        assertEquals(new Split("", "never finished </thi"), run(List.of("<think>never finished </thi")));
    }

    @Test
    void stripRemovesOnlyTheLeadingBlock() {
        assertEquals("# Doc", ThinkingFilter.strip("<think>x</think>\n# Doc"));
        assertEquals("# Doc", ThinkingFilter.strip("# Doc"));
        assertEquals("", ThinkingFilter.strip(""));
    }
}
