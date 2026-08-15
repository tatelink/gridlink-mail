package app.gridlink.ui.gridlink

import android.view.Gravity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * Modal glass: the centred sheet, the slide-out panel and the dialog every Gridlink screen shares.
 *
 * ## Where things sit, and why
 * 🔴 Nothing rises from the bottom edge any more. Tate called both of these directly: "the
 * hamburger menu should open a full top to bottom slide out panel, not the current way it does from
 * the bottom", and "the other popups should appear in the center, not rise from the bottom". So there
 * are exactly two placements now. The menu is a [GridlinkSlideOutPanel], anchored to the leading edge
 * and running the whole height of the display, because it is navigation and navigation is a place you
 * go. Everything else is a [GridlinkCenterSheet] in the middle of the screen, because it is a question
 * you answer and then it is gone.
 *
 * ## Why these are hand-rolled instead of Material 3's
 * [ProvideGridlinkTokens][app.gridlink.ui.theme.ProvideGridlinkTokens] deliberately does not touch
 * `MaterialTheme`, so upstream Gridlink's screens keep rendering in their own scheme while the
 * Gridlink screens are built. `ModalBottomSheet` and `AlertDialog` read `MaterialTheme.colorScheme`
 * and nothing else, so dropping one in here would open a sheet painted in upstream's Arctic palette
 * on top of a Gridlink screen. Fixing that means theming Material for the whole app, which is a
 * merge liability the fork has been avoiding on purpose. Two composables is the cheaper answer.
 *
 * ## Why the centred ones are a real [Dialog] and the drawer is not
 * For a card in the middle of the screen the window is worth having: it buys back-button dismissal,
 * focus, and an IME that resizes the right thing. A sheet faked inside the screen's own layout has to
 * reimplement all three, and the one people skip is the back button, which reads as fine in a
 * screenshot and terrible in the hand.
 *
 * 🔴 The drawer is the exception and is drawn in the activity instead. Everything a dialog window
 * gets wrong is an edge problem, and a full-height panel is nothing but edges: wrong frame, inherited
 * bottom-slide animation, insets that measure zero. The long version is on [GridlinkSlideOutPanel].
 */

/** Widest a centred dialog gets. A folded Fold is ~443dp, so this only binds when unfolded. */
private val DIALOG_MAX_WIDTH = 400.dp

/**
 * How much of the display the slide-out panel covers, and the most it ever covers.
 *
 * A fraction and not a fixed width, because the strip of scrim left down the trailing edge is the
 * whole affordance: it is what says the screen is still there behind this and what you tap to get
 * back to it. A panel that reached the far edge on a folded phone would read as a screen you had
 * navigated to, and then closing it by tapping outside would have nowhere to tap. The cap stops the
 * same fraction turning into a 566dp wall of empty rows when the Fold is opened.
 */
private const val PANEL_WIDTH_FRACTION = 0.84f
private val PANEL_MAX_WIDTH = 360.dp

/**
 * How long a modal takes to arrive and to leave.
 *
 * One number for every modal, because they overlap: dismissing the menu panel and opening a dialog
 * from a row in it are the same gesture, and two durations would make the second start before the
 * first had finished looking deliberate. 200ms is the short end of Material's range, which is the
 * right end for something that appears under a finger already in motion.
 */
private const val MODAL_ANIM_MS = 200

/** Where a centred modal starts from. Small: this is a settle, not a zoom. */
private const val MODAL_ENTER_SCALE = 0.96f

/**
 * How solid the frosted fill is when the backdrop behind it is really blurred.
 *
 * Far thinner than [FROST_OPACITY], and it is the blur that buys that. Once the high frequencies
 * are gone there is nothing legible left to hide, so the veil is only doing what a veil should do:
 * lifting the panel's value away from the room and giving the tint somewhere to sit. Everything
 * below 0.5 stops reading as a surface and starts reading as a smudge on the lens.
 */
