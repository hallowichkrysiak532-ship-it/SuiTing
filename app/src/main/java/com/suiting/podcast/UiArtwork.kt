package com.suiting.podcast

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

internal val Green = Color(0xFF193E35)
internal val Cream = Color(0xFFFAF9F5)
internal val Ink = Color(0xFF1B2425)
internal val Muted = Color(0xFF777C79)
internal val Line = Color(0xFFE4E6E0)
internal val Sage = Color(0xFFEAF0E5)
internal val Gold = Color(0xFFD3AD72)

@Composable internal fun SuiTingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Green, onPrimary = Color.White,
        secondary = Green, background = Cream, surface = Cream, onSurface = Ink, onBackground = Ink,
        surfaceVariant = Sage, onSurfaceVariant = Muted, outline = Line, surfaceTint = Green), content = content)
}

internal enum class Glyph { BACK, DOWN, NEXT, SEARCH, MORE, ADD, PLAY, PAUSE, PREVIOUS, SKIP_NEXT, REWIND, FORWARD, CHECK, CLOSE, WAVE, DOCUMENT, LINK, PDF }

@Composable internal fun UiGlyph(glyph: Glyph, description: String? = null, color: Color = Ink, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp).then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })) {
        val s = size.minDimension / 24f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1,y1), p(x2,y2), 1.7f * s, StrokeCap.Round)
        fun path(vararg points: Pair<Float,Float>, fill: Boolean = false) {
            val shape = Path().apply { moveTo(points[0].first*s,points[0].second*s); points.drop(1).forEach { lineTo(it.first*s,it.second*s) }; if(fill) close() }
            drawPath(shape, color, style = if(fill) androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.7f*s, cap=StrokeCap.Round, join=StrokeJoin.Round))
        }
        when(glyph) {
            Glyph.BACK -> { line(20f,12f,4f,12f); path(10f to 5f,3f to 12f,10f to 19f) }
            Glyph.DOWN -> path(5f to 9f,12f to 16f,19f to 9f)
            Glyph.NEXT -> path(9f to 5f,16f to 12f,9f to 19f)
            Glyph.SEARCH -> { drawCircle(color,7f*s,p(10f,10f),style=Stroke(1.7f*s));line(15f,15f,21f,21f) }
            Glyph.MORE -> listOf(5f,12f,19f).forEach { drawCircle(color,1.45f*s,p(12f,it)) }
            Glyph.ADD -> { line(12f,4f,12f,20f);line(4f,12f,20f,12f) }
            Glyph.PLAY -> path(8f to 4f,20f to 12f,8f to 20f,fill=true)
            Glyph.PAUSE -> { drawRoundRect(color,p(6f,4f),Size(4f*s,16f*s),androidx.compose.ui.geometry.CornerRadius(s));drawRoundRect(color,p(14f,4f),Size(4f*s,16f*s),androidx.compose.ui.geometry.CornerRadius(s)) }
            Glyph.PREVIOUS -> { line(5f,6f,5f,18f);path(18f to 5f,7f to 12f,18f to 19f,fill=true) }
            Glyph.SKIP_NEXT -> { line(19f,6f,19f,18f);path(6f to 5f,17f to 12f,6f to 19f,fill=true) }
            Glyph.CHECK -> path(5f to 12f,10f to 17f,20f to 6f)
            Glyph.CLOSE -> {line(6f,6f,18f,18f);line(18f,6f,6f,18f)}
            Glyph.WAVE -> listOf(8f,16f,21f,11f,17f).forEachIndexed { i,h -> line(3f+i*4.5f,12f-h/2,3f+i*4.5f,12f+h/2) }
            Glyph.REWIND, Glyph.FORWARD -> {
                drawArc(color, if(glyph==Glyph.REWIND) -120f else -60f, 300f, false, p(3f,4f),Size(18f*s,18f*s),style=Stroke(1.6f*s,cap=StrokeCap.Round))
                if(glyph==Glyph.REWIND) path(8f to 1f,3f to 5f,9f to 7f) else path(16f to 1f,21f to 5f,15f to 7f)
            }
            Glyph.DOCUMENT, Glyph.PDF -> {
                path(5f to 2f,15f to 2f,20f to 7f,20f to 22f,5f to 22f,5f to 2f)
                path(14f to 2f,14f to 8f,20f to 8f);line(9f,12f,16f,12f);line(9f,16f,16f,16f)
            }
            Glyph.LINK -> {path(10f to 8f,14f to 4f,19f to 4f,22f to 8f,20f to 12f,16f to 16f);path(14f to 16f,10f to 20f,5f to 20f,2f to 16f,4f to 12f,8f to 8f);line(8f,16f,16f,8f)}
        }
    }
}

