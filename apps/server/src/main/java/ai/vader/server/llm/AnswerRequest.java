package ai.vader.server.llm;

import java.util.List;
import java.util.Optional;

/**
 * A prompt split along its caching boundary.
 *
 * @param cachedBlocks stable prefix — system prompt and knowledge base. The last
 *     block carries the cache breakpoint, so everything here must be
 *     byte-identical between requests or the cache silently never hits.
 * @param conversation volatile turn context, placed after the breakpoint
 * @param image optional screenshot, placed <em>before</em> the text block
 */
public record AnswerRequest(List<String> cachedBlocks, String conversation, Optional<ImageInput> image) {

    public record ImageInput(String mediaType, String base64Data) {}
}
