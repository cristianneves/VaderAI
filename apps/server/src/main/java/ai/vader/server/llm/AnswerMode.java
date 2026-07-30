package ai.vader.server.llm;

/**
 * What kind of answer is being asked for.
 *
 * <p>Two things follow from this: which system prompt goes in the cached prefix,
 * and how many output tokens the answer is allowed. They move together — the
 * coding prompt asks for an approach, a complexity analysis and code, which does
 * not fit in the budget a spoken answer gets.
 *
 * <p>The two prompts mean two cached prefixes rather than one. With the
 * one-hour TTL both stay warm after their first use, so alternating costs one
 * cold write each and nothing after that. A zero cache-read count on the first
 * screenshot of a session is that write, not a bug.
 */
public enum AnswerMode {
    /** Spoken answer to a question the interviewer asked. */
    INTERVIEW,
    /** Technical answer about a problem, typically one on the screen. */
    CODING
}