private const val FROST_BLUR_OPACITY = 0.58f

/**
 * How solid the frosted fill is with no blur behind it, on API 30 and below.
 *
 * 🔴 Measured on the device, not chosen. 0.88 was the first attempt and it was plainly wrong: every
 * sender name and subject line in the list behind the drawer was still readable, which is the exact
 * "ghost" failure the opaque two-fill stack had been written to kill. The reason is worth keeping,
 * because it is the thing that makes fake frost hard. Real frost destroys high spatial frequencies
 * and keeps low ones, so you get colour and vague masses and no text. A flat alpha keeps ALL
 * frequencies at the same fraction, so 12% of a crisp black glyph on a pale card is still a crisp
 * black glyph, just faint. There is no alpha that blurs; there is only an alpha low enough to read
 * through. 0.94 puts the letters below the point where the eye resolves them while the panel still
 * takes a visible tint from whatever is behind it, and still shifts as that content scrolls.
 *
 * Sampled off the device across a bleed-through subject line in Day: 0.88 left a 14/255 luminance
 * spread, 0.94 leaves 8/255. If this is ever retuned, measure it the same way rather than judging a
 * downscaled screenshot, which exaggerates the bleed badly enough to have sent the first pass wrong.
 */
private const val FROST_OPACITY = 0.94f

/**
 * The lit edge. Top-leading bright, mid neutral, bottom-trailing slightly dark.
 *
 * Weak on purpose. This is the difference between a flat translucent rectangle and something that
 * reads as a physical pane, and it only works while you cannot point at it. 14% white over an
 * already-pale Day card is about two steps of value.
 */
private val FROST_SHEEN = listOf(
    0.00f to Color.White.copy(alpha = 0.14f),
    0.40f to Color.White.copy(alpha = 0.02f),
    1.00f to Color.Black.copy(alpha = 0.05f),
)

/**
 * The frosted surface shared by every modal, sheet, dialog and the drawer.
 *
 * 🔴 Tate: "make the hamburger menu, and all menus that popup or slide out frosted almost like
 * translucent but not". Both halves of that are load-bearing. Translucent enough that the screen
 * behind tints it and moves it; not so translucent that you can read what is back there.
 *
 * ## 🔴 Two frosts, and which one you get depends on the platform
 * Pass a [GridlinkBackdrop] and the panel draws a real blurred copy of what is behind it, and the
 * veil thins right down to [FROST_BLUR_OPACITY]. That is only available on API 31 and up; see
 * [GridlinkBackdrop] for the mechanism and for why the modals do not use it.
 *
 * With no backdrop the frost is composed rather than sampled: one solid veil at the much heavier
 * [FROST_OPACITY], one sheen, one hairline. [Modifier.blur][androidx.compose.ui.draw.blur] is not a
 * substitute; it blurs a composable's OWN content, not the backdrop.
 *
 * ## 🔴 Why the veil is composited first and then made transparent
 * The old stack painted two opaque fills, `background` then `surfaceRaised`, because Day's raised
 * glass is 72% white and 28% of the whole screen showing through a modal read as a ghost. Simply
 * dropping the alpha of that stack would give three different results in three palettes: Day's fill
 * would land at 0.88 × 0.72 = 63% white and dissolve, while Night's and OLED's opaque fills would
 * land at a well-behaved 88%. So [over] flattens the palette's designed glass into the single opaque
 * colour it actually resolves to, and only then is one known alpha applied to it. Every mode gets
 * the same 12% of backdrop, which is the only way one number can be tuned by eye.
 *
 * ## The sheen is skipped on OLED
 * OLED ships `usesShadows = false`, and that flag means what it says everywhere else in the app: no
 * lit or shaded fields on a mode whose whole premise is that unlit pixels are off. A 14% white wash
 * across the top third of a drawer is exactly the large soft field that mode exists to avoid.
 */
