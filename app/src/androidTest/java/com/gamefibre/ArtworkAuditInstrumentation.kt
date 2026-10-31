package com.gamefibre

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.gamefibre.assets.*
import com.gamefibre.stores.StoreGame
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Tests the publication transaction and every exposed frame, including gestures and local misses. */
class ArtworkAuditInstrumentation:Instrumentation() {
    private var mode="all"
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);mode=arguments?.getString("mode") ?: "all";start()}
    private fun field(value:Any,name:String):Any?=value.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(value)
    private fun <T> ui(action:()->T):T {var result:Result<T>?=null;runOnMainSync{result=runCatching(action)};return result!!.getOrThrow()}
    private fun fixtures() {
        val name="asset-test-${UUID.randomUUID()}.sqlite"
        val directory=File(targetContext.cacheDir,name)
        val store=AssetStore(targetContext,name,directory)
        try {
            val image=Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888)
            image.eraseColor(android.graphics.Color.BLUE)
            val bytes=ByteArrayOutputStream().also{check(image.compress(Bitmap.CompressFormat.JPEG,90,it))}.toByteArray()
            val first=store.write(bytes)
            check(store.write(bytes)==first){"Content was not deduplicated"}
            val game=LibraryEntry(LibraryStore.GOG,"1","Original",remote=StoreGame("1","Original"))
            store.stage(game,"owner","group","r1",0,false)
            check(store.games().single().selected==null){"An unprepared game was published"}
            check(store.publish(game.key,"owner","r1",first,"https://example.com/1.jpg"))
            store.stage(game.copy(name="Changed"),"owner","group","r2",0,false)
            check(store.games().single().game.name=="Original"){"Staging replaced published metadata"}
            check(!store.publish(game.key,"owner","r1",first,"https://example.com/stale.jpg")){"Stale work published"}
            store.failed(AssetStore.Job(game.key,0,"r2"),"Network failure")
            check(store.games().single().selected?.blob==first){"Failure removed known art"}
            check(store.publish(game.key,"owner","r2",first,"https://example.com/2.jpg"))
            check(store.games().single().game.name=="Changed")
            store.stage(game,"new-owner","group","r3",0,false)
            check(!store.publish(game.key,"owner","r2",first,"https://example.com/old-owner.jpg")){"Old account result published"}
            check(store.publish(game.key,"new-owner","r3",first,"https://example.com/3.jpg"))
            store.close()
            val reopened=AssetStore(targetContext,name,directory)
            check(reopened.games().single().selected?.blob==first){"Published image did not survive restart"}
            val garbageImage=Bitmap.createBitmap(40,60,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.RED)}
            val garbage=reopened.write(ByteArrayOutputStream().also{garbageImage.compress(Bitmap.CompressFormat.JPEG,90,it)}.toByteArray())
            val old=System.currentTimeMillis()-2L*24*60*60*1000
            reopened.blob(first).setLastModified(old);reopened.blob(garbage).setLastModified(old)
            reopened.trimTemporaryImages(0)
            check(reopened.blob(first).isFile){"Temporary cleanup deleted published art"}
            check(!reopened.blob(garbage).exists()){"Unselected old transport object was not reclaimed"}
            val second=LibraryEntry(LibraryStore.STEAM,"1","Original")
            reopened.stage(second,"other-owner","group","r4",0,false)
            check(!reopened.publish(game.key,"new-owner","r3",first,"https://example.com/stale-group.jpg")){"A partially staged group accepted old work"}
            reopened.stage(game,"new-owner","group","r4",0,false)
            check(reopened.publish(game.key,"new-owner","r4",first,"https://example.com/new-group.jpg"))
            reopened.close()
        } finally {runCatching{store.close()};targetContext.deleteDatabase(name);directory.deleteRecursively()}
    }
    private fun recycler(activity:Activity)=field(activity,"libraryRecycler") as? RecyclerView
    private fun pager(activity:Activity)=(field(activity,"retainedLibrary")?.let{field(it,"pager")}) as? ViewPager2
    private fun pageNames(activity:Activity):List<String> {
        val screen=field(activity,"retainedLibrary") ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return screen.javaClass.getDeclaredMethod("getPageNames").apply{isAccessible=true}.invoke(screen) as List<String>
    }
    private fun navigate(activity:Activity,name:String) = ui {
        val screen=field(activity,"retainedLibrary") ?: error("No library")
        val index=pageNames(activity).indexOf(name);check(index>=0)
        screen.javaClass.getDeclaredMethod("navigate",Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(screen,index)
    }
    private fun auditFrames(activity:Activity):Pair<Int,List<String>> {
        val missing=mutableListOf<String>();var cards=0
        val screen=field(activity,"retainedLibrary") ?: return 0 to missing
        @Suppress("UNCHECKED_CAST")
        val pages=field(screen,"pages") as Map<String,Any>
        for((name,page)in pages) {
            val list=field(page,"recycler") as RecyclerView
            if(!list.isShown)continue
            for(index in 0 until list.childCount) {
                val child=list.getChildAt(index)
                if(!child.isShown || !child.getGlobalVisibleRect(Rect()))continue
                val holder=list.getChildViewHolder(child)
                val image=field(holder,"art") as ImageView
                val bitmap=(image.drawable as? BitmapDrawable)?.bitmap
                cards++
                if(bitmap==null || bitmap.width<=3 || bitmap.height<=5 || image.alpha<.99f)missing.add("$name:${child.contentDescription}")
            }
        }
        return cards to missing
    }
    private fun await(activity:Activity,name:String,timeout:Long=120_000) {
        val end=SystemClock.elapsedRealtime()+timeout
        var lastReport=0L
        while(SystemClock.elapsedRealtime()<end) {
            if(SystemClock.elapsedRealtime()-lastReport>5000) {
                lastReport=SystemClock.elapsedRealtime()
                val snapshot=AssetRepository.get(targetContext).library.value
                val diagnostic=ui {
                    val retained=field(activity,"retainedLibrary")
                    @Suppress("UNCHECKED_CAST") val pages=retained?.let{field(it,"pages")} as? Map<String,Any>
                    "current=${pager(activity)?.currentItem}, selected=${field(activity,"collection")?.let {it.javaClass.getMethod("getList").invoke(it)}}, shown=${pager(activity)?.isShown}, mainCount=${recycler(activity)?.adapter?.itemCount}, frames=${auditFrames(activity)}, input=${pager(activity)?.isUserInputEnabled}, requested=${retained?.let{field(it,"requestedPage")}}; "+pages?.map { (pageName,page) ->
                        val adapter=field(page,"adapter")!!
                        val window=field(adapter,"window")!!
                        "$pageName[ready=${field(adapter,"ready")}, count=${(adapter as RecyclerView.Adapter<*>).itemCount}, window=${(field(window,"cards") as List<*>).size}, images=${(field(window,"images") as Map<*,*>).size}, owner=${field(page,"holder")!=null}, latest=${(field(adapter,"latest") as List<*>).size}, prep=${field(window,"preparing")}, pub=${(field(adapter,"latest") as List<*>).count { AssetRepository.get(targetContext).published(it as LibraryEntry)!=null }}]"
                    }
                }
                sendStatus(0,Bundle().apply{putString("preparation","$name: ${snapshot.cards.size}/${snapshot.discovered}, pending=${snapshot.pending}; $diagnostic")})
            }
            val ready=ui {
                val screen=field(activity,"retainedLibrary")
                val p=pager(activity)
                val collection=field(activity,"collection")
                val selected=collection?.javaClass?.getMethod("getList")?.invoke(collection)
                screen!=null && p?.isShown==true && p.isUserInputEnabled && p.scrollState==ViewPager2.SCROLL_STATE_IDLE && selected==name && (recycler(activity)?.adapter?.itemCount ?: 0)>0 && auditFrames(activity).let{it.first>0 && it.second.isEmpty()}
            }
            if(ready)return
            SystemClock.sleep(100)
        }
        error("$name did not become render-ready; display=${(targetContext.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isInteractive}; ${ui{auditFrames(activity)}}")
    }
    private fun scrolling(activity:Activity):String {
        navigate(activity,"All games");await(activity,"All games")
        ui{(field(activity,"appBar") as com.google.android.material.appbar.AppBarLayout).setExpanded(false,false)}
        val list=ui{recycler(activity)!!}
        fun offset()=ui {
            val layout=list.layoutManager as androidx.recyclerview.widget.GridLayoutManager
            val first=layout.findFirstVisibleItemPosition()
            val child=layout.findViewByPosition(first)!!
            first/layout.spanCount*layout.getDecoratedMeasuredHeight(child)-layout.getDecoratedTop(child)
        }
        val measurements=mutableListOf<String>()
        for(direction in listOf(1,-1)) {
            ui{list.scrollToPosition(list.adapter!!.itemCount*(if(direction>0)1 else 2)/3)}
            SystemClock.sleep(350)
            val before=offset()
            val speed=ui{minOf(16000,list.maxFlingVelocity)}*direction
            val expected=ui {
                android.widget.OverScroller(activity).apply{fling(0,0,0,speed,0,0,Int.MIN_VALUE,Int.MAX_VALUE)}.finalY
            }
            check(ui{list.fling(0,speed)})
            val end=SystemClock.elapsedRealtime()+8000
            do {SystemClock.sleep(30)} while(ui{list.scrollState!=RecyclerView.SCROLL_STATE_IDLE} && SystemClock.elapsedRealtime()<end)
            val actual=offset()-before
            check(kotlin.math.abs(actual)>=kotlin.math.abs(expected)*.85){"Fling stopped early: actual=$actual expected=$expected direction=$direction"}
            measurements.add("$actual/$expected px")
        }
        ui{list.scrollToPosition(list.adapter!!.itemCount/2)};SystemClock.sleep(350)
        val before=offset()
        val rect=ui{Rect().also{check(list.getGlobalVisibleRect(it))}}
        val down=SystemClock.uptimeMillis()
        fun event(action:Int,x:Float,y:Float) {
            android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).also{sendPointerSync(it);it.recycle()}
        }
        val x=rect.left+rect.width()*.3f;val y=rect.top+rect.height()*.25f
        event(android.view.MotionEvent.ACTION_DOWN,x,y)
        repeat(24){step ->
            SystemClock.sleep(12)
            // Start vertically, then drift sideways without changing the established axis.
            event(android.view.MotionEvent.ACTION_MOVE,x+rect.width()*.45f*step/23,y+rect.height()*.5f*step/23)
        }
        event(android.view.MotionEvent.ACTION_UP,x+rect.width()*.45f,y+rect.height()*.5f)
        SystemClock.sleep(600)
        check(ui{pager(activity)!!.currentItem==pageNames(activity).indexOf("All games")}){"Vertical drag changed library page"}
        check(offset()<before-list.height/4){"Vertical drag did not scroll upward"}
        check(ui{auditFrames(activity)}.let{it.first>0 && it.second.isEmpty()})
        return "PASS native fling distance down/up ${measurements.joinToString()}; diagonal upward drag stayed in All games"
    }
    override fun onStart() {
        val output=Bundle();var activity:Activity?=null;var listener:ViewTreeObserver.OnPreDrawListener?=null
        var frameCount=0;var cardFrames=0;val bad=mutableListOf<String>()
        var network:(()->Boolean)?=null
        val durations=java.util.Collections.synchronizedList(mutableListOf<Long>())
        var metricsListener:android.view.Window.OnFrameMetricsAvailableListener?=null
        val transport=AssetTransport.get(targetContext)
        if(mode=="custom-artwork") {
            try {
                output.putString("result",CustomArtworkPublicationAudit.run(targetContext))
                finish(Activity.RESULT_OK,output)
            } catch(error:Throwable) {
                output.putString("result","FAIL: ${error.stackTraceToString()}")
                finish(Activity.RESULT_CANCELED,output)
            }
            return
        }
        if(mode=="store-game-pane") {
            try {
                val screen=startActivitySync(Intent(targetContext,MainActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)})
                output.putString("result",StoreGamePaneAudit.run(this,screen))
                finish(Activity.RESULT_OK,output)
            } catch(error:Throwable) {
                output.putString("result","FAIL: ${error.stackTraceToString()}")
                finish(Activity.RESULT_CANCELED,output)
            }
            return
        }
        if(mode=="store-search") {
            try {
                val screen=startActivitySync(Intent(targetContext,MainActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)})
                output.putString("result",StoreSearchAudit.run(this,screen))
                finish(Activity.RESULT_OK,output)
            } catch(error:Throwable) {
                output.putString("result","FAIL: ${error.stackTraceToString()}")
                finish(Activity.RESULT_CANCELED,output)
            }
            return
        }
        if(mode=="tags") {
            try {
                val screen=startActivitySync(Intent(targetContext,MainActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)})
                output.putString("result",TagFiltersAudit.run(this,screen))
                finish(Activity.RESULT_OK,output)
            } catch(error:Throwable) {
                output.putString("result","FAIL: ${error.stackTraceToString()}")
                finish(Activity.RESULT_CANCELED,output)
            }
            return
        }
        if(mode=="artwork-dialog") {
            try {
                val screen=startActivitySync(Intent(targetContext,MainActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)})
                BatchArtworkDialogAudit.run(this,screen)
                output.putString("result","PASS: stable artwork dialog height and scrolling results")
                finish(Activity.RESULT_OK,output)
            } catch(error:Throwable) {
                output.putString("result","FAIL: ${error.stackTraceToString()}")
                finish(Activity.RESULT_CANCELED,output)
            }
            return
        }
        try {
            fixtures();output.putString("transactions","PASS: staging, failure retention, stale/account guards, deduplication, restart")
            if(mode!="fixtures") {
                if(mode=="offline" || mode=="scroll") {network=transport.networkAvailable;transport.networkAvailable={false}}
                val screen=startActivitySync(Intent(targetContext,MainActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)})
                activity=screen
                listener=ViewTreeObserver.OnPreDrawListener {
                    frameCount++
                    val result=auditFrames(screen);cardFrames+=result.first
                    if(result.second.isNotEmpty())bad.addAll(result.second)
                    true
                }
                metricsListener=android.view.Window.OnFrameMetricsAvailableListener { _,frame,_ -> durations.add(frame.getMetric(android.view.FrameMetrics.TOTAL_DURATION)) }
                ui{screen.window.addOnFrameMetricsAvailableListener(metricsListener!!,android.os.Handler(android.os.Looper.getMainLooper()));screen.setTurnScreenOn(true);screen.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);screen.window.decorView.viewTreeObserver.addOnPreDrawListener(listener)}
                waitForIdleSync()
                val firstRequestCount=transport.requests.get()
                if(mode=="scroll")output.putString("scroll",scrolling(screen))
                val names=if(mode=="scroll")emptyList()else ui{pageNames(screen)}.filter{it in setOf("All games","Steam","GOG","Epic","Xbox")}
                for(name in names) {
                    navigate(screen,name);await(screen,name)
                    sendStatus(0,Bundle().apply{putString("page", "$name ready")})
                    repeat(8) {ui{recycler(screen)?.smoothScrollBy(0,650)};SystemClock.sleep(250)}
                    val count=ui{recycler(screen)?.adapter?.itemCount ?: 0}
                    for(position in listOf(count/2,(count-1).coerceAtLeast(0),count/4,0)) {
                        ui{recycler(screen)?.scrollToPosition(position)};SystemClock.sleep(300)
                        check(ui{auditFrames(screen)}.let{it.first>0 && it.second.isEmpty()}){"Position $position not ready in $name"}
                    }
                }
                if(network==null)network=transport.networkAvailable
                transport.networkAvailable={false}
                SystemClock.sleep(1000)
                val requests=transport.requests.get()
                for(name in names.reversed()) {navigate(screen,name);await(screen,name,15_000);repeat(6){ui{recycler(screen)?.smoothScrollBy(0,800)};SystemClock.sleep(200)}}
                if(mode=="offline")check(transport.requests.get()==firstRequestCount){"Offline startup issued image HTTP"}
                check(transport.requests.get()==requests){"Offline navigation issued HTTP requests"}
                val choices=ui{pageNames(screen)}
                val start=choices.indexOf("GOG").coerceAtLeast(0)
                if(start<choices.lastIndex) {
                    navigate(screen,choices[start]);await(screen,choices[start])
                    val bounds=ui {val p=pager(screen)!!;val visible=Rect();check(p.getGlobalVisibleRect(visible));intArrayOf(visible.left,visible.top,visible.width(),visible.height())}
                    fun swipe(forward:Boolean) {
                        val time=SystemClock.uptimeMillis()
                        val from=bounds[0]+bounds[2]*(if(forward).85f else .15f)
                        val to=bounds[0]+bounds[2]*(if(forward).15f else .85f)
                        val y=bounds[1]+bounds[3]*.5f
                        fun event(action:Int,x:Float,at:Long) {android.view.MotionEvent.obtain(time,at,action,x,y,0).also{sendPointerSync(it);it.recycle()}}
                        event(android.view.MotionEvent.ACTION_DOWN,from,time)
                        repeat(20){i->SystemClock.sleep(10);event(android.view.MotionEvent.ACTION_MOVE,from+(to-from)*(i+1)/20,SystemClock.uptimeMillis())}
                        event(android.view.MotionEvent.ACTION_UP,to,SystemClock.uptimeMillis())
                    }
                    swipe(true);await(screen,choices[start+1]);swipe(false);await(screen,choices[start])
                    output.putString("swipes","PASS: real touch gestures with frame audit")
                }
                if(mode=="all") {
                    transport.networkAvailable=network!!
                    val before=AssetRepository.get(targetContext).library.value.cards
                    ui{screen.javaClass.getDeclaredMethod("refreshLibraries").apply{isAccessible=true}.invoke(screen)}
                    val refreshStarted=SystemClock.elapsedRealtime()
                    val deadline=refreshStarted+180_000
                    var refreshing=true
                    var observedBackgroundRefresh=false
                    while(refreshing && SystemClock.elapsedRealtime()<deadline) {
                        SystemClock.sleep(100)
                        if(SystemClock.elapsedRealtime()-refreshStarted>1200) {
                            check(ui{!(field(field(screen,"retainedLibrary")!!,"refresh") as androidx.swiperefreshlayout.widget.SwipeRefreshLayout).isRefreshing}){"Refresh indicator remained visible during background work"}
                        }
                        check(AssetRepository.get(targetContext).library.value.cards.keys.containsAll(before.keys)){"Refresh removed published games"}
                        refreshing=ui{screen.javaClass.getDeclaredMethod("getLibraryLoading").apply{isAccessible=true}.invoke(screen) as Boolean}
                        if(refreshing && SystemClock.elapsedRealtime()-refreshStarted>1200)observedBackgroundRefresh=true
                    }
                    check(!refreshing){"Store refresh did not complete in three minutes"}
                    check(ui{auditFrames(screen)}.let{it.first>0 && it.second.isEmpty()}){"Refresh exposed unprepared cards"}
                    output.putString("refresh","PASS: published membership and artwork retained")
                    output.putString("indicator",if(observedBackgroundRefresh)"PASS: spinner dismissed while refresh continued" else "PASS: refresh completed promptly")
                }
                val unresolved=AssetTransport.get(targetContext).store.games().filter{it.selected==null}
                output.putString("unresolved",unresolved.joinToString {"${it.game.name} (${it.game.key})"})
                check(bad.isEmpty()){ "Placeholder frames: ${bad.take(20)}" }
                check(cardFrames>0)
                val sorted=synchronized(durations){durations.toList()}.sorted()
                val p95=sorted.getOrNull((sorted.size*.95).toInt())?.div(1_000_000.0)
                output.putString("timing","frame p95=${p95}ms; max=${sorted.lastOrNull()?.div(1_000_000.0)}ms; heap=${(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1048576}MiB")
                output.putString("frames","$frameCount frames; $cardFrames card observations; zero placeholders")
                output.putString("offline","PASS: local navigation and scrolling; no image HTTP")
                output.putString("result","PASS")
            } else output.putString("result","PASS")
        } catch(error:Throwable) {output.putString("result","FAIL: ${error.stackTraceToString()}")}
        finally {
            network?.let{transport.networkAvailable=it}
            val screen=activity
            if(screen!=null && listener!=null)runCatching{ui{screen.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);metricsListener?.let {screen.window.removeOnFrameMetricsAvailableListener(it)};screen.window.decorView.viewTreeObserver.removeOnPreDrawListener(listener)}}
        }
        finish(if(output.getString("result")=="PASS")Activity.RESULT_OK else Activity.RESULT_CANCELED,output)
    }
}
