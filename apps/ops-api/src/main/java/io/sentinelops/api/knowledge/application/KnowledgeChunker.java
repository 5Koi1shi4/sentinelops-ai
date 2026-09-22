package io.sentinelops.api.knowledge.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits bounded Markdown into stable, version-local knowledge chunks.
 *
 * <p>Markdown block boundaries are kept whenever possible. Only a single block that exceeds the
 * code-point limit is split with a bounded suffix overlap. Chunk content is canonicalized to LF
 * line endings so the same document has the same hashes on every supported host.
 */
public final class KnowledgeChunker {

    public static final int MAX_DOCUMENT_CODE_POINTS = 120_000;
    public static final int MAX_CHUNK_CODE_POINTS = 1_200;
    public static final int MAX_OVERLAP_CHARS = 150;
    public static final int MAX_CHUNKS = 200;

    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}(?:\\s+|$).*");

    public List<Chunk> chunk(String markdown) {
        KnowledgeText.require(markdown, MAX_DOCUMENT_CODE_POINTS);

        String normalized = normalizeLineEndings(markdown);
        List<String> blocks = mergeHeadingsWithFollowingParagraph(markdownBlocks(normalized));
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("markdown must contain non-blank content");
        }

        var chunks = new ArrayList<Chunk>();
        Set<String> seenContent = new HashSet<>();
        for (String block : blocks) {
            for (String content : splitIfNeeded(block)) {
                // PostgreSQL enforces uniqueness by version/content hash. Keeping the first
                // occurrence here gives callers the same deterministic sequence before insert.
                if (seenContent.add(content)) {
                    chunks.add(new Chunk(chunks.size(), content, sha256(content)));
                    if (chunks.size() > MAX_CHUNKS) {
                        throw new IllegalArgumentException("markdown produces too many chunks");
                    }
                }
            }
        }
        return List.copyOf(chunks);
    }

    private static String normalizeLineEndings(String markdown) {
        return markdown.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static List<String> markdownBlocks(String markdown) {
        var blocks = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        String[] lines = markdown.split("\n", -1);
        for (String line : lines) {
            if (line.isBlank()) {
                appendBlock(blocks, current);
                continue;
            }
            if (!current.isEmpty() && HEADING.matcher(line).matches()) {
                appendBlock(blocks, current);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        appendBlock(blocks, current);
        return blocks;
    }

    private static List<String> mergeHeadingsWithFollowingParagraph(List<String> blocks) {
        var merged = new ArrayList<String>(blocks.size());
        for (int index = 0; index < blocks.size(); index++) {
            String block = blocks.get(index);
            if (isHeadingOnly(block) && index + 1 < blocks.size()
                    && !isHeadingOnly(blocks.get(index + 1))) {
                merged.add(block + "\n\n" + blocks.get(++index));
            } else {
                merged.add(block);
            }
        }
        return merged;
    }

    private static boolean isHeadingOnly(String block) {
        return block.indexOf('\n') < 0 && HEADING.matcher(block).matches();
    }

    private static void appendBlock(List<String> blocks, StringBuilder current) {
        if (!current.isEmpty()) {
            blocks.add(current.toString());
            current.setLength(0);
        }
    }

    private static List<String> splitIfNeeded(String block) {
        int codePointCount = block.codePointCount(0, block.length());
        if (codePointCount <= MAX_CHUNK_CODE_POINTS) {
            return List.of(block);
        }

        var chunks = new ArrayList<String>();
        int startCodePoint = 0;
        while (startCodePoint < codePointCount) {
            int endCodePoint = Math.min(startCodePoint + MAX_CHUNK_CODE_POINTS, codePointCount);
            int startOffset = block.offsetByCodePoints(0, startCodePoint);
            int endOffset = block.offsetByCodePoints(startOffset, endCodePoint - startCodePoint);
            chunks.add(block.substring(startOffset, endOffset));
            if (endCodePoint == codePointCount) {
                break;
            }

            int overlapCodePoints = overlapCodePointCount(block, startOffset, endOffset);
            startCodePoint = endCodePoint - overlapCodePoints;
        }
        return chunks;
    }

    private static int overlapCodePointCount(String block, int startOffset, int endOffset) {
        int overlapCodePoints = 0;
        int overlapChars = 0;
        int cursor = endOffset;
        while (cursor > startOffset) {
            int previous = block.offsetByCodePoints(cursor, -1);
            int codePointChars = cursor - previous;
            if (overlapChars + codePointChars > MAX_OVERLAP_CHARS) {
                break;
            }
            overlapChars += codePointChars;
            overlapCodePoints++;
            cursor = previous;
        }
        return overlapCodePoints;
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    public record Chunk(int chunkNo, String content, String contentHash) {

        public Chunk {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(contentHash, "contentHash");
            if (chunkNo < 0) {
                throw new IllegalArgumentException("chunkNo must not be negative");
            }
            KnowledgeText.require(content, MAX_CHUNK_CODE_POINTS);
            if (!contentHash.equals(sha256(content))) {
                throw new IllegalArgumentException("contentHash does not match content");
            }
        }
    }
}
