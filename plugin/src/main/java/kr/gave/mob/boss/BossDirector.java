package kr.gave.mob.boss;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import kr.gave.Props;
import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.game.Hud;
import kr.gave.gun.Gun;
import kr.gave.gun.Guns;
import kr.gave.mob.Corpse;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * 보스 웨이브 감독(docs/모듈/보스.md 3, 4, 7, 8, 10, 11.4, 18절). 감독(Director)이 생성자에서 만들어 {@code Director.boss}, {@code Director.bosses} 에
 * 단다. 보스 웨이브(2026-11-12 사용자 '35라운드를 엔딩으로': 10 B1 산더미, 20 B2 하늘 덮는, 30 B4 지평선, 35 B5 모두의 게이브(엔딩), 45 B3 에워싸는,
 * 그 뒤 55, 65 ... 에 같은 차례로 회차 +1: {@link #ORDER}, Director.bossIndex)에 그 보스를 놓고, 떼를 50% 로 줄여 보충하며, 보스가 쓰러지면 무너짐
 * 물결로 떼를 쓸고 모두에게 보상한다. 그 판에서 처음 B5 를 잡으면 엔딩(10절), 60초 쉬는 시간 뒤 다음 웨이브로 이어진다.
 *
 * <p>구현된 보스만 나온다(Species.Bx.implemented(): kr.gave.mob.sp.Bx 클래스가 있을 때). 없으면 그 웨이브는 보통 웨이브(기록 한 줄). 시험 훅
 * '보스 x 빈보스 on' 이면 없는 보스 자리에 시험용 빈 보스({@link Dummy})가 나온다. 설정: config.yml {@code bosses}(false 면 보스 없음),
 * {@code bossHp}(체력 배율).
 *
 * <p>감독이 부르는 자리(Director 의 보스 고리): plan 끝 {@link #adjust}, beginWave 끝 {@link #start}, tick 첫머리 {@link #tick}, 모든 상태
 * {@link #sideTick}, releaseBatch 목표 x {@link #aliveScale}, reserve + {@link #reserve}, waveDone {@link #alive}, 막힘 정리 {@link #blocking},
 * beginBreak 끝 {@link #beginBreak}, breakTicks {@link #breakTicks}, gameOver {@link #gameOver}, reset {@link #reset}, status, test '보스'.
 */
public final class BossDirector implements Director.BossHook {

    /** 숫자(켠 채 바꾸려면 시험 훅 '보스 x 균형 <이름> <값>'). */
    public static final class Tune {
        /** 보스 웨이브 떼: 보통 총량의 몫, 동시 수 목표의 몫, 대기열이 비면 보충 몫(REFILL_EVERY 틱마다, 화남 2배). */
        public static double BOSS_UNITS = 0.5, BOSS_ALIVE = 0.6, REFILL = 0.1;
        public static int REFILL_EVERY = 400;
        /** 감독 예비 몫(보스 졸개, 떨어지는 얼굴), 놓기 다시 해 보는 틱과 횟수. */
        public static int BOSS_ROOM = 20, SPAWN_RETRY = 20, SPAWN_TRIES = 5;
        /** 무너짐 물결: 보스 체력 0 뒤 시작 틱, 틱당 블록(엔딩 1.5), 끝 반지름. */
        public static int WAVE_DELAY = 20;
        public static double WAVE_SPEED = 1.0, ENDING_WAVE_SPEED = 1.5, WAVE_MAX = 120;
        /** 떼 경배(보스 쪽을 돌아봄) 틱, 그 뒤 몰려오는 빠르기와 틱. */
        public static int HOMAGE = 30, CHARGE = 100;
        public static double CHARGE_BUFF = 1.15;
        /** 화남: 예상 시간의 몇 배. */
        public static double ENRAGE_AT = 2.2;
        /** 보상: 45 x 웨이브 x 회차 배율 원, 엔딩 덤 원, 엔딩 뒤 쉬는 시간 틱(2026-11-14: 설정 moneyMul 1.7 을 녹여 25 → 45, 2000 → 3500). */
        public static int REWARD_PER_WAVE = 45, ENDING_MONEY = 3500, ENDING_BREAK = 1200;
        /** 앞 쉬는 시간 끝 몇 틱 전부터 땅울림(세 번, 30틱 간격). */
        public static int PRE_RUMBLE = 100;
        /** 등장: 하늘이 어두워지는 틱(놓는 틱부터), 큰 그림자가 중앙 원에 드리우는 반지름(그림자는 등장 길이 동안 커지며 짙어짐), 쓸기 시험 기본 틱. */
        public static int SKY_FALL = 40, SWEEP_TICKS = 70;
        public static double PLAZA_SHADOW = 14;

        // ---- 체력 표(보스.md 4절). 파워커브 담당이 다시 내면 여기만 고친다(켠 채: '보스 x 균형 H <보스 번호 1..5> <값>') ----
        /**
         * 보스 번호 1..5(B1..B5) 차례. H = 기준 웨이브 H_AT 에서의 바탕 체력(사람 수 몫 P(N), 회차 C(c), 설정 bossHp, 난이도를 곱하기 전).
         * 2026-11-12 파워커브(docs/모듈/파워커브.md 8절): 18500, 20000, 32000, 29000, 56000 → B1 35500, B2 57000, B3 175000, B4 91500, B5 162000
         * (무기 26종, 강화 20 의 화력에 맞춤: 30명 2~4분, 5명 3~5분). 난이도 손잡이는 감독이 hpScale 에 곱한다(Director.syncBossHp: 난이도^0.5).
         */
        public static double[] H = {35500, 57000, 129600, 91500, 162000};
        /**
         * H 를 맞춘 기준 웨이브 = 그 보스가 처음 나오는 웨이브(B1 10, B2 20, B3 33, B4 30, B5 35). 다른 웨이브(회차)에 나오면 D(w) / D(H_AT) 로 맞춘다.
         * B3 은 45 → 33 으로 당기며(2026-11-12) 같은 체력이 되게 175000 x D(33) / D(45) = 175000 x 548 / 740 = 129600 으로 옮겼다(회차 2 도 같음).
         */
        public static int[] H_AT = {10, 20, 33, 30, 35};
        /** D(w) = DPS_BASE + DPS_PER_WAVE x w: 사람 하나의 지속 화력(초당) 어림. 파워커브: 60 + 4.8w → 20 + 16w(모의 1인 화력 10웨이브 180, 20웨이브 340, 35웨이브 580). */
        public static double DPS_BASE = 20, DPS_PER_WAVE = 16;
        /** P(N) = N^PEOPLE_POW, 혼자 x PEOPLE_SOLO, 둘 x PEOPLE_DUO. */
        public static double PEOPLE_POW = 0.84, PEOPLE_SOLO = 0.65, PEOPLE_DUO = 0.8;
        /** C(c) = 1 + CYCLE_HP x (c - 1). 같은 보스 지난 회차 체력의 CYCLE_FLOOR 배 아래로 안 내려감. */
        public static double CYCLE_HP = 0.25, CYCLE_FLOOR = 1.25;
        /** 예상 싸움 초(사람 수, 초) 표(사이는 곧게 이음)와 B5 배율. 화남은 이것의 ENRAGE_AT 배. */
        public static double[][] EXPECT = {{1, 200}, {5, 240}, {10, 215}, {30, 180}};
        public static double EXPECT_B5 = 1.3;

        private Tune() {
        }
    }

    /** HUD 보스 틀(7.1): 이름 글귀, 종 키(b1), 체력 비율, 단계(1부터), 단계 수, 문턱, 화남, 죽음 연출 중. */
    public record Frame(String nameKey, String key, double frac, int phase, int phases, double[] cuts, boolean enraged, boolean dying) {
    }

    enum Stage { NONE, SPAWN, LIVE, DOWN, DONE }

    final Game game;
    final Director dir;
    private final Random rnd = new Random();

    /** 설정 bosses(false 면 보스 웨이브가 보통 웨이브), 시험 빈 보스, 설정 bossHp(체력 배율). */
    public boolean enabled = true, dummy;
    public double hpScale = 1.0;

    Boss boss;
    Species sp;
    Stage stage = Stage.NONE;
    int wave = -1, cycle = 1, pending = 1, tries, forcedN = -1, peopleN = 1, warnedWave = -1;
    long spawnAt, fightAt = -1, downAt = -1, retryAt, recheckAt, refillAt, homageUntil = -1, chargeUntil = -1;
    int downsStart;
    Tele bigShadow;
    // 무너짐 물결
    long waveAt = -1;
    double waveR, waveSpeed = 1;
    Vector waveC;
    boolean waveDone = true, rewarded;
    Tele waveTele;
    private final Set<UUID> heard = new HashSet<>();
    // 하늘(세계 시각을 틱마다 나눠 옮김)
    private long skyStart = -1, skyEnd;
    private long skyFrom, skyTo;
    // 앞 쉬는 시간 땅울림
    private int preFor = -1;
    private final boolean[] preDone = new boolean[3];
    // 엔딩(10절)
    private boolean endingSeen, endingBreak, endFull;
    private long endAt = -1, roundStart;
    /**
     * 엔딩 글(2026-11-12 사용자 확정, 원정.md 6.4절): 검은 화면에 한 줄씩 차례로(글귀 data/phrases/trip_ending.json 의 ending_1..ending_5, Hud.story).
     * 줄마다 ENDING_LINE_TICKS, 다섯째 줄 뒤 ENDING_HOLD 틱 더 검은 채, 그 뒤 숫자 줄(웨이브, 걸린 시간, 사람 수)만 잠깐. 글귀가 팩에 없으면 예전 엔딩 카드.
     * 나중에 발사장(kr.gave.trip.Launch)이 생기면 발사 연출 뒤에 같은 것(game.hud.story(ENDING_LINES, ..))을 부른다.
     */
    public static final List<String> ENDING_LINES = List.of("ending_1", "ending_2", "ending_3", "ending_4", "ending_5");
    public static final int ENDING_LINE_TICKS = 75, ENDING_HOLD = 50;
    /** 이번 엔딩에서 글 차례가 차지하는 틱(예전 카드로 떨어졌으면 0). */
    private int endStory;
    private int endWave, endPeople, endDowns;
    private long endSecs;
    private final Set<Gabe> frozenLooks = Collections.newSetFromMap(new IdentityHashMap<>());
    // 회차: 같은 보스 지난 체력(1.25배 아래로 안 내려감)
    private final Map<Species, Double> lastBase = new EnumMap<>(Species.class);
    private double floorBase;
    // 시험 예고(보스 없이 그려 보기)
    private final List<Tele> demo = new ArrayList<>();
    private String lastLog = "";
    /** 맵 연출 도구(땅 바꾸기와 되돌림 기록). 보스가 Boss.land() 로 쓴다. */
    public final Terrain terrain;
    /** 지킬 것(트럭 같은 사람 아닌 표적). 그것을 만드는 모듈이 guard 로 올린다. */
    private final List<Ward> wards = new ArrayList<>();

    public BossDirector(Game game, Director dir) {
        this.game = game;
        this.dir = dir;
        this.terrain = new Terrain(game, this);
        try {
            enabled = game.plugin.getConfig().getBoolean("bosses", true);
            hpScale = Math.max(0.01, game.plugin.getConfig().getDouble("bossHp", kr.gave.game.Director.BOSS_HP_DEFAULT));
        } catch (RuntimeException e) {
            enabled = true;
            hpScale = kr.gave.game.Director.BOSS_HP_DEFAULT;
        }
    }

    /** 이 판의 보스 감독(감독이 아직 없으면 null). */
    public static BossDirector of(Game game) {
        return game.director == null ? null : game.director.bosses;
    }

    // ------------------------------------------------------------------ 언제, 무엇이(3.1)

    /**
     * 보스가 나오는 차례: 보스 웨이브 k 번째(Director.bossIndex: 10 → 0, 20 → 1, 30 → 2, 33 → 3, 35 → 4, 45 → 5 ...)의 보스 = ORDER[k mod 5],
     * 회차 = k / 5 + 1. 그래서 10 B1, 20 B2, 30 B4, 33 B3(4막 우주선 건조 중), 35 B5(엔딩), 45 B1(회차 2), 55 B2, 65 B4, 75 B3, 85 B5,
     * 95 B1(회차 3) ... (2026-11-12 사용자 'B3 를 보통 판 33라운드에')
     */
    public static Species[] ORDER = {Species.B1, Species.B2, Species.B4, Species.B3, Species.B5};

    public static boolean isBossWave(int n) {
        return Director.bossIndex(n) >= 0;
    }

    /** 그 웨이브의 보스(ORDER 차례), 보스 웨이브가 아니면 null. */
    public static Species bossFor(int n) {
        int k = Director.bossIndex(n);
        return k < 0 ? null : ORDER[k % ORDER.length];
    }

    /** 회차(10~35: 1, 45~85: 2, 95~135: 3 ...). 보스 웨이브가 아니면 1. */
    public static int cycleOf(int n) {
        int k = Director.bossIndex(n);
        return k < 0 ? 1 : k / ORDER.length + 1;
    }

    /** 그 보스가 회차 c 에 나오는 웨이브(B1 회차 1 → 10, B5 회차 1 → 35, B1 회차 2 → 55 ...). 차례에 없으면 -1. */
    public static int waveOf(Species s, int c) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] != s) continue;
            int k = (Math.max(1, c) - 1) * ORDER.length + i;
            int[] w = Director.Tune.BOSS_WAVES;
            if (w == null || w.length == 0) return Director.Tune.BOSS_EVERY * (k + 1);
            return k < w.length ? w[k] : w[w.length - 1] + (k - w.length + 1) * Director.Tune.BOSS_EVERY;
        }
        return -1;
    }

    /** 보스 번호 1..5. */
    public static int index(Species s) {
        return s.ordinal() - Species.B1.ordinal() + 1;
    }

    /** 나올 수 있나(설정 켬, 클래스가 있거나 시험 빈 보스). */
    public boolean available(Species s) {
        return enabled && s != null && s.is(Role.BOSS) && (s.implemented() || dummy);
    }

    /** 지금 놓는 보스의 회차(보스 생성자가 읽음). */
    public int pendingCycle() {
        return pending;
    }

    // ------------------------------------------------------------------ 지킬 것(Ward)

    /** 지킬 것을 올림(트럭을 만드는 모듈이 부름). 보스가 표적으로 삼고, 맵 연출은 그 둘레 땅을 바꾸지 않는다. */
    public void guard(Ward w) {
        if (w != null && !wards.contains(w)) wards.add(w);
    }

    public void unguard(Ward w) {
        wards.remove(w);
    }

    /** 살아 있는 지킬 것들(없으면 빈 목록). */
    public List<Ward> wards() {
        if (wards.isEmpty()) return List.of();
        List<Ward> out = new ArrayList<>(wards.size());
        for (Ward w : wards) {
            try {
                if (w.alive()) out.add(w);
            } catch (RuntimeException e) {
                // 지킬 것 쪽 오류는 없는 것으로 봄
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 체력(4.1)

    /** D(w): 사람 하나의 지속 화력(초당) 어림. */
    public static double dps(int w) {
        return Tune.DPS_BASE + Tune.DPS_PER_WAVE * w;
    }

    /** P(N): 사람 수 몫. */
    public static double people(int n) {
        n = Math.max(1, n);
        double v = Math.pow(n, Tune.PEOPLE_POW);
        return n == 1 ? v * Tune.PEOPLE_SOLO : n == 2 ? v * Tune.PEOPLE_DUO : v;
    }

    /** C(c): 회차 배율. */
    public static double cycleMul(int c) {
        return 1 + Tune.CYCLE_HP * (Math.max(1, c) - 1);
    }

    /** 체력 공식(지난 회차 바닥 빼고): H[b] x D(w) / D(H_AT[b]) x P(N) x C(c) x scale. 표는 {@link Tune#H}. */
    public static double hpFormula(Species s, int w, int n, int c, double scale) {
        int i = Math.max(0, Math.min(Tune.H.length - 1, index(s) - 1));
        return Math.max(1, Tune.H[i] * (dps(w) / dps(Tune.H_AT[i])) * people(n) * cycleMul(c) * scale);
    }

    /** 체력 공식 x 설정 bossHp(지난 회차 바닥은 뺀 값: 표, 시험용). */
    public double maxHpFor(Species s, int w, int n, int c) {
        return hpFormula(s, w, n, c, hpScale);
    }

    /** 지금 싸움의 최대 체력(사람 n): 공식 x bossHp. 같은 보스 지난 회차의 사람 몫당 체력 x CYCLE_FLOOR(1.25) 아래로 안 내려감. */
    private double fightHp(int n) {
        double h = hpFormula(sp, wave, n, cycle, hpScale);
        if (floorBase > 0) h = Math.max(h, floorBase * people(n) * Tune.CYCLE_FLOOR);
        return Math.max(1, h);
    }

    /** 예상 싸움 틱(4.3절 표 Tune.EXPECT 를 사람 수로 이음, B5 x1.3). 화남은 이것의 ENRAGE_AT 배. */
    public int expectedTicks(Species s, int n) {
        double[][] pts = Tune.EXPECT;
        double sec = pts[pts.length - 1][1];
        if (n <= 1) sec = pts[0][1];
        else {
            for (int i = 1; i < pts.length; i++) {
                if (n <= pts[i][0]) {
                    double f = (n - pts[i - 1][0]) / (pts[i][0] - pts[i - 1][0]);
                    sec = pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * f;
                    break;
                }
            }
        }
        if (s == Species.B5) sec *= Tune.EXPECT_B5;
        return (int) Math.round(sec * 20);
    }

    int enrageTicks(Boss b) {
        return (int) Math.round(expectedTicks(b.sp, peopleN) * Tune.ENRAGE_AT);
    }

    private int inPlay() {
        return Math.max(1, game.players.inPlay().size());
    }

    // ------------------------------------------------------------------ 감독 고리

    /** 보스 웨이브 계획(3.3): 보통 총량 x BOSS_UNITS, 미리 깔리는 종, 새 종, 쏠림, 숨 고르기, 소리 없음, 안개와 밤과 땅 갈라짐, 금빛, 탐조등 뺌. */
    @Override
    public void adjust(Director.Plan p) {
        Species s = bossFor(p.wave);
        if (!available(s)) {
            p.boss = false;
            if (warnedWave != p.wave) {
                warnedWave = p.wave;
                log("보스 " + (s == null ? "?" : s.name()) + " 없음, 보통 웨이브" + (enabled ? "" : "(설정 bosses 꺼짐)"));
            }
            return;
        }
        p.counts.keySet().removeIf(x -> x.spawn == Species.Spawn.PLACED || x.is(Role.BONUS) || x == Species.G43 || x.is(Role.BOSS));
        p.fresh = null;
        p.lean = -1;
        p.leanExtra = 0;
        p.leanCounts.clear();
        p.breather = false;
        p.silent = false;
        p.night = false;
        p.dotDir = -1;
        p.flood = false;
        p.puddleEye = false;
        p.searchlights = 0;
        p.lightDir = -1;
        p.bonus = false;
        p.fog = 0;
        for (Map.Entry<Species, Integer> e : p.counts.entrySet()) e.setValue(Math.max(1, (int) Math.round(e.getValue() * Tune.BOSS_UNITS)));
    }

    /** 보스 웨이브 시작(감독 beginWave 끝): 보스 놓기, 떼 내보내기를 등장 길이만큼 미룸, 등장 연출. */
    @Override
    public void start(int n, Director.Plan p) {
        clear("새 웨이브 " + n);
        endingBreak = false;
        skyStart = -1;
        if (p == null || !p.boss) return;
        Species s = bossFor(n);
        if (!available(s)) return;
        sp = s;
        wave = n;
        cycle = cycleOf(n);
        tries = 0;
        retryAt = game.now();
        stage = Stage.SPAWN;
        rewarded = false;
        waveDone = true;
        // 놓는 동안 떼를 잡아 둠(놓은 뒤 그 보스 등장 길이만큼 다시 미룸, 다시 해 볼 때마다 늘림)
        dir.delayAnnounce(Tune.SPAWN_RETRY + 2);
        trySpawn();
    }

    private void trySpawn() {
        long now = game.now();
        Boss b = null;
        try {
            pending = cycle;
            if (sp.implemented()) {
                Gabe g = sp.create(game);
                if (g instanceof Boss bb) b = bb;
                else if (g != null) log("보스 오류: " + sp.name() + " 클래스가 Boss 를 이어받지 않음");
            } else if (dummy) b = new Dummy(game, sp);
        } catch (RuntimeException e) {
            game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[판] 보스 " + sp.name() + " 생성자 예외", e);
        }
        Vector at = null;
        if (b != null) {
            try {
                at = b.spawnPoint(defaultSpawn(b));
            } catch (RuntimeException e) {
                at = defaultSpawn(b);
            }
        }
        Gabe placed = b == null || at == null ? null : game.mobs.place(b, at);
        if (placed == null) {
            if (++tries >= Tune.SPAWN_TRIES) {
                log("보스 오류: " + sp.name() + " 놓기 " + tries + "번 실패, 보통 웨이브로");
                stage = Stage.NONE;
                return;
            }
            retryAt = now + Tune.SPAWN_RETRY;
            dir.delayAnnounce(Tune.SPAWN_RETRY + 2);
            return;
        }
        boss = b;
        stage = Stage.LIVE;
        spawnAt = now;
        fightAt = -1;
        peopleN = forcedN > 0 ? forcedN : inPlay();
        Double lb = lastBase.get(sp);
        floorBase = cycle > 1 && lb != null ? lb : 0;
        b.maxHp = fightHp(peopleN);
        b.hp = b.maxHp;
        lastBase.put(sp, b.maxHp / people(peopleN));
        downsStart = downs();
        dir.delayAnnounce(b.introLength() + 2);
        log(String.format(Locale.ROOT, "보스 %s 등장 w=%d c=%d N=%d hp=%.0f D=%.0f(공식)%s", sp.name(), wave, cycle, peopleN, b.maxHp, dps(wave),
                sp.implemented() ? "" : " 빈보스"));
        entrance(b, 0);
    }

    /** 기본 자리: 서 있는 사람들이 가장 많이 보는 쪽으로 가운데에서 R 60, 바닥 + hover. */
    Vector defaultSpawn(Boss b) {
        double lx = 0, lz = 0;
        for (Player p : game.players.standing()) {
            Vector d = p.getLocation().getDirection().setY(0);
            if (d.lengthSquared() > 1e-6) d.normalize();
            lx += d.getX();
            lz += d.getZ();
        }
        double a = lx * lx + lz * lz < 1e-4 ? rnd.nextDouble() * Math.PI * 2 : Math.atan2(lz, lx);
        double x = Plaza.CX + 0.5 + Math.cos(a) * 60, z = Plaza.CZ + 0.5 + Math.sin(a) * 60;
        return new Vector(x, game.mobs.flow.floor(x, z) + b.hover, z);
    }

    @Override
    public boolean alive() {
        if (stage == Stage.SPAWN || stage == Stage.LIVE || stage == Stage.DOWN) return true;
        return boss != null && !boss.dead && !boss.removed;
    }

    @Override
    public boolean blocking() {
        return alive();
    }

    /** 보스 싸움 중인가(등장부터 무너짐 물결 끝까지): 음악, 압도감 연출, HUD 가 읽음. */
    @Override
    public boolean active() {
        return stage == Stage.LIVE || stage == Stage.DOWN || stage == Stage.SPAWN;
    }

    @Override
    public double aliveScale() {
        return active() ? Tune.BOSS_ALIVE : 1.0;
    }

    @Override
    public int reserve() {
        return active() ? Tune.BOSS_ROOM : 0;
    }

    /** 지금 보스(없으면 null). */
    public Boss boss() {
        return boss;
    }

    /** 그 판에서 엔딩을 봤나(HUD 웨이브 숫자 금색). */
    public boolean endingSeen() {
        return endingSeen;
    }

    // ------------------------------------------------------------------ 틱

    /** 감독 tick 첫머리(WAVE). */
    @Override
    public void tick() {
        long now = game.now();
        switch (stage) {
            case SPAWN -> {
                if (now >= retryAt) trySpawn();
            }
            case LIVE -> {
                if (boss == null || boss.removed || boss.dead && !boss.dying) {
                    lost(boss == null ? "없음" : boss.cleaned ? "치워짐" : "사라짐(종 코드 예외?)");
                    return;
                }
                if (!boss.intro) {
                    homage(now);
                    recheck(now);
                    refill(now);
                }
            }
            case DOWN -> waveTick(now);
            default -> {
            }
        }
    }

    /** 모든 상태 매 틱(감독 곁 틱): 하늘, 엔딩 차례, 앞 쉬는 시간 땅울림, 시험 예고. */
    @Override
    public void sideTick() {
        long now = game.now();
        skyTick(now);
        if (endAt >= 0) endingTick(now);
        if (game.state == Game.State.BREAK && preFor == game.wave + 1) preRumble(now);
        if (!demo.isEmpty()) demo.removeIf(t -> !t.tick());
        sweepTick(now);
        terrain.tick();
        // 엔딩 뒤 쉬는 시간(60초)이 끝나기 4초 전: 새벽이 빨리 지나가 다시 저녁, 안개가 돌아옴
        if (endingBreak && game.state == Game.State.BREAK && game.stateAt + dir.breakTicks() - now == 80) {
            sky(baseTime(), 70);
            fog(0);
        }
        if (stage == Stage.DOWN && game.state != Game.State.WAVE) waveTick(now);
    }

    /** 보스가 엉뚱하게 사라짐: 웨이브는 보통으로(남은 떼가 끝나면 끝). */
    private void lost(String why) {
        log("보스 오류: " + (sp == null ? "?" : sp.name()) + " " + why + ", 보통 웨이브로");
        game.music.boss(false);
        if (bigShadow != null) bigShadow.remove();
        bigShadow = null;
        stage = Stage.NONE;
        boss = null;
        sky(baseTime(), 40);
        terrain.restoreAll();
    }

    /** 떼 경배(등장 끝): 감독이 낸 땅 게이브가 HOMAGE 틱 동안 제자리에서 보스 쪽을 보고, 그 뒤 CHARGE 틱 동안 CHARGE_BUFF 배로 몰려옴. */
    private void homage(long now) {
        if (now > chargeUntil || boss == null) return;
        boolean still = now < homageUntil;
        if (now == homageUntil) hordeCry(boss, 1f);
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g == boss || !dir.fromDirector(g)) continue;
            if (still) {
                if (g.flying) continue;
                g.lure(g.pos.clone(), 2);
                game.mobs.face(g, boss.pos);
            } else g.speedBuff = Math.max(g.speedBuff, Tune.CHARGE_BUFF);
        }
    }

    /** 사람 수가 바뀌면 체력을 다시 계산(지금 비율 그대로, 1초에 한 번). */
    private void recheck(long now) {
        if (now < recheckAt || boss == null || boss.dying) return;
        recheckAt = now + 20;
        int n = forcedN > 0 ? forcedN : inPlay();
        if (n == peopleN) return;
        double f = boss.hpFrac();
        peopleN = n;
        boss.maxHp = fightHp(n);
        boss.hp = boss.maxHp * f;
        log(String.format(Locale.ROOT, "보스 %s 사람 수 %d → 체력 %.0f", sp.name(), n, boss.maxHp));
    }

    /** 대기열이 비었는데 보스가 살아 있으면 REFILL_EVERY 틱마다 보통 총량 x REFILL 을 앞잡이, 받침 종으로 더 넣음(화남 2배 빠르게). */
    private void refill(long now) {
        if (boss == null || boss.dying || dir.queued() > 0) return;
        if (refillAt <= 0) refillAt = now + Tune.REFILL_EVERY;
        if (now < refillAt) return;
        refillAt = now + (boss.enraged ? Tune.REFILL_EVERY / 2 : Tune.REFILL_EVERY);
        Director.Plan p = dir.plan();
        if (p == null) return;
        int n = Math.max(1, (int) Math.round(p.total() / Math.max(0.05, Tune.BOSS_UNITS) * Tune.REFILL * (1 + 0.3 * (cycle - 1))));
        List<Species> pool = new ArrayList<>();
        if (p.lead != null) pool.add(p.lead);
        if (p.base != null) pool.add(p.base);
        if (pool.isEmpty()) {
            Species most = null;
            for (Map.Entry<Species, Integer> e : p.counts.entrySet()) {
                if (e.getKey().spawn == Species.Spawn.PLACED || !e.getKey().implemented()) continue;
                if (most == null || e.getValue() > p.counts.get(most)) most = e.getKey();
            }
            if (most != null) pool.add(most);
        }
        if (pool.isEmpty() && Species.G01.implemented()) pool.add(Species.G01);
        for (int i = 0; i < n && !pool.isEmpty(); i++) dir.addQueue(pool.get(i % pool.size()), 1);
    }

    // ------------------------------------------------------------------ 보스가 부름

    /**
     * 등장 차례(t = 0 은 놓은 틱, 그 뒤 보스 introTick 이 1..introLength). 엄청난 절망감(사용자 2026-11-12): 놓는 틱에 땅울림, 뿔나팔, 떼의 함성, 하늘이
     * 어두워짐, 큰 그림자, 레터박스, 모두의 화면에 보스 이름 큰 글귀가 한꺼번에 온다. 그 뒤 땅울림이 두 번 더(점점 크게), 끝 20틱 전에 포효.
     * 보스가 고르는 것: quietEntrance(소리와 흔들림 없이: B5 고요), ownRoar, fromGround(방향 화살촉), sweepShadow, shadowRadius, skyTime, 글귀 키.
     */
    void entrance(Boss b, int t) {
        int len = b.introLength();
        if (t == 0) {
            game.music.boss(true);
            letterbox(len + 10);
            sky(b.skyTime(), Tune.SKY_FALL);
            game.hud.bossTitle(Hud.hasPhrase(b.titleKey()) ? b.titleKey() : b.nameKey(), b.subKey(), Math.max(60, Math.min(110, len - 10)));
            if (b.fromGround) game.hud.waveFrom(Hud.hasPhrase("bs_come") ? "bs_come" : null, 80, yawTo(b.pos));
            if (!b.quietEntrance) {
                quakeSound(b, 1f);
                horn(b, 1f);
                hordeCry(b, 1f);
                game.horizon.shake(0.9, 40);
            }
            double sr = b.shadowRadius();
            if (sr > 0) {
                bigShadow = b.shadow(b.pos, sr, len);
                if (b.sweepShadow) {
                    // 큰 그림자가 사람들이 선 중앙 원에 드리운다(보스보다 그림자가 먼저 온다): 등장 길이 동안 커지며 짙어지고 싸움이 시작되면 걷힘
                    Vector c = Plaza.center(game.world).toVector();
                    shadowSweep(c, c, Tune.PLAZA_SHADOW, len);
                }
            }
        }
        if (bigShadow != null && t % 4 == 0 && !bigShadow.gone()) bigShadow.move(b.pos);
        if (t == len / 3 && !b.quietEntrance) {
            quakeSound(b, 1f);
            game.horizon.shake(0.7, 24);
        }
        if (t == len * 2 / 3 && !b.quietEntrance) {
            quakeSound(b, 1f);
            hordeCry(b, 0.9f);
            game.horizon.shake(0.8, 24);
        }
        if (t == len - 20 && !b.ownRoar) {
            b.sfxAll("sfx.boss_roar", Sound.ENTITY_RAVAGER_ROAR, 1f, 0.45f, 24);
            game.horizon.shake(1.0, 30);
        }
        if (t >= len && bigShadow != null) {
            bigShadow.remove();
            bigShadow = null;
        }
    }

    /** 등장 끝(싸움 시작): 보스 틀 켬, 떼 경배. */
    void onFight(Boss b) {
        long now = game.now();
        fightAt = now;
        homageUntil = now + Tune.HOMAGE;
        chargeUntil = homageUntil + Tune.CHARGE;
        refillAt = now + Tune.REFILL_EVERY;
        recheckAt = now + 20;
        log("보스 " + b.sp.name() + " 싸움 시작 hp=" + Math.round(b.maxHp));
    }

    void onPhase(Boss b, int n) {
        log(String.format(Locale.ROOT, "보스 %s 단계 %d t=%ds hp=%.2f", b.sp.name(), n, fightAt < 0 ? 0 : (game.now() - fightAt) / 20, b.hpFrac()));
        game.horizon.shake(0.7, 24);
    }

    void onEnrage(Boss b) {
        log(String.format(Locale.ROOT, "보스 %s 화남 t=%ds", b.sp.name(), fightAt < 0 ? 0 : (game.now() - fightAt) / 20));
    }

    /** 보스 체력 0(죽음 연출 시작): 곡 멈춤, 알림, 대기열 버림, 무너짐 물결 예약, 기록. 그 판 첫 B5 면 엔딩. */
    void onDown(Boss b, Damage d) {
        if (b != boss) return;
        long now = game.now();
        stage = Stage.DOWN;
        downAt = now;
        long secs = fightAt < 0 ? 0 : (now - fightAt) / 20;
        int downs = downs() - downsStart;
        game.music.boss(false);
        game.music.hush(true);
        if (Hud.hasPhrase("bs_down")) game.hud.notice("bs_down", 80);
        dir.dropQueue();
        if (bigShadow != null) bigShadow.remove();
        bigShadow = null;
        waveC = b.waveCenter();
        waveR = 0;
        waveSpeed = Tune.WAVE_SPEED;
        waveAt = now + Tune.WAVE_DELAY;
        waveDone = false;
        heard.clear();
        boolean b5 = b.sp == Species.B5;
        if (b5) startEnding(!endingSeen && cycle == 1, false);
        else sky(baseTime(), 60);
        game.records.noteBoss(b.sp.name(), wave, cycle, secs, peopleN, downs);
        log(String.format(Locale.ROOT, "보스 %s 쓰러짐 t=%ds 쓰러짐=%d 잡은=%s", b.sp.name(), secs, downs, d == null || d.by() == null ? "-" : d.by().getName()));
    }

    /** 죽음 연출 끝(사체가 됨). 물결도 끝났으면 보상. */
    void onCorpse(Boss b) {
        if (waveDone) settle();
    }

    /** 물결이 끝나고 보스도 사체가 됐으면 보상(시험 물결처럼 보상이 없으면 그냥 끝). 그 뒤 alive() 가 false 라 감독이 웨이브를 끝낸다. */
    private void settle() {
        if (boss != null && !boss.dead && !boss.removed) return;
        if (!rewarded) reward();
        stage = Stage.DONE;
        game.music.hush(false);
    }

    // ------------------------------------------------------------------ 무너짐 물결(3.4)

    private void waveTick(long now) {
        if (waveDone || waveAt < 0 || now < waveAt) return;
        if (waveTele == null || waveTele.gone()) {
            if (waveTele == null) waveTele = Tele.wave(game, waveC, 1, Tune.WAVE_MAX, waveSpeed);
        } else waveTele.tick();
        waveR += waveSpeed;
        double far = 0;
        for (Gabe g : new ArrayList<>(game.mobs.all())) {
            if (g.dead || g == boss || dir.inCage(g)) continue;
            double dd = Boss.horiz(g.pos, waveC);
            if (dd > waveR) {
                far = Math.max(far, dd);
                continue;
            }
            collapse(g);
        }
        for (Player p : game.players.inPlay()) {
            if (heard.contains(p.getUniqueId())) continue;
            double dd = Boss.horiz(p.getLocation().toVector(), waveC);
            if (dd > waveR) {
                far = Math.max(far, dd);
                continue;
            }
            heard.add(p.getUniqueId());
            String ev = Props.hasSound("boss.wave") ? "boss.wave" : "sfx.quake";
            Props.soundTo(p, p.getLocation(), ev, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 1f, 0.4f, -1);
            if (boss != null) boss.kick(p, 0.6);
        }
        if (waveR >= Tune.WAVE_MAX || waveR > far + 2) {
            waveDone = true;
            if (waveTele != null) waveTele.remove();
            waveTele = null;
            settle();
        }
    }

    /** 물결이 지나간 게이브 하나: 폭발로 바깥 위로 날아가 쓰러짐(보상 없음). 죽음 결과 종(업은, 총 주운, 사람 탈)은 사람이 잡은 것처럼(업힌 사람이 풀림). */
    private void collapse(Gabe g) {
        boolean result = g.sp == Species.G38 || g.sp == Species.G52 || g.sp == Species.G50;
        g.noReward = !result;
        g.reward = 0;
        Vector out = g.pos.clone().subtract(waveC).setY(0);
        if (out.lengthSquared() < 1e-6) out = new Vector(1, 0, 0);
        out.normalize();
        Vector at = g.pos.clone().subtract(out.clone().multiply(1.0));
        Damage d = new Damage(Math.max(1, g.hp) + 1, DamageType.EXPLOSION, null, at, out.clone().add(new Vector(0, 0.6, 0)).normalize(), "boss_wave", 0);
        try {
            g.die(d);
        } catch (RuntimeException e) {
            game.players.freeFrom(g);
            g.remove();
        }
        if (!g.dead) g.remove();
    }

    /** 무너짐 물결을 at 에서 시작(시험 훅 '물결', 엔딩). */
    void startWave(Vector at, double speed, int delay) {
        waveC = at.clone();
        waveR = 0;
        waveSpeed = speed;
        waveAt = game.now() + delay;
        waveDone = false;
        heard.clear();
        if (waveTele != null) waveTele.remove();
        waveTele = null;
        if (stage == Stage.NONE || stage == Stage.DONE) stage = Stage.DOWN;
    }

    // ------------------------------------------------------------------ 보상(3.6)

    private void reward() {
        if (rewarded || wave < 0) return;
        rewarded = true;
        int money = (int) Math.round(Tune.REWARD_PER_WAVE * wave * cycleMul(cycle));
        boolean b5 = sp == Species.B5;
        List<Player> ps = game.players.inPlay();
        game.players.teamMoney(money);
        if (b5) game.players.teamMoney(Tune.ENDING_MONEY);
        for (Player p : ps) {
            PState s = game.players.get(p);
            if (s.life != Life.ALIVE) game.players.reviveAtShop(p, false);
            else game.players.heal(p, s.maxHp);
            game.guns.refillAll(p);
            game.augments.offer(p, 3);
            if (b5) {
                game.augments.offer(p, 3);
                Guns.Arms a = game.guns.arms(p);
                Guns.Kind cur = a == null ? null : a.primary;
                game.shop.offerGun(p, Gun.roll(Gun.rollKind(wave, cur, rnd), Gun.Rarity.LEGENDARY, rnd));
            }
        }
        Vector at = null;
        for (Corpse c : game.mobs.bossCorpses()) if (!c.gone()) at = c.pos.clone();
        if (at == null && waveC != null) at = waveC.clone();
        if (at != null) {
            // 사체 옆(경기장 안쪽으로 4블록), 상자가 이미 둘이면 건너뜀
            Vector in = new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5).subtract(at.clone().setY(0));
            if (in.lengthSquared() > 1e-6) at.add(in.normalize().multiply(4));
            double r = Plaza.radius(at.getX(), at.getZ());
            if (r > Plaza.R_ROAM - 4) {
                double k = (Plaza.R_ROAM - 4) / Math.max(1, r);
                at = new Vector(Plaza.CX + 0.5 + (at.getX() - Plaza.CX - 0.5) * k, 0, Plaza.CZ + 0.5 + (at.getZ() - Plaza.CZ - 0.5) * k);
            }
            if (game.crates.live().size() < 2) game.crates.drop(at);
        }
        log(String.format(Locale.ROOT, "보스 보상 w=%d %d원%s 사람 %d 증강 %d", wave, money, b5 ? " + 엔딩 " + Tune.ENDING_MONEY : "", ps.size(), b5 ? 2 : 1));
    }

    // ------------------------------------------------------------------ 쉬는 시간, 판 끝

    @Override
    public void beginBreak(int n) {
        if (boss != null && !boss.dead && !boss.removed) boss.cleanRemove();
        if (stage != Stage.NONE && stage != Stage.DONE) log("보스 웨이브 " + n + " 끝(보스 정리 " + stage + ")");
        if (bigShadow != null) bigShadow.remove();
        bigShadow = null;
        if (waveTele != null) waveTele.remove();
        waveTele = null;
        waveDone = true;
        stage = Stage.NONE;
        boss = null;
        game.music.hush(false);
        // 보스가 바꾼 땅을 모두 되돌림(쉬는 시간에는 평평한 원래 맵)
        terrain.restoreAll();
        // 엔딩 뒤 쉬는 시간에는 새벽 하늘과 맑은 안개를 그대로 둠(끝나기 4초 전에 sideTick 이 되돌림)
        if (endingBreak) fog(1);
        else sky(baseTime(), 40);
        Director.Plan np = dir.nextPlan();
        preFor = -1;
        if (np != null && np.boss && available(bossFor(np.wave))) {
            preFor = np.wave;
            java.util.Arrays.fill(preDone, false);
            if (Hud.hasPhrase("bs_next")) game.hud.notice("bs_next", Hud.hasIcon(bossFor(np.wave)) ? "ic_m_" + bossFor(np.wave).key()
                    : kr.gave.hud.HudLine.has("ic_boss") ? "ic_boss" : "ic_m_default", 80);
            for (Player p : game.players.inPlay()) Props.sound(p, Props.hasSound("boss.next") ? "boss.next" : "sfx.horn", Sound.EVENT_RAID_HORN, 0.7f, 1f, 0.8f);
        }
    }

    @Override
    public int breakTicks(int base) {
        return endingBreak ? Tune.ENDING_BREAK : base;
    }

    /** 앞 쉬는 시간 끝 PRE_RUMBLE 틱 전부터 30틱마다 세 번 점점 크게(B2 바람, B4 종, B5 고요). */
    private void preRumble(long now) {
        long left = game.stateAt + dir.breakTicks() - now;
        Species s = bossFor(preFor);
        for (int i = 0; i < 3; i++) {
            if (preDone[i] || left > Tune.PRE_RUMBLE - 30L * i) continue;
            preDone[i] = true;
            if (s == Species.B5) continue;
            float vol = 0.5f + 0.25f * i;
            for (Player p : game.players.inPlay()) {
                double a = rnd.nextDouble() * Math.PI * 2;
                Location at = p.getLocation().clone().add(Math.cos(a) * 30, 2, Math.sin(a) * 30);
                if (s == Species.B2) Props.soundTo(p, at, "sfx.wind", Sound.ITEM_ELYTRA_FLYING, vol, 0.8f, 0.6f, -1);
                else if (s == Species.B4) Props.soundTo(p, at, null, Sound.BLOCK_BELL_RESONATE, vol, 0.5f, 0.5f, -1);
                else Props.soundTo(p, at, "sfx.quake", Sound.ENTITY_WARDEN_EMERGE, vol, 1f, 0.6f, -1);
            }
            game.horizon.shake(0.2 + 0.1 * i, 20);
        }
    }

    @Override
    public void gameOver() {
        if (boss != null && !boss.dead && !boss.dying && !boss.removed) {
            try {
                boss.victory();
            } catch (RuntimeException e) {
                game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[판] 보스 승리 연출 예외", e);
            }
            log("보스 " + boss.sp.name() + " 승리(전멸)");
        }
        game.music.hush(false);
    }

    @Override
    public void retry() {
        clear("다시 하기");
        endingBreak = false;
        endAt = -1;
        skyStart = -1;
        unfreezeLooks();
        game.mobs.frozen = false;
    }

    @Override
    public void reset() {
        if (boss != null && !boss.dead) boss.cleanRemove();
        clear("판 바뀜");
        boolean starting = game.state == Game.State.LOBBY;
        if (starting) roundStart = game.now();
        endingSeen = false;
        endingBreak = false;
        endAt = -1;
        unfreezeLooks();
        game.mobs.frozen = false;
        preFor = -1;
        skyStart = -1;
        lastBase.clear();
        floorBase = 0;
        wave = -1;
        for (Tele t : demo) t.remove();
        demo.clear();
    }

    /** 지난 보스 상태를 지움(새 웨이브, 판 끝). */
    private void clear(String why) {
        if (boss != null && !boss.dead && !boss.removed) boss.cleanRemove();
        if (bigShadow != null) bigShadow.remove();
        bigShadow = null;
        sweepClear();
        if (waveTele != null) waveTele.remove();
        waveTele = null;
        if (stage == Stage.LIVE || stage == Stage.DOWN || stage == Stage.SPAWN) {
            log("보스 상태 지움(" + why + ")");
            game.music.boss(false);
        }
        boss = null;
        stage = Stage.NONE;
        waveDone = true;
        waveAt = -1;
        homageUntil = chargeUntil = -1;
        game.music.hush(false);
        terrain.restoreAll();
    }

    // ------------------------------------------------------------------ 엔딩(10절)

    /** full: 그 판 첫 B5(세상 멈춤, 딸깍, 물결 1.5, 새벽, 카드, 기록). 아니면 짧은 판(물결, 새벽, '또 아침'). test: 시험 훅(보상 없음). */
    void startEnding(boolean full, boolean test) {
        // 판이 끝난 뒤(OVER) 쓰러진 B5 는 엔딩을 열지 않는다(2026-11-12 버그 훑기)
        if (game.state == Game.State.OVER) {
            log("엔딩 건너뜀: 판이 끝난 뒤" + (test ? "(시험)" : ""));
            return;
        }
        long now = game.now();
        endAt = now;
        endFull = full;
        endingBreak = true;
        endWave = Math.max(1, game.wave);
        endSecs = Math.max(0, (now - roundStart) / 20);
        endPeople = inPlay();
        endDowns = downs();
        game.music.hush(true);
        if (full) {
            for (Gabe g : game.mobs.all()) {
                if (g.dead || g.body == null || g == boss) continue;
                g.body.look.open = false;
                g.body.look.freeze = true;
                frozenLooks.add(g);
            }
            game.mobs.frozen = true;
            if (waveC == null) waveC = Plaza.center(game.world).toVector();
            waveAt = now + 40;
            waveSpeed = Tune.ENDING_WAVE_SPEED;
        }
        log("엔딩 시작 " + (full ? "첫" : "또") + (test ? "(시험)" : "") + " w=" + endWave);
    }

    private void unfreezeLooks() {
        for (Gabe g : frozenLooks) if (!g.dead && g.body != null) g.body.look.freeze = false;
        frozenLooks.clear();
    }

    private void endingTick(long now) {
        long t = now - endAt;
        if (endFull) {
            if (t == 40) {
                for (Player p : game.players.inPlay()) Props.sound(p, "gabe.b5.click", Sound.UI_BUTTON_CLICK, 1f, 1f, 0.6f);
                game.mobs.frozen = false;
                unfreezeLooks();
            }
            if (t == 80) {
                fog(1);
                sky(25000, 60);
            }
            if (t == 100 && !(game.trip != null && game.trip.has("Launch"))) {
                // 검은 화면에 엔딩 글 다섯 줄을 한 줄씩. 글귀가 아직 팩에 없으면 예전 엔딩 카드('아침')
                endStory = Hud.hasPhrase(ENDING_LINES.get(0)) ? game.hud.story(ENDING_LINES, ENDING_LINE_TICKS, ENDING_HOLD) : 0;
                if (endStory == 0) game.hud.endingCard("t_end", "end_sub", endWave, endSecs, endPeople, 200);
                for (Player p : game.players.inPlay()) Props.sound(p, "gabe.b5.dawn", Sound.ENTITY_PARROT_AMBIENT, 1f, 1f, 1f);
            }
            // 다섯 줄이 끝나면 숫자 줄(웨이브, 걸린 시간, 사람 수)만 잠깐
            if (endStory > 0 && t == 100 + endStory) game.hud.endingCard(null, null, endWave, endSecs, endPeople, 160);
            if (t == 120) record();
            if (t >= 320 + (endStory > 0 ? endStory + 20 : 0)) endAt = -1;
        } else {
            if (t == 20) sky(23500, 60);
            if (t == 60) game.hud.endingCard("t_again", null, endWave, endSecs, endPeople, 160);
            if (t == 80) record();
            if (t >= 240) endAt = -1;
        }
    }

    private void record() {
        endingSeen = true;
        game.records.noteEnding(endWave, endSecs, endPeople, endDowns);
        String mmss = String.format(Locale.ROOT, "%d:%02d", endSecs / 60, endSecs % 60);
        Component msg = Component.text(endWave + "웨이브 엔딩: " + (Hud.phrase("name_b5") == null ? "모두의 게이브" : Hud.phrase("name_b5")), NamedTextColor.GOLD)
                .append(Component.text("(" + mmss + ", " + endPeople + "명, 쓰러짐 " + endDowns + "번)", NamedTextColor.YELLOW));
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(msg);
        log(String.format(Locale.ROOT, "엔딩 w=%d t=%s 사람 %d 쓰러짐 %d%s", endWave, mmss, endPeople, endDowns, game.records.skipped ? " skipped" : ""));
    }

    // ------------------------------------------------------------------ 연출 도구

    /** 보스가 끝난 뒤 돌아갈 세계 시각(원정 테마의 막 시각, 원정이 꺼져 있으면 13000). trip_theme 요청. */
    private long baseTime() {
        return game.trip == null ? 13000L : game.trip.baseTime();
    }

    /** 세계 시각을 ticks 동안 target 으로(2틱마다 나눠 보냄). 감독 reset 과 다음 웨이브 시작이 바탕 시각(원정 테마, 아니면 13000)으로 되돌린다. */
    public void sky(long target, int ticks) {
        skyFrom = game.world.getTime();
        skyTo = target;
        skyStart = game.now();
        skyEnd = skyStart + Math.max(1, ticks);
    }

    private void skyTick(long now) {
        if (skyStart < 0 || (now & 1) != 0) return;
        double f = Math.min(1, (now - skyStart) / (double) Math.max(1, skyEnd - skyStart));
        long t = Math.round(skyFrom + (skyTo - skyFrom) * f);
        game.world.setTime(Math.floorMod(t, 24000L));
        if (f >= 1) skyStart = -1;
    }

    /** 안개 단계(감독 bossFog). */
    public void fog(int level) {
        dir.bossFog(level);
    }

    /** 레터박스 덮개(판에 든 모두, 다른 덮개가 걸린 사람은 건너뜀). */
    void letterbox(int ticks) {
        if (!Hud.hasCover("letterbox")) return;
        long now = game.now();
        for (Player p : game.players.inPlay()) {
            PState s = game.players.get(p);
            if (s.cover != null && s.coverUntil > now) continue;
            game.hud.cover(p, "letterbox", ticks);
        }
    }

    /** 땅울림(sfx.quake): 사람마다 보스 쪽 24블록 안 자리에서. */
    void quakeSound(Boss b, float vol) {
        b.sfxAll("sfx.quake", Sound.ENTITY_WARDEN_EMERGE, vol, 0.6f, 24);
    }

    /** 뿔나팔(sfx.horn): 사람마다 보스 쪽 30블록 안 자리에서. */
    void horn(Boss b, float vol) {
        b.sfxAll("sfx.horn", Sound.EVENT_RAID_HORN, vol, 0.7f, 30);
    }

    // 쓸고 지나가는 큰 그림자(한 번에 하나)
    private Tele sweepTele;
    private Vector sweepFrom, sweepTo;
    private long sweepStart = -1;
    private int sweepLen = 1;

    /**
     * 큰 그림자가 from 에서 to 로 ticks 동안 바닥을 쓸고 지나간다(하늘 보스가 머리 위를 지나갈 때). from 과 to 가 같으면 그 자리에서 커지며 짙어진다
     * (보스 등장 때 중앙 원에 드리우는 그림자). 한 번에 하나(새로 부르면 앞 것은 지움). 맞음 판정은 없다(연출). 판 모델(b1_shadow)이 없을 때의
     * 대체 그림은 움직이는 그림자가 게임 안에서 안 보였다: 움직이는 그림자는 판 모델을 구운 뒤에 쓴다.
     */
    public void shadowSweep(Vector from, Vector to, double radius, int ticks) {
        if (sweepTele != null) sweepTele.remove();
        sweepLen = Math.max(2, ticks);
        sweepTele = Tele.shadow(game, from, radius, sweepLen);
        sweepFrom = from.clone();
        sweepTo = to.clone();
        sweepStart = game.now();
    }

    private void sweepTick(long now) {
        if (sweepTele == null) return;
        long t = now - sweepStart;
        if (!sweepTele.tick() || t > sweepLen) {
            sweepTele.remove();
            sweepTele = null;
            return;
        }
        if (t % 2 == 0 && sweepFrom.distanceSquared(sweepTo) > 1e-6) {
            double f = t / (double) sweepLen;
            sweepTele.move(sweepFrom.clone().add(sweepTo.clone().subtract(sweepFrom).multiply(f)));
        }
    }

    private void sweepClear() {
        if (sweepTele != null) sweepTele.remove();
        sweepTele = null;
    }

    /** 떼의 함성(sfx.horde_cry): 사람마다 사방 두 곳에서. */
    void hordeCry(Boss b, float vol) {
        for (Player p : game.players.inPlay()) {
            for (int i = 0; i < 2; i++) {
                double a = rnd.nextDouble() * Math.PI * 2;
                Location at = p.getLocation().clone().add(Math.cos(a) * 26, 2, Math.sin(a) * 26);
                Props.soundTo(p, at, "sfx.horde_cry", Sound.ENTITY_ZOMBIE_VILLAGER_AMBIENT, vol, 0.9f + rnd.nextFloat() * 0.2f, 0.5f, -1);
            }
        }
    }

    /** 가운데에서 본 그 자리의 월드 yaw(HUD 화살촉: 0 남, 90 서, 180 북, 270 동). */
    static double yawTo(Vector at) {
        double dx = at.getX() - Plaza.CX - 0.5, dz = at.getZ() - Plaza.CZ - 0.5;
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    private int downs() {
        int n = 0;
        for (PState s : game.players.all()) n += s.downs;
        return n;
    }

    private void log(String s) {
        lastLog = s;
        game.plugin.getLogger().info("[판] " + s);
    }

    // ------------------------------------------------------------------ HUD, 상태

    /** HUD 보스 틀(등장 끝부터 죽음 연출 끝까지), 없으면 null. */
    public Frame frame() {
        Boss b = boss;
        if (b == null || b.intro || b.dead || b.removed || (stage != Stage.LIVE && stage != Stage.DOWN)) return null;
        return new Frame(b.nameKey(), b.sp.key(), b.dying ? 0 : b.hpFrac(), b.phase, b.phases(), b.cuts, b.enraged, b.dying);
    }

    /** /gave 상태 의 boss=B1:0.73:p2(없으면 boss=-). */
    @Override
    public String status() {
        Boss b = boss;
        if (b == null || b.removed) return "boss=" + (stage == Stage.SPAWN ? "spawn" : stage == Stage.DOWN ? "wave" : "-");
        return String.format(Locale.ROOT, "boss=%s:%.2f:p%d%s", b.sp.name(), b.dying ? 0 : b.hpFrac(), b.phase, b.dying ? ":down" : b.intro ? ":intro" : "");
    }

    // ------------------------------------------------------------------ 시험 훅(18절)

    /**
     * /gave 시험 보스 &lt;이름|x&gt; &lt;행동&gt; ...: 상태, 빈보스 on|off, 켬|끔, 배율 &lt;값&gt;, 소환 &lt;B1..B5&gt; [회차] [사람수], 체력 &lt;0..1&gt;,
     * 단계 &lt;n&gt;, 공격 &lt;이름&gt; [대상], 멈춤 on|off, 부위 &lt;이름&gt; &lt;0..1&gt;, 죽이기, 화남, 엔딩 [또], 물결, 예고 &lt;원|띠|그림자|안전|파도&gt; [반지름],
     * 사람수 &lt;n|자동&gt;, 균형 [이름 [값]].
     */
    @Override
    public String test(Player p, String[] a) {
        String act = a.length > 3 ? a[3] : "상태";
        Boss b = boss;
        try {
            switch (act) {
                case "상태" -> {
                    return "[보스] stage=" + stage + " enabled=" + enabled + " dummy=" + dummy + " hpScale=" + hpScale + " wave=" + wave + " " + status()
                            + (b == null ? "" : " | " + b.status()) + " | 최근 " + lastLog;
                }
                case "빈보스" -> {
                    dummy = a.length <= 4 || a[4].equals("on") || a[4].equals("켬");
                    return "[보스] 빈보스 " + dummy;
                }
                case "켬", "끔" -> {
                    enabled = act.equals("켬");
                    return "[보스] 보스 웨이브 " + enabled;
                }
                case "배율" -> {
                    hpScale = Math.max(0.001, Double.parseDouble(a[4]));
                    if (b != null && !b.dying) {
                        double f = b.hpFrac();
                        b.maxHp = fightHp(peopleN);
                        b.hp = b.maxHp * f;
                    }
                    return "[보스] 배율 " + hpScale + (b == null ? "" : " hp=" + Math.round(b.hp) + "/" + Math.round(b.maxHp));
                }
                case "사람수" -> {
                    forcedN = a.length > 4 && !a[4].equals("자동") ? Integer.parseInt(a[4]) : -1;
                    recheckAt = 0;
                    return "[보스] 사람수 " + (forcedN > 0 ? forcedN : "자동");
                }
                case "소환" -> {
                    return summonTest(p, a);
                }
                case "엔딩" -> {
                    boolean again = a.length > 4 && a[4].equals("또");
                    startEnding(!again, true);
                    if (game.state == Game.State.WAVE && (stage == Stage.NONE || stage == Stage.DONE)) {
                        wave = Math.max(1, game.wave);
                        rewarded = true;
                        startWave(Plaza.center(game.world).toVector(), Tune.ENDING_WAVE_SPEED, again ? Tune.WAVE_DELAY : 40);
                    }
                    return "[보스] 엔딩 " + (again ? "또" : "첫") + " w=" + endWave;
                }
                case "물결" -> {
                    Vector at = p != null ? p.getLocation().toVector() : Plaza.center(game.world).toVector();
                    if (wave < 0) wave = Math.max(1, game.wave);
                    rewarded = true;
                    startWave(at, a.length > 4 ? Double.parseDouble(a[4]) : Tune.WAVE_SPEED, 0);
                    return "[보스] 물결 at " + Math.round(at.getX()) + "," + Math.round(at.getZ());
                }
                case "예고" -> {
                    return teleTest(p, a);
                }
                case "땅" -> {
                    return terrain.test(p, a);
                }
                case "예고값" -> {
                    // 예고값 [LIFT|BOX] [값]: 대체 그림의 바닥 띄움 높이, 컬링 상자 배율(그리기 확인용)
                    if (a.length > 5 && a[4].equals("LIFT")) Tele.LIFT = Double.parseDouble(a[5]);
                    if (a.length > 5 && a[4].equals("BOX")) Tele.BOX = Float.parseFloat(a[5]);
                    return "[보스] 예고값 LIFT=" + Tele.LIFT + " BOX=" + Tele.BOX;
                }
                case "쓸기" -> {
                    // 쓸기 [반지름] [틱]: 그 사람 앞 30블록에서 그 사람을 지나 뒤 10블록까지 큰 그림자
                    if (p == null) return "[보스] 사람 없음";
                    Vector f = p.getLocation().getDirection().setY(0);
                    if (f.lengthSquared() < 1e-6) f = new Vector(0, 0, 1);
                    f.normalize();
                    Vector me = p.getLocation().toVector();
                    double r = a.length > 4 ? Double.parseDouble(a[4]) : 10;
                    int tk = a.length > 5 ? Integer.parseInt(a[5]) : Tune.SWEEP_TICKS;
                    shadowSweep(me.clone().add(f.clone().multiply(30)), me.clone().subtract(f.clone().multiply(10)), r, tk);
                    return "[보스] 쓸기 r=" + r + " 틱=" + tk;
                }
                case "균형" -> {
                    return tune(a);
                }
                default -> {
                }
            }
            if (b == null || b.removed) return "[보스] 보스 없음(" + act + "): 소환 <B1..B5> 또는 빈보스 on 뒤 gave 웨이브 10";
            switch (act) {
                case "체력" -> {
                    double f = Math.max(0, Math.min(1, Double.parseDouble(a[4])));
                    b.hp = Math.max(1, b.maxHp * f);
                    b.checkPhase();
                    return "[보스] " + b.status();
                }
                case "단계" -> {
                    b.forcePhase(Integer.parseInt(a[4]));
                    return "[보스] " + b.status();
                }
                case "공격" -> {
                    Player who = a.length > 5 ? Bukkit.getPlayerExact(a[5]) : null;
                    boolean ok = a.length > 4 && b.force(a[4], who);
                    StringBuilder names = new StringBuilder();
                    for (Boss.Attack x : b.attacks) names.append(names.length() == 0 ? "" : ",").append(x.name);
                    return "[보스] 공격 " + (a.length > 4 ? a[4] : "?") + (ok ? " 예약" : " 없음") + " (" + names + ")";
                }
                case "멈춤" -> {
                    b.paused = a.length <= 4 || a[4].equals("on");
                    if (b.paused) b.hold();
                    return "[보스] 멈춤 " + b.paused;
                }
                case "부위" -> {
                    Boss.Region r = a.length > 4 ? b.region(a[4]) : null;
                    if (r == null) {
                        StringBuilder rs = new StringBuilder();
                        for (Boss.Region x : b.regions) rs.append(' ').append(x.name);
                        return "[보스] 부위 <이름> <0..1>:" + rs;
                    }
                    r.setLeft(a.length > 5 ? Double.parseDouble(a[5]) : 0);
                    return "[보스] 부위 " + r.name + " " + Math.round(r.left() * 100) + "% cut=" + r.cut;
                }
                case "죽이기" -> {
                    b.die(new Damage(b.hp + 1, DamageType.OTHER, p, b.pos.clone(), null, "test_kill", 0));
                    return "[보스] 죽이기 " + b.status();
                }
                case "화남" -> {
                    b.enrage();
                    return "[보스] 화남";
                }
                default -> {
                    return "[보스] 모르는 행동 " + act;
                }
            }
        } catch (RuntimeException e) {
            return "[보스] " + act + " 실패: " + e;
        }
    }

    /** 소환 &lt;B1..B5&gt; [회차] [사람수]: 지금 웨이브에 그 보스(클래스가 없으면 빈 보스)를 바로 놓음. */
    private String summonTest(Player p, String[] a) {
        if (game.state != Game.State.WAVE) return "[보스] 소환은 웨이브 중에만(대기실이면 gave 시작, 그 뒤 gave 웨이브 n)";
        Species s = a.length > 4 ? Species.of(a[4].toUpperCase(Locale.ROOT)) : null;
        if (s == null || !s.is(Role.BOSS)) return "[보스] 소환 <B1..B5> [회차] [사람수]";
        clear("시험 소환");
        sp = s;
        wave = Math.max(1, game.wave);
        cycle = a.length > 5 ? Math.max(1, Integer.parseInt(a[5])) : cycleOf(Math.max(10, wave));
        if (a.length > 6) forcedN = Integer.parseInt(a[6]);
        boolean was = dummy;
        dummy = true;
        tries = 0;
        stage = Stage.SPAWN;
        rewarded = false;
        trySpawn();
        dummy = was;
        Boss b = boss;
        return b == null ? "[보스] 소환 실패" : String.format(Locale.ROOT, "[보스] boss=%s hp=%.0f N=%d c=%d%s", b.sp.name(), b.maxHp, peopleN, cycle,
                s.implemented() ? "" : " 빈보스");
    }

    /** 예고 &lt;원|띠|그림자|안전|파도&gt; [반지름]: 그 사람 앞 8블록에 예고 하나(보스 없이, 그리기 확인용). */
    private String teleTest(Player p, String[] a) {
        if (p == null) return "[보스] 사람 없음";
        String k = a.length > 4 ? a[4] : "원";
        double r = a.length > 5 ? Double.parseDouble(a[5]) : 4;
        Vector f = p.getLocation().getDirection().setY(0);
        if (f.lengthSquared() < 1e-6) f = new Vector(0, 0, 1);
        f.normalize();
        Vector at = p.getLocation().toVector().add(f.clone().multiply(8));
        Tele t = switch (k) {
            case "띠" -> Tele.strip(game, p.getLocation().toVector().add(f.clone().multiply(3)), at.clone().add(f.clone().multiply(12)), r, 60);
            case "그림자" -> Tele.shadow(game, at, r, 60);
            case "안전" -> Tele.safe(game, at, r, 100);
            case "파도" -> Tele.wave(game, p.getLocation().toVector(), 2, 2 + r * 4, 0.6);
            default -> Tele.disc(game, at, r, 60);
        };
        demo.add(t);
        return "[보스] 예고 " + t.kind + " r=" + r + " 판모델=" + t.model;
    }

    private static String tune(String[] a) {
        try {
            if (a.length > 5) {
                java.lang.reflect.Field f = Tune.class.getField(a[4]);
                if (f.getType() == int.class) f.setInt(null, (int) Double.parseDouble(a[5]));
                else if (f.getType() == double.class) f.setDouble(null, Double.parseDouble(a[5]));
                else if (a.length > 6 && f.getType() == double[].class) {
                    // 표(H): 균형 H <보스 번호 1..5> <값>
                    ((double[]) f.get(null))[Integer.parseInt(a[5]) - 1] = Double.parseDouble(a[6]);
                } else if (a.length > 6 && f.getType() == int[].class) {
                    ((int[]) f.get(null))[Integer.parseInt(a[5]) - 1] = (int) Double.parseDouble(a[6]);
                } else return "[보스] 균형 " + a[4] + ": 표는 '균형 " + a[4] + " <번호> <값>'";
            }
            StringBuilder sb = new StringBuilder("[보스] 균형");
            for (java.lang.reflect.Field f : Tune.class.getFields()) {
                if (a.length > 4 && !f.getName().equals(a[4])) continue;
                Object v = f.get(null);
                String s = v instanceof double[] d ? java.util.Arrays.toString(d) : v instanceof int[] n ? java.util.Arrays.toString(n)
                        : v instanceof double[][] dd ? java.util.Arrays.deepToString(dd) : String.valueOf(v);
                sb.append(' ').append(f.getName()).append('=').append(s);
            }
            return sb.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "[보스] 균형 [이름 [값]]: " + e;
        }
    }
}
