package ai.vader.server.summary;

import ai.vader.server.preferences.Language;
import java.util.List;
import java.util.Map;

/** The one prompt and schema behind the post-call recap. */
final class SummaryPrompts {

    private SummaryPrompts() {}

    static final String SYSTEM_PROMPT =
            """
            You are writing up a conversation that has just finished, for the \
            person who was in it. They were being interviewed, and they had a \
            private assistant feeding them answers, so the transcript below \
            contains both what was said out loud and what that assistant \
            suggested.

            Write the recap they would want an hour later, when the details have \
            gone: what was actually discussed, what they committed to, what was \
            left open. Address them directly.

            Be specific and short. A key point is something that was said, not a \
            category of thing that was said — "they run Postgres on RDS and are \
            moving to Aurora" rather than "infrastructure was discussed". If the \
            conversation was too short or too garbled to say anything useful, \
            say that in the summary and return empty lists rather than padding \
            them.

            Only action items that were genuinely raised. Do not invent \
            follow-ups that sound plausible for an interview.\
            """;

    static List<String> cachedBlocks(Language language) {
        return List.of(SYSTEM_PROMPT + languageInstruction(language));
    }

    private static String languageInstruction(Language language) {
        return language == Language.MULTI
                ? "\n\nWrite the recap in the language the conversation was held in."
                : "\n\nWrite the recap in " + language.englishName() + ".";
    }

    static String recapPrompt(String conversation) {
        return "Here is the conversation.\n\n" + conversation + "\n\nWrite the recap.";
    }

    /**
     * {@code additionalProperties: false} on every object and no optional fields
     * — structured outputs reject a schema without the first and quietly omit
     * fields without the second.
     */
    static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "required",
            List.of("summary", "keyPoints", "actionItems"),
            "properties",
            Map.of(
                    "summary",
                    Map.of("type", "string"),
                    "keyPoints",
                    Map.of("type", "array", "items", Map.of("type", "string")),
                    "actionItems",
                    Map.of("type", "array", "items", Map.of("type", "string"))));

    record Recap(String summary, List<String> keyPoints, List<String> actionItems) {}
}
