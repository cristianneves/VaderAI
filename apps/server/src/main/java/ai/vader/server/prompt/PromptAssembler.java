package ai.vader.server.prompt;

import ai.vader.server.llm.AnswerMode;
import ai.vader.server.llm.AnswerRequest;
import ai.vader.server.preferences.Language;
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
            clarifying question mid-interview.

            They can also type a request straight to you — a follow-up like "explain \
            that more simply", or an instruction like "shorter". That comes from them, \
            not from the interviewer: apply it to the answer you just gave rather than \
            treating it as a new interview question.\
            """;

    /**
     * Used when there is a problem on the screen rather than a question in the
     * air. Deliberately not the interview prompt with a sentence bolted on: the
     * interview prompt's whole frame — first person, in their voice, a few
     * sentences someone can say out loud — is wrong for a LeetCode problem, and
     * an answer that opens by narrating an approach in the first person is
     * exactly the thing that wastes the seconds it was meant to save.
     */
    static final String CODING_SYSTEM_PROMPT =
            """
            You are helping someone through a technical interview problem, live. \
            Your answer is shown on a private overlay only they can see, while \
            they are being watched.

            Lead with the approach in one or two sentences, so they have \
            something to say immediately. Then give the code. Then state the time \
            and space complexity, and name the edge cases that matter — empty \
            input, a single element, duplicates, overflow, whatever this problem \
            actually turns on.

            Write the code as a fenced Markdown block with a language tag. Use \
            the language on the screen if you can see one, and Python if you \
            cannot. It has to be code they could type out and defend, not \
            pseudocode.

            Prefer the solution a competent candidate reaches under time \
            pressure. If there is a substantially better approach, give the \
            working one first and name the better one in a sentence — a clever \
            answer they cannot explain is worse than a plain one they can.

            If the problem statement is cut off or unreadable, solve the most \
            likely reading and say in one line what you assumed.\
            """;

    /**
     * How the answer language is stated. Part of the cached prefix, which is
     * correct: it is constant for a session, so it costs one cold write and then
     * caches — and two users in different languages get different prefixes,
     * which is what they should get.
     *
     * <p>{@link Language#MULTI} is deliberately not given a language to write
     * in. It means the speaker switches between several, so the only right
     * instruction is to follow them.
     */
    static String languageInstruction(Language language) {
        return language == Language.MULTI
                ? "\n\nAnswer in whichever language the interviewer just used."
                : "\n\nAnswer in " + language.englishName() + ".";
    }

    /**
     * @param recentTurns conversation tail, oldest first
     * @param knowledgeBase the user's résumé and notes; empty until Phase 5 fills it
     * @param question what the user typed in the ask bar, or null to answer the
     *     interviewer's most recent question from the transcript
     * @param priorExchanges answers already given this session, oldest first
     * @param requested which prompt the client asked for; a screenshot overrides it
     */
    public AnswerRequest assemble(
            List<TranscriptEvent> recentTurns,
            String knowledgeBase,
            Optional<AnswerRequest.ImageInput> image,
            String question,
            List<AnswerRequest.Exchange> priorExchanges,
            Language language,
            AnswerMode requested) {
        // A screenshot means there is a problem on the screen, which is the one
        // case where the interview prompt is the wrong shape entirely. Otherwise
        // the client decides — the ask bar's Code toggle, or interview when it
        // says nothing.
        AnswerMode mode = image.isPresent() ? AnswerMode.CODING : requested;
        String systemPrompt = mode == AnswerMode.CODING ? CODING_SYSTEM_PROMPT : SYSTEM_PROMPT;

        List<String> cached = new ArrayList<>();
        cached.add(systemPrompt + languageInstruction(language));
        if (knowledgeBase != null && !knowledgeBase.isBlank()) {
            cached.add("Background on the person you are helping:\n\n" + knowledgeBase.strip());
        }

        return new AnswerRequest(
                List.copyOf(cached),
                List.copyOf(priorExchanges),
                conversation(recentTurns, image.isPresent(), question),
                image,
                mode);
    }

    private String conversation(List<TranscriptEvent> turns, boolean hasImage, String question) {
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
        text.append('\n').append(instruction(hasImage, question));
        return text.toString();
    }

    private String instruction(boolean hasImage, String question) {
        boolean typed = question != null && !question.isBlank();
        if (typed && hasImage) {
            return "They are asking you this about what is on the screen above:\n\n" + question.strip();
        }
        if (typed) {
            return "They are asking you this directly:\n\n" + question.strip();
        }
        return hasImage
                ? "Answer the interviewer's question about what is on the screen above."
                : "Answer the interviewer's most recent question.";
    }
}
