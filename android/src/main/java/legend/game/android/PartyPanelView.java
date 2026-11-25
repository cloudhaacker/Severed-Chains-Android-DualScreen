package legend.game.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import legend.game.characters.VitalsStat;
import legend.game.characters.CharacterData2c;
import legend.game.types.GameState52c;
import legend.lodmod.LodMod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static legend.game.Scus94491BpeSegment_800b.gameState_800babc8;

/**
 * Dual Screen fork: live party status for the lower screen.
 *
 * <p>Reads the engine's own game state (party slots, level, dragoon level,
 * HP/MP/SP, gold, stardust, chapter) a few times a second. Nothing here
 * writes to the game. The engine thread may be changing these values while
 * we read them, so each refresh copies what it needs into a snapshot and
 * simply skips a frame if anything is mid-update.</p>
 *
 * <p>Out of battle only for now: during battle the live values live on the
 * battle entities and the party values here only catch up afterwards.</p>
 */
public final class PartyPanelView extends View {
  private static final long REFRESH_MS = 250;

  // Matches the port's dark-glass-and-gold controls
  private static final int BG_TOP = 0xff151210;
  private static final int BG_BOTTOM = 0xff302a24;
  private static final int CARD = 0xcc1e1915;
  private static final int CARD_EDGE = 0xff6b5a3a;
  private static final int GOLD = 0xffd9b45a;
  private static final int TEXT = 0xfff2e8d5;
  private static final int TEXT_DIM = 0xffa89a82;
  private static final int BAR_BG = 0xff2a241e;
  private static final int HP_COLOUR = 0xff6fc36a;
  private static final int HP_LOW = 0xffd9534f;
  private static final int MP_COLOUR = 0xff5a9be0;
  private static final int SP_COLOUR = 0xffe0b040;

  private static final class Member {
    String name;
    int level;
    int dlevel;
    int hp, hpMax, mp, mpMax, sp, spMax;
  }

  private static final class Snapshot {
    final List<Member> party = new ArrayList<>();
    int gold;
    int stardust;
    int chapter;
  }

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private final float density;
  private Snapshot snapshot;
  private boolean running;

  private final Runnable tick = new Runnable() {
    @Override
    public void run() {
      if(!PartyPanelView.this.running) {
        return;
      }
      PartyPanelView.this.snapshot = takeSnapshot(PartyPanelView.this.snapshot);
      PartyPanelView.this.invalidate();
      PartyPanelView.this.postDelayed(this, REFRESH_MS);
    }
  };

  public PartyPanelView(final Context context) {
    super(context);
    this.density = context.getResources().getDisplayMetrics().density;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    this.running = true;
    this.post(this.tick);
  }

  @Override
  protected void onDetachedFromWindow() {
    this.running = false;
    this.removeCallbacks(this.tick);
    super.onDetachedFromWindow();
  }

  /** Returns a fresh snapshot, or the previous one if the game was mid-update. */
  private static Snapshot takeSnapshot(final Snapshot previous) {
    try {
      final GameState52c state = gameState_800babc8;
      if(state == null) {
        return null;
      }

      final Snapshot snap = new Snapshot();
      snap.gold = state.gold_94;
      snap.stardust = state.stardust_9c;
      snap.chapter = state.chapterIndex_98 + 1;

      for(int slot = 0; slot < state.charIds_88.size(); slot++) {
        final int charId = state.charIds_88.getInt(slot);
        if(charId < 0 || charId >= state.charData_32c.size()) {
          continue;
        }

        final CharacterData2c c = state.charData_32c.get(charId);
        if(c == null) {
          continue;
        }

        final Member m = new Member();
        m.name = prettyName(c.template.getRegistryId().entryId());
        m.level = c.level_12;
        m.dlevel = c.dlevel_13;

        final VitalsStat hp = c.stats.getStat(LodMod.HP_STAT.get());
        final VitalsStat mp = c.stats.getStat(LodMod.MP_STAT.get());
        final VitalsStat sp = c.stats.getStat(LodMod.SP_STAT.get());
        if(hp != null) { m.hp = hp.getCurrent(); m.hpMax = hp.getMax(); }
        if(mp != null) { m.mp = mp.getCurrent(); m.mpMax = mp.getMax(); }
        if(sp != null) { m.sp = sp.getCurrent(); m.spMax = sp.getMax(); }

        snap.party.add(m);
      }

      return snap;
    } catch(final RuntimeException | LinkageError e) {
      // Engine was changing the party or registries aren't ready yet
      return previous;
    }
  }