@Composable
private fun Modifier.gridlinkFrostedGlass(
    shape: Shape,
    backdrop: GridlinkBackdrop? = null,
    backdropShift: DrawScope.() -> Float = { 0f },
): Modifier {
    val colors = GridlinkTheme.colors
    val alpha = if (backdrop != null) FROST_BLUR_OPACITY else FROST_OPACITY
    val veil = remember(colors.surfaceRaised, colors.background, alpha) {
        colors.surfaceRaised.over(colors.background).copy(alpha = alpha)
    }
    return this
        // 🔴 Clip first. Everything below draws to the node's full bounds, and the blurred backdrop
        // is a copy of the entire window, so without the clip already in place the panel paints the
        // whole screen over itself.
        .clip(shape)
        .gridlinkBackdropBehind(backdrop, backdropShift)
        .background(veil, shape)
        .then(
            if (colors.usesShadows) {
                // Diagonal, not vertical. A vertical wash on a full-height drawer is a gradient you
                // can name; across the corner it reads as a light source somewhere off the screen.
                Modifier.background(
                    brush = Brush.linearGradient(
                        colorStops = FROST_SHEEN.toTypedArray(),
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    ),
                    shape = shape,
                )
            } else {
                Modifier
            },
        )
        .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
}

/**
 * Flatten a translucent colour onto an opaque one, source-over.
 *
 * ⚠️ Assumes [base] is opaque, which every palette's `background` is. If one ever ships translucent,
 * this returns a colour that is too light and nothing will fail; check there before trusting it.
 */
private fun Color.over(base: Color): Color {
    if (alpha >= 1f) return this
    val a = alpha
    return Color(
        red = red * a + base.red * (1f - a),
        green = green * a + base.green * (1f - a),
        blue = blue * a + base.blue * (1f - a),
    )
}

/**
 * The window, the scrim and the dismiss behaviour, with nothing opinionated inside it.
 *
 * Public because the schedule-send sheet needs to put something on the scrim that is NOT the card:
 * the send control it was long-pressed from stays lit above the sheet, so the sheet visibly belongs
 * to that button. [GridlinkCenterSheet] is the right entry point for everything else.
 *
 * [content] is handed the system bar insets because it cannot read them itself: the window below is
 * put into FLAG_LAYOUT_NO_LIMITS and `WindowInsets.systemBars` measures flat zero inside it (see the
 * note on [bars]). Callers that stay inside the [GridlinkSpacing.chrome] margin can ignore the
 * parameter, since the scrim has already applied the same insets on their behalf.
 *
 * ## The animation, and why it is here rather than at each call site
 * 🔴 The platform's own window animation is switched off and this composable replaces it. Tate
 * caught it on the menu: a Compose [Dialog] inherits the activity theme's window animation, and this
 * app's is a bottom slide, so every modal in here visibly rose from the bottom no matter what shape
 * it was. That animation belongs to the WINDOW, so nothing done to the content can undo it; it has
 * to be killed at the window and re-done in Compose. The replacement is a fade and a settle from
 * [MODAL_ENTER_SCALE], applied here so every dialog and sheet gets it without asking.
 *
 * 🔴 [onDismiss] is deferred by [MODAL_ANIM_MS], not called immediately. Every caller's answer to a
 * dismiss is to stop composing the modal, which takes the window with it, so an exit animation
 * started at the same moment plays into a window that has already gone. Running it first and
 * reporting afterwards is what makes the modal fade out instead of blink out. The back button is
 * routed through the same path, so it looks the same as tapping the scrim.
 *
 * ⚠️ Not the right primitive for a drawer. See [GridlinkSlideOutPanel], which deliberately does not
 * use this.
 */
