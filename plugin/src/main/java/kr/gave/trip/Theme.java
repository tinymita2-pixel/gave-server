package kr.gave.trip;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import kr.gave.game.Game;
import kr.gave.mob.boss.BossDirector;
import kr.gave.world.Fog;
import kr.gave.world.Plaza;
import kr.gave.world.PlazaGen;

/**
 * 원정의 막 테마(docs/모듈/원정.md 2.1, 3.5절): 네 막(1 마른 길, 2 젖은 벌, 3 재의 땅, 4 발사장)과 막 안 진행에 따라 하늘, 안개 색, 빛, 세계 시각,
 * 날씨를 정하고, 막이 바뀌면 땅 겉면을 다시 칠한다({@link Repaint}). 뒤로 갈수록 어둡다. 어둠은 안개 거리가 아니라 색과 빛으로 낸다(안개 거리는
 * 감독의 안개 단계가 그대로 정한다. 실서버에서 짙은 안개가 '하나도 안 보인다'는 불만이 있었다).
 *
 * <p>방법(26.3): 데이터팩 gave_env 의 시계 gave:theme(색, 빛, 별, 달, 날리는 재), gave:dim(한 단계 더 어둡게), gave:rain(비가 당긴 안개 시작을
 * 되돌림)을 멈춘 채 틱만 옮긴다({@link Fog}). 테마 시계 틱 = 막 x 1000 + 800 x 진행, 0 은 옛 모습(대기실, 원정이 꺼진 판), 5000 대는 발사 뒤 아침.
 * 색 표는 tools/make_theme_env.py 한곳에 있고 이 클래스는 jar 의 trip/theme.json 에서 세계 시각, 비, 번개, 기본 안개 단계만 읽는다.
 * 시계가 없으면(데이터팩이 아직 안 먹음: 깐 뒤 처음 켠 판) 세계 시각만 옮기고 비는 켜지 않는다.
 *
 * <p>언제 바뀌나: 라운드 n 을 열기 전({@link #beforeRound})에 그 라운드의 막과 진행으로 맞춘다(진행: 1~3막은 스테이지마다 한 칸 0, 0.25 .. 1,
 * 4막은 라운드마다 한 칸). 이동 연출(Travel)이 있으면 화면이 다 가려졌을 때(사건 travelHidden) 먼저 다음 라운드 것으로 바꾼다: 하늘은
 * {@link #HIDDEN_TICKS} 틱, 막이 바뀌면 땅도 {@link #HIDDEN_ROWS} 줄씩(201줄에 50틱) 가려진 동안 끝낸다. 이동 연출이 없으면 라운드를 열 때 하늘은
 * {@link #ROUND_TICKS}(막이 바뀌면 {@link #STAGE_TICKS}) 틱에 걸쳐 넘어가고 땅은 북에서 남으로 {@link #SWEEP_ROWS} 줄씩 쓸어 칠한다(새 땅이 앞에서
 * 밀려와 발밑을 지나간다). 판이 끝나면 옛 모습과 바탕 막 땅으로 돌아가고, 서버를 켠 뒤에도 땅을 바탕 막으로 맞춘다.
 *
 * <p>다른 장치와: 감독이 웨이브마다 세계 시각을 13000 으로 되돌리면 같은 틱({@link #beginRound})에 다시 맞춘다. 보스 싸움 중(BossDirector.active)에는
 * 보스가 세계 시각을 쥐고(색은 테마 시계 그대로), 보스가 끝난 뒤 시각을 내리는 동안에도 다 내릴 때까지 기다렸다가 맞춘다({@link #YIELD_TICKS}).
 * 보스 땅 도구(Terrain)와 땅: Terrain 이 먼저 되돌리고 그다음 칠한다({@link Repaint} 머리글).
 * 감독의 밤 사건({@code Director.night()})과 큰 물결의 해질녘(사람 시각 덧셈)은 색을 덮은 뒤라 안 보이므로 gave:dim 으로 옮겨 어둡게 한다.
 * 기본 안개 단계가 0 이 아닌 막(발사장 = 맑음)은 감독의 안개 사건이 없을 때만 안개 시계를 그 자리로 옮긴다(사건이 생기면 바로 놓는다).
 *
 * <p>다른 부품이 부를 것: {@link #act()}, {@link #progress()}, {@link #baseTime()}, {@link #fogBase()}, {@link #go}, {@link #paint}, {@link #dim},
 * {@link #rain}, {@link #storm}, {@link #lightning()}, {@link #flood}, {@link #morning}, {@link #hold}. 물음 키: baseTime(Long), fogBase(Integer),
 * themeAct(Integer), themeProgress(Double), painting(Boolean).
 */
public final class Theme implements TripPart {

