package com.gamefibre.assets

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Parcelable
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

/** Strong local handles are prepared ahead of layout; binding performs no I/O. */
internal class ReadyArtworkWindow(private val repository:AssetRepository,private val scope:CoroutineScope) {
    private var images=emptyMap<String,Bitmap>()
    private var preparing:Job?=null
    private var warming:Job?=null
    private var wanted=IntRange.EMPTY
    var cards:List<PublishedCard> = emptyList();private set
    var localMisses=0;private set
    fun bitmap(position:Int):Bitmap = images[cards[position].asset.blob] ?: error("Unprepared card entered layout at $position")
    fun ready(range:IntRange):Boolean=range.all { it !in cards.indices || images.containsKey(cards[it].asset.blob) }
    fun prepare(value:List<PublishedCard>,range:IntRange,canCommit:()->Boolean={true},done:()->Unit) {
        preparing?.cancel();warming?.cancel()
        preparing=scope.launch {
            val decoded=mutableMapOf<String,Bitmap>()
            for(card in range.filter { it in value.indices }.map { value[it] }.distinctBy { it.asset.blob }) {
                try { decoded[card.asset.blob]=repository.bitmap(card) }
                catch(error:CancellationException){throw error}
                catch(error:Exception){repository.repair(card);return@launch}
            }
            while(!canCommit())delay(16)
            ensureActive()
            images=decoded;cards=value;wanted=range
            done()
        }
    }
    /** Coalesce look-ahead without cancelling a useful decode whenever another scroll frame arrives. */
    fun warm(range:IntRange) {
        wanted=range
        if(warming?.isActive==true)return
        warming=scope.launch {
            while(isActive) {
                val target=wanted
                val snapshot=cards
                for(position in target) {
                    val card=snapshot.getOrNull(position) ?: continue
                    if(images.containsKey(card.asset.blob))continue
                    val bitmap=try {repository.bitmap(card)}catch(error:CancellationException){throw error}
                        catch(error:Exception){repository.repair(card);continue}
                    if(cards!==snapshot)return@launch
                    images=images+(card.asset.blob to bitmap)
                    if(wanted!=target)break
                }
                if(wanted==target) {
                    val keep=target.mapNotNull { cards.getOrNull(it)?.asset?.blob }.toSet()
                    images=images.filterKeys { it in keep }
                    break
                }
            }
        }
    }
    /** Exceptional local miss: finish before layout, never report a fictitious scroll boundary. */
    fun requireLocal(range:IntRange) {
        for(position in range) {
            val card=cards.getOrNull(position) ?: continue
            if(images.containsKey(card.asset.blob))continue
            localMisses++
            images=images+(card.asset.blob to repository.bitmapLocal(card))
        }
    }
    fun close(){preparing?.cancel();warming?.cancel();images=emptyMap();cards=emptyList()}
}

/** Native GridLayoutManager owns distance, velocity, nested scrolling and fling termination. */
internal class ArtworkGridLayoutManager(context:Context,span:Int,
    private val window:ReadyArtworkWindow,
    private val changed:()->Unit
):GridLayoutManager(context,span) {
    private val minimumRowHeight=(72*context.resources.displayMetrics.density).toInt()
    private var requestedPosition:Int?=null
    private var requestedOffset=0
    init {isItemPrefetchEnabled=false}
    override fun supportsPredictiveItemAnimations()=false
    override fun calculateExtraLayoutSpace(state:RecyclerView.State,extraLayoutSpace:IntArray) {
        // The artwork look-ahead is independent of RecyclerView's offscreen view creation.
        extraLayoutSpace[0]=0;extraLayoutSpace[1]=0
    }
    private fun viewport(first:Int,dy:Int=0):IntRange {
        val rowHeight=getChildAt(0)?.let(::getDecoratedMeasuredHeight)?.takeIf { it>0 } ?: minimumRowHeight
        val rows=height/rowHeight+3
        val displacement=(kotlin.math.abs(dy)+rowHeight-1)/rowHeight*spanCount
        val start=(first/spanCount*spanCount-rows*spanCount-displacement).coerceAtLeast(0)
        return start until (first+rows*spanCount+displacement).coerceAtMost(window.cards.size)
    }
    private fun lookAhead(first:Int):IntRange {
        val visible=viewport(first)
        return (visible.first-spanCount*8).coerceAtLeast(0) until (visible.last+1+spanCount*8).coerceAtMost(window.cards.size)
    }
    override fun onLayoutChildren(recycler:RecyclerView.Recycler,state:RecyclerView.State) {
        if(state.itemCount>0 && window.cards.isEmpty())return
        val first=requestedPosition ?: findFirstVisibleItemPosition().coerceAtLeast(0)
        window.requireLocal(viewport(first))
        requestedPosition?.let {super.scrollToPositionWithOffset(it,requestedOffset);requestedPosition=null}
        super.onLayoutChildren(recycler,state)
        window.warm(lookAhead(findFirstVisibleItemPosition().coerceAtLeast(0)))
        changed()
    }
    override fun scrollVerticallyBy(dy:Int,recycler:RecyclerView.Recycler,state:RecyclerView.State):Int {
        window.requireLocal(viewport(findFirstVisibleItemPosition().coerceAtLeast(0),dy))
        val consumed=super.scrollVerticallyBy(dy,recycler,state)
        window.warm(lookAhead(findFirstVisibleItemPosition().coerceAtLeast(0)))
        changed()
        return consumed
    }
    override fun scrollToPosition(position:Int){scrollToPositionWithOffset(position,0)}
    override fun scrollToPositionWithOffset(position:Int,offset:Int) {
        requestedPosition=position;requestedOffset=offset
        super.scrollToPositionWithOffset(position,offset)
    }
    override fun onSaveInstanceState():Parcelable=Bundle().apply {
        val position=findFirstVisibleItemPosition().coerceAtLeast(0);putInt("position",position)
        putInt("offset",findViewByPosition(position)?.let(::getDecoratedTop) ?: 0)
    }
    override fun onRestoreInstanceState(state:Parcelable?){(state as? Bundle)?.let{scrollToPositionWithOffset(it.getInt("position"),it.getInt("offset"))}}
}
