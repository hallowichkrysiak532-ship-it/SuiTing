package com.suiting.podcast

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/** Platform-only UI/device smoke test, with generated local audio and no network dependency. */
class UiSmoke : Instrumentation() {
    private var suffix=""
    override fun onCreate(arguments: Bundle?) {super.onCreate(arguments);suffix=arguments?.getString("suffix").orEmpty();start()}
    private fun find(predicate: (AccessibilityNodeInfo)->Boolean): AccessibilityNodeInfo? {
        if(android.os.Build.VERSION.SDK_INT>=33)uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(node.isVisibleToUser&&predicate(node))return node
            for(i in 0 until node.childCount)node.getChild(i)?.let{visit(it)?.let{return it}}
            return null
        }
        return uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun waitFor(message: String, condition: ()->Boolean) {
        val end=SystemClock.uptimeMillis()+12000
        while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(100)}
        error(message)
    }
    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo?=node
        repeat(7){if(current?.isClickable==true){check(current!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(250);return};current=current?.parent}
        error("No clickable parent: ${node.text} / ${node.contentDescription}")
    }
    private fun byText(text: String): AccessibilityNodeInfo?=find{node->
        node.text?.toString()?.split('\n',',')?.any{it.trim().removePrefix("▸ ")==text}==true ||
            node.actionList.any{it.label?.toString()=="播放$text"}
    }
    private fun clickText(text: String){waitFor("Missing text $text"){byText(text)!=null};click(byText(text)!!)}
    private fun clickDescription(text: String){waitFor("Missing action $text"){find{it.contentDescription?.toString()==text}!=null};click(find{it.contentDescription?.toString()==text}!!)}
    private fun setText(description: String,value: String) {
        fun editor()=find{it.contentDescription?.toString()==description}?.let{label->
            generateSequence(label){it.parent}.take(5).firstOrNull{it.isEditable||it.className=="android.widget.EditText"}
        }
        waitFor("Missing editor $description"){editor()!=null}
        check(editor()!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}))
    }
    private fun scrollTo(text: String) {
        repeat(3){if(byText(text)!=null)return;find{it.isScrollable&&it.className?.toString() !in setOf("android.widget.SeekBar","android.widget.EditText")}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);Thread.sleep(250)}
        repeat(8){if(byText(text)!=null)return;find{it.isScrollable&&it.className?.toString() !in setOf("android.widget.SeekBar","android.widget.EditText")}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(250)}
        check(byText(text)!=null){"Cannot scroll to $text"}
    }
    private fun shell(command: String){uiAutomation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}
    private fun screenshot(name: String) {
        Thread.sleep(400)
        val image=uiAutomation.takeScreenshot()?:error("No screenshot")
        File(targetContext.getExternalFilesDir(null),"$name$suffix.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
    private fun tone(file: File,seconds: Int) {
        file.parentFile!!.mkdirs()
        val rate=8000;val data=seconds*rate*2
        RandomAccessFile(file,"rw").use {out->
            out.setLength(0)
            out.write(ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply{
                put("RIFF".toByteArray());putInt(data+36);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(rate);putInt(rate*2);putShort(2);putShort(16);put("data".toByteArray());putInt(data)
            }.array())
            val pcm=ByteBuffer.allocate(data).order(ByteOrder.LITTLE_ENDIAN)
            repeat(seconds*rate){i->pcm.putShort((sin(i*2*Math.PI*330/rate)*1200).toInt().toShort())}
            out.write(pcm.array())
        }
    }
    private fun artworkFixture(url:String) {
        val key=java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(""){"%02x".format(it)}
        val file=File(targetContext.filesDir,"artwork/$key.jpg").apply{parentFile!!.mkdirs()}
        val bitmap=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(bitmap)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader=android.graphics.LinearGradient(0f,0f,0f,480f,0xFFB1CDCF.toInt(),0xFF344F69.toInt(),android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,640f,480f,paint);paint.shader=null
        paint.color=0xFFF5E6BD.toInt();canvas.drawCircle(480f,120f,56f,paint)
        paint.color=0xFF496F70.toInt();canvas.drawPath(android.graphics.Path().apply{moveTo(0f,480f);lineTo(240f,190f);lineTo(500f,480f);close()},paint)
        paint.color=0xFF294E54.toInt();canvas.drawPath(android.graphics.Path().apply{moveTo(230f,480f);lineTo(510f,240f);lineTo(640f,380f);lineTo(640f,480f);close()},paint)
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,88,it)};bitmap.recycle()
    }
    private fun iconPreview() {
        val bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
        val drawable=targetContext.getDrawable(R.mipmap.ic_launcher)!!
        drawable.setBounds(0,0,512,512);drawable.draw(android.graphics.Canvas(bitmap))
        File(targetContext.getExternalFilesDir(null),"icon-preview.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    override fun onStart() {
        val report=StringBuilder()
        try {
            waitForIdleSync()
            val library=targetContext.library
            library.episodes.value.filter{it.sourceText=="ui-smoke-fixture"}.forEach{library.beginDelete(it.id);library.delete(it.id)}
            val first=Episode(title="如何找到值得研究的问题",voice="zh-CN-XiaoxiaoNeural",status="ready",positionMs=4000,sourceText="ui-smoke-fixture",segments=listOf(
                Segment("在开始研究一个问题之前，最重要的是先问自己：这个问题为什么值得研究？",12000,0,0,12000),
                Segment("一个好的研究问题，往往能够同时满足三个条件：重要性、可行性和新颖性。",12000,0,12000,24000),
                Segment("把注意力留给真正重要的事，也给忙碌的生活留一点空白。",12000,0,24000,36000)),coverUrl="https://example.com/ui-smoke-original.jpg",sourceUrl="https://example.com/article")
            artworkFixture(first.coverUrl);iconPreview()
            val second=Episode(title="给忙碌生活的一点留白",voice="zh-CN-YunxiNeural",status="ready",sourceText="ui-smoke-fixture",segments=listOf(Segment("今天，给自己留一点空白。",12000)),coverStyle=2)
            val draft=Episode(title="把注意力还给真正重要的事",voice="zh-CN-XiaoxiaoNeural",sourceText="ui-smoke-fixture",segments=listOf(Segment("一段等待生成的正文。")))
            library.add(draft);library.add(second);library.add(first)
            tone(library.file(first.id,0),36);tone(library.file(second.id,0),12)
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("Library not shown"){byText("我的节目")!=null}
            screenshot("ui-library")
            clickText(first.title)
            waitFor("Listening page not shown"){byText("正在收听")!=null}
            waitFor("Offline original artwork not shown"){find{it.contentDescription?.toString()=="原文封面"}!=null}
            screenshot("ui-listening")
            clickDescription("节目操作");clickText("更换封面")
            waitFor("Cover picker not shown"){byText("选择封面")!=null};screenshot("ui-cover-picker")
            scrollTo("月下海岸");clickText("月下海岸")
            waitFor("Landscape selection not saved"){library.find(first.id)?.coverStyle==5}
            clickText("完成");screenshot("ui-listening-landscape")
            clickDescription("节目操作");clickText("更换封面");scrollTo("原文封面");clickText("原文封面");clickText("完成")
            waitFor("Original artwork not restored"){library.find(first.id)?.coverStyle==-1&&find{it.contentDescription?.toString()=="原文封面"}!=null}
            report.appendLine("PASS offline original cover, landscape switching, artwork persistence and adaptive icon rendering")
            clickDescription("播放节目")
            waitFor("Playback not started"){find{it.contentDescription?.toString()=="暂停播放"}!=null}
            val slider=find{it.contentDescription?.toString()=="播放进度"}?:error("No slider")
            check(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply{putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,.6f)}))
            waitFor("Whole-episode seek did not enter paragraph 2"){library.find(first.id)?.segmentIndex==1}
            report.appendLine("PASS playback and whole-episode seek across a shared audio file")
            scrollTo("段落目录");clickText("段落目录")
            scrollTo("第 1 段");clickText("第 1 段")
            waitFor("Paragraph jump failed"){library.find(first.id)?.segmentIndex==0}
            shell("input keyevent 4")
            waitFor("Back did not return to library"){byText("我的节目")!=null}
            clickDescription("迷你播放器暂停")
            waitFor("Mini-player did not pause"){find{it.contentDescription?.toString()=="迷你播放器播放"}!=null}
            report.appendLine("PASS paragraph navigation, system back and mini-player pause")
            clickDescription("搜索节目")
            setText("搜索节目标题","留白")
            waitFor("Search did not filter rows"){byText("1 期")!=null&&byText(second.title)!=null}
            shell("input keyevent 4")
            clickText("生成中")
            waitFor("Generating filter did not update"){byText("0 期")!=null}
            clickText("全部")
            report.appendLine("PASS title search and library status filters")
            clickDescription("添加节目")
            waitFor("Add page not shown"){byText("节目标题")!=null}
            scrollTo("节目标题");setText("节目标题","如何找到值得研究的问题")
            val text="在开始研究一个问题之前，最重要的是先问自己：这个问题为什么值得研究？\n\n一个好的研究问题，往往能够同时满足三个条件：重要性、可行性和新颖性。\n\n把注意力留给真正重要的事，也给忙碌的生活留一点空白。"
            scrollTo("正文内容");setText("正文内容",text)
            waitFor("Create action not enabled"){byText("生成并收听")?.isEnabled==true||byText("生成并收听")?.parent?.isEnabled==true}
            runOnMainSync{targetContext.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken,0)}
            screenshot("ui-create")
            scrollTo("更多音色");clickText("更多音色");clickText("Aria · 英语女声")
            scrollTo("自动分段 · 3 段");clickText("自动分段 · 3 段")
            waitFor("Paragraph preview not shown"){byText("分段预览 · 3 段")!=null};clickText("返回编辑")
            scrollTo("节目封面");clickText("节目封面");scrollTo("沙丘落日");clickText("沙丘落日");clickText("完成")
            clickDescription("返回")
            clickDescription("添加节目")
            waitFor("Draft cover was lost"){byText("沙丘落日")!=null}
            scrollTo("正文内容");waitFor("Draft body was lost"){find{it.isEditable&&it.text?.toString()==text}!=null}
            report.appendLine("PASS full-page editor, voice selection, paragraph preview and draft restoration")
            finish(Activity.RESULT_OK,Bundle().apply{putString("stream",report.toString()+"OK (UI smoke)\n")})
        } catch(e: Throwable) {
            runCatching{screenshot("ui-failure")}
            finish(Activity.RESULT_CANCELED,Bundle().apply{putString("stream",report.toString()+"FAIL: "+e.stackTraceToString())})
        }
    }
}
