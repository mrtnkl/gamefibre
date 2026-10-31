package com.gamefibre

import androidx.recyclerview.widget.DiffUtil

/** Account refreshes update only changed cards; unchanged artwork stays attached. */
internal class LibraryDiff(
    private val previous: List<LibraryEntry>,
    private val next: List<LibraryEntry>,
    private val presentationChanged: Boolean,
    private val previousScores: Map<String, Int?>,
    private val nextScores: Map<String, Int?>
) : DiffUtil.Callback() {
    override fun getOldListSize() = previous.size
    override fun getNewListSize() = next.size
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        previous[oldItemPosition].key == next[newItemPosition].key

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val old = previous[oldItemPosition]
        val updated = next[newItemPosition]
        return !presentationChanged && old == updated && previousScores[old.key] == nextScores[updated.key]
    }
}
