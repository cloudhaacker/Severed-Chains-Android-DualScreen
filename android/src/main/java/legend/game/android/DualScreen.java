package legend.game.android;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.function.Function;

/**
 * Dual Screen fork: owns the window on the second display (the Ayn Thor's
 * lower screen) and whatever panel is showing there.
 *
 * <p>Callers hand in a factory rather than a view, because the panel has to
 * be built with the second display's own context (its size and density),
 * and has to be rebuilt if the display disappears and comes back.</p>
 *
 * <p>When the device has only one screen every method is a harmless no-op,
 * so the app behaves exactly like upstream's Android port.</p>
 */
public final class DualScreen {
  private static final String TAG = "DualScreen";

  private static Activity activity;
  private static DisplayManager displayManager;
  private static Presentation presentation;
  private static FrameLayout host;
  private static Function<Context, View> factory;
  private static boolean focusable;

  private DualScreen() { }

  private static final DisplayManager.DisplayListener LISTENER = new DisplayManager.DisplayListener() {
    @Override
    public void onDisplayAdded(final int displayId) {
      Log.i(TAG, "display added: " + displayId);
      ensurePresentation();
    }

    @Override
    public void onDisplayRemoved(final int displayId) {
      Log.i(TAG, "display removed: " + displayId);
      if(presentation != null && presentation.getDisplay().getDisplayId() == displayId) {
        dismiss();
      }
    }

    @Override
    public void onDisplayChanged(final int displayId) { }
  };

  /** Call once from MainActivity.onCreate. */
  public static void init(final Activity owner) {
    activity = owner;
    displayManager = owner.getSystemService(DisplayManager.class);
    if(displayManager != null) {
      displayManager.registerDisplayListener(LISTENER, new Handler(Looper.getMainLooper()));
    }
    // Wait for the activity's own window before opening one on the other
    // screen, or Android rejects the window token
    owner.getWindow().getDecorView().post(DualScreen::ensurePresentation);
  }

  /** Call from MainActivity.onDestroy. */
  public static void release() {
    if(displayManager != null) {
      displayManager.unregisterDisplayListener(LISTENER);
    }
    dismiss();
    factory = null;
    activity = null;
  }

  /** True when a second screen is connected and our window is showing on it. */
  public static boolean available() {
    return presentation != null;
  }

  /** Display id of the lower screen, or -1 when there isn't one. */
  public static int displayId() {
    return presentation != null ? presentation.getDisplay().getDisplayId() : -1;
  }

  /**
   * Show a panel on the lower screen. {@code focusable} decides whether the
   * lower screen may take key focus: true for setup (so a controller can
   * drive it), false while the game runs so gamepad input always reaches the
   * game on the top screen. Touch works either way.
   */
  public static void setContent(final Function<Context, View> panelFactory, final boolean takeFocus) {
    factory = panelFactory;
    focusable = takeFocus;
    if(presentation != null) {
      attachContent();
    } else if(activity != null) {
      activity.getWindow().getDecorView().post(DualScreen::ensurePresentation);
    }
  }

  public static void clearContent() {
    factory = null;
    if(host != null) {
      host.removeAllViews();
    }
  }

  private static Display findLowerDisplay() {
    if(displayManager == null) {
      return null;
    }

    // Presentation-category displays first (how most dual-screen handhelds
    // expose the second panel), then any other display that isn't the main one
    for(final Display d : displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
      if(d.getDisplayId() != Display.DEFAULT_DISPLAY) {
        return d;
      }
    }

    for(final Display d : displayManager.getDisplays()) {
      if(d.getDisplayId() != Display.DEFAULT_DISPLAY) {
        return d;
      }
    }

    return null;
  }

  private static void ensurePresentation() {
    if(presentation != null || activity == null || activity.isFinishing()) {
      return;
    }

    final Display display = findLowerDisplay();
    if(display == null) {
      return;
    }

    Log.i(TAG, "using display " + display.getDisplayId() + " (" + display.getName() + ")");

    final Presentation p = new Presentation(activity, display);
    final FrameLayout frame = new FrameLayout(p.getContext());
    frame.setBackgroundColor(0xff151210);
    p.setContentView(frame);
    p.setOnDismissListener(dialog -> {
      if(presentation == p) {
        presentation = null;
        host = null;
      }
    });

    try {
      p.show();
    } catch(final RuntimeException e) {
      Log.w(TAG, "couldn't show on display " + display.getDisplayId(), e);
      return;
    }

    presentation = p;
    host = frame;
    attachContent();
  }

  private static void attachContent() {
    if(presentation == null || host == null) {
      return;
    }

    if(focusable) {
      presentation.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    } else {
      presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    host.removeAllViews();
    if(factory == null) {
      return;
    }

    final View panel = factory.apply(presentation.getContext());
    if(panel.getParent() instanceof ViewGroup) {
      ((ViewGroup)panel.getParent()).removeView(panel);
    }
    host.addView(panel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
  }

  private static void dismiss() {
    final Presentation p = presentation;
    presentation = null;
    host = null;
    if(p != null) {
      try {
        p.dismiss();
      } catch(final IllegalArgumentException ignored) {
        // Window already gone with its display
      }
    }
  }
}
