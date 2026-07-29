package ai.vader.server.practice;

/**
 * One graded answer, as the model returns it. Field names match
 * {@link PracticePrompts#GRADE_SCHEMA} so Jackson can read the response directly.
 *
 * <p>Scores run 1 (weak) to 5 (strong) and the schema constrains them to that
 * range, so no validation is needed here.
 *
 * @param structure did the answer have a shape — situation, action, outcome
 * @param specificity did it name real projects, numbers, and outcomes
 * @param relevance did it answer the question the job description implies
 * @param feedback what to fix, in one or two sentences
 * @param rewrite the same answer, rewritten as it should have been said
 */
record PracticeGrade(int structure, int specificity, int relevance, String feedback, String rewrite) {}
