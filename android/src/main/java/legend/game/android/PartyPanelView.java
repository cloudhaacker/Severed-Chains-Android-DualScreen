package legend.game.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.Log;
import android.view.View;
import legend.game.EngineState;
import legend.game.characters.CharacterData2c;
import legend.game.characters.StatCollection;
import legend.game.characters.VitalsStat;
import legend.game.combat.Battle;
import legend.game.combat.bent.PlayerBattleEntity;
import legend.game.combat.types.BattleStateEf4;
import legend.game.scripting.ScriptState;
import legend.game.submap.SMap;
import legend.game.types.GameState52c;
import legend.game.unpacker.Loader;
import legend.game.wmap.WMap;
import legend.lodmod.LodMod;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static legend.game.EngineStates.currentEngineState_8004dd04;
import static legend.game.Scus94491BpeSegment_8006.battleState_8006e398;
import static legend.game.Scus94491BpeSegment_800b.gameState_800babc8;

/**
 * Dual Screen fork: live party status for the lower screen.
 *
 * <p>Reads the engine's own state a few times a second and never writes to
 * it. Outside battle the values come from the saved party data; in battle
 * they come from the battle entities, which is where the game keeps the live
 * HP/MP/SP during a fight. Portraits are the 48x48 menu portraits the game's
 * installer extracts from the player's own discs.</p>
 *
 * <p>The engine thread may be changing these values while we read them, so
 * each refresh copies what it needs into a snapshot and keeps the previous
 * snapshot if anything was mid-update.</p>
 */
public final class PartyPanelView extends View {
  private static final String TAG = "PartyPanel";
  private static final long REFRESH_MS = 200;

  // Encounter thresholds, matching the game's own field indicator
  // (SMap.getEncounterTriangleColour / encounter limit)
  private static final float ENCOUNTER_LIMIT = 0x1400;
  private static final float ENCOUNTER_WARN = 0xa00;
  private static final float ENCOUNTER_DANGER = 0xf00;

  /** Width of one menu pixel relative to its height: 320 / 368. */
  private static final float PIXEL_ASPECT = 320f / 368f;

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
  private static final int ENC_CALM = 0xff4f8fd9;
  private static final int ENC_WARN = 0xffe0c040;
  private static final int ENC_DANGER = 0xffd9534f;

  private enum Mode { FIELD, WORLD, BATTLE, OTHER }

  private static final class Member {
    String id;
    String name;
    int level;
    int dlevel;
    int hp, hpMax, mp, mpMax, sp, spMax;
  }

  private static final class Snapshot {
    final List<Member> party = new ArrayList<>();
    Mode mode = Mode.OTHER;
    int gold;
    int stardust;
    int chapter;
    /** 0..1 progress to the next random battle, or -1 for none / safe area. */
    float encounter = -1;
  }

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pixelPaint = new Paint();
  private final RectF rect = new RectF();
  private final float density;
  private final Map<String, Bitmap> portraits = new HashMap<>();
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
    // Portraits are pixel art: scale them up crisp, not blurry
    this.pixelPaint.setFilterBitmap(false);
    this.pixelPaint.setAntiAlias(false);
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

  // ---------------------------------------------------------------- reading

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

      final EngineState<?> engine = currentEngineState_8004dd04;
      if(engine instanceof Battle && readBattleParty(state, snap.party)) {
        snap.mode = Mode.BATTLE;
      } else {
        readFieldParty(state, snap.party);

        if(engine instanceof SMap) {
          final SMap smap = (SMap)engine;
          snap.mode = Mode.FIELD;
          snap.encounter = smap.submap != null && smap.submap.getEncounterRate() > 0
            ? Math.min(1f, smap.encounterAccumulator_800c6ae8 / ENCOUNTER_LIMIT)
            : -1;
        } else if(engine instanceof WMap) {
          snap.mode = Mode.WORLD;
          snap.encounter = Math.min(1f, ((WMap)engine).encounterAccumulator_800c6ae8 / ENCOUNTER_LIMIT);
        }
      }

