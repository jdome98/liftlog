package com.splitstak.app.wear.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import com.splitstak.app.wear.Haptic
import com.splitstak.app.wear.RotaryDispatcher
import com.splitstak.app.wear.data.ActionSender
import com.splitstak.app.wear.data.Exercise
import com.splitstak.app.wear.data.SetEntry
import com.splitstak.app.wear.data.Snapshot
import com.splitstak.app.wear.data.WatchState
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * The main interaction surface — one exercise, one set displayed at a time.
 *
 * Layout: a semicircular "header" near the top of the screen whose arc
 * traces close to the watch's bezel curvature, then value boxes, done
 * circle, and progress dots centered in the rest of the screen.
 *
 * Three focusable shapes — exercise semicircle, WT box, RPS box — all
 * driven by the same pattern: tap to focus (orange pulsing border),
 * crown to adjust, tap again to release. The crown is RATCHETED:
 * each detent fires one action + a tick haptic, then locks out further
 * actions for a short window so a fast spin doesn't blow past three
 * exercises at once.
 *
 * Rotary input is read from [RotaryDispatcher] (Activity-level intercept)
 * inside one persistent LaunchedEffect using rememberUpdatedState — no
 * re-subscription on focus/state changes.
 *
 * The PR overlay flashes only on the rising edge of done-count (a set
 * just transitioned to done) AND while the exercise is in PR territory.
 * Tap the overlay to dismiss early.
 */
@Composable
fun ActiveExerciseScreen(snapshot: Snapshot) {
    val context = LocalContext.current
    val selectedId by WatchState.widgetSelectedFlow.collectAsState()
    val exercise = snapshot.exercises.firstOrNull { it.id == selectedId }
        ?: snapshot.currentExercise()
        ?: return

    val isCardio = exercise.kind == "cardio"

    val setIdx = remember(exercise.id, exercise.sets) {
        val idx = exercise.sets.indexOfFirst { !it.d }
        if (idx >= 0) idx else (exercise.sets.size - 1).coerceAtLeast(0)
    }

    // Focus state — intentionally NOT keyed on exercise.id so it
    // persists while the user crowns through exercises.
    var focused by remember { mutableStateOf<String?>(null) }

    val isLefty = remember { detectLefty(context) }

    // Slow breathing pulse, read as State and dereferenced inside
    // graphicsLayer { } so alpha changes redraw without recomposing.
    val pulse = rememberInfiniteTransition(label = "focus-pulse")
    val pulseAlphaState: State<Float> = pulse.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "focus-pulse-alpha"
    )

    // One persistent rotary collector — RATCHETED so each detent fires
    // one action then locks out further events for a short window.
    val focusedRef = rememberUpdatedState(focused)
    val exerciseIdRef = rememberUpdatedState(exercise.id)
    val setIdxRef = rememberUpdatedState(setIdx)
    LaunchedEffect(Unit) {
        var accum = 0f
        var lockedUntilMs = 0L
        RotaryDispatcher.events.collect { delta ->
            val f = focusedRef.value ?: run { accum = 0f; return@collect }
            val now = System.currentTimeMillis()
            if (now < lockedUntilMs) {
                // In lockout — discard delta so it doesn't build up.
                accum = 0f
                return@collect
            }
            accum += delta
            val threshold = if (f == "exercise") 80f else 42f
            if (abs(accum) >= threshold) {
                val sign = if (accum > 0) 1 else -1
                accum = 0f
                val exId = exerciseIdRef.value
                val sIdx = setIdxRef.value
                when (f) {
                    "exercise" -> ActionSender.nav(context, sign)
                    "weight" -> ActionSender.incWeight(context, exId, sIdx, sign)
                    "reps"   -> ActionSender.incReps(context, exId, sIdx, sign)
                    "hold"   -> ActionSender.incHold(context, exId, sIdx, sign)
                    "ctime"  -> ActionSender.incTime(context, exId, sign.toDouble())
                    "cdist"  -> ActionSender.incDistance(context, exId, sign.toDouble())
                }
                Haptic.tick(context)
                // Ratchet lockout: long enough that sustained spinning
                // produces ~1 fire/sec for values, ~1/sec for exercise
                // nav, so landing on a specific value is deliberate.
                lockedUntilMs = now + if (f == "exercise") 1100L else 700L
            }
        }
    }

    // PR overlay — fires only on the rising edge of (allComplete && isPr),
    // i.e., the moment the user finishes the exercise AND it qualifies
    // as a PR. Adjusting weight/reps mid-exercise no longer triggers it.
    var showPrOverlay by remember { mutableStateOf(false) }
    val isPrComplete = exercise.allComplete && exercise.isPr
    val prevPrComplete = remember(exercise.id) { mutableStateOf(isPrComplete) }
    LaunchedEffect(exercise.id, isPrComplete) {
        if (isPrComplete && !prevPrComplete.value) {
            showPrOverlay = true
            delay(2500)
            showPrOverlay = false
        }
        prevPrComplete.value = isPrComplete
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitstakColors.Bg)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ExerciseSemicircle(
                exercise = exercise,
                setIdx = setIdx,
                isCardio = isCardio,
                focused = focused == "exercise",
                pulseAlphaState = pulseAlphaState,
                onClick = {
                    focused = if (focused == "exercise") null else "exercise"
                }
            )

            // Bottom area — body content centered vertically in
            // whatever's left below the semicircle.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BodyBoxes(
                    exercise = exercise,
                    setIdx = setIdx,
                    isCardio = isCardio,
                    focused = focused,
                    pulseAlphaState = pulseAlphaState,
                    onToggleFocus = { tag -> focused = if (focused == tag) null else tag }
                )

                val isDone: Boolean = if (isCardio) {
                    exercise.cardio?.done == true
                } else {
                    exercise.sets.getOrNull(setIdx)?.d == true
                }
                DoneCircle(
                    done = isDone,
                    onClick = {
                        if (isCardio) {
                            ActionSender.toggleDone(context, exercise.id, -1)
                        } else {
                            ActionSender.toggleDone(context, exercise.id, setIdx)
                        }
                    }
                )

                ProgressDots(exercises = snapshot.exercises)
            }
        }

        // Crown indicator arrow — pulses in sync with focused border.
        if (focused != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 2.dp),
                contentAlignment = if (isLefty) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Text(
                    text = if (isLefty) "‹" else "›",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = SplitstakColors.Accent,
                    modifier = Modifier.graphicsLayer { alpha = pulseAlphaState.value }
                )
            }
        }

        // PR overlay
        if (showPrOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SplitstakColors.Bg)
                    .pointerInput(Unit) {
                        detectTapGestures { showPrOverlay = false }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "PR",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 76.sp,
                    fontWeight = FontWeight.Black,
                    color = SplitstakColors.Accent
                )
            }
        }
    }
}

