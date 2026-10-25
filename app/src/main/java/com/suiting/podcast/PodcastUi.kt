@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.suiting.podcast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

internal data class EpisodeActions(
    val open: (Episode) -> Unit,
    val toggle: (Episode) -> Unit,
    val paragraph: (Episode, Int) -> Unit,
    val seek: (Episode, ParagraphPosition) -> Unit,
    val generate: (Episode) -> Unit,
    val stop: () -> Unit,
    val delete: (Episode) -> Unit,
    val probe: (String) -> Unit,
    val speed: (Float) -> Unit,
    val cover: (Episode) -> Unit
)

@Composable internal fun ProgressLine(value: Float, available: Float = 1f, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        val stroke=size.height
        fun line(fraction: Float, color: Color) { if(fraction>0) drawLine(color,Offset(0f,stroke/2),Offset(size.width*fraction.coerceIn(0f,1f),stroke/2),stroke,StrokeCap.Round) }
        line(1f,Line);line(available,Color(0xFFA1B99D));line(value,Green)
    }
}

@Composable internal fun ScreenHeading(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=onBack) { UiGlyph(Glyph.BACK,"返回") }
        Text(title,Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center)
        Box(Modifier.widthIn(min=48.dp),contentAlignment=Alignment.Center) {trailing()}
    }
}

@Composable private fun EpisodeMenu(e: Episode, ready: Int, actions: EpisodeActions) {
    var expanded by remember(e.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick={expanded=true}) { UiGlyph(Glyph.MORE,"节目操作") }
        DropdownMenu(expanded,onDismissRequest={expanded=false}) {
            if(e.status=="generating") DropdownMenuItem(text={Text("停止生成")},onClick={expanded=false;actions.stop()})
            else if(ready<e.segments.size) DropdownMenuItem(text={Text("继续生成")},onClick={expanded=false;actions.generate(e)},enabled=e.status!="deleting")
            DropdownMenuItem(text={Text("试听音色")},onClick={expanded=false;actions.probe(e.voice)})
            DropdownMenuItem(text={Text("更换封面")},onClick={expanded=false;actions.cover(e)},enabled=e.status!="deleting")
            DropdownMenuItem(text={Text("删除节目",color=MaterialTheme.colorScheme.error)},onClick={expanded=false;actions.delete(e)},enabled=e.status!="deleting")
        }
    }
}

