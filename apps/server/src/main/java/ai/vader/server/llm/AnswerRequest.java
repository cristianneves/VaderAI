package ai.vader.server.llm;

import java.util.List;
import java.util.Optional;

/**
 * A prompt split along its caching boundary.
 *
 * @param cachedBlocks stable prefix — system prompt and knowledge base. The last
 *     block carries the cache breakpoint, so everything here must be
 *     byte-identical between requests or the cache silently never hits.
 * @param priorExchanges answers already given this session, replayed as real
 *     user/assistant turns so a follow-up has something to follow. They sit
 *     <em>after</em> the breakpoint — they change every question, and putting
 *     them in the prefix would throw the cache away on every ask.
 * @param conversation volatile turn context, placed after the breakpoint
 * @param image optional screenshot, placed <em>before</em> the text block
 * @param mode which kind of answer this is; sets the output ceiling
 */
public record AnswerRequest(
        List<String> cachedBlocks,
        List<Exchange> priorExchanges,
        String conversation,
        Optional<ImageInput> image,
        AnswerMode mode) {

    /**
     * One completed question/answer pair.
     *
     * @param asked a short rendering of what prompted the answer — the typed
     *     question, or the interviewer turn that triggered it. Not the whole
     *     transcript: that already travels in {@code conversation}, and sending
     *     it twice per exchange would grow every request quadratically.
     */
    public record Exchange(String asked, String answer) {}

    public record ImageInput(String mediaType, String base64Data) {}
}
