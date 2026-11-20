package ro.ak.vodunify.core.connector

/** Ce poate face un conector. Fiecare sursă declară doar ce suportă cu adevărat. */
enum class Capability {
    /** Citește istoricul de vizionare (filme/episoade văzute complet). */
    HISTORY_READ,

    /** Marchează / demarchează titluri ca văzute. */
    HISTORY_WRITE,

    /** Citește punctele de reluare (progres parțial, separat de istoric). */
    RESUME_READ,

    /** Scrie/publică progresul de reluare înapoi în sursă. */
    RESUME_WRITE,

    /** Citește lista „De văzut” (watchlist). */
    WATCHLIST_READ,

    /** Adaugă / scoate din lista „De văzut”. */
    WATCHLIST_WRITE,

    /** Calculează „Următorul episod” și progresul pe seriale. */
    UP_NEXT_READ,

    /** Poate deschide conținutul în aplicația externă (deep link). */
    DEEP_LINK,
}