@Composable internal fun LibraryScreen(
    episodes: List<Episode>, readyCounts: Map<String,Int>, playback: PlaybackUi, resume: Episode?,
    actions: EpisodeActions, onAdd: () -> Unit, onVoices: () -> Unit, onAbout: () -> Unit,
    hasDraft: Boolean, loadError: String?
) {
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var search by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val filtered=remember(episodes,readyCounts,filter,search) { episodes.filter { filter.matches(it,readyCounts[it.id]?:0) && it.title.contains(search.trim(),true) } }
    BackHandler(searching) {searching=false;search=""}
    Scaffold(modifier=Modifier.fillMaxSize().safeDrawingPadding(),containerColor=Cream,contentWindowInsets=WindowInsets(0,0,0,0),
        topBar={
            Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                if(searching) {
                    OutlinedTextField(search,{search=it},Modifier.weight(1f).semantics {contentDescription="搜索节目标题"},singleLine=true,
                        placeholder={Text("搜索节目标题",fontSize=14.sp)},leadingIcon={IconButton(onClick={searching=false;search=""}){UiGlyph(Glyph.BACK,"关闭搜索")}},shape=RoundedCornerShape(14.dp))
                } else {
                    Text("随听",fontSize=27.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold,color=Green)
                    UiGlyph(Glyph.WAVE,color=Green,modifier=Modifier.padding(start=8.dp).size(18.dp))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick={searching=true}) {UiGlyph(Glyph.SEARCH,"搜索节目")}
                }
                Box {
                    IconButton(onClick={menu=true}) {UiGlyph(Glyph.MORE,"更多选项")}
                    DropdownMenu(menu,onDismissRequest={menu=false}) {
                        DropdownMenuItem(text={Text("音色试听")},onClick={menu=false;onVoices()})
                        DropdownMenuItem(text={Text("关于随听")},onClick={menu=false;onAbout()})
                    }
                }
                FilledIconButton(onClick=onAdd,modifier=Modifier.size(42.dp),shape=RoundedCornerShape(14.dp)) {UiGlyph(Glyph.ADD,"添加节目",Color.White)}
            }
        },bottomBar={
            if(resume!=null && (readyCounts[resume.id]?:0)>0) MiniPlayer(resume,readyCounts[resume.id]?:0,playback,actions)
        }
    ) {padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(start=22.dp,end=22.dp,top=12.dp,bottom=24.dp)) {
            if(loadError!=null) item {NoticeCard(loadError,true);Spacer(Modifier.height(12.dp))}
            if(resume!=null) {
                item {Text("继续收听",fontSize=15.sp,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(12.dp))}
                item {ContinueCard(resume,readyCounts[resume.id]?:0,playback,actions);Spacer(Modifier.height(20.dp))}
            }
            item {
                Row(Modifier.fillMaxWidth()) {
                    LibraryFilter.entries.forEach { value ->
                        Column(Modifier.weight(1f).clickable {filter=value}.padding(top=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(value.label,color=if(value==filter)Green else Muted,fontWeight=if(value==filter)FontWeight.SemiBold else FontWeight.Normal,fontSize=14.sp,modifier=Modifier.padding(bottom=12.dp))
                            Box(Modifier.fillMaxWidth(.78f).height(2.dp).background(if(value==filter)Green else Color.Transparent,RoundedCornerShape(2.dp)))
                        }
                    }
                }
                HorizontalDivider(color=Line,thickness=.7.dp)
                Row(Modifier.fillMaxWidth().padding(top=23.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("我的节目",Modifier.weight(1f),fontSize=21.sp,fontWeight=FontWeight.SemiBold)
                    Text("${filtered.size} 期",fontSize=12.sp,color=Muted)
                }
            }
            if(filtered.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical=35.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    UiGlyph(Glyph.DOCUMENT,color=Green,modifier=Modifier.size(36.dp))
                    Text(if(episodes.isEmpty())"把想读的，留给耳朵" else "这里还没有节目",fontSize=18.sp,fontWeight=FontWeight.Medium)
                    Text(if(episodes.isEmpty())"粘贴文字，或导入一篇文章与 PDF。" else "试试其他筛选，或换个标题搜索。",fontSize=13.sp,color=Muted,textAlign=TextAlign.Center)
                }
            }
            items(filtered,key={it.id}) {e -> EpisodeRow(e,readyCounts[e.id]?:0,actions)}
            item {
                Button(onClick=onAdd,modifier=Modifier.fillMaxWidth().padding(top=16.dp).heightIn(min=48.dp),shape=RoundedCornerShape(12.dp),
                    colors=ButtonDefaults.buttonColors(containerColor=Sage,contentColor=Green)) {
                    UiGlyph(Glyph.ADD,color=Green,modifier=Modifier.size(19.dp));Spacer(Modifier.width(9.dp));Text(if(hasDraft)"继续编辑草稿" else "添加节目")
                }
            }
        }
    }
}

