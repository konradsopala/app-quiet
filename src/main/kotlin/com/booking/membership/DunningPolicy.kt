package com.booking.membership

/**
 * Declarative retry schedule for failed renewal charges.
 *
 * [retryOffsetsDays] are measured from the charge's due date: with the default
 * `[1, 3, 7]` a charge due on the 1st is retried on the 2nd, 4th and 8th. When
 * the last retry fails — or a non-retryable decline arrives — the membership is
 * suspended, and after [suspendedGraceDays] more days it expires.
 */
data class DunningPolicy(
    val retryOffsetsDays: List<Int> = listOf(1, 3, 7),
    val suspendedGraceDays: Int = 14,
    val stopOnNonRetryable: Boolean = true,
) {
    init {
        require(retryOffsetsDays.isNotEmpty()) { "At least one retry offset is required." }
        require(retryOffsetsDays.all { it > 0 }) { "Retry offsets must be positive." }
        require(retryOffsetsDays.zipWithNext().all { (a, b) -> a < b }) { "Retry offsets must be strictly increasing." }
        require(suspendedGraceDays >= 0) { "Suspended grace days cannot be negative." }
    }

    /** Total number of attempts including the first (on the due date). */
    val maxAttempts: Int get() = retryOffsetsDays.size + 1

    /**
     * Days after the due date at which attempt number [attempt] (1-based) is
     * made, or null if [attempt] exceeds the schedule.
     */
    fun offsetForAttempt(attempt: Int): Int? = when {
        attempt < 1 -> null
        attempt == 1 -> 0
        attempt - 2 < retryOffsetsDays.size -> retryOffsetsDays[attempt - 2]
        else -> null
    }

    /** True once [attemptsMade] has used up the whole schedule. */
    fun isExhausted(attemptsMade: Int): Boolean = attemptsMade >= maxAttempts

    companion object {
        val DEFAULT = DunningPolicy()

        /** A stricter policy for plans that shouldn't carry arrears. */
        val STRICT = DunningPolicy(retryOffsetsDays = listOf(2), suspendedGraceDays = 3)
    }
}
