package io.github.mshykhov.inboxwatcher.core

enum class Importance { IMPORTANT, NOT_IMPORTANT }

enum class Urgency { URGENT, NOT_URGENT }

enum class Category(
    val wire: String,
) {
    RECRUITER_INTERVIEW_REQUEST("recruiter-interview-request"),
    RECRUITER_REJECTION("recruiter-rejection"),
    RECRUITER_GENERIC("recruiter-generic"),
    AI_NEWS("ai-news"),
    TRANSACTIONAL("transactional"),
    OTHER("other"),
}

data class Classification(
    val importance: Importance,
    val urgency: Urgency,
    val category: Category,
    val summary: String,
    /** LLM's one-sentence English justification of the verdict; stored for debugging skips, never shown in the push. */
    val reason: String,
    /** Short company/organization name for the notification headline; null when the model can't tell. */
    val company: String? = null,
    /** Concrete next step required from the user; null when the email is informational or already confirmed. */
    val action: String? = null,
)