/**
 * Worn-on-right-wrist mode rotates the display 180°. Check both the
 * Display's rotation and USER_ROTATION fallback.
 */
private fun detectLefty(context: Context): Boolean {
    val rot = runCatching {
        if (Build.VERSION.SDK_INT >= 30) {
            context.display?.rotation
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation
        }
    }.getOrNull()
    if (rot == Surface.ROTATION_180) return true
    val user = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION, 0)
    }.getOrDefault(0)
    return user == Surface.ROTATION_180
}

/**
 * Arc shape that passes exactly through the bottom-left, top-center,
 * and bottom-right of the box, with a small inset so the border stroke
 * doesn't get clipped at the apex.
 *
 * Math: given box (w, h) and desired apex y = inset, find circle
 * center (w/2, cy) and radius r such that the circle passes through
 * (0, h) AND (w/2, inset). By symmetry the right corner (w, h) is also
 * on the circle.
 *
 *   r = cy - inset                      (distance from center to apex)
 *   (w/2)² + (h - cy)² = r²             (left corner on circle)
 *
 * Solving:  cy = ((w/2)² + h² - inset²) / (2·(h - inset))
 */
private object SemicircleShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val w = size.width
        val h = size.height
        val inset = with(density) { 2.dp.toPx() }
        val cx = w / 2f
        val cy = (cx * cx + h * h - inset * inset) / (2f * (h - inset))
        val r = cy - inset

        // Endpoint angles (Compose convention: 0° = +x, clockwise)
        val dyEdge = h - cy   // negative because h < cy
        val startRad = Math.atan2(dyEdge.toDouble(), -cx.toDouble())
        val endRad = Math.atan2(dyEdge.toDouble(), cx.toDouble())
        val startDeg = ((Math.toDegrees(startRad).toFloat() % 360f) + 360f) % 360f
        val endDeg = ((Math.toDegrees(endRad).toFloat() % 360f) + 360f) % 360f
        val sweepDeg = (endDeg - startDeg + 360f) % 360f

        val path = Path().apply {
            moveTo(0f, h)
            arcTo(
                rect = Rect(cx - r, cy - r, cx + r, cy + r),
                startAngleDegrees = startDeg,
                sweepAngleDegrees = sweepDeg,
                forceMoveTo = false
            )
            close()
        }
        return Outline.Generic(path)
    }
}

