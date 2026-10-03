package io.github.th3rumbl3m4t0r.jmail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * The x11 look (after the x11.css of the author's web UIs): night / day palettes,
 * one adjustable accent, square corners, monospace, 1px borders, no animations.
 */

data class Palette(
    val bg: Color,
    val panel: Color,
    val panel2: Color,
    val fg: Color,
    val dim: Color,
    val border: Color,
    val titlebar: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    val accent: Color = Color(0xFF3B8A3E),
) {
    /** --accent-soft: 22 % of the accent over a panel. */
    val accentSoft get() = accent.copy(alpha = .22f).compositeOver(panel)
}

val NIGHT = Palette(
    bg = Color(0xFF17110C), panel = Color(0xFF201810), panel2 = Color(0xFF281F16),
    fg = Color(0xFFDDD2C4), dim = Color(0xFF8C7F6D), border = Color(0xFF4A3C2D),
    titlebar = Color(0xFF120D08), ok = Color(0xFF7BBF6A), warn = Color(0xFFD0A040), danger = Color(0xFFC0473B),
)

val DAY = Palette(
    bg = Color(0xFFCCD2DA), panel = Color(0xFFDBE0E6), panel2 = Color(0xFFE9EDF1),
    fg = Color(0xFF1F262E), dim = Color(0xFF5A636F), border = Color(0xFFA6AFBB),
    titlebar = Color(0xFFBFC7D1), ok = Color(0xFF2F7A2A), warn = Color(0xFF8A6300), danger = Color(0xFFA8322A),
)

const val DEFAULT_ACCENT = "#3b8a3e"

/** Offered in settings next to the hex field; the web pages have a colour picker instead. */
val ACCENTS = listOf("#3b8a3e", "#5f9ea0", "#8fb3c8", "#b294bb", "#c0473b", "#c8702a", "#d0a040", "#a89984")

/** "#rrggbb" (the # is optional) or null. */
fun parseHex(s: String): Color? {
    val h = s.trim().removePrefix("#")
    if (!Regex("[0-9a-fA-F]{6}").matches(h)) return null
    return Color(0xFF000000 or h.toLong(16))
}

val LocalX = staticCompositionLocalOf { NIGHT }

private val MONO = FontFamily.Monospace
private val BASE = TextStyle(fontFamily = MONO, fontSize = 14.sp, lineHeight = 20.sp)

@Composable
fun X11Theme(night: Boolean, accent: String, content: @Composable () -> Unit) {
    val x = (if (night) NIGHT else DAY).copy(accent = parseHex(accent) ?: parseHex(DEFAULT_ACCENT)!!)
    val scheme = (if (night) darkColorScheme() else lightColorScheme()).copy(
        primary = x.accent, onPrimary = x.bg, secondary = x.accent, onSecondary = x.bg,
        background = x.bg, onBackground = x.fg, surface = x.panel, onSurface = x.fg,
        surfaceVariant = x.panel2, onSurfaceVariant = x.dim, surfaceTint = x.panel,
        surfaceContainer = x.panel, surfaceContainerHigh = x.panel2, surfaceContainerHighest = x.panel2,
        outline = x.border, outlineVariant = x.border, error = x.danger,
    )
    val square = RoundedCornerShape(0.dp)
    val type = Typography().run {
        copy(
            displayLarge = displayLarge.copy(fontFamily = MONO), displayMedium = displayMedium.copy(fontFamily = MONO),
            displaySmall = displaySmall.copy(fontFamily = MONO), headlineLarge = headlineLarge.copy(fontFamily = MONO),
            headlineMedium = headlineMedium.copy(fontFamily = MONO), headlineSmall = headlineSmall.copy(fontFamily = MONO),
            titleLarge = titleLarge.copy(fontFamily = MONO), titleMedium = titleMedium.copy(fontFamily = MONO),
            titleSmall = titleSmall.copy(fontFamily = MONO), bodyLarge = BASE, bodyMedium = BASE,
            bodySmall = BASE.copy(fontSize = 12.sp, lineHeight = 16.sp), labelLarge = BASE,
            labelMedium = BASE.copy(fontSize = 12.sp), labelSmall = BASE.copy(fontSize = 11.sp),
        )
    }
    MaterialTheme(scheme, Shapes(square, square, square, square, square), type) {
        CompositionLocalProvider(LocalX provides x, LocalIndication provides Block, LocalTextStyle provides BASE) {
            Surface(Modifier.fillMaxSize(), color = x.bg, contentColor = x.fg, content = content)
        }
    }
}

/** Press feedback without an animation: an accent wash while the finger is down, gone on release. */
object Block : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = BlockNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = javaClass.hashCode()
}

