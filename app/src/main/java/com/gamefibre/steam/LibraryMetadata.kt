package com.gamefibre.steam

import org.json.JSONObject

/** Each library record explicitly tracks whether its metadata is pending. */
internal fun needsSteamMetadata(game: JSONObject): Boolean =
    game.getBoolean("metadataPending")

/** An interrupted refresh resumes its pending records, rather than restarting completed batches. */
internal fun refreshAllSteamMetadata(previous: List<JSONObject>, force: Boolean): Boolean =
    force && previous.none(::needsSteamMetadata)
