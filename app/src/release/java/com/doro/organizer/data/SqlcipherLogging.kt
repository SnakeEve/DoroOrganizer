package com.doro.organizer.data

import net.zetetic.database.Logger
import net.zetetic.database.NoopTarget

/** Release, qa and benchmark: SQLCipher's Java logger writes nothing (DEC-08, DEC-70). */
internal object SqlcipherLogging {
    fun configure() {
        Logger.setTarget(NoopTarget())
    }
}