@Composable
fun GridlinkModal(
    onDismiss: () -> Unit,
    alignment: Alignment,
    content: @Composable (bars: PaddingValues) -> Unit,
) {
    // 🔴 Read the bars HERE, outside the Dialog, and hand them in. The window below is put into
    // FLAG_LAYOUT_NO_LIMITS, and a no-limits window is by definition told it has nothing to avoid:
    // `WindowInsets.systemBars` inside the dialog measures a flat zero (verified on device, top=0
    // bottom=0). A sheet that trusted it would sit its last action under the gesture bar. This line
    // still runs in the host activity's composition, where the same insets are real.
    val bars = WindowInsets.systemBars.asPaddingValues()
    // 🔴 And the keyboard, for the same reason and read from the same place, but applied ONLY to the
    // modal's own frame below — not handed to [content], which already lays itself out against
    // [bars] and would shift by the keyboard's whole height if this went in there too.
    //
    // Without it a centred dialog is centred in the display while the keyboard covers the bottom
    // half of it, and its Cancel/confirm row is drawn underneath the keys. Found on the link dialog,
    // which is the first one in the app that opens with the keyboard already up and stays there
    // while you type. Every other dialog with a text field has had it latent since it was written:
    // the rename dialogs are shorter, so their buttons happened to clear the keys.
    //
    // union takes the larger of each side rather than the sum, so the gesture bar's inset does not
    // get added on top of a keyboard that already covers it.
    val insets = WindowInsets.systemBars.union(WindowInsets.ime).asPaddingValues()
    // False on the first frame so there is something to animate from. Flipped in a LaunchedEffect
    // rather than seeded true, because a state that is already at its target does not animate.
    var shown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = MODAL_ANIM_MS, easing = FastOutSlowInEasing),
        label = "modalProgress",
    )
    val dismiss: () -> Unit = {
        // Guarded, so a second tap during the exit does not queue a second onDismiss. The scrim is
        // still up and still clickable for those 200ms.
        if (shown) {
            shown = false
            scope.launch {
                delay(MODAL_ANIM_MS.toLong())
                onDismiss()
            }
        }
    }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            // The card decides its own width; the platform's dialog width would put a bottom sheet
            // in a floating rounded box two thirds of the way across the screen.
            usePlatformDefaultWidth = false,
            // 🔴 So the scrim reaches the status bar. Left at the default the dialog window is inset
            // by the system bars, the dim stops short at the top, and the modal reads as a panel
            // that failed to cover the screen rather than as a layer over it. The content pays for
            // this by applying the insets itself, below.
            //
            // ⚠️ Necessary but NOT sufficient. See the window surgery immediately below: on Compose
            // 1.7 this flag alone does not get the window onto the display, it only stops the
            // *content* being inset inside whatever frame the window ends up with.
            decorFitsSystemWindows = false,
        ),
    ) {
        // 🔴 Put the dialog window on the DISPLAY, not in the app's content area. Measured on the
        // emulator with `dumpsys window windows`, the dialog window came back as
        //   parent=[0,152][1080,2305]  frame=[0,152][1080,2305]  Requested w=1080 h=2364
        // while the display is [0,0][1080,2364]. The window asked for the full display height and
        // was refused, because `decorFitsSystemWindows = false` does NOT set
        // FLAG_LAYOUT_NO_LIMITS in this Compose version: the window's parent frame stays the app
        // area, so it is both positioned 152px down and capped 59px short. The Compose content is
        // still measured at the full 2364, so the whole bottom inset hung off the end of the screen
        // and the last row of every sheet landed under the gesture bar.
        //
        // 🔴 That is a [GridlinkModal] fault, not a schedule-sheet fault: the folder action sheet
        // had it from the day it was written (Delete row measured at [49,2267][1031,2364]). A short
        // sheet just hides it better, which is why it went unnoticed until a four-row sheet.
        //
        // Every line is load-bearing, and the two flags are NOT the same flag. NO_LIMITS alone only
        // lets the window overhang its parent: it grew the frame to [0,152][1080,2516], still
        // starting 152px down and now hanging 152px off the bottom. LAYOUT_IN_SCREEN is what
        // re-parents it from the app area to the display so TOP means the top of the screen. (The
        // system wallpaper window in the same dump carries exactly this pair for the same reason.)
        // MATCH_PARENT then claims all of it, and TOP|START stops it being centred. Drop any one
        // and the sheet drifts again.
        val view = LocalView.current
        // 🔴 Composition-time, NOT a SideEffect, and this one is not interchangeable with the block
        // below. A window's enter animation is picked when it is shown, and Compose shows the dialog
        // from an effect, which runs after the content's effects have already been applied. Setting
        // this in a SideEffect therefore lands one frame late and cancels only the EXIT: measured as
        // a drawer that slid up from the bottom, arrived, and then slid out sideways. Doing it during
        // composition gets in before show(). Resource 0 means "no animation style at all".
        remember(view) {
            (view.parent as? DialogWindowProvider)?.window?.setWindowAnimations(0)
        }
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            )
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            window.setGravity(Gravity.TOP or Gravity.START)
            // Belt and braces on the animation the block above already killed, for the case where
            // this window outlives a configuration change and gets re-shown.
            window.setWindowAnimations(0)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                // ⚠️ Per-mode, not one flat black. Day's used to be 55% black over a blue gradient,
                // which desaturated the whole page toward grey and read as the screen having gone
                // wrong. It is now the background blue at 50%, so the page dims without changing
                // family. OLED goes the other way and gets a heavier one, because black over black
                // separates nothing.
                //
                // Faded with the content rather than snapped on. Scaling the token's own alpha, not
                // replacing it: the three modes ship three different scrim strengths on purpose and
                // a flat alpha here would throw all three away.
                .background(
                    GridlinkTheme.colors.scrim.copy(
                        alpha = GridlinkTheme.colors.scrim.alpha * progress,
                    ),
                )
                // Tap outside to dismiss. No indication: a ripple the size of the screen is not a
                // press state, it is a flash.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = dismiss,
                )
                .padding(insets)
                .padding(GridlinkSpacing.chrome),
            contentAlignment = alignment,
        ) {
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = progress
                    val settle = MODAL_ENTER_SCALE + (1f - MODAL_ENTER_SCALE) * progress
                    scaleX = settle
                    scaleY = settle
                },
                content = { content(bars) },
            )
        }
    }
}

