package com.suiting.podcast

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable internal fun CreateScreen(
    model: ImportDraftModel, systemVoice: SystemVoiceStatus?, onProbe: (String)->Unit,
    onSettings: ()->Unit, onDismiss: ()->Unit, onCreate: (String,String,String,List<Segment>)->Unit
) {
    val draft by model.draft.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val pending by model.pending.collectAsStateWithLifecycle()
    var tab by rememberSaveable{mutableIntStateOf(0)}
    var voice by rememberSaveable{mutableStateOf("zh-CN-XiaoxiaoNeural")}
    var keepLines by rememberSaveable{mutableStateOf(false)}
    var preview by remember{mutableStateOf(false)}
    var browser by remember{mutableStateOf<String?>(null)}
    var restoring by remember{mutableStateOf(false)}
    var link by rememberSaveable{mutableStateOf("")}
    var linkEdited by rememberSaveable{mutableStateOf(false)}
    var moreVoices by remember{mutableStateOf(false)}
    var choosingCover by remember{mutableStateOf(false)}
    val text=draft.text
    val onlyLink=Regex("^https?://\\S+\\s*$",RegexOption.IGNORE_CASE).matches(text.trim())
    val split by produceState<Result<List<Segment>>?>(null,text,keepLines) {
        value=null;delay(250)
        value=withContext(Dispatchers.Default){runCatching{TextSegments.split(text,mode=if(keepLines)TextSegments.Mode.KEEP_LINES else TextSegments.Mode.AUTO)}}
    }
    val parts=split?.getOrNull()
    val pdf=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)model.file(uri)}
    LaunchedEffect(draft.source,text.takeIf{onlyLink},draft.originalText.takeIf{it.length<2000}) {
        if(linkEdited)return@LaunchedEffect
        if(onlyLink){link=text.trim();tab=1}
        else if(draft.source.startsWith("https://"))link=draft.source
        else {val raw=draft.originalText.ifBlank{text};if(raw.length<2000)Regex("https?://\\S+").find(raw)?.value?.let{link=it}}
    }
    LaunchedEffect(systemVoice){if(voice=="system"&&systemVoice?.available==false)voice="zh-CN-XiaoxiaoNeural"}
    BackHandler(browser==null&&!preview&&!restoring&&!choosingCover&&!moreVoices&&pending==null){onDismiss()}
    val canCreate=parts!=null&&!onlyLink&&!busy&&text.length<=TextSegments.MAX_INPUT&&(voice!="system"||systemVoice?.available==true)
    Scaffold(modifier=Modifier.fillMaxSize().safeDrawingPadding().imePadding(),containerColor=Cream,contentWindowInsets=WindowInsets(0,0,0,0),
        topBar={ScreenHeading("添加节目",onDismiss)},bottomBar={
            Column(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Button(onClick={parts?.let{onCreate(draft.title.trim(),text,voice,it)}},enabled=canCreate,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp),shape=RoundedCornerShape(14.dp)) {Text("生成并收听",fontSize=16.sp,fontWeight=FontWeight.Medium)}
                Text("先听已生成的部分，其余会在后台继续",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=7.dp))
            }
        }
    ){padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),contentPadding=PaddingValues(start=22.dp,end=22.dp,top=5.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
            item {
                Row(Modifier.fillMaxWidth()) {
                    listOf("粘贴文字","网页链接","导入 PDF").forEachIndexed{i,label->
                        Column(Modifier.weight(1f).clickable{tab=i}.padding(top=9.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(label,fontSize=14.sp,color=if(i==tab)Green else Muted,fontWeight=if(i==tab)FontWeight.SemiBold else FontWeight.Normal,modifier=Modifier.padding(bottom=12.dp))
                            HorizontalDivider(color=if(i==tab)Green else Line,thickness=if(i==tab)2.dp else .7.dp)
                        }
                    }
                }
            }
            if(tab==1)item {
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(link,{link=it;linkEdited=true},modifier=Modifier.fillMaxWidth(),label={Text("公众号或网页链接")},singleLine=true,shape=RoundedCornerShape(12.dp))
                    OutlinedButton(onClick={runCatching{ArticleHtml.url(link).toString()}.onSuccess{browser=it}.onFailure{model.error.value=it.message?:"链接无效"}},enabled=link.isNotBlank()&&!busy,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)) {
                        UiGlyph(Glyph.LINK,color=Green,modifier=Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("打开网页提取正文")
                    }
                }
            }
            if(tab==2)item {
                Surface(color=Sage,shape=RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("把一份 PDF，变成可以听的节目",fontSize=15.sp,fontWeight=FontWeight.Medium,color=Green)
                        Text("支持文字型 PDF，最多 20 MB、300 页。",fontSize=12.sp,color=Muted)
                        OutlinedButton(onClick={pdf.launch(arrayOf("application/pdf"))},enabled=!busy,shape=RoundedCornerShape(11.dp)) {UiGlyph(Glyph.PDF,color=Green,modifier=Modifier.size(18.dp));Spacer(Modifier.width(7.dp));Text("选择 PDF 文件")}
                    }
                }
            }
            if(busy)item {
                Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在提取正文…",fontSize=12.sp,color=Green);TextButton(onClick=model::cancel){Text("取消导入")}}
            }
            if(error.isNotBlank())item {NoticeCard(error,true)}
            item {
                Surface(onClick={choosingCover=true},color=Color.White,shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,Line)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        CoverArtwork(draft.title,draft.title.ifBlank{"随听"},draft.coverUrl,draft.source,draft.coverStyle,Modifier.size(54.dp))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text("节目封面",fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                            Text(if(draft.coverStyle==-1&&draft.coverUrl.isNotBlank())"优先使用原文封面" else LandscapeNames[landscapeStyle(draft.title.ifBlank{"随听"},draft.coverStyle)],fontSize=12.sp,color=Muted)
                        }
                        Text("更换",fontSize=12.sp,color=Green);UiGlyph(Glyph.NEXT,color=Muted,modifier=Modifier.size(15.dp))
                    }
                }
            }
            item {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("节目标题",fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                    OutlinedTextField(draft.title,model::title,modifier=Modifier.fillMaxWidth().semantics{contentDescription="节目标题"},singleLine=true,
                        placeholder={Text("给这期节目起个名字（可选）",fontSize=14.sp,color=Muted)},shape=RoundedCornerShape(12.dp),
                        colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=Color.White,focusedContainerColor=Color.White,unfocusedBorderColor=Line))
                }
            }
            item {
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("正文内容",Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                        TextButton(onClick=model::clipboard,enabled=!busy,contentPadding=PaddingValues(horizontal=3.dp)) {UiGlyph(Glyph.DOCUMENT,color=Green,modifier=Modifier.size(15.dp));Spacer(Modifier.width(5.dp));Text("粘贴正文",fontSize=12.sp)}
                    }
                    LongTextEditor(text,model::text,Modifier.fillMaxWidth().height(185.dp))
                    Text("${text.length} / ${TextSegments.MAX_INPUT} 字符",fontSize=11.sp,color=if(text.length>TextSegments.MAX_INPUT)MaterialTheme.colorScheme.error else Muted)
                    if(onlyLink)Text("正文还是一个链接，请先打开网页提取文章。",fontSize=12.sp,color=MaterialTheme.colorScheme.error)
                    if(draft.notice.contains("20000 字符"))NoticeCard("剪贴板仅提供了 20000 字符，可改用链接或 PDF 导入全文。")
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                        TextButton(onClick=model::cleanText,enabled=!busy&&text.isNotBlank(),contentPadding=PaddingValues(horizontal=1.dp)) {Text("清理网址和乱码",fontSize=11.sp)}
                        if(draft.originalText.isNotBlank()&&draft.originalText!=text)TextButton(onClick={restoring=true},enabled=!busy,contentPadding=PaddingValues(horizontal=1.dp)){Text("恢复原稿",fontSize=11.sp)}
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("声音",Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                    TextButton(onClick={moreVoices=true},contentPadding=PaddingValues(horizontal=6.dp)){Text("更多音色",fontSize=12.sp)}
                    TextButton(onClick={onProbe(voice)},enabled=voice!="system"||systemVoice?.available==true,contentPadding=PaddingValues(horizontal=3.dp)) {UiGlyph(Glyph.PLAY,color=Green,modifier=Modifier.size(15.dp));Spacer(Modifier.width(5.dp));Text("试听",fontSize=12.sp)}
                }
                Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    val primary=EdgeSpeechEngine.voices.keys.take(2)
                    val featured=if(voice in primary)primary else listOf(voice,primary.first())
                    featured.map{it to EdgeSpeechEngine.voices.getValue(it)}.chunked(2).forEach{voices->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                            voices.forEach{(key,label)->
                                val available=key!="system"||systemVoice?.available==true
                                val selected=voice==key
                                Surface(onClick={voice=key},enabled=available,modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp),
                                    color=if(selected)Sage else Color.White,border=BorderStroke(1.dp,if(selected)Green else Line)) {
                                    Row(Modifier.padding(horizontal=10.dp,vertical=12.dp).heightIn(min=35.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        RadioButton(selected,onClick=null,enabled=available,modifier=Modifier.size(19.dp))
                                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                            Text(label,fontSize=12.sp,lineHeight=18.sp,color=if(available)Ink else Muted,fontWeight=if(selected)FontWeight.Medium else FontWeight.Normal)
                                            Text(if(!available)"暂不可用" else if(key.startsWith("en-"))"英文朗读" else if(key=="system")"使用手机语音引擎" else "自然朗读 · 中文",fontSize=10.sp,lineHeight=14.sp,color=Muted)
                                        }
                                    }
                                }
                            }
                            if(voices.size==1)Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if(voice=="system")Text(systemVoice?.message?:"正在检测系统声音…",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=7.dp))
            }
            item {
                Text("分段预览",fontSize=14.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(bottom=10.dp))
                Surface(onClick={preview=true},enabled=parts!=null&&!onlyLink,color=Color.White,shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,Line)) {
                    Row(Modifier.fillMaxWidth().padding(15.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)) {
                        UiGlyph(Glyph.DOCUMENT,color=Green,modifier=Modifier.size(22.dp))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text("${if(keepLines)"保留每行" else "自动分段"} · ${parts?.size?:0} 段",fontSize=14.sp,fontWeight=FontWeight.Medium)
                            Text(if(parts==null&&text.isNotBlank())"正在整理正文…" else "按段落和句子整理，便于收听",fontSize=11.sp,color=Muted)
                        }
                        UiGlyph(Glyph.NEXT,color=Muted,modifier=Modifier.size(17.dp))
                    }
                }
                if(split?.isFailure==true&&text.isNotBlank())Text(split?.exceptionOrNull()?.message?:"请检查正文",fontSize=12.sp,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=7.dp))
            }
        }
    }
    if(choosingCover)CoverPicker(draft.title,draft.title.ifBlank{"随听"},draft.coverUrl,draft.source,draft.coverStyle,model::cover){choosingCover=false}
    if(moreVoices)AlertDialog(onDismissRequest={moreVoices=false},title={Text("选择声音")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            EdgeSpeechEngine.voices.forEach{(key,label)->
                val available=key!="system"||systemVoice?.available==true
                Row(Modifier.fillMaxWidth().clickable(enabled=available){voice=key;moreVoices=false},verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(voice==key,onClick={voice=key;moreVoices=false},enabled=available)
                    Text(label+if(available)"" else "（不可用）",fontSize=14.sp,color=if(available)Ink else Muted)
                }
            }
            if(systemVoice?.available==false)TextButton(onClick=onSettings){Text("打开系统语音设置")}
        }
    },confirmButton={TextButton(onClick={moreVoices=false}){Text("关闭")}})
    if(preview&&parts!=null)AlertDialog(onDismissRequest={preview=false},title={Text("分段预览 · ${parts.size} 段")},text={
        Column {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("保留每行",Modifier.weight(1f),fontSize=13.sp);Switch(keepLines,{keepLines=it})}
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=390.dp),verticalArrangement=Arrangement.spacedBy(13.dp)) {
                itemsIndexed(parts){i,part->Column(verticalArrangement=Arrangement.spacedBy(5.dp)){Text("第 ${i+1} 段 · ${part.text.length} 字符",fontSize=12.sp,color=Green);Text(part.text,fontSize=14.sp,lineHeight=23.sp);HorizontalDivider(color=Line)}}
            }
        }
    },confirmButton={TextButton(onClick={preview=false}){Text("返回编辑")}})
    pending?.let{incoming->AlertDialog(onDismissRequest={model.pending.value=null},title={Text("替换当前正文？")},text={Text("当前草稿 ${text.length} 字符；新导入 ${incoming.text.length} 字符。")},
        confirmButton={TextButton(onClick=model::accept){Text("使用导入内容")}},dismissButton={TextButton(onClick={model.pending.value=null}){Text("保留当前正文")}})}
    browser?.let{WebArticleDialog(it,onDismiss={browser=null},onExtract={value->model.offer(value);browser=null})}
    if(restoring)AlertDialog(onDismissRequest={restoring=false},title={Text("恢复导入时的原稿？")},text={Text("将替换当前修改。")},
        confirmButton={TextButton(onClick={model.restoreOriginal();restoring=false}){Text("恢复原稿")}},dismissButton={TextButton(onClick={restoring=false}){Text("取消")}})
}
