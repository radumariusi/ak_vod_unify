package ro.ak.vodunify.core.connector

/** Ce poate face un conector. Fiecare sursă declară doar ce suportă cu adevărat. */
enum class Capability {
    /** Citește istoricul de vizionare (filme/episoade văzute). */
    HISTORY_READ,

    /** Citește punctele de reluare (progres parțial). */
    RESUME_READ,

    /** Scrie/publică progresul de reluare înapoi în sursă. */
    RESUME_WRITE,

    /** Poate deschide conținutul în aplicația externă (deep link). */
    DEEP_LINK,
}