/**
 * The card itself.
 *
 * 🔴 Swallows clicks. Without the no-op clickable, every tap on the sheet falls through to the
 * scrim's dismiss handler and the sheet closes when you reach for the thing inside it.
 *
 * The surface is [gridlinkFrostedGlass], which is where the reasoning about Day's 72%-white raised
 * glass over a whole screen now lives. Read it before changing the fill.
 */
@Composable
fun GridlinkModalCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(GridlinkRadii.card)
    Column(
        modifier = modifier
            .gridlinkFrostedGlass(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        content = content,
    )
}

/**
 * A sheet of actions, centred on the screen.
 *
 * Centred, not anchored where the finger was. The row that was long-pressed can be anywhere down a
 * tall screen, and an anchored menu ends up wherever that happens to be, including the top third of
 * an unfolded Fold where no thumb reaches. A sheet in the middle is always in the same place, and it
 * has room to restate which folder is being acted on, which a floating menu can only do by repeating
 * the name in a header nobody reads.
 *
 * 🔴 This used to rise from the bottom edge, on the reasoning that the bottom is where the thumb
 * lives. Tate overruled it: "the other popups should appear in the center, not rise from the
 * bottom". Centre it is, and the two effects are worth naming so nobody quietly reverts them. The
 * card now takes [DIALOG_MAX_WIDTH] rather than the full width, so a sheet and a
 * [GridlinkDialog] are the same object at two heights instead of two different shapes; and a short
 * sheet no longer sits under the composer's own controls, which is what made the folder sheet's
 * Delete row feel adjacent to the tree it was deleting from.
 */
