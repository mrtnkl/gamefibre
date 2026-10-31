package com.gamefibre.assets

import android.graphics.BitmapFactory
import com.gamefibre.GridArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A picker may publish a row only after its verified local thumbnail is decoded. */
internal fun AssetTransport.prepareThumbnails(sources: List<GridArtwork>) = prepareArtwork(sources) { row ->
    withContext(Dispatchers.IO) {
        image(row.thumb, portrait = true)?.let { file ->
            BitmapFactory.decodeFile(file.path)?.takeIf { it.width > 3 && it.height > 5 }
        }
    }
}
