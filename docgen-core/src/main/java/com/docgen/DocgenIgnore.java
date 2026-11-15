package com.docgen;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ordered exclusion rules from the root {@code .docgenignore} and every
 * {@code .gitignore} encountered below it. Filesystem access is deliberately not
 * performed here: {@link RepoScanner} supplies channels opened relative to a
 * {@code SecureDirectoryStream}, so ignore loading has the same confinement
 * guarantee as repository-file reads.
 */
final class DocgenIgnore {
    private static final int MAX_IGNORE_BYTES = 64 * 1024;
    private static final int MAX_RULES = 512;
    private static final int MAX_PATTERN_CHARS = 512;
    private static final int MAX_COMPILED_MATCHERS = 2_048;
    private static final int MAX_COMPILED_SEGMENT_MATCHERS = 8_192;
    private static final long MAX_COMPILED_MATCHER_STATES = 250_000L;
    private static final long MAX_MATCH_WORK = 25_000_000L;

    private final List<Rule> docgenRules = new ArrayList<>();
    private final List<Rule> gitRules = new ArrayList<>();
    private final MatchBudget matchBudget = new MatchBudget(MAX_MATCH_WORK);
    private int compiledMatchers;
    private int compiledSegmentMatchers;
    private long compiledMatcherStates;

    void addDocgenIgnore(Path scope, SeekableByteChannel channel, String source) throws IOException {
        addRules(scope, channel, source, false, docgenRules);
    }

    void addGitIgnore(Path scope, SeekableByteChannel channel, String source) throws IOException {
        addRules(scope, channel, source, true, gitRules);
    }

