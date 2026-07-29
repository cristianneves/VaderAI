package ai.vader.server.llm;

/**
 * @param tokens how large the text is in tokens
 * @param estimated true when the count came from a local approximation rather
 *     than from the API — the UI says so rather than implying false precision
 */
public interface TokenCounter {

    TokenCount count(String text);

    record TokenCount(long tokens, boolean estimated) {}
}
