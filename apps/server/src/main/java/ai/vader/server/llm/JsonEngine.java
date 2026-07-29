package ai.vader.server.llm;

import java.util.List;
import java.util.Map;

/**
 * A blocking LLM call that returns a JSON document of a given shape.
 *
 * <p>Separate from {@link AnswerEngine} because the two want opposite things.
 * A live answer is streamed token by token and only has to read well; a question
 * set or a grade is useless until it is complete and parseable, and the caller
 * wants a value, not a stream.
 *
 * <p>Implementations must not leak vendor types across this boundary — the
 * schema goes in as plain maps and lists, the result comes out as text.
 */
public interface JsonEngine {

    /**
     * @param cachedBlocks stable system blocks, oldest first; the last one carries the cache breakpoint
     * @param prompt the volatile instruction, sent as the user turn after the breakpoint
     * @param schema a JSON Schema the provider constrains the response to
     * @return the response text, guaranteed by the provider to satisfy {@code schema}
     * @throws RuntimeException if the call fails; callers surface this as a 5xx
     */
    String complete(List<String> cachedBlocks, String prompt, Map<String, Object> schema);
}