@Composable private fun ContinueCard(e: Episode, ready: Int, playback: PlaybackUi, actions: EpisodeActions) {
    val timeline=EpisodeTimeline(e,ready)
    val index=playback.index(e,ready)
    val elapsed=timeline.elapsed(index,playback.position(e))
    Surface(onClick={actions.open(e)},shape=RoundedCornerShape(16.dp),color=Color.White,shadowElevation=2.dp) {
        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            EpisodeCover(e,Modifier.size(76.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(e.title,fontSize=15.sp,lineHeight=21.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(voiceLabel(e.voice),fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                ProgressLine(timeline.fraction(index,playback.position(e)),timeline.availableFraction)
                Text("${timeLabel(elapsed)} / ${if(timeline.estimated)"约 " else ""}${timeLabel(timeline.totalMs)}",fontSize=10.sp,color=Muted)
            }
            RoundPlayButton(playback.requested(e,ready),{actions.toggle(e)},Modifier.size(40.dp),enabled=ready>0)
        }
    }
}

@Composable private fun EpisodeRow(e: Episode, ready: Int, actions: EpisodeActions) {
    val timeline=EpisodeTimeline(e,ready)
    Row(Modifier.fillMaxWidth().clickable {actions.open(e)}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)) {
        EpisodeCover(e,Modifier.size(68.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(e.title,fontSize=15.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text("${if(timeline.estimated)"约 " else ""}${ceil(timeline.totalMs/60000.0).toInt()} 分钟 · ${voiceLabel(e.voice).substringBefore(" ·")}",fontSize=12.sp,color=Muted)
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                if(e.status=="generating") CircularProgressIndicator(progress={ready.toFloat()/e.segments.size},modifier=Modifier.size(13.dp),strokeWidth=1.5.dp,color=Green,trackColor=Line)
                else if(ready==e.segments.size) UiGlyph(Glyph.CHECK,color=Color(0xFF59875D),modifier=Modifier.size(13.dp))
                Text(if(e.status=="generating")"生成中  $ready / ${e.segments.size} 段" else if(ready==e.segments.size)"已下载" else if(ready>0)"已可听 $ready / ${e.segments.size} 段" else statusLabel(e),fontSize=11.sp,color=if(e.status=="failed")MaterialTheme.colorScheme.error else Muted)
            }
        }
        EpisodeMenu(e,ready,actions)
    }
}

@Composable private fun MiniPlayer(e: Episode, ready: Int, playback: PlaybackUi, actions: EpisodeActions) {
    val timeline=EpisodeTimeline(e,ready)
    val index=playback.index(e,ready)
    Surface(onClick={actions.open(e)},modifier=Modifier.padding(horizontal=14.dp,vertical=9.dp).fillMaxWidth(),shape=RoundedCornerShape(17.dp),color=Color.White,shadowElevation=7.dp) {
        Row(Modifier.padding(11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)) {
            EpisodeCover(e,Modifier.size(46.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(e.title,fontSize=12.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("${timeLabel(timeline.elapsed(index,playback.position(e)))} / ${if(timeline.estimated)"约 " else ""}${timeLabel(timeline.totalMs)}",fontSize=10.sp,color=Muted)
                ProgressLine(timeline.fraction(index,playback.position(e)),timeline.availableFraction,Modifier.padding(top=1.dp))
            }
            val playing=playback.requested(e,ready)
            RoundPlayButton(playing,{actions.toggle(e)},Modifier.size(42.dp),description=if(playing)"迷你播放器暂停" else "迷你播放器播放")
        }
    }
}

@Composable internal fun NoticeCard(message: String, error: Boolean = false) {
    Surface(color=if(error)MaterialTheme.colorScheme.errorContainer.copy(alpha=.55f)else Sage,shape=RoundedCornerShape(12.dp)) {
        Text(message,Modifier.fillMaxWidth().padding(13.dp),fontSize=13.sp,lineHeight=21.sp,color=if(error)MaterialTheme.colorScheme.onErrorContainer else Green)
    }
}

@Composable internal fun ListeningScreen(e: Episode, ready: Int, playback: PlaybackUi, actions: EpisodeActions, onBack: () -> Unit) {
    var directory by rememberSaveable(e.id) {mutableStateOf(false)}
    val timeline=remember(e,ready){EpisodeTimeline(e,ready)}
    val index=playback.index(e,ready)
    val elapsed=timeline.elapsed(index,playback.position(e))
    var dragging by remember(e.id){mutableStateOf<Float?>(null)}
    Scaffold(modifier=Modifier.fillMaxSize().safeDrawingPadding(),containerColor=Cream,contentWindowInsets=WindowInsets(0,0,0,0),
        topBar={Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack){UiGlyph(Glyph.DOWN,"返回节目库")}
            Text("正在收听",Modifier.weight(1f),textAlign=TextAlign.Center,fontSize=16.sp,fontWeight=FontWeight.Medium)
            EpisodeMenu(e,ready,actions)
        }}
    ) {padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(bottom=24.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal=24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                        val coverSize=(maxWidth*.67f).coerceIn(160.dp,236.dp)
                        EpisodeCover(e,Modifier.padding(top=8.dp,bottom=15.dp).size(coverSize),large=true)
                    }
                    Text(e.title,fontSize=25.sp,lineHeight=34.sp,fontWeight=FontWeight.Bold,color=Ink,textAlign=TextAlign.Center,
                        style=androidx.compose.ui.text.TextStyle(lineBreak=androidx.compose.ui.text.style.LineBreak.Heading),modifier=Modifier.widthIn(max=248.dp).fillMaxWidth().padding(horizontal=8.dp))
                    Text("个人节目 · ${voiceLabel(e.voice)}",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=7.dp,bottom=13.dp),textAlign=TextAlign.Center)
                    Surface(color=Sage,shape=RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                            UiGlyph(if(e.status=="generating")Glyph.WAVE else Glyph.CHECK,color=Green,modifier=Modifier.size(16.dp))
                            Text(if(e.status=="generating")"后台生成中 · 已可听 $ready / ${e.segments.size} 段" else if(ready==e.segments.size)"已全部下载 · 可离线收听" else "已可听 $ready / ${e.segments.size} 段",fontSize=12.sp,color=Green)
                        }
                    }
                    if(e.error.isNotBlank()) {Spacer(Modifier.height(10.dp));NoticeCard(e.error,true)}
                    if(e.status!="generating" && ready<e.segments.size && e.status!="deleting") {
                        TextButton(onClick={actions.generate(e)}) {Text(if(ready>0)"继续生成剩余段落" else "生成音频")}
                    } else Spacer(Modifier.height(8.dp))
                    Slider(value=(dragging?:timeline.fraction(index,playback.position(e))).coerceIn(0f,1f),
                        onValueChange={dragging=it.coerceIn(0f,timeline.availableFraction)},enabled=ready>0 && e.status!="deleting",
                        onValueChangeFinished={timeline.seekFraction(dragging?:0f)?.let{actions.seek(e,it)};dragging=null},
                        modifier=Modifier.fillMaxWidth().semantics {contentDescription="播放进度"},
                        thumb={Box(Modifier.size(13.dp).background(Green,CircleShape))},
                        track={ProgressLine(dragging?:timeline.fraction(index,playback.position(e)),timeline.availableFraction)})
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(timeLabel(dragging?.let{(it*timeline.totalMs).toLong()}?:elapsed),fontSize=12.sp,color=Muted)
                        Text((if(timeline.estimated)"预计 " else "")+timeLabel(timeline.totalMs),fontSize=12.sp,color=Muted)
                    }
                    Row(Modifier.fillMaxWidth().padding(top=10.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                        SkipSecondsButton(false,ready>0){timeline.seekTo(elapsed-15000)?.let{actions.seek(e,it)}}
                        RoundPlayButton(playback.requested(e,ready),{actions.toggle(e)},Modifier.size(76.dp),enabled=ready>0&&e.status!="deleting",large=true)
                        SkipSecondsButton(true,ready>0){timeline.seekTo(elapsed+15000)?.let{actions.seek(e,it)}}
                    }
                    Row(Modifier.fillMaxWidth().padding(top=6.dp,bottom=13.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick={actions.paragraph(e,index-1)},enabled=index>0){UiGlyph(Glyph.PREVIOUS,color=if(index>0)Green else Muted,modifier=Modifier.size(16.dp));Spacer(Modifier.width(5.dp));Text("上段",fontSize=12.sp)}
                        SpeedMenu(playback.speed,actions.speed)
                        TextButton(onClick={actions.paragraph(e,index+1)},enabled=index+1<ready){Text("下段",fontSize=12.sp);Spacer(Modifier.width(5.dp));UiGlyph(Glyph.SKIP_NEXT,color=if(index+1<ready)Green else Muted,modifier=Modifier.size(16.dp))}
                    }
                    if(playback.episodeId==e.id && playback.state==androidx.media3.common.Player.STATE_ENDED && ready<e.segments.size && playback.playWhenReady && e.status=="generating") {
                        Text("正在等待下一段，生成完成后会自动续播",fontSize=12.sp,color=Green,modifier=Modifier.padding(bottom=14.dp))
                    }
                }
            }
            item {
                Surface(color=Color.White,shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(top=6.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            listOf("文稿","段落目录").forEachIndexed {i,label ->
                                val selected=directory==(i==1)
                                Column(Modifier.weight(1f).clickable{directory=i==1}.padding(top=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                    Text(label,color=if(selected)Green else Muted,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal,fontSize=14.sp,modifier=Modifier.padding(bottom=12.dp))
                                    Box(Modifier.width(42.dp).height(2.dp).background(if(selected)Green else Color.Transparent))
                                }
                            }
                        }
                        HorizontalDivider(color=Line,modifier=Modifier.padding(horizontal=20.dp))
                        Text("第 ${index+1} / ${e.segments.size} 段 · ${if(directory)"点击已下载段落跳转" else "正在阅读的段落会高亮"}",fontSize=11.sp,color=Muted,modifier=Modifier.padding(horizontal=22.dp,vertical=12.dp))
                    }
                }
            }
            itemsIndexed(e.segments,key={i,_->i}) {i,s ->
                val active=i==index
                Box(Modifier.fillMaxWidth().background(Color.White).padding(horizontal=18.dp,vertical=3.dp)) {
                    Surface(color=if(active)Sage else Color.White,shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth()
                        .clickable(enabled=i<ready && e.status!="deleting",onClickLabel="播放第 ${i+1} 段"){actions.paragraph(e,i)}) {
                        Column(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            if(directory) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Text("${if(active)"▸ " else ""}第 ${i+1} 段",fontSize=12.sp,color=Green,fontWeight=FontWeight.Medium)
                                Text(if(i<ready)timeLabel(s.durationMs) else "待生成",fontSize=11.sp,color=Muted)
                            }
                            Text(s.text,color=if(i<ready)Ink else Muted,fontSize=15.sp,lineHeight=25.sp,maxLines=if(directory)3 else Int.MAX_VALUE,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SkipSecondsButton(forward: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(52.dp)) {
        Box(Modifier.size(34.dp).semantics {contentDescription=if(forward)"前进15秒" else "后退15秒"},contentAlignment=Alignment.Center) {
            UiGlyph(if(forward)Glyph.FORWARD else Glyph.REWIND,color=if(enabled)Ink else Muted,modifier=Modifier.fillMaxSize())
            Text("15",fontSize=10.sp,color=if(enabled)Ink else Muted,modifier=Modifier.padding(top=5.dp))
        }
    }
}

@Composable private fun SpeedMenu(speed: Float, onSpeed: (Float)->Unit) {
    var open by remember{mutableStateOf(false)}
    Box {
        OutlinedButton(onClick={open=true},contentPadding=PaddingValues(horizontal=14.dp,vertical=0.dp),shape=RoundedCornerShape(10.dp)) {
            Text("${speed}×",fontSize=13.sp,color=Ink);Spacer(Modifier.width(8.dp));UiGlyph(Glyph.DOWN,"播放速度",modifier=Modifier.size(13.dp))
        }
        DropdownMenu(open,onDismissRequest={open=false}) {
            listOf(.8f,1f,1.25f,1.5f,2f).forEach {value -> DropdownMenuItem(text={Text("$value×${if(value==speed)"  ✓" else ""}")},onClick={onSpeed(value);open=false})}
        }
    }
}
