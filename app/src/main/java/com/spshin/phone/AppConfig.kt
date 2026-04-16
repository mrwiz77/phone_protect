package com.spshin.phone

import com.spshin.phone.model.RestrictionPolicy

object AppConfig {
    const val familyCode = "SGF"
    const val parentPassword = "SPSHIN1234!!"
    val defaultRestrictionPolicy = RestrictionPolicy(
        startHour = 22,
        startMinute = 0,
        endHour = 6,
        endMinute = 0
    )
}
