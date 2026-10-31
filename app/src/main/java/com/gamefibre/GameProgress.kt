package com.gamefibre

/** Stable IDs are persisted and exported; labels are only used for presentation. */
internal enum class GameProgress(val id: String, val label: String) {
    UNPLAYED("unplayed", "Unplayed"),
    PLAY_NEXT("play-next", "Play next"),
    PLAYING("playing", "Playing"),
    ON_HOLD("on-hold", "On hold"),
    FINISHED("finished", "Finished");

    companion object {
        fun fromId(id: String?) = entries.find { it.id == id }
        fun fromLabel(label: String) = entries.find { it.label.equals(label, ignoreCase = true) }
    }
}
