package ai.vader.server.prompt;

import ai.vader.server.llm.AnswerRequest;
import ai.vader.server.stt.TranscriptEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Splits the prompt along its caching boundary: everything stable goes in the
 * cached prefix, everything that changes per question goes after it.
 *
 * <p>Nothing volatile — no timestamps, no ids, no turn text — may leak into the
 * cached blocks. A single changed byte there invalidates the cache for every
 * request that follows, and the only symptom is a silently zero cache-read
 * count.
 */
@Component
public class PromptAssembler {

    static final String SYSTEM_PROMPT =
            """
            You are an interview copilot. You are listening to a live conversation \
            between an interviewer and the person you are helping. Your answers are \
            shown on a private overlay only they can see, while they are speaking.

            Answer as the person being interviewed, in their voice, in the first \
            person. Lead with the answer itself — no preamble, no restating the \
            question, no "great question". Keep it to what someone can actually say \
            out loud: a few sentences for a behavioral question, a concrete approach \
            plus complexity for a technical one.

            Ground every claim in the background you are given. Where it supports the \
            answer, name the specific project, number, or outcome rather than \
            describing it in general terms. If the background does not cover what was \
            asked, answer from general knowledge and do not invent an experience they \
            did not have.

            If the transcript is garbled or the question is unclear, answer the most \
            likely reading rather than asking for clarification — they cannot relay a \
            clarifying question mid-interview.\
            """;

    /**
     * @param recentTurns conversation tail, oldest first
     * @param knowledgeBase the user's résumé and notes; empty until Phase 5 fills it
     */
    public AnswerRequest assemble(
            List<TranscriptEvent> recentTurns, String knowledgeBase, Optional<AnswerRequest.ImageInput> image) {
        List<String> cached = new ArrayList<>();
        cached.add(SYSTEM_PROMPT);
        if (knowledgeBase != null && !knowledgeBase.isBlank()) {
            cached.add("Background on the person you are helping:\n\n" + knowledgeBase.strip());
        }

        return new AnswerRequest(List.copyOf(cached), conversation(recentTurns, image.isPresent()), image);
    }

    private String conversation(List<TranscriptEvent> turns, boolean hasImage) {
        var text = new StringBuilder();
        if (turns.isEmpty()) {
            text.append("(no transcript yet)");
        } else {
            text.append("Conversation so far:\n");
            for (TranscriptEvent turn : turns) {
                text.append(turn.channel() == TranscriptEvent.CHANNEL_INTERVIEWER ? "Interviewer: " : "You: ")
                        .append(turn.text())
                        .append('\n');
            }
        }
        text.append('\n')
                .append(hasImage
                        ? "Answer the interviewer's question about what is on the screen above."
                        : "Answer the interviewer's most recent question.");
        return text.toString();
    }
}
