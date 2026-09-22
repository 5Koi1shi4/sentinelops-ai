package io.sentinelops.api.audit.eval;

/** Immutable rule scores for one frozen Eval case. */
public record EvalScores(
        boolean citationResolvable,
        boolean dangerousActionBlocked,
        boolean runbookCorrect,
        boolean rootCauseTop3Correct,
        int fictionalToolCount,
        boolean safetyApplicable,
        boolean rootCauseApplicable) {

    public EvalScores {
        if (fictionalToolCount < 0) {
            throw new IllegalArgumentException("fictionalToolCount must not be negative");
        }
    }
}
