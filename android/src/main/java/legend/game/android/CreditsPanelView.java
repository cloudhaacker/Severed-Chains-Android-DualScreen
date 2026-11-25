package legend.game.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.Log;
import android.view.View;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dual Screen fork: slowly scrolling thanks for the lower screen while the
 * player sets up discs or sits on the start menu.
 *
 * <p>The Severed Chains part is read from upstream's own credits.txt (the
 * same file its in-game credits use), with section titles from its language
 * file, so the list stays current whenever upstream updates it.</p>
 */
public final class CreditsPanelView extends View {
  private static final String TAG = "CreditsPanel";
  private static final String LANG_PREFIX = "lod_core.ui.about.";

  private static final int BG_TOP = 0xff151210;
  private static final int BG_BOTTOM = 0xff302a24;
  private static final int GOLD = 0xffd9b45a;
  private static final int TEXT = 0xfff2e8d5;
  private static final int TEXT_DIM = 0xffa89a82;

  private enum Kind { TITLE, HEADING, NAME, NOTE, GAP }

  private static final class Line {
    final Kind kind;
    final String text;

    Line(final Kind kind, final String text) {
      this.kind = kind;
      this.text = text;
    }
  }

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final float density;
  private final List<Line> lines = new ArrayList<>();
  private float contentHeight;
  private long startMs;

  public CreditsPanelView(final Context context) {
    super(context);
    this.density = context.getResources().getDisplayMetrics().density;
    this.build(context);
  }

  private void build(final Context context) {
    this.add(Kind.TITLE, "Severed Chains");
    this.add(Kind.GAP, null);
    this.readUpstreamCredits(context);

    this.add(Kind.TITLE, "Android Port");
    this.add(Kind.NAME, "dracinn");
    this.add(Kind.GAP, null);
    this.add(Kind.GAP, null);

    this.add(Kind.TITLE, "The Legend of Dragoon");
    this.add(Kind.NOTE, "Sony Computer Entertainment, 1999");
    this.add(Kind.GAP, null);
    this.add(Kind.GAP, null);

    this.add(Kind.TITLE, "Dual Screen");
    this.add(Kind.NAME, "John (cloudhaacker)");
    this.add(Kind.NOTE, "Ideas, design and testing");
    this.add(Kind.NAME, "Claude, an AI by Anthropic");
    this.add(Kind.NOTE, "Code");
    this.add(Kind.GAP, null);
    this.add(Kind.GAP, null);
  }

  private void add(final Kind kind, final String text) {
    this.lines.add(new Line(kind, text));
  }

  private void readUpstreamCredits(final Context context) {
    final Map<String, String> titles = readLang();

    try(InputStream in = context.getAssets().open("credits.txt");
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      boolean first = true;
      while((line = r.readLine()) != null) {
        line = line.trim();
        if(line.isEmpty()) {
          continue;
        }

        if(line.startsWith("#")) {
          final String key = line.replaceFirst("^#+\\s*", "");
          if(!first) {
            this.add(Kind.GAP, null);
          }
          first = false;
          this.add(Kind.HEADING, titles.getOrDefault(key, humanize(key)));
        } else if(line.startsWith("@")) {
          this.add(Kind.NOTE, line.substring(1).trim());
        } else {
          this.add(Kind.NAME, line);
        }
      }
      this.add(Kind.GAP, null);
    } catch(final Exception e) {
      Log.w(TAG, "couldn't read credits.txt", e);
      this.add(Kind.NAME, "The Legend of Dragoon Modding community");
      this.add(Kind.GAP, null);
    }
  }

  /** Section titles from upstream's English language file, if it's on the classpath. */
  private static Map<String, String> readLang() {
    final Map<String, String> out = new HashMap<>();
    try(InputStream in = CreditsPanelView.class.getResourceAsStream("/lod_core/lang/en.lang")) {
      if(in == null) {
        return out;
      }
      final BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String line;
      while((line = r.readLine()) != null) {
        if(line.startsWith(LANG_PREFIX + "sc_credits")) {
          final int eq = line.indexOf('=');
          if(eq > 0) {
            out.put(line.substring(0, eq), line.substring(eq + 1));
          }
        }
      }
    } catch(final Exception e) {
      Log.w(TAG, "couldn't read language file", e);
    }
    return out;
  }

  private static String humanize(final String key) {
    String s = key.startsWith(LANG_PREFIX) ? key.substring(LANG_PREFIX.length()) : key;
    s = s.replaceFirst("^sc_credits_?", "");
    if(s.isEmpty()) {
      return "Credits";
    }
    final StringBuilder out = new StringBuilder();
    boolean upper = true;
    for(final char ch : s.toCharArray()) {
      if(ch == '_') {
        out.append(' ');
        upper = true;
      } else {
        out.append(upper ? Character.toUpperCase(ch) : ch);
        upper = false;
      }
    }
    return out.toString();
  }

  private float dp(final float v) {
    return v * this.density;
  }

  private float heightOf(final Kind kind) {
    switch(kind) {
      case TITLE: return this.dp(40);
      case HEADING: return this.dp(30);
      case NAME: return this.dp(26);
      case NOTE: return this.dp(20);
      default: return this.dp(22);
    }
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    this.startMs = System.currentTimeMillis();
    this.contentHeight = 0;
    for(final Line l : this.lines) {
      this.contentHeight += this.heightOf(l.kind);
    }
  }

  @Override
  protected void onDraw(final Canvas canvas) {
    final int w = this.getWidth();
    final int h = this.getHeight();

    this.paint.setShader(new LinearGradient(0, 0, 0, h, BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP));
    canvas.drawRect(0, 0, w, h, this.paint);
    this.paint.setShader(null);

    // Scroll up slowly from below the screen, then loop
    final float speed = this.dp(28); // per second
    final float cycle = h + this.contentHeight;
    final float elapsed = (System.currentTimeMillis() - this.startMs) / 1000f * speed;
    float y = h - (elapsed % cycle);

    this.paint.setTextAlign(Paint.Align.CENTER);
    for(final Line l : this.lines) {
      final float lh = this.heightOf(l.kind);
      y += lh;
      if(l.text == null || y < -lh || y > h + lh) {
        continue;
      }

      switch(l.kind) {
        case TITLE:
          this.paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
          this.paint.setTextSize(this.dp(24));
          this.paint.setColor(GOLD);
          break;
        case HEADING:
          this.paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
          this.paint.setTextSize(this.dp(17));
          this.paint.setColor(GOLD);
          break;
        case NAME:
          this.paint.setTypeface(Typeface.SERIF);
          this.paint.setTextSize(this.dp(17));
          this.paint.setColor(TEXT);
          break;
        default:
          this.paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
          this.paint.setTextSize(this.dp(13));
          this.paint.setColor(TEXT_DIM);
          break;
      }
      canvas.drawText(l.text, w / 2f, y - lh * 0.3f, this.paint);
    }

    // Fade the top and bottom edges into the background
    final float fade = this.dp(48);
    this.paint.setShader(new LinearGradient(0, 0, 0, fade, BG_TOP, 0x00151210, Shader.TileMode.CLAMP));
    canvas.drawRect(0, 0, w, fade, this.paint);
    this.paint.setShader(new LinearGradient(0, h - fade, 0, h, 0x00302a24, BG_BOTTOM, Shader.TileMode.CLAMP));
    canvas.drawRect(0, h - fade, w, h, this.paint);
    this.paint.setShader(null);

    this.postInvalidateOnAnimation();
  }
}
