package com.gamefibre

internal data class Attribution(val title: String, val notice: String)

/** Keep the overview short; copyright and license text lives in the notice viewer. */
internal val appAttributions = listOf(
    Attribution("Steam catalog, titles and community tags dataset", "licenses/fronkongames-steam-dataset-MIT.txt"),
    Attribution("DM Sans", "licenses/dm-sans-LICENSE.txt"),
    Attribution("Lucide & Feather icons", "licenses/lucide-LICENSE.txt"),
    Attribution("Open source licenses", "licenses/runtime-NOTICES.txt"),
)
