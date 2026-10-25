package com.suiting.podcast

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private class BodyEditText(context: Context) : EditText(context) { var updating = false }

@Composable fun LongTextEditor(text: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val changed by rememberUpdatedState(onChange)
    AndroidView(modifier = modifier, factory = { context ->
        BodyEditText(context).apply {
            filters = emptyArray() // Validation reports excess length; input is never truncated.
            isSaveEnabled = false // The full body is kept by ImportDraftModel, outside the Binder state bundle.
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
            textSize = 15f; minLines = 6; maxLines = 10
            contentDescription = "正文内容"
            setHorizontallyScrolling(false)
            hint = "粘贴正文，或从网页 / PDF 导入"
            setTextColor(Color.rgb(27, 36, 37)); setHintTextColor(Color.rgb(125, 130, 126))
            val density = resources.displayMetrics.density
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            setLineSpacing(4 * density, 1.05f)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 12 * density; setStroke(density.toInt().coerceAtLeast(1), Color.rgb(228, 230, 224)) }
            filters = emptyArray()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(value: Editable?) { if (!updating) changed(value?.toString().orEmpty()) }
            })
        }
    }, update = { editor ->
        if (editor.text.toString() != text) {
            editor.updating = true
            try { editor.setText(text); editor.setSelection(text.length) }
            finally { editor.updating = false }
        }
    })
}