@Composable internal fun RoundPlayButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, description: String? = null, large: Boolean = false) {
    FilledIconButton(onClick = onClick, modifier = modifier.size(48.dp), enabled = enabled,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Green, contentColor = Color.White)) {
        UiGlyph(if(playing) Glyph.PAUSE else Glyph.PLAY, description ?: if(playing) "暂停播放" else "播放节目", Color.White, Modifier.size(if(large)32.dp else 22.dp))
    }
}

@Composable internal fun LandscapeCover(title:String,seed:String,style:Int,modifier:Modifier=Modifier,large:Boolean=false) {
    Box(modifier.clip(RoundedCornerShape(if(large)12.dp else 9.dp))) {
        Canvas(Modifier.matchParentSize()){drawLandscape(style,seed.hashCode(),large)}
        if(large&&title.isNotBlank())Text(title,Modifier.align(Alignment.TopStart).padding(22.dp).fillMaxWidth(.65f),
            fontSize=25.sp,lineHeight=33.sp,fontFamily=FontFamily.Serif,fontWeight=FontWeight.SemiBold,
            color=if(style in setOf(4,5))Color(0xFFF7F1DE)else Color(0xFF233F3C),maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLandscape(style:Int,seed:Int,large:Boolean) {
    val w=size.width;val h=size.height
    val random=Random(seed)
    val sunX=.72f+random.nextFloat()*.1f
    fun color(hex:Long)=Color(hex)
    fun sky(top:Long,bottom:Long){drawRect(Brush.verticalGradient(listOf(color(top),color(bottom))))}
    fun sun(tint:Long,radius:Float=.1f,y:Float=.25f){drawCircle(color(tint),w*radius,Offset(w*sunX,h*y))}
    fun hills(base:Float,tint:Long,shift:Float=0f) {
        drawPath(Path().apply{moveTo(0f,h*base);cubicTo(w*.2f,h*(base-.13f+shift),w*.38f,h*(base+.16f),w*.6f,h*(base-.03f));cubicTo(w*.8f,h*(base-.2f),w*.88f,h*(base-.21f),w,h*(base-.13f));lineTo(w,h);lineTo(0f,h);close()},color(tint))
    }
    fun waterline(y:Float,x:Float,length:Float,tint:Long,alpha:Float=1f){drawLine(color(tint).copy(alpha=alpha),Offset(w*x,h*y),Offset(w*(x+length),h*y),w*.005f,StrokeCap.Round)}
    when(style) {
        1->{ // Warm sky, turquoise sea and bright surf.
            sky(0xFFF0D4BC,0xFFF8EDD5);sun(0xFFFFF3CD,.12f,.26f)
            drawRect(Brush.verticalGradient(listOf(color(0xFF85B5B4),color(0xFF236477)),startY=h*.48f,endY=h),Offset(0f,h*.48f),Size(w,h*.52f))
            hills(.98f,0xFFE4D7B9,.09f)
            repeat(9){i->val y=.54f+i*.04f;waterline(y,random.nextFloat()*.42f,.18f+random.nextFloat()*.32f,0xFFEFF6DF,.65f)}
            drawPath(Path().apply{moveTo(w*.69f,h*.49f);quadraticTo(w*.85f,h*.39f,w,h*.46f);lineTo(w,h*.5f);close()},color(0xFF4E7D7D))
        }
        2->{
            sky(0xFFE6BA90,0xFFF4DFBD);sun(0xFFF9ECD0,.13f,.28f)
            hills(.59f,0xFFD4A577);hills(.75f,0xFFB97C55,.1f);hills(.87f,0xFF965D46,-.06f)
            drawPath(Path().apply{moveTo(0f,h*.75f);quadraticTo(w*.5f,h*.5f,w,h*.79f);quadraticTo(w*.5f,h*.66f,0f,h*.78f);close()},color(0xFFEAC39A))
        }
        3->{
            sky(0xFFB4CDDA,0xFFE3E8DF);sun(0xFFF8F0D7,.08f,.23f)
            drawPath(Path().apply{moveTo(0f,h*.64f);lineTo(w*.28f,h*.27f);lineTo(w*.48f,h*.49f);lineTo(w*.68f,h*.2f);lineTo(w,h*.63f);close()},color(0xFF6E8D9B))
            drawPath(Path().apply{moveTo(w*.13f,h*.47f);lineTo(w*.28f,h*.27f);lineTo(w*.4f,h*.41f);lineTo(w*.3f,h*.38f);lineTo(w*.26f,h*.42f);lineTo(w*.23f,h*.39f);close();moveTo(w*.52f,h*.43f);lineTo(w*.68f,h*.2f);lineTo(w*.84f,h*.42f);lineTo(w*.71f,h*.35f);lineTo(w*.67f,h*.38f);lineTo(w*.64f,h*.33f);close()},color(0xFFF4F2E8))
            drawRect(Brush.verticalGradient(listOf(color(0xFF91BFC0),color(0xFF427D88))),Offset(0f,h*.64f),Size(w,h*.36f))
            repeat(7){i->waterline(.69f+i*.035f,.28f+random.nextFloat()*.2f,.25f,0xFFDCE7DA,.65f)}
            hills(.99f,0xFF365B59)
        }
        4->{
            sky(0xFF234E43,0xFF92AC86);sun(0xFFDCE8AF,.1f,.26f)
            hills(.65f,0xFF64856A);hills(.83f,0xFF385E4D)
            listOf(.06f,.24f,.46f,.77f,.96f).forEachIndexed{i,x->
                val top=.34f+(i%3)*.14f
                drawPath(Path().apply{moveTo(w*x,h*top);lineTo(w*(x-.13f),h*(top+.27f));lineTo(w*(x-.065f),h*(top+.25f));lineTo(w*(x-.19f),h*(top+.47f));lineTo(w*(x+.18f),h*(top+.47f));lineTo(w*(x+.065f),h*(top+.25f));lineTo(w*(x+.13f),h*(top+.27f));close()},color(if(i%2==0)0xFF1D493D else 0xFF2D5947))
                drawLine(color(0xFF244B3D),Offset(w*x,h*(top+.24f)),Offset(w*x,h),w*.012f)
            }
            hills(1.08f,0xFF183E34)
        }
        5->{
            sky(0xFF263B53,0xFF6C8194);sun(0xFFF4E9C9,.085f,.23f)
            repeat(22){drawCircle(color(0xFFE7E7D4).copy(alpha=.75f),w*.002f,Offset(random.nextFloat()*w,random.nextFloat()*h*.46f))}
            drawRect(Brush.verticalGradient(listOf(color(0xFF456A80),color(0xFF263E57))),Offset(0f,h*.5f),Size(w,h*.5f))
            repeat(9){i->val length=.02f+i*.014f;waterline(.55f+i*.044f,sunX-length/2,length,0xFFDBD9BE,.7f-i*.05f)}
            drawPath(Path().apply{moveTo(0f,h*.57f);quadraticTo(w*.34f,h*.66f,w*.23f,h*.81f);quadraticTo(w*.08f,h*.94f,w*.57f,h);lineTo(0f,h);close()},color(0xFF203C48))
        }
        6->{
            sky(0xFFE7D5B4,0xFFF3E6CC);sun(0xFFFFF1C9,.11f,.28f)
            hills(.64f,0xFFC6BF8A);hills(.78f,0xFFB29D66,.07f);hills(.93f,0xFF8D8E59)
            drawPath(Path().apply{moveTo(w*.36f,h);quadraticTo(w*.6f,h*.8f,w*.5f,h*.65f);quadraticTo(w*.69f,h*.81f,w*.58f,h);close()},color(0xFFE3CE9B))
            drawLine(color(0xFF795E43),Offset(w*.81f,h*.47f),Offset(w*.81f,h*.77f),w*.016f)
            drawOval(color(0xFF9B6E43),Offset(w*.66f,h*.36f),Size(w*.29f,h*.24f))
            drawCircle(color(0xFFB88C51),w*.07f,Offset(w*.75f,h*.43f))
        }
        7->{
            sky(0xFFD4B7BD,0xFFF0D7CD);sun(0xFFF8E4CE,.13f,.3f)
            hills(.6f,0xFFC0A9B3);hills(.72f,0xFF9B91A7);hills(.87f,0xFF6C738E);hills(1f,0xFF4F5D75)
            waterline(.66f,.03f,.28f,0xFFF5E6DC,.5f);waterline(.74f,.41f,.5f,0xFFF5E6DC,.4f)
        }
        else->{
            sky(0xFFD5DFCE,0xFFECEDDE);sun(0xFFF9F0D7)
            hills(.48f,0xFF9AB29A);hills(.63f,0xFF75917B);hills(.78f,0xFF416858);hills(.93f,0xFF193E35)
            drawPath(Path().apply{moveTo(w*.61f,h*.6f);cubicTo(w*.72f,h*.71f,w*.26f,h*.73f,w*.4f,h*.82f);cubicTo(w*.46f,h*.88f,w*.8f,h*.94f,w*.82f,h);lineTo(w*.44f,h);cubicTo(w*.6f,h*.91f,w*.23f,h*.91f,w*.29f,h*.81f);cubicTo(w*.34f,h*.71f,w*.68f,h*.71f,w*.61f,h*.6f);close()},color(0xFFE9E3CD))
        }
    }
    repeat(if(large)1400 else 100){drawCircle(Color.White.copy(alpha=.08f),.35f+random.nextFloat()*.5f,Offset(random.nextFloat()*w,random.nextFloat()*h))}
}