      return snap;
    } catch(final RuntimeException | LinkageError e) {
      // Engine was changing the party or registries aren't ready yet
      return previous;
    }
  }

  private static void readFieldParty(final GameState52c state, final List<Member> out) {
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
      m.id = c.template.getRegistryId().entryId();
      m.name = prettyName(m.id);
      m.level = c.level_12;
      m.dlevel = c.dlevel_13;
      readVitals(c.stats, m);
      out.add(m);
    }
  }

  /** Live values from the fight. Returns false if the battle isn't set up yet. */
  private static boolean readBattleParty(final GameState52c state, final List<Member> out) {
    final BattleStateEf4 battle = battleState_8006e398;
    if(battle == null || battle.playerBents_e40.isEmpty()) {
      return false;
    }

    for(final ScriptState<PlayerBattleEntity> ss : battle.playerBents_e40) {
      final PlayerBattleEntity bent = ss != null ? ss.innerStruct_00 : null;
      if(bent == null) {
        continue;
      }

      final Member m = new Member();
      final int charId = bent.charId_272;
      if(charId >= 0 && charId < state.charData_32c.size() && state.charData_32c.get(charId) != null) {
        m.id = state.charData_32c.get(charId).template.getRegistryId().entryId();
      } else {
        m.id = null;
      }
      m.name = prettyName(m.id);
      m.level = bent.level_04;
      m.dlevel = bent.dlevel_06;
      readVitals(bent.stats, m);
      out.add(m);
    }

    return !out.isEmpty();
  }

  private static void readVitals(final StatCollection stats, final Member m) {
    final VitalsStat hp = stats.getStat(LodMod.HP_STAT.get());
    final VitalsStat mp = stats.getStat(LodMod.MP_STAT.get());
    final VitalsStat sp = stats.getStat(LodMod.SP_STAT.get());
    if(hp != null) { m.hp = hp.getCurrent(); m.hpMax = hp.getMax(); }
    if(mp != null) { m.mp = mp.getCurrent(); m.mpMax = mp.getMax(); }
    if(sp != null) { m.sp = sp.getCurrent(); m.spMax = sp.getMax(); }
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

  /** The game's own menu portrait from the player's install, or null. Cached. */
  private Bitmap portrait(final String id) {
    if(id == null) {
      return null;
    }
    if(this.portraits.containsKey(id)) {
      return this.portraits.get(id);
    }

    Bitmap bmp = null;
    try {
      final File f = Loader.resolve("characters/" + id + "/portrait.png").toFile();
      if(f.isFile()) {
        bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
      }
    } catch(final RuntimeException e) {
      Log.w(TAG, "no portrait for " + id, e);
    }
    this.portraits.put(id, bmp);
    return bmp;
  }

  // ---------------------------------------------------------------- drawing

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
    final String title = snap != null && snap.mode == Mode.BATTLE ? "BATTLE" : "PARTY";
    this.paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
    this.paint.setColor(GOLD);
    this.paint.setTextSize(this.dp(20));
    this.paint.setTextAlign(Paint.Align.LEFT);
    canvas.drawText(title, pad, pad + this.dp(20), this.paint);
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

    final boolean showEncounter = snap.mode == Mode.FIELD || snap.mode == Mode.WORLD;
    final float footerH = this.dp(56);
    final float encounterH = showEncounter ? this.dp(52) : 0;
    final float gap = this.dp(12);
    final float top = pad + this.dp(44);
    final float bottom = h - pad - footerH - gap - (showEncounter ? encounterH + gap : 0);
    final int count = Math.max(snap.party.size(), 3);
    final float cardH = Math.min(this.dp(150), (bottom - top - gap * (count - 1)) / count);

    float y = top;
    for(final Member m : snap.party) {
      this.drawCard(canvas, m, pad, y, w - pad, y + cardH);
      y += cardH + gap;
    }

    final float fy = h - pad - footerH;
    if(showEncounter) {
      this.drawEncounter(canvas, snap.encounter, pad, fy - gap - encounterH, w - pad, fy - gap);
    }
    this.drawFooter(canvas, snap, pad, fy, w - pad, h - pad);
  }

  private void drawPanel(final Canvas canvas, final float l, final float t, final float r, final float b) {
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
  }

  private void drawCard(final Canvas canvas, final Member m, final float l, final float t, final float r, final float b) {
    this.drawPanel(canvas, l, t, r, b);

    final float inner = this.dp(12);
    float x = l + inner;
    final float barR = r - inner;

    // Portrait in a gold frame, as tall as the card allows
    final Bitmap face = this.portrait(m.id);
    if(face != null) {
      // The game draws its menus 368 wide on a 4:3 screen, so its pixels are
      // narrower than they are tall; match that instead of a square
      final float size = Math.min(this.dp(72), b - t - inner * 2);
      final float faceW = size * PIXEL_ASPECT;
      final float py = t + (b - t - size) / 2f;
      this.rect.set(x, py, x + faceW, py + size);
      canvas.drawBitmap(face, null, this.rect, this.pixelPaint);
      this.paint.setStyle(Paint.Style.STROKE);
      this.paint.setStrokeWidth(this.dp(2));
      this.paint.setColor(GOLD);
      canvas.drawRect(this.rect, this.paint);
      this.paint.setStyle(Paint.Style.FILL);
      x += faceW + inner;
    }

    // Name + levels
    this.paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
    this.paint.setTextAlign(Paint.Align.LEFT);
    this.paint.setTextSize(this.dp(19));
    this.paint.setColor(m.hpMax > 0 && m.hp == 0 ? TEXT_DIM : TEXT);
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
    final float barsTop = t + this.dp(38);
    final float rowH = Math.min(this.dp(30), (b - barsTop - this.dp(6)) / bars);

    final boolean lowHp = m.hpMax > 0 && m.hp * 4 <= m.hpMax;
    this.drawBar(canvas, "HP", m.hp, m.hpMax, lowHp ? HP_LOW : HP_COLOUR, x, barsTop, barR, rowH);
    this.drawBar(canvas, "MP", m.mp, m.mpMax, MP_COLOUR, x, barsTop + rowH, barR, rowH);
    if(showSp) {
      this.drawBar(canvas, "SP", m.sp, m.spMax, SP_COLOUR, x, barsTop + rowH * 2, barR, rowH);
    }
  }

  private void drawBar(final Canvas canvas, final String label, final int value, final int max, final int colour, final float l, final float t, final float r, final float rowH) {
    final float labelW = this.dp(30);
    final float valueW = this.dp(92);
    final float barH = Math.min(this.dp(10), rowH * 0.45f);
    final float midY = t + rowH / 2f;

    this.paint.setTypeface(Typeface.DEFAULT_BOLD);
    this.paint.setTextSize(this.dp(13));
    this.paint.setTextAlign(Paint.Align.LEFT);
    this.paint.setColor(TEXT_DIM);
    canvas.drawText(label, l, midY + this.dp(5), this.paint);

    final float bl = l + labelW;
    final float br = r - valueW;
    this.fillBar(canvas, bl, midY - barH / 2f, br, midY + barH / 2f, max > 0 ? value / (float)max : 0, colour);

    this.paint.setTypeface(Typeface.MONOSPACE);
    this.paint.setTextSize(this.dp(13));
    this.paint.setTextAlign(Paint.Align.RIGHT);
    this.paint.setColor(TEXT);
    canvas.drawText(value + " / " + max, r, midY + this.dp(5), this.paint);
  }

  private void fillBar(final Canvas canvas, final float l, final float t, final float r, final float b, final float frac, final int colour) {
    final float radius = (b - t) / 2f;
    this.rect.set(l, t, r, b);
    this.paint.setColor(BAR_BG);
    canvas.drawRoundRect(this.rect, radius, radius, this.paint);

    final float f = Math.max(0f, Math.min(1f, frac));
    if(f > 0) {
      this.rect.set(l, t, l + (r - l) * f, b);
      this.paint.setColor(colour);
      canvas.drawRoundRect(this.rect, radius, radius, this.paint);
    }
  }

  private void drawEncounter(final Canvas canvas, final float progress, final float l, final float t, final float r, final float b) {
    this.drawPanel(canvas, l, t, r, b);

    final float inner = this.dp(14);
    final float midY = (t + b) / 2f;

    this.paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
    this.paint.setTextSize(this.dp(14));
    this.paint.setTextAlign(Paint.Align.LEFT);
    this.paint.setColor(GOLD);
    canvas.drawText("ENCOUNTER", l + inner, midY + this.dp(5), this.paint);

    final float barL = l + inner + this.dp(104);
    final float barR = r - inner - this.dp(70);

    final String status;
    final int colour;
    if(progress < 0) {
      status = "Safe";
      colour = ENC_CALM;
      this.fillBar(canvas, barL, midY - this.dp(5), barR, midY + this.dp(5), 0, colour);
    } else {
      final float acc = progress * ENCOUNTER_LIMIT;
      if(acc <= ENCOUNTER_WARN) {
        status = "Calm";
        colour = ENC_CALM;
      } else if(acc <= ENCOUNTER_DANGER) {
        status = "Near";
        colour = ENC_WARN;
      } else {
        status = "Danger";
        colour = ENC_DANGER;
      }
      this.fillBar(canvas, barL, midY - this.dp(5), barR, midY + this.dp(5), progress, colour);
    }

    this.paint.setTypeface(Typeface.DEFAULT_BOLD);
    this.paint.setTextSize(this.dp(14));
    this.paint.setTextAlign(Paint.Align.RIGHT);
    this.paint.setColor(colour);
    canvas.drawText(status, r - inner, midY + this.dp(5), this.paint);
  }

  private void drawFooter(final Canvas canvas, final Snapshot snap, final float l, final float t, final float r, final float b) {
    this.drawPanel(canvas, l, t, r, b);

    final float midY = (t + b) / 2f + this.dp(6);
    final float third = (r - l) / 3f;
    this.paint.setTypeface(Typeface.DEFAULT_BOLD);
    this.paint.setTextSize(this.dp(15));
    this.paint.setTextAlign(Paint.Align.CENTER);
    this.paint.setColor(GOLD);
    canvas.drawText(String.format(Locale.US, "%,d G", snap.gold), l + third * 0.5f, midY, this.paint);
    canvas.drawText("Stardust " + snap.stardust + "/50", l + third * 1.5f, midY, this.paint);
    canvas.drawText("Chapter " + snap.chapter, l + third * 2.5f, midY, this.paint);
  }
}