@Composable
private fun ExerciseSemicircle(
    exercise: Exercise,
    setIdx: Int,
    isCardio: Boolean,
    focused: Boolean,
    pulseAlphaState: State<Float>,
    onClick: () -> Unit
) {
    // Wider-than-tall semicircle that occupies roughly the top 40% of
    // the screen — enough vertical room for name + target + SET row
    // without crowding the watch bottom.
    Box(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .aspectRatio(2.2f)
            .background(SplitstakColors.Surface, shape = SemicircleShape)
            .pointerInput(Unit) { detectTapGestures { onClick() } }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.5.dp, SplitstakColors.Border, shape = SemicircleShape)
        )
        if (focused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = pulseAlphaState.value }
                    .border(1.5.dp, SplitstakColors.Accent, shape = SemicircleShape)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 14.dp, top = 32.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = exercise.name.uppercase(),
                fontFamily = FontFamily.SansSerif,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = SplitstakColors.Text,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (exercise.target.isNotEmpty()) {
                Text(
                    text = exercise.target,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    color = SplitstakColors.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = if (isCardio) "CARDIO" else "SET ${setIdx + 1}/${exercise.sets.size}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = SplitstakColors.TextDim,
                    maxLines = 1
                )
                if (exercise.allComplete && exercise.isPr) {
                    PrPill()
                }
            }
        }
    }
}

@Composable
private fun PrPill() {
    // Bare-letter "PR" — no border, no padding. Sits inline next to
    // the SET line and stays legible at small sizes.
    Text(
        text = "PR",
        fontFamily = FontFamily.SansSerif,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = SplitstakColors.Accent
    )
}

@Composable
private fun BodyBoxes(
    exercise: Exercise,
    setIdx: Int,
    isCardio: Boolean,
    focused: String?,
    pulseAlphaState: State<Float>,
    onToggleFocus: (String) -> Unit
) {
    val set = exercise.sets.getOrNull(setIdx) ?: SetEntry("", "", "", false)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            isCardio -> {
                val c = exercise.cardio
                ValueBox("MIN", c?.time ?: "", focused == "ctime", pulseAlphaState) { onToggleFocus("ctime") }
                ValueBox("MI", c?.distance ?: "", focused == "cdist", pulseAlphaState) { onToggleFocus("cdist") }
            }
            exercise.mode == "bodyweight" -> {
                ValueBox("RPS", set.r, focused == "reps", pulseAlphaState) { onToggleFocus("reps") }
            }
            exercise.mode == "time" -> {
                ValueBox("SEC", set.t, focused == "hold", pulseAlphaState) { onToggleFocus("hold") }
            }
            else -> {
                ValueBox("WT", set.w, focused == "weight", pulseAlphaState) { onToggleFocus("weight") }
                ValueBox("RPS", set.r, focused == "reps", pulseAlphaState) { onToggleFocus("reps") }
            }
        }
    }
}

@Composable
private fun ValueBox(
    label: String,
    value: String,
    focused: Boolean,
    pulseAlphaState: State<Float>,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            color = SplitstakColors.TextFaint
        )
        Spacer(Modifier.height(1.dp))
        Box(
            modifier = Modifier
                .width(54.dp)
                .height(32.dp)
                .background(SplitstakColors.Surface)
                .pointerInput(Unit) { detectTapGestures { onClick() } }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.5.dp, SplitstakColors.Border)
            )
            if (focused) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = pulseAlphaState.value }
                        .border(1.5.dp, SplitstakColors.Accent)
                )
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = value.ifEmpty { "—" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SplitstakColors.Text
                )
            }
        }
    }
}

@Composable
private fun DoneCircle(done: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (done) SplitstakColors.Accent else SplitstakColors.Surface,
            contentColor = if (done) SplitstakColors.Bg else SplitstakColors.TextDim
        )
    ) {
        Text(
            text = if (done) "✓" else "○",
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (done) SplitstakColors.Bg else SplitstakColors.TextDim
        )
    }
}

@Composable
private fun ProgressDots(exercises: List<Exercise>) {
    if (exercises.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (ex in exercises) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .background(
                        if (ex.allComplete) SplitstakColors.Accent
                        else SplitstakColors.Border,
                        shape = CircleShape
                    )
            )
        }
    }
}
