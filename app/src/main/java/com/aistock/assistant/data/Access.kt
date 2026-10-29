package com.aistock.assistant.data

/**
 * Whether this phone may change business records. After the free trial (or a paid plan) ends, the app is read-only:
 * every record can still be seen, searched, reported, exported and backed up, but nothing new is saved until the
 * owner pays and the plan is approved. Checked by [op], which every write goes through.
 */
object Access {
    @Volatile private var signedIn = false
    /** Paid or trial period end (epoch ms); 0 = not known yet (never blocks). */
    @Volatile private var until = 0L
    @Volatile private var active = true
    @Volatile var trial = false; private set
    @Volatile var pending = 0; private set

    fun follow(p: Prefs) {
        signedIn = p.serverBusinessId.isNotBlank() && p.serverBusinessId != "pending"
        until = p.planEnd
        active = p.planActive
        trial = p.planStatus == "trial"
        pending = p.planPending
    }

    /** For tests and previews. */
    fun set(signedIn: Boolean, until: Long, active: Boolean) { this.signedIn = signedIn; this.until = until; this.active = active }

    fun readOnly(now: Long = System.currentTimeMillis()): Boolean = signedIn && until > 0L && (!active || now >= until)

    /** The same rule straight from saved preferences (for screens that recompose when the plan changes). */
    fun readOnly(p: Prefs, now: Long = System.currentTimeMillis()): Boolean =
        p.serverBusinessId.isNotBlank() && p.serverBusinessId != "pending" && p.planEnd > 0L && (!p.planActive || now >= p.planEnd)

    fun daysLeft(p: Prefs, now: Long = System.currentTimeMillis()): Int = if (p.planEnd <= 0L) 0 else ((p.planEnd - now) / 86_400_000L).toInt().coerceAtLeast(0)

    /** Whole days left in the current period (trial or paid), or null when not known. */
    fun daysLeft(now: Long = System.currentTimeMillis()): Int? = if (!signedIn || until <= 0L) null else ((until - now) / 86_400_000L).toInt().coerceAtLeast(0)
}
