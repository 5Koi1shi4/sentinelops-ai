package io.sentinelops.api.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.HexFormat;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class KnowledgeChunkerTest {

    private final KnowledgeChunker chunker = new KnowledgeChunker();

    @Test
    void prefersMarkdownBoundariesAndReturnsStableHashedDeduplicatedChunks() {
        String markdown = "# Connection pool\n\nIncrease the pool size after checking saturation.\n\n"
                + "# Saturation\n\nCheck the pending acquisition metric before changing capacity.";

        List<KnowledgeChunker.Chunk> first = chunker.chunk(markdown);
        List<KnowledgeChunker.Chunk> second = chunker.chunk(markdown);

        assertThat(first).hasSize(2);
        assertThat(first).extracting(KnowledgeChunker.Chunk::chunkNo).containsExactly(0, 1);
        assertThat(first).extracting(KnowledgeChunker.Chunk::content)
                .containsExactly(
                        "# Connection pool\n\nIncrease the pool size after checking saturation.",
                        "# Saturation\n\nCheck the pending acquisition metric before changing capacity.");
        assertThat(first).extracting(KnowledgeChunker.Chunk::content)
                .containsExactlyElementsOf(second.stream().map(KnowledgeChunker.Chunk::content).toList());
        assertThat(first.getFirst().contentHash()).isEqualTo(sha256(first.getFirst().content()));
    }

    @Test
    void omitsDuplicateContentWhileKeepingChunkNumbersContiguous() {
        List<KnowledgeChunker.Chunk> chunks = chunker.chunk("same paragraph\n\nsame paragraph");

        assertThat(chunks).extracting(KnowledgeChunker.Chunk::chunkNo).containsExactly(0);
        assertThat(chunks).extracting(KnowledgeChunker.Chunk::content).containsExactly("same paragraph");
    }

    @Test
    void splitsLongParagraphsWithBoundedUnicodeCodePointsAndOverlapWithoutDroppingText() {
        String paragraph = uniqueBmpSequence(2_400);

        List<KnowledgeChunker.Chunk> chunks = chunker.chunk(paragraph);

        assertThat(chunks).hasSize(3);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content().codePoints().count()).isLessThanOrEqualTo(1_200));
        assertThat(chunks.get(0).content()).isEqualTo(paragraph.substring(0, 1_200));
        assertThat(chunks.get(1).content()).isEqualTo(paragraph.substring(1_050, 2_250));

        StringBuilder reconstructed = new StringBuilder(chunks.getFirst().content());
        for (int index = 1; index < chunks.size(); index++) {
            String previous = chunks.get(index - 1).content();
            String current = chunks.get(index).content();
            int overlap = commonSuffixPrefixLength(previous, current);
            assertThat(overlap).isEqualTo(150);
            reconstructed.append(current.substring(overlap));
        }
        assertThat(reconstructed.toString()).isEqualTo(paragraph);
    }

    @Test
    void preservesSurrogatePairsAcrossTheBoundedOverlap() {
        String paragraph = "🙂".repeat(1_350) + "🚀".repeat(300);

        List<KnowledgeChunker.Chunk> chunks = chunker.chunk(paragraph);

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.content().codePointCount(0, chunk.content().length()))
                    .isLessThanOrEqualTo(1_200);
            assertThat(hasUnpairedSurrogate(chunk.content())).isFalse();
        });
        int overlap = commonSuffixPrefixLength(chunks.get(0).content(), chunks.get(1).content());
        assertThat(overlap).isEqualTo(150);
        assertThat(chunks.get(0).content()
                        + chunks.get(1).content().substring(overlap))
                .isEqualTo(paragraph);
    }

    @Test
    void keepsSurrogatePairsTogetherAndRejectsBlankOrOversizedDocuments() {
        String emojiText = "🙂".repeat(1_201);
        List<KnowledgeChunker.Chunk> chunks = chunker.chunk(emojiText);

        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.content().codePoints().count()).isLessThanOrEqualTo(1_200);
            assertThat(hasUnpairedSurrogate(chunk.content())).isFalse();
        });
        assertThatThrownBy(() -> chunker.chunk(" \n\t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker.chunk("x".repeat(120_001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnpairedSurrogatesBeforeHashing() {
        String highSurrogate = "bad" + '\uD800' + "tail";
        String lowSurrogate = "bad" + '\uDC00' + "tail";

        assertThatThrownBy(() -> chunker.chunk(highSurrogate))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker.chunk(lowSurrogate))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeChunker.Chunk(0, highSurrogate, sha256(highSurrogate)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appliesTheDocumentLimitToUnicodeCodePoints() {
        String accepted = "🙂".repeat(60_001);

        assertThatCode(() -> chunker.chunk(accepted)).doesNotThrowAnyException();
        assertThatThrownBy(() -> chunker.chunk("🙂".repeat(120_001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsADocumentThatWouldProduceMoreThanTheChunkLimit() {
        String document = IntStream.range(0, 201)
                .mapToObj(index -> "paragraph-" + index)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElseThrow();

        assertThatThrownBy(() -> chunker.chunk(document))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many chunks");
    }

    private static int commonSuffixPrefixLength(String previous, String current) {
        int max = Math.min(150, Math.min(previous.length(), current.length()));
        for (int length = max; length > 0; length--) {
            if (previous.regionMatches(previous.length() - length, current, 0, length)) {
                return length;
            }
        }
        return 0;
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private static String uniqueBmpSequence(int codePoints) {
        StringBuilder value = new StringBuilder(codePoints);
        for (int index = 0; index < codePoints; index++) {
            value.appendCodePoint(0x1000 + index);
        }
        return value.toString();
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