    private void addRules(Path scope, SeekableByteChannel channel, String source,
                          boolean allowNegation, List<Rule> destination) throws IOException {
        String content = readBoundedUtf8(channel, source);
        if (content.indexOf('\0') >= 0) {
            throw new IOException(source + " contains a NUL byte and cannot be interpreted safely.");
        }
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '\r'
                    && index + 1 < content.length()
                    && content.charAt(index + 1) != '\n') {
                throw new IOException(source
                        + " contains a bare carriage return and cannot be interpreted safely.");
            }
        }
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String rawLine;
            int lineNumber = 0;
            while ((rawLine = reader.readLine()) != null) {
                lineNumber++;
                // Leading spaces are part of a gitignore pattern. Only trailing
                // spaces that are not escaped are discarded.
                String line = stripUnescapedTrailingSpaces(rawLine);
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                boolean escapedLeadingMarker = line.startsWith("\\#") || line.startsWith("\\!");
                if (escapedLeadingMarker) {
                    line = line.substring(1);
                }

                boolean negated = !escapedLeadingMarker && line.startsWith("!");
                if (negated && !allowNegation) {
                    throw new IOException(source + " line " + lineNumber
                            + ": negation patterns ('!') are not supported.");
                }
                if (negated) {
                    line = line.substring(1);
                }
                if (line.length() > MAX_PATTERN_CHARS) {
                    throw new IOException(source + " line " + lineNumber
                            + ": pattern exceeds " + MAX_PATTERN_CHARS + " characters.");
                }
                if (!Normalizer.isNormalized(line, Normalizer.Form.NFC)) {
                    throw new IOException(source + " line " + lineNumber
                            + ": non-NFC Unicode patterns are not supported.");
                }
                if (docgenRules.size() + gitRules.size() >= MAX_RULES) {
                    throw new IOException("Ignore rules exceed the limit of " + MAX_RULES + ".");
                }

                try {
                    Rule rule = compileRule(scope, line, negated);
                    if (rule != null) {
                        destination.add(rule);
                    }
                } catch (IOException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw new IOException(source + " line " + lineNumber
                            + ": invalid pattern '" + line + "'.", e);
                }
            }
        }
    }

    private static String stripUnescapedTrailingSpaces(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            int backslashes = 0;
            for (int index = end - 2; index >= 0 && value.charAt(index) == '\\'; index--) {
                backslashes++;
            }
            if ((backslashes & 1) == 1) {
                break;
            }
            end--;
        }
        return value.substring(0, end);
    }

    private static String readBoundedUtf8(SeekableByteChannel channel, String source) throws IOException {
        if (channel.size() > MAX_IGNORE_BYTES) {
            throw new IOException(source + " exceeds the ignore-file limit of " + MAX_IGNORE_BYTES + " bytes.");
        }
        InputStream input = Channels.newInputStream(channel);
        byte[] bytes = input.readNBytes(MAX_IGNORE_BYTES + 1);
        if (bytes.length > MAX_IGNORE_BYTES) {
            throw new IOException(source + " exceeds the ignore-file limit of " + MAX_IGNORE_BYTES + " bytes.");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException(source + " is not valid UTF-8.", e);
        }
    }

    private Rule compileRule(Path scope, String rawPattern, boolean negated) throws IOException {
        String pattern = rawPattern;
        if (pattern.contains("//")) {
            throw new IllegalArgumentException("empty path segments are unsupported");
        }
        boolean anchored = pattern.startsWith("/");
        boolean directoryOnly = pattern.endsWith("/");
        while (pattern.startsWith("/")) {
            pattern = pattern.substring(1);
        }
        while (pattern.endsWith("/")) {
            pattern = pattern.substring(0, pattern.length() - 1);
        }
        if (pattern.isEmpty()) {
            return null;
        }
        anchored = anchored || pattern.contains("/");

        Set<String> exactGlobs = new LinkedHashSet<>();
        exactGlobs.add(pattern);
        if (!anchored) {
            exactGlobs.add("**/" + pattern);
        }

        reserveCompilation(exactGlobs);

        return new Rule(scope, compileMatchers(exactGlobs, false), compileMatchers(exactGlobs, true),
                directoryOnly, negated);
    }

    /**
     * Reserves every allocation-driving unit before constructing matchers. A
     * matcher-count limit alone is insufficient because one long pattern can
     * contain hundreds of path segments and each segment owns NFA tables.
     */
    private void reserveCompilation(Set<String> globs) throws IOException {
        int newMatchers = Math.multiplyExact(globs.size(), 2);
        long segmentMatchersPerMode = 0;
        long matcherStatesPerMode = 0;
        for (String glob : globs) {
            String[] rawSegments = glob.split("/", -1);
            for (int index = 0; index < rawSegments.length; index++) {
                String segment = rawSegments[index];
                if (GlobMatcher.isRecursiveSegment(segment)) {
                    if (index == rawSegments.length - 1 && rawSegments.length > 1) {
                        segmentMatchersPerMode++;
                        matcherStatesPerMode += 2; // the required trailing '*' segment
                    }
                    continue;
                }
                segmentMatchersPerMode++;
                // SegmentMatcher never compiles more tokens than UTF-8 input
                // bytes; add the accepting NFA state as a conservative bound.
                matcherStatesPerMode += segment.getBytes(StandardCharsets.UTF_8).length + 1L;
            }
        }
        long newSegmentMatchers = segmentMatchersPerMode * 2L;
        long newMatcherStates = matcherStatesPerMode * 2L;

        if (newMatchers > MAX_COMPILED_MATCHERS - compiledMatchers) {
            throw new IOException("Compiled ignore matchers exceed the limit of "
                    + MAX_COMPILED_MATCHERS + ".");
        }
        if (newSegmentMatchers > MAX_COMPILED_SEGMENT_MATCHERS - compiledSegmentMatchers) {
            throw new IOException("Compiled ignore segment matchers exceed the limit of "
                    + MAX_COMPILED_SEGMENT_MATCHERS + ".");
        }
        if (newMatcherStates > MAX_COMPILED_MATCHER_STATES - compiledMatcherStates) {
            throw new IOException("Compiled ignore matcher states exceed the limit of "
                    + MAX_COMPILED_MATCHER_STATES + ".");
        }
        compiledMatchers += newMatchers;
        compiledSegmentMatchers += (int) newSegmentMatchers;
        compiledMatcherStates += newMatcherStates;
    }

    private static List<GlobMatcher> compileMatchers(Set<String> globs, boolean asciiCaseInsensitive) {
        List<GlobMatcher> matchers = new ArrayList<>(globs.size());
        for (String glob : globs) {
            matchers.add(new GlobMatcher(glob, asciiCaseInsensitive));
        }
        return List.copyOf(matchers);
    }

    String exclusionSource(Path relativePath) throws IOException {
        return exclusionSource(relativePath, false);
    }

    String exclusionSource(Path relativePath, boolean directory) throws IOException {
        if (isExcluded(docgenRules, relativePath, directory)) {
            return ".docgenignore";
        }
        if (isExcluded(gitRules, relativePath, directory)) {
            return ".gitignore";
        }
        return null;
    }

    /**
     * Fast path used by the secure recursive scanner. Its caller has already
     * refused every excluded ancestor directory, so only the final path state is
     * needed and the depth-by-rule nested loop can be avoided.
     */
    String exclusionSourceForReachablePath(Path relativePath, boolean directory) throws IOException {
        if (isPathExcluded(docgenRules, relativePath, directory)) {
            return ".docgenignore";
        }
        if (isPathExcluded(gitRules, relativePath, directory)) {
            return ".gitignore";
        }
        return null;
    }

    private boolean isExcluded(List<Rule> rules, Path relativePath, boolean directory) throws IOException {
        Path ancestor = relativePath.getFileSystem().getPath("");
        for (int i = 0; i < relativePath.getNameCount() - 1; i++) {
            ancestor = ancestor.resolve(relativePath.getName(i));
            if (isPathExcluded(rules, ancestor, true)) {
                // A file cannot be re-included while a parent directory remains
                // ignored. This is both git-compatible and fail-closed.
                return true;
            }
        }
        return isPathExcluded(rules, relativePath, directory);
    }

    private boolean isPathExcluded(List<Rule> rules, Path path, boolean directory) throws IOException {
        boolean caseSensitiveExcluded = false;
        boolean caseInsensitiveExcluded = false;
        for (Rule rule : rules) {
            matchBudget.consume(1);
            if (rule.matches(path, directory, rule.exactMatchers(), matchBudget)) {
                caseSensitiveExcluded = !rule.negated();
            }
            matchBudget.consume(1);
            if (rule.matches(path, directory, rule.insensitiveMatchers(), matchBudget)) {
                caseInsensitiveExcluded = !rule.negated();
            }
        }
        // core.ignoreCase and filesystem case behavior are not trusted here.
        // Negation is ordered independently in both interpretations, and the
        // conservative union cannot re-include a path excluded by either one.
        return caseSensitiveExcluded || caseInsensitiveExcluded;
    }

    private record Rule(Path scope, List<GlobMatcher> exactMatchers,
                        List<GlobMatcher> insensitiveMatchers,
                        boolean directoryOnly, boolean negated) {
        int matcherCount() {
            return exactMatchers.size() + insensitiveMatchers.size();
        }

        boolean matches(Path repositoryRelativePath, boolean directory, List<GlobMatcher> matchers,
                        MatchBudget budget) throws IOException {
            Path scopedPath;
            if (scope.toString().isEmpty()) {
                scopedPath = repositoryRelativePath;
            } else {
                if (!repositoryRelativePath.startsWith(scope)) {
                    return false;
                }
                scopedPath = scope.relativize(repositoryRelativePath);
                if (scopedPath.toString().isEmpty()) {
                    return false;
                }
            }

            String[] pathSegments = new String[scopedPath.getNameCount()];
            budget.consume(pathSegments.length);
            for (int index = 0; index < pathSegments.length; index++) {
                pathSegments[index] = scopedPath.getName(index).toString();
            }
            if (!directoryOnly || directory) {
                if (matchesAny(matchers, pathSegments, budget)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean matchesAny(List<GlobMatcher> matchers, String[] path,
                                          MatchBudget budget) throws IOException {
            return matchesAny(matchers, path, path.length, budget);
        }

        private static boolean matchesAny(List<GlobMatcher> matchers, String[] path, int pathLength,
                                          MatchBudget budget) throws IOException {
            for (GlobMatcher matcher : matchers) {
                budget.consume(1);
                if (matcher.matches(path, pathLength, budget)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Deterministic gitignore-style glob matcher. The JDK filesystem glob
     * implementation is regex-backed and can exhibit catastrophic backtracking
     * for attacker-controlled patterns. This matcher supports the gitignore
     * wildcard surface we document ({@code *}, {@code ?}, character classes and
     * whole-segment {@code **}) with bounded, iterative matching.
     */
    private static final class GlobMatcher {
        private final List<GlobSegment> pattern;

        private GlobMatcher(String glob, boolean asciiCaseInsensitive) {
            String[] rawSegments = glob.split("/", -1);
            List<GlobSegment> compiled = new ArrayList<>(rawSegments.length);
            for (int index = 0; index < rawSegments.length; index++) {
                String rawSegment = rawSegments[index];
                if (rawSegment.isEmpty()) {
                    throw new IllegalArgumentException("empty path segment");
                }
                if (isRecursiveSegment(rawSegment)) {
                    if (index == rawSegments.length - 1 && rawSegments.length > 1) {
                        // In gitignore, a trailing '/**' means descendants and
                        // does not match the directory itself. Model that as
                        // one required segment followed by zero-or-more.
                        compiled.add(GlobSegment.regular(new SegmentMatcher("*", asciiCaseInsensitive)));
                    }
                    compiled.add(GlobSegment.recursiveSegment());
                } else {
                    compiled.add(GlobSegment.regular(new SegmentMatcher(rawSegment, asciiCaseInsensitive)));
                }
            }
            pattern = List.copyOf(compiled);
        }

        private static boolean isRecursiveSegment(String value) {
            if (value.length() < 2) {
                return false;
            }
            for (int index = 0; index < value.length(); index++) {
                if (value.charAt(index) != '*') {
                    return false;
                }
            }
            return true;
        }

        private boolean matches(String[] path, int pathLength, MatchBudget budget) throws IOException {
            int patternIndex = 0;
            int pathIndex = 0;
            int lastRecursive = -1;
            int lastRecursivePath = -1;

            while (pathIndex < pathLength) {
                budget.consume(1);
                if (patternIndex < pattern.size() && pattern.get(patternIndex).recursive()) {
                    lastRecursive = patternIndex++;
                    lastRecursivePath = pathIndex;
                } else if (patternIndex < pattern.size()
                        && pattern.get(patternIndex).matcher().matches(path[pathIndex], budget)) {
                    patternIndex++;
                    pathIndex++;
                } else if (lastRecursive >= 0) {
                    patternIndex = lastRecursive + 1;
                    pathIndex = ++lastRecursivePath;
                } else {
                    return false;
                }
            }
            while (patternIndex < pattern.size() && pattern.get(patternIndex).recursive()) {
                patternIndex++;
            }
            return patternIndex == pattern.size();
        }
    }

    private record GlobSegment(boolean recursive, SegmentMatcher matcher) {
        private static GlobSegment recursiveSegment() {
            return new GlobSegment(true, null);
        }

        private static GlobSegment regular(SegmentMatcher matcher) {
            return new GlobSegment(false, matcher);
        }
    }

    private static final class SegmentMatcher {
        private static final int LITERAL = 0;
        private static final int ANY = 1;
        private static final int STAR = 2;
        private static final int CHARACTER_CLASS = 3;

        private final int stateWords;
        private final int acceptingWord;
        private final long acceptingBit;
        private final long[] starMask;
        private final long[] anyMask;
        private final long[][] literalMasks;
        private final List<ClassToken> classTokens;
        private final boolean asciiCaseInsensitive;
        private final long classComparisonWork;

        private SegmentMatcher(String pattern, boolean asciiCaseInsensitive) {
            this.asciiCaseInsensitive = asciiCaseInsensitive;
            byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
            List<SegmentToken> compiled = new ArrayList<>(patternBytes.length);
            for (int index = 0; index < patternBytes.length;) {
                int character = Byte.toUnsignedInt(patternBytes[index++]);
                if (character == '\\') {
                    if (index >= patternBytes.length) {
                        throw new IllegalArgumentException("trailing escape");
                    }
                    // Git case-folds ordinary literals, but an escaped literal
                    // remains byte-exact even under core.ignoreCase.
                    compiled.add(SegmentToken.literal(Byte.toUnsignedInt(patternBytes[index++])));
                } else if (character == '*') {
                    while (index < patternBytes.length && patternBytes[index] == '*') {
                        index++;
                    }
                    if (compiled.isEmpty() || compiled.getLast().type() != STAR) {
                        compiled.add(SegmentToken.star());
                    }
                } else if (character == '?') {
                    compiled.add(SegmentToken.any());
                } else if (character == '[') {
                    ParsedCharacterClass parsed = parseCharacterClass(patternBytes, index, asciiCaseInsensitive);
                    compiled.add(SegmentToken.characterClass(parsed.characterClass()));
                    index = parsed.nextIndex();
                } else {
                    compiled.add(SegmentToken.literal(foldAscii(character, asciiCaseInsensitive)));
                }
            }
            int stateCount = compiled.size() + 1;
            stateWords = (stateCount + Long.SIZE - 1) / Long.SIZE;
            acceptingWord = compiled.size() / Long.SIZE;
            acceptingBit = 1L << (compiled.size() % Long.SIZE);
            starMask = new long[stateWords];
            anyMask = new long[stateWords];
            long[][] literals = new long[256][];
            List<ClassToken> classes = new ArrayList<>();
            for (int tokenIndex = 0; tokenIndex < compiled.size(); tokenIndex++) {
                SegmentToken token = compiled.get(tokenIndex);
                int word = tokenIndex / Long.SIZE;
                long bit = 1L << (tokenIndex % Long.SIZE);
                if (token.type() == STAR) {
                    starMask[word] |= bit;
                } else if (token.type() == ANY) {
                    anyMask[word] |= bit;
                } else if (token.type() == LITERAL) {
                    if (literals[token.literal()] == null) {
                        literals[token.literal()] = new long[stateWords];
                    }
                    literals[token.literal()][word] |= bit;
                } else {
                    classes.add(new ClassToken(tokenIndex, token.characterClass()));
                }
            }
            literalMasks = literals;
            classTokens = List.copyOf(classes);
            classComparisonWork = classes.stream()
                    .mapToLong(token -> (long) token.characterClass().ranges().size() * 2L)
                    .sum();
        }

        private boolean matches(String value, MatchBudget budget) throws IOException {
            long[] active = new long[stateWords];
            long[] next = new long[stateWords];
            long[] classMask = classTokens.isEmpty() ? null : new long[stateWords];
            active[0] = 1L;
            addStarEpsilonTransitions(active);

            byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
            budget.consume((long) valueBytes.length
                    * (stateWords + Math.max(1L, classComparisonWork)));
            for (byte valueByte : valueBytes) {
                int character = foldAscii(Byte.toUnsignedInt(valueByte), asciiCaseInsensitive);
                long[] literalMask = literalMasks[character];
                if (classMask != null) {
                    Arrays.fill(classMask, 0L);
                    for (ClassToken classToken : classTokens) {
                        if (classToken.characterClass().matches(character)) {
                            int word = classToken.tokenIndex() / Long.SIZE;
                            classMask[word] |= 1L << (classToken.tokenIndex() % Long.SIZE);
                        }
                    }
                }

                Arrays.fill(next, 0L);
                long carry = 0L;
                for (int word = 0; word < stateWords; word++) {
                    long matchingTokens = anyMask[word]
                            | (literalMask == null ? 0L : literalMask[word])
                            | (classMask == null ? 0L : classMask[word]);
                    long matched = active[word] & matchingTokens;
                    next[word] = active[word] & starMask[word] | matched << 1 | carry;
                    carry = matched >>> (Long.SIZE - 1);
                }
                addStarEpsilonTransitions(next);
                long[] previous = active;
                active = next;
                next = previous;
            }
            return (active[acceptingWord] & acceptingBit) != 0;
        }

        private void addStarEpsilonTransitions(long[] states) {
            long carry = 0L;
            for (int word = 0; word < stateWords; word++) {
                long stars = states[word] & starMask[word];
                long advanced = stars << 1 | carry;
                carry = stars >>> (Long.SIZE - 1);
                states[word] |= advanced;
            }
        }

        private static ParsedCharacterClass parseCharacterClass(
                byte[] pattern, int index, boolean asciiCaseInsensitive) {
            boolean negated = false;
            if (index < pattern.length && (pattern[index] == '!' || pattern[index] == '^')) {
                negated = true;
                index++;
            }

            List<CharacterRange> ranges = new ArrayList<>();
            if (index < pattern.length && pattern[index] == ']') {
                ranges.add(new CharacterRange(']', ']', false));
                index++;
            }

            boolean closed = false;
            while (index < pattern.length) {
                if (pattern[index] == '[') {
                    throw new IllegalArgumentException("nested/POSIX character classes are unsupported");
                }
                if (pattern[index] == ']') {
                    closed = true;
                    index++;
                    break;
                }
                ParsedClassCharacter start = parseClassCharacter(pattern, index);
                index = start.nextIndex();
                int end = start.value();
                boolean explicitRange = false;
                if (index < pattern.length && pattern[index] == '-'
                        && index + 1 < pattern.length && pattern[index + 1] != ']') {
                    ParsedClassCharacter rangeEnd = parseClassCharacter(pattern, index + 1);
                    index = rangeEnd.nextIndex();
                    end = rangeEnd.value();
                    if (start.value() > end) {
                        throw new IllegalArgumentException("reversed character range");
                    }
                    explicitRange = true;
                }
                ranges.add(new CharacterRange(start.value(), end, explicitRange));
            }
            if (!closed || ranges.isEmpty()) {
                throw new IllegalArgumentException("unclosed or empty character class");
            }

            return new ParsedCharacterClass(
                    new CharacterClass(negated, List.copyOf(ranges), asciiCaseInsensitive), index);
        }

        private static ParsedClassCharacter parseClassCharacter(byte[] pattern, int index) {
            int value = Byte.toUnsignedInt(pattern[index++]);
            if (value == '\\') {
                if (index >= pattern.length) {
                    throw new IllegalArgumentException("trailing escape in character class");
                }
                value = Byte.toUnsignedInt(pattern[index++]);
            }
            if (value >= 0x80) {
                throw new IllegalArgumentException("non-ASCII character classes are unsupported");
            }
            return new ParsedClassCharacter(value, index);
        }

        private static int foldAscii(int value, boolean asciiCaseInsensitive) {
            return asciiCaseInsensitive && value >= 'A' && value <= 'Z'
                    ? value + ('a' - 'A')
                    : value;
        }
    }

    private record SegmentToken(int type, int literal, CharacterClass characterClass) {
        private static SegmentToken literal(int value) {
            return new SegmentToken(SegmentMatcher.LITERAL, value, null);
        }

        private static SegmentToken any() {
            return new SegmentToken(SegmentMatcher.ANY, 0, null);
        }

        private static SegmentToken star() {
            return new SegmentToken(SegmentMatcher.STAR, 0, null);
        }

        private static SegmentToken characterClass(CharacterClass value) {
            return new SegmentToken(SegmentMatcher.CHARACTER_CLASS, 0, value);
        }
    }

    private record CharacterClass(boolean negated, List<CharacterRange> ranges,
                                  boolean asciiCaseInsensitive) {
        private boolean matches(int value) {
            boolean found = contains(value);
            return negated != found;
        }

        private boolean contains(int value) {
            for (CharacterRange range : ranges) {
                if (value >= range.start() && value <= range.end()) {
                    return true;
                }
                if (asciiCaseInsensitive && range.explicitRange()
                        && value >= 'a' && value <= 'z') {
                    int upper = value - ('a' - 'A');
                    if (upper >= range.start() && upper <= range.end()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private record CharacterRange(int start, int end, boolean explicitRange) {
    }

    private record ParsedCharacterClass(CharacterClass characterClass, int nextIndex) {
    }

    private record ParsedClassCharacter(int value, int nextIndex) {
    }

    private record ClassToken(int tokenIndex, CharacterClass characterClass) {
    }

    private static final class MatchBudget {
        private long remaining;

        private MatchBudget(long maximum) {
            remaining = maximum;
        }

        private void consume(long work) throws IOException {
            if (work < 0 || work > remaining) {
                remaining = 0;
                throw new IOException("Ignore matching exceeded the deterministic work safety limit.");
            }
            remaining -= work;
        }
    }
}
