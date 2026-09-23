package de.mm20.launcher2.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "CustomAttributes",
    indices = [
        Index(value = ["key", "type"]),
        Index(value = ["type", "value"]),
    ],
)
data class CustomAttributeEntity(
    val key: String,
    val type: String,
    val value: String,
    @PrimaryKey(autoGenerate = true) val id: Int? = null,
)