    /** 이동 연출이 화면을 다 가렸을 때(사건 travelHidden: 가려진 채 80틱, 막 넘김은 120틱) 하늘이 넘어가는 틱, 땅을 칠하는 빠르기(줄/틱). */
    public static int HIDDEN_TICKS = 40;
    public static double HIDDEN_ROWS = 4;
    /** 이동 연출 없이 하늘과 빛이 넘어가는 틱: 막이 바뀔 때, 같은 막에서 라운드가 바뀔 때, 판 시작(옛 모습에서 1막으로). */
    public static int STAGE_TICKS = 120, ROUND_TICKS = 100, START_TICKS = 120;
    /** 이동 연출 없이 막이 넘어갈 때 땅을 쓸어 칠하는 빠르기(줄/틱. 1.2 = 이동 연출이 흐르는 빠르기, 201줄에 167틱). */
    public static double SWEEP_ROWS = 1.2;
    /** 이동 연출(Travel)이 내는 사건: 화면이 다 가려짐. */
    private static final String EV_HIDDEN = "travelHidden";
    /** 먼 번개가 치는 거리(가운데에서. 서버가 번개를 보내는 거리 64 안쪽), 간격(틱). */
    public static double BOLT_R0 = 50, BOLT_R1 = 62;
    public static int BOLT_GAP0 = 400, BOLT_GAP1 = 1100;
    private static final int ACT_OLD = 0, ACT_MORNING = 5;
    /** 남(보스 하늘 sky(), 감독의 13000 되돌림 말고 이어지는 옮김)이 세계 시각을 옮기면 이 틱 동안 맞추지 않고 기다린다. */
    public static int YIELD_TICKS = 4;
    /** 서버를 켠 뒤 보스 땅 기록이 남아 있으면 땅 맞추기를 이만큼까지 미룬다(틱). */
    private static final int STARTUP_WAIT = 600;
    private static final String TERRAIN_JOURNAL = "boss_terrain.journal";

    /** 막 표(1..4, 5 = 아침). */
    private static final class Row {
        /** 진행 0, 0.25, 0.5, 0.75, 1 의 세계 시각(사이는 곧게). */
        final long[] time = new long[5];
        double rainFrom = 9, boltFrom = 9;
        int fogBase;
        String key = "";
    }

    private final Game game;
    private final Trip trip;
    private final World world;
    private final Repaint repaint;
    private final Row[] rows = new Row[6];
    private final boolean themeClock, dimClock, rainClock;

    /** 목표: 막(0 옛 모습, 1..4, 5 아침)과 막 안 진행. */
    private int act;
    private double progress;
    /** 테마 시계(실수 틱): 지금, 목표, 틱마다 옮기는 양. */
    private double clock, clockGoal, clockStep;
    private long clockSent = -1, timeSent = -1, dimSent = -1, rainSent = -1;
    /** 내가 마지막으로 둔(또는 남에게 맡긴 동안 본) 세계 시각, 남이 세계 시각을 옮긴 마지막 틱. */
    private long timeSet = -1, foreignAt = Long.MIN_VALUE / 2;
    /** 잠깐 덮어쓰기(보스의 하늘): 이 틱까지 시계를 holdTicks 에 둔다. */
    private long holdUntil = -1, holdTicks;
    private double dim, dimAsk;
    private long dimAskUntil;
    private Boolean rainAsk;
    private boolean stormAsk;
    private long nextBolt = -1;
    /** 내가 옮기는 안개 자리(-1 = 안 건드림). */
    private double fogPos = -1;
    public Theme(Game game, Trip trip) {
        this.game = game;
        this.trip = trip;
        this.world = game.world;
        this.repaint = new Repaint(game.plugin, world);
        load();
        themeClock = Fog.ready(world, Fog.THEME);
        dimClock = Fog.ready(world, Fog.DIM);
        rainClock = Fog.ready(world, Fog.RAIN);
        // 앞 실행이 남긴 것(시계는 월드에 저장된다)을 옛 모습으로
        if (themeClock) Fog.setTicks(world, Fog.THEME, 0);
        if (dimClock) Fog.setTicks(world, Fog.DIM, 0);
        if (rainClock) Fog.setTicks(world, Fog.RAIN, 0);
        if (world.hasStorm()) world.setStorm(false);
        if (world.isThundering()) world.setThundering(false);
        if (!themeClock) trip.log("테마: 시계 gave:theme 없음(데이터팩 gave_env 가 아직 안 먹음: 다음 켤 때부터 색이 바뀐다. 지금은 세계 시각만, 비 없음)");
        repaint.foreign(this::terrainBusy);
        // 서버를 켠 뒤 땅을 바탕 막으로 맞춘다(지난 실행이 다른 막에서 꺼졌거나 옛 월드에 길이 없을 때만 블록이 바뀐다).
        // 보스 땅 도구가 먼저: 지난번에 못 되돌린 보스 땅(기록 파일)을 보스 감독이 첫 틱에 되돌린 뒤에 칠한다
        Bukkit.getScheduler().runTaskLater(game.plugin, () -> startupPaint(0), 40L);
    }

    /** 서버를 켠 뒤 바탕 막 맞추기. 보스 땅 기록이 남아 있으면 1초씩 기다린다(30초까지). */
    private void startupPaint(int waited) {
        if (repaint.running() || !game.plugin.isEnabled()) return;
        if ((terrainBusy() || Files.isRegularFile(journal())) && waited < STARTUP_WAIT) {
            Bukkit.getScheduler().runTaskLater(game.plugin, () -> startupPaint(waited + 20), 20L);
            return;
        }
        repaint.start(PlazaGen.baseAct(), 0, 0, () -> {
            if (repaint.changed() > 0) trip.log("테마: 땅을 바탕 막 " + PlazaGen.baseAct() + " 로 맞춤 " + repaint.stats() + (waited > 0 ? " (보스 땅을 " + waited + "틱 기다림)" : ""));
        });
    }

