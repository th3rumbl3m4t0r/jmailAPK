package io.github.th3rumbl3m4t0r.jmail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

/* Pieces the x11 Look.kt doesn't have: a password field, a text area, a danger button, list lines. */

private val MONO = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val x = LocalX.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    BasicTextField(
        value, onChange,
        modifier.fillMaxWidth().background(x.panel2).border(1.dp, if (focused) x.accent else x.border).padding(8.dp),
        singleLine = true,
        textStyle = MONO.copy(color = x.fg),
        cursorBrush = SolidColor(x.accent),
        interactionSource = source,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

/** A multi-line input (notes). */
@Composable
fun Area(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, minLines: Int = 3) {
    val x = LocalX.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    BasicTextField(
        value, onChange,
        modifier.fillMaxWidth().heightIn(min = (18 * minLines + 16).dp).background(x.panel2)
            .border(1.dp, if (focused) x.accent else x.border).padding(8.dp),
        textStyle = MONO.copy(color = x.fg),
        cursorBrush = SolidColor(x.accent),
        interactionSource = source,
    )
}

/** button.danger: a destructive action, or its "sure?" second step. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val x = LocalX.current
    Box(
        modifier.background(x.panel2).border(1.dp, x.danger).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.lowercase(), color = x.danger, fontSize = 13.sp, letterSpacing = .3.sp, maxLines = 1)
    }
}

/** A 1px line down each side (closing a list under its titlebar). */
fun Modifier.sideLines(color: Color) = drawBehind {
    val px = 1.dp.toPx()
    drawRect(color, Offset.Zero, Size(px, size.height))
    drawRect(color, Offset(size.width - px, 0f), Size(px, size.height))
}

/** Right and bottom lines of a grid cell. */
fun Modifier.cellLines(color: Color) = drawBehind {
    val px = 1.dp.toPx()
    drawRect(color, Offset(size.width - px, 0f), Size(px, size.height))
    drawRect(color, Offset(0f, size.height - px), Size(size.width, px))
}

/**
 * A sideways swipe over the element turns a page: fires the moment the finger has moved
 * [SWIPE_DP] sideways (not on release, so it feels instant), once per gesture. Left = next.
 * Vertical movement is left to the scroll underneath.
 */
@Composable
fun Modifier.swipePages(onPrev: () -> Unit, onNext: () -> Unit): Modifier {
    val prev by rememberUpdatedState(onPrev)
    val next by rememberUpdatedState(onNext)
    return pointerInput(Unit) {
        val threshold = SWIPE_DP.dp.toPx()
        var total = 0f
        var fired = false
        detectHorizontalDragGestures(
            onDragStart = { total = 0f; fired = false },
            onHorizontalDrag = { change, amount ->
                total += amount
                if (!fired && abs(total) > threshold) {
                    fired = true
                    if (total < 0) next() else prev()
                }
                change.consume()
            },
        )
    }
}

private const val SWIPE_DP = 56

/** Wall-clock ms, refreshed every [everyMs] so "x min ago" moves. */
@Composable
fun ticker(everyMs: Long = 30_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(everyMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}
