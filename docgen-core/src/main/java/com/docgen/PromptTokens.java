package com.docgen;

/**
 * Conservative estimate of how many tokens a prompt occupies in a model's
 * context window. Ollama has no tokenize endpoint, so the context size sent as
 * {@code num_ctx} is sized from this estimate; an underestimate would let
 * Ollama silently drop the start of the prompt, where the security rules are.
 *
 * <p>Weights per code point, calibrated against counts that Ollama reported
 * with the Qwen2.5 tokenizer (see {@code token-calibration/measured.json} in
 * the tests) and chosen to stay above every measured class of text:</p>
 * <ul>
 *   <li>ASCII digits cost a full token each: Qwen tokenizes every digit
 *   separately (CSV and SVG path data measured at about one token per
 *   character), and so does an ASCII character next to a digit.</li>
 *   <li>Dense runs, such as base64 or hex (20+ characters of
 *   {@code [A-Za-z0-9+/=_-]} that include a digit), and runs of four or more
 *   punctuation characters cost 0.75 per character.</li>
 *   <li>Other ASCII costs a third of a token (code and prose measured at 3.2 to
 *   5.2 characters per token).</li>
 *   <li>Non-ASCII characters cost one token (CJK measured at 1.9 characters per
 *   token), and characters outside the Basic Multilingual Plane, such as
 *   emoji, cost four.</li>
 * </ul>
 * <p>The sum gets a 10% margin plus a fixed allowance for the model's chat
 * template. Ordinary code and prose come out 1.3 to 2 times above the real
 * count: the price of never truncating silently is a larger context window.</p>
 */
final class PromptTokens {
    static final int TEMPLATE_ALLOWANCE = 64;
    private static final double SAFETY_MARGIN = 1.10;
    private static final int DENSE_RUN_MINIMUM = 20;
    private static final int PUNCTUATION_RUN_MINIMUM = 4;

    private PromptTokens() {
    }

    static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return TEMPLATE_ALLOWANCE;
        }
        int[] codePoints = text.codePoints().toArray();
        boolean[] dense = denseRuns(codePoints);
        int[] punctuationRun = punctuationRunLengths(codePoints);
        double total = 0;
        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            if (codePoint > 0xFFFF) {
                total += 4;
            } else if (codePoint > 0x7F) {
                total += 1;
            } else if (isDigit(codePoint) || nextToDigit(codePoints, index)) {
                total += 1;
            } else if (dense[index] || punctuationRun[index] >= PUNCTUATION_RUN_MINIMUM) {
                total += 0.75;
            } else {
                total += 1.0 / 3;
            }
        }
        double estimate = Math.ceil(total * SAFETY_MARGIN) + TEMPLATE_ALLOWANCE;
        return estimate >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) estimate;
    }

    private static boolean[] denseRuns(int[] codePoints) {
        boolean[] dense = new boolean[codePoints.length];
        int start = 0;
        while (start < codePoints.length) {
            if (!isDenseCharacter(codePoints[start])) {
                start++;
                continue;
            }
            int end = start;
            boolean hasDigit = false;
            while (end < codePoints.length && isDenseCharacter(codePoints[end])) {
                hasDigit |= isDigit(codePoints[end]);
                end++;
            }
            if (end - start >= DENSE_RUN_MINIMUM && hasDigit) {
                for (int index = start; index < end; index++) {
                    dense[index] = true;
                }
            }
            start = end;
        }
        return dense;
    }

    private static int[] punctuationRunLengths(int[] codePoints) {
        int[] lengths = new int[codePoints.length];
        int start = 0;
        while (start < codePoints.length) {
            if (!isPunctuation(codePoints[start])) {
                start++;
                continue;
            }
            int end = start;
            while (end < codePoints.length && isPunctuation(codePoints[end])) {
                end++;
            }
            for (int index = start; index < end; index++) {
                lengths[index] = end - start;
            }
            start = end;
        }
        return lengths;
    }

    private static boolean nextToDigit(int[] codePoints, int index) {
        return index > 0 && isDigit(codePoints[index - 1])
                || index + 1 < codePoints.length && isDigit(codePoints[index + 1]);
    }

    private static boolean isDigit(int codePoint) {
        return codePoint >= '0' && codePoint <= '9';
    }

    private static boolean isDenseCharacter(int codePoint) {
        return codePoint >= 'A' && codePoint <= 'Z' || codePoint >= 'a' && codePoint <= 'z' || isDigit(codePoint)
                || codePoint == '+' || codePoint == '/' || codePoint == '=' || codePoint == '_' || codePoint == '-';
    }

    private static boolean isPunctuation(int codePoint) {
        return codePoint >= '!' && codePoint <= '/' || codePoint >= ':' && codePoint <= '@'
                || codePoint >= '[' && codePoint <= '`' || codePoint >= '{' && codePoint <= '~';
    }
}