    /** 보스 땅 도구(kr.gave.mob.boss.Terrain)가 지금 바꾼 칸을 쥐고 있나. */
    private boolean terrainBusy() {
        try {
            BossDirector bd = BossDirector.of(game);
            return bd != null && bd.terrain.size() > 0;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 보스 땅 기록 파일(plugins/Gave/boss_terrain.journal). */
    private Path journal() {
        return game.plugin.getDataFolder().toPath().resolve(TERRAIN_JOURNAL);
    }

    /** 보스가 하늘(세계 시각)을 쥐고 있나: 보스 싸움 중(등장부터 무너짐 물결 끝까지). */
    private boolean bossSky() {
        try {
            BossDirector bd = BossDirector.of(game);
            return bd != null && bd.active();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private void load() {
        // 기본값(tools/make_theme_env.py 와 같은 값. theme.json 이 있으면 그것으로 덮는다)
        row(1, 12600, 13400, 9, 9, 0);
        row(2, 13600, 17450, 0.25, 0.5, 0);
        rows[2].time[1] = 17000;
        rows[2].time[2] = 17150;
        rows[2].time[3] = 17300;
        row(3, 17500, 18500, 9, 9, 0);
        row(4, 18500, 21500, 9, 9, 1);
        row(ACT_MORNING, 23000, 24000, 9, 9, 1);
        String[] keys = {"", "road", "marsh", "ash", "pad", "morning"};
        for (int i = 1; i <= ACT_MORNING; i++) rows[i].key = keys[i];
        try (InputStream in = game.plugin.getResource("trip/theme.json")) {
            if (in == null) {
                trip.log("테마: jar 에 trip/theme.json 없음(기본값으로)");
                return;
            }
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : o.getAsJsonArray("acts")) read(e.getAsJsonObject(), e.getAsJsonObject().get("act").getAsInt());
            read(o.getAsJsonObject("morning"), ACT_MORNING);
        } catch (IOException | RuntimeException e) {
            trip.log("테마: trip/theme.json 을 못 읽음(기본값으로): " + e);
        }
    }

    private void row(int i, long t0, long t1, double rain, double bolt, int fog) {
        Row r = new Row();
        for (int k = 0; k < 5; k++) r.time[k] = Math.round(t0 + (t1 - t0) * k / 4.0);
        r.rainFrom = rain;
        r.boltFrom = bolt;
        r.fogBase = fog;
        rows[i] = r;
    }

    private void read(JsonObject o, int i) {
        if (i < 1 || i > ACT_MORNING) return;
        Row r = rows[i];
        com.google.gson.JsonArray ta = o.getAsJsonArray("time");
        for (int k = 0; k < 5; k++) {
            r.time[k] = ta.size() >= 5 ? ta.get(k).getAsLong() : Math.round(ta.get(0).getAsLong() + (ta.get(ta.size() - 1).getAsLong() - ta.get(0).getAsLong()) * k / 4.0);
        }
        r.rainFrom = o.has("rainFrom") && !o.get("rainFrom").isJsonNull() ? o.get("rainFrom").getAsDouble() : 9;
        r.boltFrom = o.has("lightningFrom") && !o.get("lightningFrom").isJsonNull() ? o.get("lightningFrom").getAsDouble() : 9;
        r.fogBase = o.has("fogBase") ? o.get("fogBase").getAsInt() : 0;
        if (o.has("key")) r.key = o.get("key").getAsString();
    }

    // ------------------------------------------------------------------ 다른 부품이 부르는 것

    /** 목표 막(0 옛 모습, 1..4, 5 아침). */
    public int act() {
        return act;
    }

    /** 목표 막 안 진행 0..1. */
    public double progress() {
        return progress;
    }

    /** 라운드 n 의 막 안 진행: 1~3막은 스테이지마다 한 칸(0, 0.25, 0.5, 0.75, 1), 4막은 라운드마다 한 칸. */
    public static double progressOf(int n) {
        return progressOf(Trip.actOf(Math.max(1, n)), Trip.stageOf(Math.max(1, n)), n);
    }

    /** 막 a, 스테이지 stage, 라운드 n 의 막 안 진행(길을 정하는 부품이 막과 스테이지를 따로 답할 때). */
    public static double progressOf(int a, int stage, int n) {
        if (a >= 4 || stage >= Trip.STAGES) return Math.max(0, Math.min(4, n - Trip.LAST_ROAD_ROUND - 1)) / 4.0;
        return ((Math.max(1, stage) - 1) % 5) / 4.0;
    }

    /** 지금 세계 시각 바탕(옛 모습이면 13000). 감독이 웨이브마다 되돌리는 값 대신 쓴다. */
    public long baseTime() {
        long t = timeAt(clock);
        return t < 0 ? 13000 : t;
    }

    /** 목표 막의 감독 안개 기본 단계(0 흙먼지 끝 64, 1 맑음 끝 160). */
    public int fogBase() {
        return act >= 1 && act <= ACT_MORNING ? rows[act].fogBase : 0;
    }

    /** 하늘과 빛을 막 a(0 옛 모습, 1..4)의 진행 p 로 ticks 틱에 걸쳐 옮긴다(땅은 안 바뀐다: {@link #paint}). */
    public void go(int a, double p, int ticks) {
        act = Math.max(ACT_OLD, Math.min(ACT_MORNING, a));
        progress = Math.max(0, Math.min(1, p));
        holdUntil = -1;
        if (act == ACT_OLD) {
            clock = clockGoal = 0;
            return;
        }
        // 옛 모습 구간(0..900)은 어디나 같은 모습이라 건너뛴다
        if (clock < Fog.THEME_OLD_END) clock = Fog.THEME_OLD_END;
        clockGoal = act == ACT_MORNING ? Fog.THEME_MORNING + Fog.THEME_SPAN * progress : Fog.themeTicks(act, progress);
        clockStep = ticks <= 0 ? 1e9 : Math.abs(clockGoal - clock) / ticks;
    }

    /** 발사 뒤 아침(진행 0 해 뜨기 전 → 1 아침). */
    public void morning(double p, int ticks) {
        go(ACT_MORNING, p, ticks);
    }

    /** 땅을 막 a(0 옛 땅, 1..4)로 다시 칠한다. rowsPerTick 줄씩 북에서 남으로, 0 이하면 빠르게(예닐곱 틱). */
    public void paint(int a, double rowsPerTick) {
        repaint.start(a, 0, rowsPerTick, null);
    }

    /** 젖은 벌의 물 높이(0 기본 .. 1 거의 다 잠김: 밀물 같은 라운드). 젖은 벌 땅일 때만. 끝나면 0 으로 다시 부른다. */
    public void flood(double wet, double rowsPerTick) {
        if (Plaza.groundAct() != 2) return;
        repaint.start(2, wet, rowsPerTick, null);
    }

    /** 칠하는 중인가. */
    public boolean painting() {
        return repaint.running();
    }

    /** 한 단계 더 어둡게(0..1: 안개와 하늘 x 0.25 까지, 땅 빛 x 0.7 까지). ticks 틱 뒤 풀린다(0 이하면 다시 부를 때까지). */
    public void dim(double amount, int ticks) {
        dimAsk = Math.max(0, Math.min(1, amount));
        dimAskUntil = ticks > 0 ? game.now() + ticks : -1;
    }

    /** 비를 켜고 끔(null 이면 막을 따름: 젖은 벌 스테이지 7부터). 비 시계가 없으면 켜지지 않는다. */
    public void rain(Boolean on) {
        rainAsk = on;
    }

    /** 폭풍(비 + 천둥: 안개 색이 4분의 1 로 어두워지고 먼 번개가 잦다). 비가 올 때만. */
    public void storm(boolean on) {
        stormAsk = on;
    }

    /** 먼 번개 한 번(피해도 불도 없는 연출. 번쩍이는 동안 온 벌판이 보인다). */
    public void lightning() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double a = r.nextDouble() * Math.PI * 2, d = BOLT_R0 + r.nextDouble() * (BOLT_R1 - BOLT_R0);
        world.strikeLightningEffect(new Location(world, Plaza.CX + 0.5 + Math.cos(a) * d, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + Math.sin(a) * d));
    }

    /** 테마 시계를 ticks 틱 동안 themeTicks 에 둔다(보스가 하늘을 잠깐 가져감). 끝나면 가던 곳으로 돌아간다. ticks 0 이하면 바로 풂. */
    public void hold(long themeTicks, int ticks) {
        holdTicks = themeTicks;
        holdUntil = ticks > 0 ? game.now() + ticks : -1;
    }

    // ------------------------------------------------------------------ 허브 고리

    @Override
    public void startGame() {
        dimAsk = 0;
        rainAsk = null;
        stormAsk = false;
        // 판 시작마다 땅이 바탕 막과 맞는지 한 번 훑는다(맞는 칸은 안 건드려 값싸다: 일곱 틱 안팎, 틱마다 예산 안). 지난 판이 남긴 칠이 있으면 고치고 기록에 남긴다
        // (판 정리의 칠하기가 아직 도는 중이었으면 그것을 처음부터 다시 하는 것이라 기록하지 않는다)
        boolean resuming = repaint.running();
        repaint.start(PlazaGen.baseAct(), 0, 0, () -> {
            if (!resuming && repaint.changed() > 0) trip.log("테마: 판 시작에 땅이 바탕 막과 달라 맞춤 " + repaint.stats());
        });
        go(1, 0, START_TICKS);
    }

    @Override
    public void beforeRound(int n) {
        // 막과 스테이지는 허브에 묻는다(길 부품이 있으면 그 답, 없으면 라운드 번호로 셈)
        int a = trip.act();
        double p = progressOf(a, trip.stage(), n);
        if (a != act || Math.abs(p - progress) > 1e-6) go(a, p, a != act ? STAGE_TICKS : ROUND_TICKS);
        // 이동 연출 없이 막이 넘어갔으면 여기서 쓸어 칠한다(있으면 가려진 동안 이미 칠했다)
        if (Plaza.groundAct() != groundOf(a)) repaint.start(groundOf(a), 0, SWEEP_ROWS, null);
    }

    @Override
    public void beginRound(int n) {
        // 감독이 웨이브를 열며 세계 시각을 13000(밤 사건이면 18000)으로 되돌린 바로 뒤: 같은 틱에 다시 맞춰 하늘이 깜빡이지 않게
        foreignAt = Long.MIN_VALUE / 2;
        long t = timeAt(shown());
        if (t >= 0 && !bossSky()) setTime(Math.floorMod(t, 24000L));
    }

    @Override
    public void on(String event, Object arg) {
        // 길 부품은 화면을 가리기 바로 전에 막과 스테이지를 다음 것으로 넘긴다(trip.act(), trip.stage())
        if (EV_HIDDEN.equals(event)) hidden(trip.act(), progressOf(trip.act(), trip.stage(), game.wave + 1));
        else if (Trip.EV_TRUCK_DOWN.equals(event)) dim(1, 0);
    }

    /** 이동 연출이 화면을 다 가림: 막 a, 진행 p 의 테마로 바꾼다(가려진 동안 끝나게). 막이 바뀌면 땅도 칠한다. */
    private void hidden(int a, double p) {
        go(a, p, HIDDEN_TICKS);
        if (Plaza.groundAct() != groundOf(a)) repaint.start(groundOf(a), 0, HIDDEN_ROWS, null);
    }

    private static int groundOf(int a) {
        return a >= 1 && a <= 4 ? a : PlazaGen.baseAct();
    }

    @Override
    public void gameOver() {
        // 트럭이 터져 끝난 판: 한 단계 더 어둡게(등불이 꺼진 뒤의 어둠)
        if (Trip.END_TRUCK.equals(trip.endCause())) dim(1, 0);
    }

    /** 다시 하기: 판 끝에 더한 어둠을 걷는다. */
    @Override
    public void retry() {
        dim(0, 0);
    }

    @Override
    public void reset() {
        go(ACT_OLD, 0, 0);
        dimAsk = 0;
        dim = 0;
        rainAsk = null;
        stormAsk = false;
        nextBolt = -1;
        fogPos = -1;
        apply();
        if (Plaza.groundAct() != PlazaGen.baseAct() || repaint.running()) {
            repaint.start(PlazaGen.baseAct(), 0, 0, null);
            // 서버를 끄는 중이면 틱이 더 돌지 않으니 지금 끝낸다
            if (!game.plugin.isEnabled()) repaint.finishNow();
        }
    }

    @Override
    public void enabled(boolean on) {
        PlazaGen.baseAct(on ? 1 : 0);
        go(ACT_OLD, 0, 0);
        dimAsk = 0;
        dim = 0;
        rainAsk = null;
        stormAsk = false;
        apply();
        repaint.start(PlazaGen.baseAct(), 0, 0, null);
    }

    @Override
    public void tick() {
        if (clock != clockGoal) clock = Math.abs(clockGoal - clock) <= clockStep ? clockGoal : clock + Math.signum(clockGoal - clock) * clockStep;
        apply();
        fogTick();
        boltTick();
        // 칠하는 동안 보스 땅 도구가 칸을 쥐고 있었으면, 그 칸이 옛 막으로 되돌아간 뒤 같은 막으로 한 번 더 맞춘다(어긋난 칸만)
        if (repaint.needsCheck() && !repaint.running() && !terrainBusy()) repaint.start(Plaza.groundAct(), repaint.wet(), 0, null);
    }

    /** 세계 시각을 둠(내가 둔 값을 적어 남이 옮겼는지 안다). */
    private void setTime(long day) {
        if (world.getTime() != day) world.setTime(day);
        timeSet = day;
    }

    /**
     * 세계 시각 맞추기. 보스 싸움 중에는 보스가 하늘을 쥔다(BossDirector.sky). 남이 시각을 이어서 옮기는 중(보스가 끝난 뒤 13000 으로 내리는 40틱 같은 것)이면
     * {@link #YIELD_TICKS} 틱 기다렸다가 맞춘다. 감독이 웨이브를 열며 한 번 되돌리는 것은 {@link #beginRound} 가 같은 틱에 바로잡는다.
     */
    private void applyTime(long day) {
        long cur = world.getTime(), now = game.now();
        if (timeSet >= 0 && cur != timeSet && cur != day) foreignAt = now;
        if (cur == day) {
            timeSet = day;
            return;
        }
        if (bossSky() || now - foreignAt <= YIELD_TICKS) {
            timeSet = cur;
            return;
        }
        setTime(day);
    }

    // ------------------------------------------------------------------ 시계, 세계 시각, 날씨

    /** 지금 보이는 시계 틱(잠깐 덮어쓴 값이 있으면 그것). */
    private double shown() {
        if (holdUntil >= 0 && game.now() >= holdUntil) holdUntil = -1;
        return holdUntil >= 0 ? holdTicks : clock;
    }

    /** 시계 틱의 막(0 옛 모습, 1..5)과 막 안 진행. */
    private static int actAt(double c) {
        return c < Fog.THEME_STEP ? 0 : (int) Math.min(ACT_MORNING, Math.floor(c / Fog.THEME_STEP));
    }

    private static double progressAt(double c) {
        int a = actAt(c);
        return a == 0 ? 0 : Math.max(0, Math.min(1, (c - a * Fog.THEME_STEP) / Fog.THEME_SPAN));
    }

    /** 시계 틱의 세계 시각(옛 모습이면 -1: 안 건드림). 막 안은 처음에서 끝으로, 막 사이는 앞 막 끝에서 다음 막 처음으로 곧게. */
    private long timeAt(double c) {
        if (c < Fog.THEME_OLD_END) return -1;
        if (c < Fog.THEME_STEP) return Math.round(13000 + (rows[1].time[0] - 13000) * (c - Fog.THEME_OLD_END) / (Fog.THEME_STEP - Fog.THEME_OLD_END));
        int a = actAt(c);
        double in = c - a * Fog.THEME_STEP;
        Row r = rows[a];
        if (in <= Fog.THEME_SPAN || a == ACT_MORNING) {
            double q = Math.min(1, in / Fog.THEME_SPAN) * 4;
            int k = (int) Math.min(3, Math.floor(q));
            return Math.round(r.time[k] + (r.time[k + 1] - r.time[k]) * (q - k));
        }
        Row nx = rows[a + 1];
        return Math.round(r.time[4] + (nx.time[0] - r.time[4]) * (in - Fog.THEME_SPAN) / (Fog.THEME_STEP - Fog.THEME_SPAN));
    }

    private void apply() {
        double c = shown();
        long ct = Math.round(c);
        if (themeClock && ct != clockSent) {
            Fog.setTicks(world, Fog.THEME, ct);
            clockSent = ct;
        }
        long t = timeAt(c);
        if (t >= 0) {
            long day = Math.floorMod(t, 24000L);
            applyTime(day);
            timeSent = day;
        } else if (timeSent >= 0) {
            // 옛 모습으로 돌아옴: 감독의 13000
            timeSent = -1;
            timeSet = -1;
            if (world.getTime() != 13000 && !bossSky()) world.setTime(13000);
        }
        weather(c);
        dimTick(c);
    }

    private void weather(double c) {
        int a = actAt(c);
        double p = progressAt(c);
        // 비는 색이 넘어가기 시작할 때 같이 오기 시작한다(비 세기가 오르는 5초와 색이 넘어가는 시간이 겹치게)
        boolean auto = a >= 1 && a <= 4 && c <= a * Fog.THEME_STEP + Fog.THEME_SPAN && p >= rows[a].rainFrom * 0.3;
        boolean want = (rainAsk != null ? rainAsk : auto) && rainClock;
        if (world.hasStorm() != want) world.setStorm(want);
        boolean th = want && stormAsk;
        if (world.isThundering() != th) world.setThundering(th);
        if (rainClock) {
            long rt = Math.round(rainLevel() * 100);
            if (rt != rainSent) {
                Fog.setTicks(world, Fog.RAIN, rt);
                rainSent = rt;
            }
        }
    }

    private double rainLevel() {
        try {
            return ((CraftWorld) world).getHandle().getRainLevel(1f);
        } catch (RuntimeException | LinkageError e) {
            return world.hasStorm() ? 1 : 0;
        }
    }

    private void dimTick(double c) {
        double want = 0;
        if (c >= Fog.THEME_STEP) {
            // 색을 덮은 뒤라 감독의 밤(세계 시각 18000)과 큰 물결의 해질녘(사람 시각 덧셈)이 색으로 안 보인다: 어둡게 시계로 옮긴다
            if (game.director.night()) want = 1;
            want = Math.max(want, dusk());
        }
        if (dimAsk > 0 && dimAskUntil >= 0 && game.now() >= dimAskUntil) dimAsk = 0;
        want = Math.max(want, dimAsk);
        dim += Math.max(-0.04, Math.min(0.04, want - dim));
        long dt = Math.round(dim * 100);
        if (dimClock && dt != dimSent) {
            Fog.setTicks(world, Fog.DIM, dt);
            dimSent = dt;
        }
    }

    /** 큰 물결의 해질녘(Horizon 이 사람마다 시각을 더함: 1200 이 가장 큼)을 어둡게 0..0.8 로. */
    private double dusk() {
        for (Player p : world.getPlayers()) {
            if (p.isPlayerTimeRelative() && p.getPlayerTimeOffset() > 0) return Math.min(1, p.getPlayerTimeOffset() / 1200.0) * 0.8;
        }
        return 0;
    }

    /** 기본 안개 단계가 0 이 아닌 막: 감독의 안개 사건이 없고 안개 시계가 쉬는 자리(또는 내가 둔 자리)일 때만 그 단계로 옮긴다. */
    private void fogTick() {
        if (!Fog.ready(world)) return;
        int base = clock >= Fog.THEME_STEP ? rows[Math.max(1, actAt(clock))].fogBase : 0;
        long cur = Fog.ticks(world), rest = Fog.ticksFor(0);
        boolean mine = fogPos >= 0 && cur == Fog.ticksAt(fogPos);
        if (game.director.fog() != 0 || !(mine || cur == rest)) {
            fogPos = -1;
            return;
        }
        double goal = Fog.pos(base), now = fogPos >= 0 ? fogPos : Fog.pos(0);
        if (now == goal) {
            if (base == 0) fogPos = -1;
            return;
        }
        double step = 1 / 30.0;
        now = Math.abs(goal - now) <= step ? goal : now + Math.signum(goal - now) * step;
        Fog.setTicks(world, Fog.ticksAt(now));
        fogPos = now;
    }

    private void boltTick() {
        double c = shown();
        int a = actAt(c);
        boolean on = world.hasStorm() && game.state != Game.State.LOBBY && !game.director.silent()
            && (stormAsk || (a >= 1 && a <= 4 && progressAt(c) >= rows[a].boltFrom - 1e-6));
        if (!on) {
            nextBolt = -1;
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int gap0 = stormAsk ? BOLT_GAP0 / 3 : BOLT_GAP0, gap1 = stormAsk ? BOLT_GAP1 / 3 : BOLT_GAP1;
        if (nextBolt < 0) nextBolt = game.now() + gap0 / 2 + r.nextInt(gap1 - gap0 + 1);
        if (game.now() >= nextBolt) {
            lightning();
            nextBolt = game.now() + gap0 + r.nextInt(gap1 - gap0 + 1);
        }
    }

    // ------------------------------------------------------------------ 물음, 상태, 시험

    @Override
    public Object ask(String key) {
        return switch (key) {
            case "baseTime" -> clock >= Fog.THEME_OLD_END ? Long.valueOf(baseTime()) : null;
            case "fogBase" -> act >= 1 ? Integer.valueOf(fogBase()) : null;
            case "themeAct" -> Integer.valueOf(act);
            case "themeProgress" -> Double.valueOf(progress);
            case "painting" -> Boolean.valueOf(painting());
            default -> null;
        };
    }

    /**
     * HUD 값(그리기는 허브 쪽 HUD 가 한다): theme.act 보이는 막(0 옛 모습, 1..4, 5 아침), theme.key 막 키(road, marsh, ash, pad, morning, 옛 모습은 ""),
     * theme.progress 막 안 진행 0..1, theme.paint 땅 칠한 몫 0..1(안 칠하는 중이면 1), theme.rain 비 세기 0..1, theme.dim 어둡게 0..1.
     */
    @Override
    public void hud(Player p, Map<String, Object> out) {
        double c = shown();
        int a = actAt(c);
        out.put("theme.act", a);
        out.put("theme.key", a >= 1 && a <= ACT_MORNING ? rows[a].key : "");
        out.put("theme.progress", progressAt(c));
        out.put("theme.paint", repaint.progress());
        out.put("theme.rain", rainSent >= 0 ? rainSent / 100.0 : 0.0);
        out.put("theme.dim", dim);
    }

    @Override
    public String status() {
        String sky = bossSky() ? "boss" : game.now() - foreignAt <= YIELD_TICKS ? "wait" : "theme";
        return String.format(Locale.ROOT, "theme=%d/%.2f clock=%d>%d time=%d sky=%s rain=%.2f dim=%.2f ground=%d%s%s", act, progress, Math.round(shown()), Math.round(clockGoal),
            world.getTime(), sky, rainLevel(), dim, Plaza.groundAct(), repaint.running() ? String.format(Locale.ROOT, " paint=%.0f%%", repaint.progress() * 100) : "",
            repaint.needsCheck() ? " recheck" : "");
    }

    /**
     * 시험 훅 '막 x ...': '막 x &lt;0..4|아침&gt; [진행 0..1] [틱] [쓸기|빨리|그대로]'(하늘을 그 막으로 옮기고, 땅이 다르면 다시 칠함: 기본 빨리, 쓸기는 북에서 남으로,
     * 그대로는 땅을 안 건드림), '막 x 상태', '막 x 라운드 n'(라운드 n 을 열기 전과 같은 길), '막 x 가림 [n]'(이동 연출이 화면을 가렸을 때와 같은 길: 다음 라운드 것으로),
     * '막 x 칠하기 &lt;막&gt; [줄/틱]', '막 x 물 &lt;0..1&gt; [줄/틱]', '막 x 재기 &lt;이름|끔&gt;'(그 사람에게 나가는 패킷 세기), '막 x 비 &lt;on|off|자동&gt;',
     * '막 x 폭풍 &lt;on|off&gt;', '막 x 번개', '막 x 어둡게 &lt;0..1&gt; [틱]', '막 x 시계 &lt;틱&gt; [틱 수]'(테마 시계를 그 값에 둠), '막 x 값 &lt;이름&gt; &lt;수&gt;'.
     */
    @Override
    public String test(Player p, String[] a) {
        if (a.length < 2 || !a[1].equals("막")) return null;
        String sub = a.length > 3 ? a[3] : "상태";
        switch (sub) {
            case "상태" -> {
                return "[막] " + status() + " clocks=" + (themeClock ? "theme " : "") + (dimClock ? "dim " : "") + (rainClock ? "rain " : "") + "fog="
                    + Fog.ticks(world) + " base=" + PlazaGen.baseAct() + " wet=" + Plaza.wetCount() + " " + repaint.stats();
            }
            case "라운드" -> {
                // 라운드 n 을 열 때와 같은 길(이동 연출 없이): 하늘은 천천히, 막이 바뀌면 땅은 쓸어 칠함
                int n = (int) num(a, 4, Math.max(1, game.wave));
                int to = Trip.actOf(n);
                boolean other = to != act;
                if (other || Math.abs(progressOf(n) - progress) > 1e-6) go(to, progressOf(n), other ? STAGE_TICKS : ROUND_TICKS);
                if (Plaza.groundAct() != groundOf(to)) repaint.start(groundOf(to), 0, SWEEP_ROWS, null);
                return "[막] 라운드 " + n + " → " + status();
            }
            case "가림", "떠남" -> {
                int n = (int) num(a, 4, game.wave + 1);
                hidden(Trip.actOf(n), progressOf(n));
                return "[막] 가림(다음 라운드 " + n + ") → " + status();
            }
            case "칠하기" -> {
                int to = (int) num(a, 4, PlazaGen.baseAct());
                paint(to, num(a, 5, 0));
                return "[막] 칠하기 " + to + " 줄/틱 " + num(a, 5, 0);
            }
            case "물" -> {
                flood(num(a, 4, 0), num(a, 5, 0));
                return "[막] 물 " + num(a, 4, 0) + " ground=" + Plaza.groundAct();
            }
            case "재기" -> {
                Player t = a.length > 4 && !a[4].equals("끔") ? Bukkit.getPlayerExact(a[4]) : null;
                return "[막] " + repaint.tap(t);
            }
            case "비" -> {
                String v = a.length > 4 ? a[4] : "자동";
                rain(v.equals("on") ? Boolean.TRUE : v.equals("off") ? Boolean.FALSE : null);
                return "[막] 비 " + v + " 시계=" + rainClock;
            }
            case "폭풍" -> {
                storm(a.length > 4 && a[4].equals("on"));
                return "[막] 폭풍 " + stormAsk;
            }
            case "번개" -> {
                lightning();
                return "[막] 번개";
            }
            case "어둡게" -> {
                dim(num(a, 4, 1), (int) num(a, 5, 0));
                return "[막] 어둡게 " + dimAsk;
            }
            case "시계" -> {
                hold((long) num(a, 4, 0), (int) num(a, 5, 200));
                return "[막] 시계 " + holdTicks + " 틱 " + (int) num(a, 5, 200);
            }
            case "값" -> {
                if (a.length < 6) return "[막] HIDDEN_TICKS=" + HIDDEN_TICKS + " HIDDEN_ROWS=" + HIDDEN_ROWS + " STAGE_TICKS=" + STAGE_TICKS + " ROUND_TICKS=" + ROUND_TICKS
                    + " START_TICKS=" + START_TICKS + " SWEEP_ROWS=" + SWEEP_ROWS + " BUDGET_MS=" + Repaint.BUDGET_MS + " YIELD_TICKS=" + YIELD_TICKS;
                double v = num(a, 5, 0);
                switch (a[4]) {
                    case "HIDDEN_TICKS" -> HIDDEN_TICKS = (int) v;
                    case "HIDDEN_ROWS" -> HIDDEN_ROWS = v;
                    case "STAGE_TICKS" -> STAGE_TICKS = (int) v;
                    case "ROUND_TICKS" -> ROUND_TICKS = (int) v;
                    case "START_TICKS" -> START_TICKS = (int) v;
                    case "SWEEP_ROWS" -> SWEEP_ROWS = v;
                    case "BUDGET_MS" -> Repaint.BUDGET_MS = v;
                    case "YIELD_TICKS" -> YIELD_TICKS = (int) v;
                    default -> {
                        return "[막] 모르는 이름 " + a[4];
                    }
                }
                return "[막] " + a[4] + "=" + v;
            }
            default -> {
                int to;
                if (sub.equals("아침")) to = ACT_MORNING;
                else {
                    try {
                        to = Integer.parseInt(sub);
                    } catch (NumberFormatException e) {
                        return "막 x <0..4|아침> [진행] [틱] [쓸기|빨리|그대로] | 상태 | 라운드 n | 가림 [n] | 칠하기 막 [줄/틱] | 물 0..1 | 재기 이름|끔 | 비 on|off|자동 | 폭풍 on|off | 번개 | 어둡게 0..1 [틱] | 시계 틱 | 값";
                    }
                }
                double pr = num(a, 4, 0);
                int ticks = (int) num(a, 5, 40);
                String how = a.length > 6 ? a[6] : "빨리";
                go(to, pr, ticks);
                if (to == ACT_OLD) apply();
                int ground = groundOf(to);
                if (!how.equals("그대로") && (Plaza.groundAct() != ground || repaint.running())) paint(ground, how.equals("쓸기") ? SWEEP_ROWS : 0);
                return "[막] " + status();
            }
        }
    }

    private static double num(String[] a, int i, double def) {
        if (a.length <= i) return def;
        try {
            return Double.parseDouble(a[i]);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
