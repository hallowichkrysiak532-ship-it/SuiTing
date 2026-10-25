package com.suiting.podcast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

internal val LandscapeNames=listOf("青山入云","海上日出","沙丘落日","雪山与湖","林间晨光","月下海岸","秋日旷野","远山霞光")
internal fun landscapeStyle(seed:String,style:Int):Int=if(style in LandscapeNames.indices)style else (seed.hashCode() and Int.MAX_VALUE)%LandscapeNames.size

@Composable internal fun EpisodeCover(episode:Episode,modifier:Modifier=Modifier,large:Boolean=false) {
    CoverArtwork(episode.title,episode.id,episode.coverUrl,episode.sourceUrl,episode.coverStyle,modifier,large)
}

@Composable internal fun CoverArtwork(title:String,seed:String,url:String,source:String,style:Int,modifier:Modifier=Modifier,large:Boolean=false) {
    val context=LocalContext.current.applicationContext
    val bitmap by produceState<android.graphics.Bitmap?>(null,url,source,style) {
        value=null
        if(style==-1&&url.isNotBlank()) {
            value=try{ArtworkStore.load(context,url,source)}catch(e:CancellationException){throw e}catch(_:Exception){null}
        }
    }
    Box(modifier.clip(RoundedCornerShape(if(large)12.dp else 9.dp))) {
        LandscapeCover(title,seed,landscapeStyle(seed,style),Modifier.fillMaxSize(),large)
        bitmap?.let{Image(it.asImageBitmap(),contentDescription="原文封面",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}
    }
}

@Composable internal fun CoverPicker(title:String,seed:String,url:String,source:String,style:Int,onSelect:(Int)->Unit,onDismiss:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,containerColor=Cream,titleContentColor=Ink,textContentColor=Ink,title={Text("选择封面")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Surface(onClick={onSelect(-1)},color=if(style==-1)Sage else Cream,shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,if(style==-1)Green else Line)) {
                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    CoverArtwork(title,seed,url,source,-1,Modifier.size(52.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(if(url.isNotBlank())"原文封面" else "自动选择风景",fontSize=14.sp,fontWeight=FontWeight.Medium)
                        Text(if(url.isNotBlank())"无法加载时显示风景封面" else "这篇内容没有提供原文封面",fontSize=11.sp,color=Muted)
                    }
                    if(style==-1)UiGlyph(Glyph.CHECK,color=Green,modifier=Modifier.size(18.dp))
                }
            }
            Text("风景封面",fontSize=13.sp,color=Muted)
            LandscapeNames.indices.chunked(2).forEach{row->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    row.forEach{index->
                        Surface(onClick={onSelect(index)},modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp),color=if(style==index)Sage else Cream,border=BorderStroke(1.dp,if(style==index)Green else Line)) {
                            Column(Modifier.padding(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                LandscapeCover("",seed,index,Modifier.fillMaxWidth().aspectRatio(1.45f))
                                Row(Modifier.fillMaxWidth().padding(start=3.dp,end=3.dp,bottom=3.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Text(LandscapeNames[index],Modifier.weight(1f),fontSize=12.sp)
                                    if(style==index)UiGlyph(Glyph.CHECK,color=Green,modifier=Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    },confirmButton={TextButton(onClick=onDismiss){Text("完成")}})
}