@Composable
fun GridlinkCenterSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GridlinkModal(onDismiss = onDismiss, alignment = Alignment.Center) {
        GridlinkModalCard(
            modifier = modifier
                .widthIn(max = DIALOG_MAX_WIDTH)
                .fillMaxWidth(),
            content = content,
        )
    }
}

/**
 * A panel that slides in from the leading edge and runs the full height of the display.
 *
 * 🔴 The menu's container, and Tate's words for it: "the hamburger menu should open a full top to
 * bottom slide out panel, not the current way it does from the bottom. clicking off it closes". It
 * is a different object from [GridlinkCenterSheet] on purpose. A centred card is a question, asked
 * and answered and gone; this is the app's other half, and a thing you can leave open while you look
 * at what it did (which is exactly what the mode track in it needs, since you judge a palette by
 * watching the screen behind repaint).
 *
 * ## Shape and edges
 * Square on the leading edge, rounded on the trailing one. The leading edge is off the display, so
 * rounding it would carve two notches out of the screen corners and make the panel look like it had
 * failed to reach them. The trailing edge is the one you can see against the scrim, so that is the
 * one that gets the radius.
 *
 * The panel is full-bleed vertically, under the status bar and under the gesture bar, and pads its
 * own content back out by [bars] instead. A panel that stopped short of either would leave a band of
 * scrim above and below it, which reads as a card that did not fit rather than as a drawer.
 *
 * Scrolls, because the content is a list that will grow: accounts, mailboxes, and whatever Settings
 * turns out to need. A drawer that silently clips its last row on a short screen is the failure mode
 * here, and it is invisible on the device it was built on.
 *
 * ## 🔴 Not a dialog. Deliberately.
 * This is the one modal in the app that is drawn INSIDE the activity, as the last child of
 * [GridlinkScaffold]'s background box, and it took three failed attempts in a [Dialog] to get here.
 * A dialog window brings three problems that a drawer cannot live with and a centred card can:
 *
 *  1. **The frame.** Opened by tapping the hamburger (but not when the activity launched with the
 *     menu already open) the dialog window came back parented to the app area rather than the
 *     display, `parent=[0,152][1080,2305]` against a `[0,0][1080,2364]` screen, even with
 *     FLAG_LAYOUT_IN_SCREEN set. The drawer sat 152px down with a black band under it. A card that
 *     floats in the middle never notices; a full-height panel is nothing but edges.
 *  2. **The animation.** A dialog inherits the activity theme's window animation, which here is a
 *     bottom slide. [GridlinkModal] beats that down with `setWindowAnimations(0)` at composition
 *     time, which works, but it is a fight with the platform that has to be won on every device.
 *  3. **The insets.** A no-limits dialog window measures `WindowInsets.systemBars` as flat zero, so
 *     the real values have to be read outside and passed in.
 *
 * Drawn in the activity, all three simply do not exist: the box is the display, the only animation is
 * the one written below, and the insets are the activity's own. The cost is that the caller has to
 * place it last so it paints over everything, and has to own the back button, which
 * [GridlinkScaffold] does.
 */