private class BlockNode(private val source: InteractionSource) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var presses = 0

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect {
                when (it) {
                    is PressInteraction.Press -> presses++
                    is PressInteraction.Release, is PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
                    else -> return@collect
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (presses > 0) drawRect(currentValueOf(LocalX).accent.copy(alpha = .35f))
    }
}

/** .titlebar: small uppercase accent caption on the darkest shade, border below. */
@Composable
fun Titlebar(text: String, modifier: Modifier = Modifier) {
    val x = LocalX.current
    Text(
        text.uppercase(),
        modifier.fillMaxWidth().background(x.titlebar).bottomLine(x.border).padding(horizontal = 8.dp, vertical = 4.dp),
        color = x.accent, fontSize = 11.sp, letterSpacing = 1.5.sp, maxLines = 1,
    )
}

/** .win: a framed panel with a titlebar. */
@Composable
fun Window(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val x = LocalX.current
    Column(modifier.fillMaxWidth().background(x.panel).border(1.dp, x.border)) {
        Titlebar(title)
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), content = content)
    }
}

/** .lbl */
@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, color = LocalX.current.dim, fontSize = 11.sp, letterSpacing = .5.sp)
}

/**
 * A button: lowercase label in a 1px box. [on] is the inverted accent block (a selected
 * dwm tag), [primary] an accent outline.
 */
@Composable
fun XButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    on: Boolean = false,
    primary: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val x = LocalX.current
    val fg = when {
        on -> x.bg
        primary -> x.accent
        else -> x.fg
    }
    Box(
        modifier
            .background(if (on) x.accent else x.panel2)
            .border(1.dp, if (on || primary) x.accent else x.border)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val c = if (enabled) fg else fg.copy(alpha = .4f)
        CompositionLocalProvider(LocalContentColor provides c) {
            if (content != null) content()
            else Text(text.lowercase(), color = c, fontSize = 13.sp, letterSpacing = .3.sp, maxLines = 1)
        }
    }
}

/** A flat bar filled to [fraction]; download progress and the like. */
@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier) {
    val x = LocalX.current
    Box(modifier.fillMaxWidth().height(3.dp).background(x.panel2)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(x.accent))
    }
}

/**
 * Seek bar: follows the finger from the first touch (a tap jumps), reports 0..1 while moving
 * and calls [onDone] on release.
 */
@Composable
fun SeekBar(fraction: Float, enabled: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val x = LocalX.current
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier.fillMaxWidth().height(32.dp)
            .then(
                if (!enabled) Modifier
                else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val at = { px: Float -> (px / size.width).coerceIn(0f, 1f) }
                        down.consume()
                        change(at(down.position.x))
                        drag(down.id) {
                            change(at(it.position.x))
                            it.consume()
                        }
                        done()
                    }
                },
            )
            .drawBehind {
                // whole pixels, so the frame stays a crisp line
                val px = 1.dp.roundToPx().toFloat()
                val h = 8.dp.roundToPx().toFloat()
                val top = ((size.height - h) / 2).roundToInt().toFloat()
                val inner = Size(size.width - 2 * px, h - 2 * px)
                drawRect(x.border, Offset(0f, top), Size(size.width, h))
                drawRect(x.panel2, Offset(px, top + px), inner)
                drawRect(if (enabled) x.accent else x.dim, Offset(px, top + px), inner.copy(width = inner.width * f))
                val w = 4.dp.roundToPx().toFloat()
                drawRect(if (enabled) x.fg else x.dim, Offset((size.width * f - w / 2).roundToInt().toFloat().coerceIn(0f, size.width - w), 0f), Size(w, size.height))
            },
    )
}

/** A single-line input; the border turns accent while focused. */
@Composable
fun Field(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val x = LocalX.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    BasicTextField(
        value, onChange,
        modifier.fillMaxWidth().background(x.panel2).border(1.dp, if (focused) x.accent else x.border)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        singleLine = true,
        textStyle = BASE.copy(color = x.fg, fontSize = 13.sp),
        cursorBrush = SolidColor(x.accent),
        interactionSource = source,
    )
}

/**
 * A window over the screen, shown and dismissed instantly (no dialog animation). Back or a
 * tap outside closes it.
 */
@Composable
fun Overlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f))
            .clickable(interactionSource = null, indication = null, onClick = onDismiss)
            .safeDrawingPadding().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.clickable(interactionSource = null, indication = null) {}) { content() }
    }
}

/** A 1px line along the bottom edge. */
fun Modifier.bottomLine(color: Color) = drawBehind {
    val px = 1.dp.toPx()
    drawRect(color, Offset(0f, size.height - px), Size(size.width, px))
}