  private static String prettyName(final String id) {
    if(id == null || id.isEmpty()) {
      return "?";
    }

    final StringBuilder out = new StringBuilder();
    boolean upper = true;
    for(final char ch : id.toCharArray()) {
      if(ch == '_' || ch == '-') {
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

  @Override
  protected void onDraw(final Canvas canvas) {
    final int w = this.getWidth();
    final int h = this.getHeight();

    this.paint.setShader(new LinearGradient(0, 0, 0, h, BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP));
    canvas.drawRect(0, 0, w, h, this.paint);
    this.paint.setShader(null);

    final float pad = this.dp(16);
    final Snapshot snap = this.snapshot;

    // Header
    this.paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
    this.paint.setColor(GOLD);
    this.paint.setTextSize(this.dp(20));
    this.paint.setTextAlign(Paint.Align.LEFT);
    canvas.drawText("PARTY", pad, pad + this.dp(20), this.paint);
    this.paint.setStrokeWidth(this.dp(1));
    canvas.drawLine(pad, pad + this.dp(30), w - pad, pad + this.dp(30), this.paint);

    if(snap == null || snap.party.isEmpty()) {
      this.paint.setTypeface(Typeface.DEFAULT);
      this.paint.setColor(TEXT_DIM);
      this.paint.setTextSize(this.dp(16));
      this.paint.setTextAlign(Paint.Align.CENTER);
      canvas.drawText("Party status appears once you load a game", w / 2f, h / 2f, this.paint);
      return;
    }

    final float footerH = this.dp(56);
    final float top = pad + this.dp(44);
    final float gap = this.dp(12);
    final int count = Math.max(snap.party.size(), 3);
    final float cardH = Math.min(this.dp(150), (h - top - footerH - pad - gap * (count - 1)) / count);

    float y = top;
    for(final Member m : snap.party) {
      this.drawCard(canvas, m, pad, y, w - pad, y + cardH);
      y += cardH + gap;
    }

    this.drawFooter(canvas, snap, pad, h - pad - footerH, w - pad, h - pad);
  }

  private void drawCard(final Canvas canvas, final Member m, final float l, final float t, final float r, final float b) {
    final float radius = this.dp(12);
    this.rect.set(l, t, r, b);
    this.paint.setStyle(Paint.Style.FILL);
    this.paint.setColor(CARD);
    canvas.drawRoundRect(this.rect, radius, radius, this.paint);
    this.paint.setStyle(Paint.Style.STROKE);
    this.paint.setStrokeWidth(this.dp(1.5f));
    this.paint.setColor(CARD_EDGE);
    canvas.drawRoundRect(this.rect, radius, radius, this.paint);
    this.paint.setStyle(Paint.Style.FILL);

    final float inner = this.dp(14);
    final float x = l + inner;
    final float barR = r - inner;

    // Name + levels
    this.paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
    this.paint.setTextAlign(Paint.Align.LEFT);
    this.paint.setTextSize(this.dp(19));
    this.paint.setColor(TEXT);
    canvas.drawText(m.name, x, t + this.dp(28), this.paint);

    this.paint.setTypeface(Typeface.DEFAULT_BOLD);
    this.paint.setTextAlign(Paint.Align.RIGHT);
    this.paint.setTextSize(this.dp(14));
    this.paint.setColor(GOLD);
    final String levels = m.dlevel > 0
      ? String.format(Locale.US, "Lv %d   D.Lv %d", m.level, m.dlevel)
      : String.format(Locale.US, "Lv %d", m.level);
    canvas.drawText(levels, barR, t + this.dp(27), this.paint);

    // Bars share the remaining height
    final boolean showSp = m.dlevel > 0 && m.spMax > 0;
    final int bars = showSp ? 3 : 2;
    final float barsTop = t + this.dp(40);
    final float rowH = Math.min(this.dp(30), (b - barsTop - this.dp(8)) / bars);

    final boolean lowHp = m.hpMax > 0 && m.hp * 4 <= m.hpMax;
    this.drawBar(canvas, "HP", m.hp, m.hpMax, lowHp ? HP_LOW : HP_COLOUR, x, barsTop, barR, rowH);
    this.drawBar(canvas, "MP", m.mp, m.mpMax, MP_COLOUR, x, barsTop + rowH, barR, rowH);
    if(showSp) {
      this.drawBar(canvas, "SP", m.sp, m.spMax, SP_COLOUR, x, barsTop + rowH * 2, barR, rowH);
    }
  }

  private void drawBar(final Canvas canvas, final String label, final int value, final int max, final int colour, final float l, final float t, final float r, final float rowH) {
    final float labelW = this.dp(34);
    final float valueW = this.dp(96);
    final float barH = Math.min(this.dp(10), rowH * 0.45f);
    final float midY = t + rowH / 2f;

    this.paint.setTypeface(Typeface.DEFAULT_BOLD);
    this.paint.setTextSize(this.dp(13));
    this.paint.setTextAlign(Paint.Align.LEFT);
    this.paint.setColor(TEXT_DIM);
    canvas.drawText(label, l, midY + this.dp(5), this.paint);

    final float bl = l + labelW;
    final float br = r - valueW;
    this.rect.set(bl, midY - barH / 2f, br, midY + barH / 2f);
    this.paint.setColor(BAR_BG);
    canvas.drawRoundRect(this.rect, barH / 2f, barH / 2f, this.paint);

    if(max > 0 && value > 0) {
      final float frac = Math.min(1f, value / (float)max);
      this.rect.set(bl, midY - barH / 2f, bl + (br - bl) * frac, midY + barH / 2f);
      this.paint.setColor(colour);
      canvas.drawRoundRect(this.rect, barH / 2f, barH / 2f, this.paint);
    }

    this.paint.setTypeface(Typeface.MONOSPACE);
    this.paint.setTextAlign(Paint.Align.RIGHT);
    this.paint.setColor(TEXT);
    canvas.drawText(value + " / " + max, r, midY + this.dp(5), this.paint);
  }

  private void drawFooter(final Canvas canvas, final Snapshot snap, final float l, final float t, final float r, final float b) {
    final float radius = this.dp(12);
    this.rect.set(l, t, r, b);
    this.paint.setColor(CARD);
    canvas.drawRoundRect(this.rect, radius, radius, this.paint);

    final float midY = (t + b) / 2f + this.dp(6);
    final float third = (r - l) / 3f;
    this.paint.setTypeface(Typeface.DEFAULT_BOLD);
    this.paint.setTextSize(this.dp(15));
    this.paint.setTextAlign(Paint.Align.CENTER);
    this.paint.setColor(GOLD);
    canvas.drawText(String.format(Locale.US, "%,d G", snap.gold), l + third * 0.5f, midY, this.paint);
    canvas.drawText("Stardust " + snap.stardust, l + third * 1.5f, midY, this.paint);
    canvas.drawText("Chapter " + snap.chapter, l + third * 2.5f, midY, this.paint);
  }
}
