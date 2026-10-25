package com.docgen;

/**
 * Keeps a model's reasoning apart from its answer (ROADMAP §6.4). Ollama puts
 * the reasoning of models it recognizes in a separate {@code thinking} field;
 * a model or template it does not recognize can still write it inline, as a
 * {@code <think>…</think>} block at the start of the content (the Qwen3
 * format). This filter moves such a block to the reasoning channel, also when
 * the tags arrive split across stream chunks, so reasoning never reaches the
 * documentation validator, the plan parser or the screen as part of the answer.
 *
 * <p>Only a block at the very start counts (leading whitespace aside): a
 * {@code <think>} later in an answer is the answer's own text.</p>
 */
final class ThinkingFilter {
    static final String OPEN = "<think>";
    static final String CLOSE = "</think>";

    /** Where filtered text goes. */
    interface Sink {
        void answer(String text);

        void reasoning(String text);
    }

    /** {@code AFTER_REASONING}: the block closed; whitespace before the answer is dropped, wherever it arrives. */
    private enum State { START, REASONING, AFTER_REASONING, ANSWER }

    private final Sink sink;
    private final StringBuilder pending = new StringBuilder();
    private State state = State.START;

    ThinkingFilter(Sink sink) {
        this.sink = sink;
    }

    /** Removes a leading reasoning block from a complete answer. */
    static String strip(String text) {
        StringBuilder answer = new StringBuilder();
        ThinkingFilter filter = new ThinkingFilter(new Sink() {
            @Override
            public void answer(String part) {
                answer.append(part);
            }

            @Override
            public void reasoning(String part) {
                // Discarded: reasoning is never part of an answer.
            }
        });
        filter.accept(text);
        filter.finish();
        return answer.toString();
    }

    void accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        if (state == State.ANSWER) {
            sink.answer(chunk);
            return;
        }
        if (state == State.AFTER_REASONING) {
            String answer = chunk.stripLeading();
            if (!answer.isEmpty()) {
                state = State.ANSWER;
                sink.answer(answer);
            }
            return;
        }
        pending.append(chunk);
        if (state == State.START) {
            String lead = pending.toString().stripLeading();
            if (lead.isEmpty() || OPEN.startsWith(lead) && lead.length() < OPEN.length()) {
                return;
            }
            if (!lead.startsWith(OPEN)) {
                state = State.ANSWER;
                flushAnswer();
                return;
            }
            state = State.REASONING;
            pending.setLength(0);
            pending.append(lead, OPEN.length(), lead.length());
        }
        int close = pending.indexOf(CLOSE);
        if (close >= 0) {
            emitReasoning(pending.substring(0, close));
            String rest = pending.substring(close + CLOSE.length());
            pending.setLength(0);
            state = State.AFTER_REASONING;
            accept(rest);
            return;
        }
        int keep = partialCloseSuffix();
        emitReasoning(pending.substring(0, pending.length() - keep));
        pending.delete(0, pending.length() - keep);
    }

    /** Ends the stream: text still held back is released to its channel. */
    void finish() {
        if (state == State.REASONING) {
            emitReasoning(pending.toString());
        } else {
            flushAnswer();
        }
        pending.setLength(0);
    }

    private void flushAnswer() {
        if (!pending.isEmpty()) {
            sink.answer(pending.toString());
            pending.setLength(0);
        }
    }

    private void emitReasoning(String text) {
        if (!text.isEmpty()) {
            sink.reasoning(text);
        }
    }

    /** Length of the longest suffix of the pending text that could be the start of {@code </think>}. */
    private int partialCloseSuffix() {
        for (int length = Math.min(CLOSE.length() - 1, pending.length()); length > 0; length--) {
            if (CLOSE.startsWith(pending.substring(pending.length() - length))) {
                return length;
            }
        }
        return 0;
    }
}
