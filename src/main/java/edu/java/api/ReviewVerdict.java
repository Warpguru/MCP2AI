package edu.java.api;

/**
 * Possible verdicts returned by the {@link OpenAIChat#review} LLM reviewer.
 *
 * <ul>
 * <li>{@link #PASS} - the answer is correct, complete, and clear</li>
 * <li>{@link #FAIL} - the answer contains significant errors, is misleading, or fails to address the question</li>
 * <li>{@link #PARTIAL} - the answer is partially correct but incomplete, unclear, or improvable</li>
 * </ul>
 */
public enum ReviewVerdict {

    /** The answer is correct, complete, and clear. */
    PASS,

    /** The answer contains significant errors, is misleading, or fails to address the question. */
    FAIL,

    /** The answer is partially correct but incomplete, unclear, or improvable. */
    PARTIAL;

    /**
     * Returns the verdict name as a lowercase-comparable string (delegates to {@link Enum#name()}).
     * The LLM returns these values in upper-case; use {@link #name()} for JSON serialization.
     *
     * @return the enum constant name, e.g. {@code "PASS"}
     */
    @Override
    public String toString() {
        return name();
    }

}