@Composable
fun GridlinkSlideOutPanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = GridlinkTheme.colors
    // Null below API 31, and null if whoever drew this did not mark a region to look through. Both
    // fall back to the composed frost rather than to nothing.
    val backdrop = LocalGridlinkBackdrop.current
    val shape = RoundedCornerShape(
        topEnd = GridlinkRadii.card,
        bottomEnd = GridlinkRadii.card,
    )
    // The activity's own insets, read here where they are real. The panel is full-bleed and pads its
    // content back out by these; the scrim wants them left alone so it reaches both bars.
    val bars = WindowInsets.systemBars.asPaddingValues()
    // Same arrival machinery as GridlinkModal, and it has to be duplicated rather than shared: that
    // one owns a window, this one owns a Box, and the only part they have in common is the timing.
    var shown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = MODAL_ANIM_MS, easing = FastOutSlowInEasing),
        label = "panelProgress",
    )
    val dismiss: () -> Unit = {
        // 🔴 Deferred, not immediate. The caller's answer to a dismiss is to stop composing this, so
        // reporting it straight away removes the panel mid-slide and it blinks out. Guarded so a
        // second tap during the exit does not queue a second onDismiss.
        if (shown) {
            shown = false
            scope.launch {
                delay(MODAL_ANIM_MS.toLong())
                onDismiss()
            }
        }
    }
    // The back button closes the drawer instead of leaving the screen, which a dialog got for free
    // and an in-activity overlay has to ask for. Same path as tapping the scrim, so it animates.
    BackHandler(enabled = shown, onBack = dismiss)
    // Measured, not guessed from the configuration. `screenWidthDp` is the window's width in the
    // common case and quietly wrong in the two that matter on this device: split screen, and the
    // moment during an unfold when the two disagree.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Scaling the token's own alpha, not replacing it: the three modes ship three different
            // scrim strengths on purpose and a flat alpha here would throw all three away.
            .background(colors.scrim.copy(alpha = colors.scrim.alpha * progress))
            // Tap outside to dismiss, which is the whole of Tate's "clicking off it closes". No
            // indication: a ripple the size of the screen is not a press state, it is a flash.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = dismiss,
            ),
    ) {
        Column(
            modifier = modifier
                .width((maxWidth * PANEL_WIDTH_FRACTION).coerceAtMost(PANEL_MAX_WIDTH))
                .fillMaxHeight()
                // Slides in from off the leading edge and back out the same way. Its own width, read
                // at draw time, so the distance is right whether the cap bound or not. A
                // graphicsLayer and not an offset: this moves every frame, and re-laying out a
                // scrolling column to do it would be the one place in the app that stutters.
                .graphicsLayer { translationX = -size.width * (1f - progress) }
                // Same surface as GridlinkModalCard, and the drawer is the one that shows it off:
                // it is the only frosted panel with live content sliding past behind it, and the
                // only one that gets a really blurred backdrop rather than the composed stand-in.
                //
                // 🔴 The shift undoes the translationX above. The panel slides; the room behind it
                // does not.
                .gridlinkFrostedGlass(
                    shape = shape,
                    backdrop = backdrop,
                    backdropShift = { size.width * (1f - progress) },
                )
                // 🔴 Swallow taps, or every press inside the panel falls through to the scrim's
                // dismiss and the drawer closes as you reach for a row in it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(
                    top = bars.calculateTopPadding(),
                    bottom = bars.calculateBottomPadding(),
                )
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

/**
 * The sheet's identity line: what you long-pressed, restated.
 *
 * Not decoration. A sheet that says only "Rename / Delete" is a sheet you have to trust hit the row
 * you meant, and on a dense tree with five sibling store numbers in it that trust is misplaced often
 * enough to matter. Deleting the wrong mailbox is not recoverable here.
 */
@Composable
fun GridlinkSheetHeading(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subline: String? = null,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = GridlinkSpacing.chrome,
                end = GridlinkSpacing.chrome,
                top = GridlinkSpacing.chrome,
                bottom = GridlinkSpacing.s16,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.padding(start = GridlinkSpacing.s12)) {
            Text(
                text = title,
                style = GridlinkType.senderName,
                color = colors.textPrimary,
            )
            if (subline != null) {
                Text(
                    text = subline,
                    style = GridlinkType.metadata,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/**
 * One action in a sheet.
 *
 * [onClick] of null renders the row as an explanation rather than a control: the glyph and label go
 * secondary and the [subline] says why. That is the honest treatment for something like deleting a
 * folder that still has folders in it, where the server will refuse and the user is owed the reason
 * before they tap rather than a toast afterwards.
 */
@Composable
fun GridlinkSheetAction(
    label: String,
    icon: ImageVector,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subline: String? = null,
    tint: Color? = null,
    /**
     * Extra start padding on the row's CONTENT, for a nested folder.
     *
     * 🔴 Inside the clickable, not around it. Indenting the row itself would make a child mailbox's
     * tap target narrower than its parent's and leave a dead strip down the left of the drawer that
     * looks tappable and is not.
     */
    indent: Dp = 0.dp,
    /**
     * Drawn hard against the end of the row, after the label column takes the slack.
     *
     * For a row whose tap changes a state rather than opening something: the state has to be
     * readable before the tap, and a sheet action that silently means "off" is a control the user
     * has to poke to interrogate.
     */
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = GridlinkTheme.colors
    val enabled = onClick != null
    val contentColor = when {
        !enabled -> colors.textSecondary
        tint != null -> tint
        else -> colors.textPrimary
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick!!) else Modifier)
            .padding(horizontal = GridlinkSpacing.chrome, vertical = GridlinkSpacing.s12)
            .padding(start = indent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Column(
            modifier = Modifier
                .padding(start = GridlinkSpacing.s16)
                .then(if (trailing != null) Modifier.weight(1f) else Modifier),
        ) {
            Text(
                text = label,
                style = GridlinkType.senderName,
                color = contentColor,
            )
            if (subline != null) {
                Text(
                    text = subline,
                    style = GridlinkType.metadata,
                    color = colors.textSecondary,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(GridlinkSpacing.s12))
            trailing()
        }
    }
}

/** Hairline across a sheet, edge to edge. Same rule as the message list: separate, never gap. */
@Composable
fun GridlinkSheetDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(GridlinkDimens.hairline)
            .background(GridlinkTheme.colors.divider),
    )
}

/** Bottom padding for a sheet, so the last action is not flush against the card's rounded edge. */
@Composable
fun GridlinkSheetFooterSpace() {
    Spacer(Modifier.height(GridlinkSpacing.s12))
}

/**
 * A centred dialog that asks one question and takes one answer.
 *
 * The confirm button is disabled rather than hidden when the answer is not usable yet, and whatever
 * made it unusable is stated in [body] where the eye already is. A confirm that silently does
 * nothing, or one that vanishes, both leave the user guessing at a rule the app already knows.
 */
@Composable
fun GridlinkDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    body: @Composable ColumnScope.() -> Unit,
) {
    val colors = GridlinkTheme.colors
    GridlinkModal(onDismiss = onDismiss, alignment = Alignment.Center) {
        GridlinkModalCard(
            modifier = modifier
                .widthIn(max = DIALOG_MAX_WIDTH)
                .fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(GridlinkSpacing.chrome)) {
                Text(
                    text = title,
                    style = GridlinkType.senderName,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(GridlinkSpacing.s16))
                body()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = GridlinkSpacing.s12,
                        end = GridlinkSpacing.s12,
                        bottom = GridlinkSpacing.s12,
                    ),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GridlinkTextButton(label = "Cancel", onClick = onDismiss)
                Spacer(Modifier.width(GridlinkSpacing.s8))
                GridlinkTextButton(
                    label = confirmLabel,
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    tint = if (destructive) colors.destructive else colors.accent,
                )
            }
        }
    }
}

/**
 * Text-only button for dialog footers.
 *
 * ⚠️ Disabled state IS dimmed here, which the app forbids elsewhere. That ban is about on/off
 * controls, where dim reads as "off" and lies about the state of a switch. A confirm button is not
 * a state, it is a door, and dim reading as "not yet" is exactly right.
 */
@Composable
fun GridlinkTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = GridlinkTheme.colors.accent,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Box(
        modifier = modifier
            .clip(shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = GridlinkSpacing.s16, vertical = GridlinkSpacing.s12),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = GridlinkType.toolbarLabel.copy(fontSize = GridlinkType.senderName.fontSize),
            color = if (enabled) tint else colors.textSecondary.copy(alpha = 0.45f),
        )
    }
}
