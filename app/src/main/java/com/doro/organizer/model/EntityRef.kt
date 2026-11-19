package com.doro.organizer.model

/** A linked person or project. Ids travel with names because names may repeat (§1, DEC-32). */
data class EntityRef(val id: Long, val name: String) {
    override fun toString() = "EntityRef(id=$id)"
}
