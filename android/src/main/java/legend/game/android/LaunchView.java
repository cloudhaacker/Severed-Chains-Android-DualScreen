package legend.game.android;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Dual Screen fork: the start menu shown on launch once the game is
 * installed. One big Start Game button (focused, so A or Start on a
 * controller begins right away) and a small Disc setup button for changing
 * the install.
 */
public final class LaunchView extends LinearLayout {
  private static final int GOLD = 0xffd9b45a;
  private static final int GOLD_DARK = 0xff8a6d2e;
  private static final int TEXT = 0xfff2e8d5;
  private static final int TEXT_DIM = 0xffa89a82;

  private final Button start;

  public LaunchView(final Context context, final Runnable onStart, final Runnable onSetup) {
    super(context);
    this.setOrientation(VERTICAL);
    this.setGravity(Gravity.CENTER);
    this.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xff151210, 0xff302a24}));

    final TextView title = new TextView(context);
    title.setText("SEVERED CHAINS");
    title.setTextColor(GOLD);
    title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
    title.setLetterSpacing(0.12f);
    title.setGravity(Gravity.CENTER);
    this.addView(title, wrap());

    final TextView subtitle = new TextView(context);
    subtitle.setText("The Legend of Dragoon  ·  Dual Screen");
    subtitle.setTextColor(TEXT_DIM);
    subtitle.setTypeface(Typeface.SERIF);
    subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    subtitle.setGravity(Gravity.CENTER);
    final LayoutParams subParams = wrap();
    subParams.topMargin = this.dp(6);
    subParams.bottomMargin = this.dp(40);
    this.addView(subtitle, subParams);

    this.start = this.button(context, "Start Game", 22, true);
    this.start.setOnClickListener(v -> onStart.run());
    final LayoutParams startParams = new LayoutParams(this.dp(300), this.dp(64));
    startParams.bottomMargin = this.dp(18);
    this.addView(this.start, startParams);

    final Button setup = this.button(context, "Disc setup", 14, false);
    setup.setOnClickListener(v -> onSetup.run());
    this.addView(setup, new LayoutParams(this.dp(160), this.dp(40)));

    // Controller: up/down moves between the buttons, A or Start presses
    this.start.setId(View.generateViewId());
    setup.setId(View.generateViewId());
    this.start.setNextFocusDownId(setup.getId());
    setup.setNextFocusUpId(this.start.getId());
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    this.start.requestFocus();
  }

  @Override
  public boolean dispatchKeyEvent(final KeyEvent event) {
    final int code = event.getKeyCode();
    if(code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_BUTTON_START) {
      if(event.getAction() == KeyEvent.ACTION_UP) {
        final View focused = this.findFocus();
        (focused != null ? focused : this.start).performClick();
      }
      return true;
    }
    return super.dispatchKeyEvent(event);
  }

  private Button button(final Context context, final String label, final int textSp, final boolean primary) {
    final Button b = new Button(context);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
    b.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
    b.setFocusable(true);
    b.setFocusableInTouchMode(true);
    b.setStateListAnimator(null);

    final android.content.res.ColorStateList fill = new android.content.res.ColorStateList(
      new int[][] {{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
      primary ? new int[] {GOLD, GOLD, GOLD_DARK} : new int[] {0x55d9b45a, 0x55d9b45a, 0x00000000});

    final GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(this.dp(32));
    bg.setColor(fill);
    bg.setStroke(this.dp(primary ? 2 : 1), GOLD);
    b.setBackground(bg);
    b.setTextColor(primary ? 0xff151210 : TEXT);
    return b;
  }

  private static LayoutParams wrap() {
    return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
  }

  private int dp(final int v) {
    return Math.round(v * this.getResources().getDisplayMetrics().density);
  }
}
