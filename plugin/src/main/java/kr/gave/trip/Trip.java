package kr.gave.trip;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.game.Shop;

/**
 * 원정의 허브(docs/모듈/원정.md 13절): 설정(trip.*), 부품 찾기와 만들기, Game 고리를 부품에 나눠 주기, 막과 스테이지 셈, 부품 사이 물음과 사건,
 * 시험 훅 '원정', /gave 상태 의 trip=.
 *
 * <p>부품은 이름({@link #PARTS})으로 찾아 만든다: 클래스 {@code kr.gave.trip.<이름>} 이 {@link TripPart} 를 구현하고 공개 생성자 (Game, Trip) 이 있으면
 * 쓰고, 없으면 건너뛴다(없는 부품은 지금 판 동작으로 떨어짐). 다른 묶음 작업자는 이 파일과 Game.java 를 고치지 않고 자기 부품 클래스만 올린다.
 * 부품끼리는 {@link #part(String)}, {@link #part(Class)}, {@link #ask(String)}, {@link #mul(String)}, {@link #fire(String, Object)} 로 잇는다.
 * 설정 trip.enabled(기본 true)가 false 면 부품을 하나도 부르지 않는다(지금 판 그대로).
 */
public final class Trip {

    /** 부품 이름(부르는 차례이기도 함). special 은 Rounds 가 다룬다. */
    public static final String[] PARTS = {"Truck", "Revive", "Route", "Travel", "Theme", "Deck", "Rounds", "Launch", "Story"};

    /** 부품 사이 사건 키({@link #fire}). arg: ARRIVE, DEPART = 스테이지 종류 키(String), DEAD, REVIVED = Player, TRUCK_HIT = Double(깎인 양), 나머지 null. */
    public static final String EV_ARRIVE = "arrive", EV_DEPART = "depart", EV_BOSS_DOWN = "bossDown", EV_DEAD = "dead", EV_REVIVED = "revived",
        EV_TRUCK_HIT = "truckHit", EV_TRUCK_DOWN = "truckDown";

    /** 판 끝 까닭({@link #endCause()}). */
    public static final String END_WIPE = "wipe", END_TRUCK = "truck", END_LAUNCH = "launch";

    /** 막의 수, 스테이지의 수(1~15 길 + 16 발사장), 마지막 길 라운드, 엔딩 라운드. */
    public static final int ACTS = 4, STAGES = 16, LAST_ROAD_ROUND = 30, FINAL_ROUND = 35;

    /**
     * 원정 숫자 표 한곳(처음 값: docs/모듈/파워커브.md 6절과 build/curve35/patch.md, 거기 없는 것은 원정.md). 켠 채 바꾸기 '/gave 시험 원정값 x [이름 [값]]'.
     * U = 그 라운드 무기 상자 바탕값({@link Trip#unit()}).
     */
    public static final class Tune {
        /** 트럭 체력(사람 수 n 에 TRUCK_HP x n^TRUCK_HP_POW: 0 이면 사람 수와 무관) */
        public static double TRUCK_HP = 1000, TRUCK_HP_POW = 0;
        /** 감독이 내는 개체 가운데 트럭을 노리는 몫: TRUCK_SHARE + TRUCK_SHARE_PER_WAVE x (라운드 - 1), TRUCK_SHARE_MAX 까지. 목표 압박 역할 종은 늘 트럭 */
        public static double TRUCK_SHARE = 0.10, TRUCK_SHARE_PER_WAVE = 0.005, TRUCK_SHARE_MAX = 0.30;
        /** 트럭이 받는 피해 = 무는 값 x 감독 피해 배율(적은 인원 배율 빼고) x TRUCK_DMG / 사람 수^TRUCK_POW */
        public static double TRUCK_DMG = 0.71, TRUCK_POW = 0.4;
        /** 위협 등급 1..5 의 한 번 무는 값 */
        public static double[] TRUCK_BITE = {1, 1.5, 2, 3, 4};
        /** 무는 간격(틱, Gabe.cd 로 박자 배율을 탐), 동시에 무는 자리 수, 발자국에서 무는 거리(블록) */
        public static double TRUCK_BITE_CD = 30, TRUCK_SLOTS = 16, TRUCK_REACH = 1.2;
        /** 사람을 이 틱 동안 표적으로 못 잡은 개체도 트럭 곁이면 묾, 트럭 노림 개체가 사람에게 맞으면 이 틱 동안 그 사람을 노림 */
        public static double TRUCK_IDLE_TICKS = 40, TRUCK_DISTRACT = 80;
        /** G14 가 다 갉을 때마다(최대 체력 비율), 발자국 3블록 안 폭발의 배수, 6블록 넘는 몸이 닿아 있을 때 초당(최대 체력 비율) */
        public static double TRUCK_GNAW = 0.02, TRUCK_BLAST = 3.0, TRUCK_BIG = 0.01;
        /** 체력 0 뒤 판 끝(OVER)까지 틱(경적, 폭발) */
        public static double TRUCK_DYING = 40;
        /** 수리 한 번: 값 REPAIR_U x U x REPAIR_STEP^(판 전체 누른 수), 회복 = 최대 체력 x REPAIR_FRAC / 사람 수^REPAIR_POW */
        public static double REPAIR_U = 0.3, REPAIR_FRAC = 0.10, REPAIR_POW = 0.8, REPAIR_STEP = 1.0;
        /** 보스 처치 때 트럭 회복(최대 체력 비율) */
        public static double BOSS_REPAIR = 0.30;
        /** 꺼내는 값 = REVIVE_U x U x (1 + REVIVE_STEP x 그 사람이 이미 꺼내진 횟수), REVIVE_MAX x U 까지, x 막 배율 x 깎임 */
        public static double REVIVE_U = 1.0, REVIVE_STEP = 0.25, REVIVE_MAX = 4.0;
        public static double[] REVIVE_ACT = {1.0, 1.15, 1.3, 1.5};
        /** 깎임: 죽은 뒤 라운드가 하나 지날 때마다, 그 바닥, 그 사람 얼굴을 한 게이브를 잡음, 보스를 잡은 쉬는 시간, 갇힌 채 라운드가 끝난 사람, 모든 깎임의 바닥 */
        public static double REVIVE_DECAY = 0.85, REVIVE_DECAY_MIN = 0.4, REVIVE_FACE = 0.7, REVIVE_BOSS = 0.5, REVIVE_TRAPPED = 0.5, REVIVE_FLOOR = 0.1;
        /** 한 번 누름 = 값의 1 / REVIVE_PARTS, 돌아올 때 체력 비율, 무적 틱, '살려 달라' 신호 간격(틱) */
        public static double REVIVE_PARTS = 4, REVIVE_HP = 0.5, REVIVE_SAFE = 60, REVIVE_CALL_EVERY = 600;
        /** 자동 탄약 칸 값(x U, 한 번 사면 판 내내) */
        public static double AUTO_AMMO_U = 0.6;
        // 죽은 사람의 돈 손실은 Players.DEATH_LOSS(0, 예전 판 20% 손실도 2026-11-12 없앰): 모의(tools/balance_sim.py)가 그 자리에서 읽어 그대로 둔다.
        // 라운드가 끝나 쉬는 시간이 되면 죽은 사람도 모두 공짜로 돌아온다(2026-11-12 사용자, Players.reviveFree): 꺼내기 값은 라운드 중에만 쓰인다.
        // 보스를 잡은 순간 바로 꺼내 주는 것은 설정 trip.bossFreeRevive(발사 차례에도 돎), 엔딩 뒤 발사 차례 쉬는 시간에는 공짜 부활이 안 돈다.

        private Tune() {
        }
    }

    public final Game game;
    /** 설정 trip.enabled: false 면 지금 판 그대로(부품을 부르지 않음). */
    public boolean enabled;
    /** 설정 trip.voteKeys: 숫자키 1 2 3 으로도 길 투표(기본 꺼짐). */
    public boolean voteKeys;
    /** 설정 trip.afterLaunch: true 면 발사 뒤 36라운드로 이어 감(기본 false: 발사하면 판 끝). */
    public boolean afterLaunch;
    /** 설정 trip.bossFreeRevive: true 면 보스를 잡을 때 죽은 사람도 모두 공짜로 돌아옴(기본 false: 반값). */
    public boolean bossFreeRevive;
    /** 이번 판 씨앗(startGame 에서 정함, 기록에 남김). 부품은 {@link #rng(String)} 로 갈래마다 따로 굴린다. */
    public long seed;

    private final Map<String, TripPart> parts = new LinkedHashMap<>();
    private final List<String> missing = new ArrayList<>();
    private final Map<String, Long> failedAt = new HashMap<>();
    private String endCause;
    /** 판 중에 '/gave 원정 on|off' 를 쳤을 때 대기실로 돌아가면 바꿀 값(없으면 null). */
    private Boolean wanted;

    public Trip(Game game) {
        this.game = game;
        FileConfiguration c = game.plugin.getConfig();
        enabled = c.getBoolean("trip.enabled", true);
        voteKeys = c.getBoolean("trip.voteKeys", false);
        afterLaunch = c.getBoolean("trip.afterLaunch", false);
        bossFreeRevive = c.getBoolean("trip.bossFreeRevive", false);
        // 설정 trip.tune.<이름>: 숫자 표 덮어쓰기(없는 이름은 기록만)
        if (c.isConfigurationSection("trip.tune")) {
            for (String k : c.getConfigurationSection("trip.tune").getKeys(false)) {
                Object o = c.get("trip.tune." + k);
                String v = o instanceof List<?> l ? String.join(",", l.stream().map(String::valueOf).toList()) : String.valueOf(o);
                String r = tune(k, v);
                if (r == null) game.plugin.getLogger().warning("[원정] 설정 trip.tune 에 모르는 이름: " + k);
            }
        }
        for (String name : PARTS) {
            TripPart p = create(name);
            if (p != null) parts.put(name, p);
            else missing.add(name);
        }
        game.plugin.getLogger().info("[원정] " + (enabled ? "켜짐" : "꺼짐") + " 부품 " + parts.keySet() + (missing.isEmpty() ? "" : " 없음 " + missing));
    }

    private TripPart create(String name) {
        Class<?> c;
        try {
            c = Class.forName("kr.gave.trip." + name, true, Trip.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
        if (!TripPart.class.isAssignableFrom(c)) {
            game.plugin.getLogger().warning("[원정] kr.gave.trip." + name + " 이 TripPart 가 아니다: 건너뜀");
            return null;
        }
        try {
            return (TripPart) c.getConstructor(Game.class, Trip.class).newInstance(game, this);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Throwable t = e.getCause() != null ? e.getCause() : e;
            game.plugin.getLogger().log(Level.WARNING, "[원정] 부품 " + name + " 을 못 만듦: 건너뜀", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ 부품 찾기

    /** 이름의 부품(없거나 못 만들었으면 null). 이름은 {@link #PARTS}. */
    public TripPart part(String name) {
        return parts.get(name);
    }

    /** 이 꼴의 부품(없으면 null). 예: {@code Truck t = trip.part(Truck.class)}. */
    public <T> T part(Class<T> type) {
        for (TripPart p : parts.values()) if (type.isInstance(p)) return type.cast(p);
        return null;
    }

    /** 트럭(원정이 꺼져 있거나 부품이 없으면 null). */
    public Truck truck() {
        return enabled ? part(Truck.class) : null;
    }

    /** 꺼내기(원정이 꺼져 있거나 부품이 없으면 null). */
    public Revive revive() {
        return enabled ? part(Revive.class) : null;
    }

    /** 부품이 있고 원정이 켜져 있나. */
    public boolean has(String name) {
        return enabled && parts.containsKey(name);
    }

    /** 만든 부품 이름(차례대로). */
    public List<String> partNames() {
        return Collections.unmodifiableList(new ArrayList<>(parts.keySet()));
    }

    // ------------------------------------------------------------------ 막, 스테이지(부품이 없어도 라운드 번호로 셈)

    /** 지금 라운드(= Game.wave, 준비 중엔 0). */
    public int round() {
        return game.wave;
    }

    /** 라운드 n 의 막(1..4): 1~10, 11~20, 21~30, 31~. */
    public static int actOf(int n) {
        return n <= 10 ? 1 : n <= 20 ? 2 : n <= 30 ? 3 : 4;
    }

    /** 라운드 n 의 스테이지(1..16): 2라운드가 한 스테이지, 31부터는 16(발사장). */
    public static int stageOf(int n) {
        return n <= 0 ? 1 : n > LAST_ROAD_ROUND ? STAGES : (n + 1) / 2;
    }

    /** 보스 라운드인가(10, 20, 30, 35, 그 뒤는 감독의 보스 차례). */
    public static boolean bossRound(int n) {
        return Director.bossIndex(n) >= 0;
    }

    /** 지금 막(1..4). 부품이 'act' 로 답하면 그 값(이동 중에 먼저 넘기는 식), 아니면 지금 라운드로 셈. */
    public int act() {
        return Math.max(1, Math.min(ACTS, askInt("act", actOf(Math.max(1, game.wave)))));
    }

    /** 지금 스테이지(1..16). 부품이 'stage' 로 답하면 그 값. */
    public int stage() {
        return Math.max(1, Math.min(STAGES, askInt("stage", stageOf(Math.max(1, game.wave)))));
    }

    /** 우주선까지 남은 스테이지 수(스테이지 1 에서 15, 발사장에서 0). */
    public int stagesLeft() {
        return STAGES - stage();
    }

    /** 지금 스테이지 종류 키(Route 가 'stageKind' 로 답함. 없으면 "fight"). */
    public String stageKind() {
        return askStr("stageKind", "fight");
    }

    /** 세계 시각 바탕(Theme 가 'baseTime' 으로 답함. 없으면 지금 판의 13000). */
    public long baseTime() {
        Object o = ask("baseTime");
        return o instanceof Number n ? n.longValue() : 13000L;
    }

    /** U: 이 라운드 무기 상자 바탕값(원, 5 단위. 갉힘, 할인은 안 곱함). 10라운드 190, 20라운드 255, 30라운드 320. */
    public int unit() {
        Shop.Ware w = Shop.Ware.BOX;
        double v = w.base * (1 + w.growth * (Math.max(1, game.wave) - 1));
        return Math.max(5, (int) Math.round(v / 5.0) * 5);
    }

    /** 판에 든 사람 수(나간 사람 빼고, 1 이상). 사람 수 비례 셈에 쓴다. */
    public int people() {
        return Math.max(1, game.players.inPlay().size());
    }

    /** 이번 판 씨앗에서 갈래(salt)마다 따로 굴리는 난수(같은 판, 같은 salt 면 같은 차례). */
    public Random rng(String salt) {
        return new Random(seed * 31 + salt.hashCode());
    }

    /** 죽은 사람이 저절로 돌아오지 않나(원정이 켜져 있고 Revive 부품이 있음). Game.beginBreak 와 Players 가 읽는다. */
    public boolean paidRevive() {
        return has("Revive");
    }

    /** 판 끝 까닭(END_WIPE 서 있는 사람 0, END_TRUCK 트럭 폭발, END_LAUNCH 발사, 판이 안 끝났으면 null). */
    public String endCause() {
        return endCause;
    }

    /** 판 끝 까닭을 적음(Launch 가 발사로 판을 끝낼 때 Game.gameOver() 앞에 부른다). */
    public void endCause(String cause) {
        endCause = cause;
    }

    // ------------------------------------------------------------------ 부품 사이 물음, 배율, 사건

    /** 차례로 물어 처음 null 아닌 답(없으면 null). 키는 {@link TripPart#ask}. */
    public Object ask(String key) {
        if (!enabled) {
            TripPart st = parts.get("Story");
            if (st == null) return null;
            try {
                return st.ask(key);
            } catch (RuntimeException | LinkageError ex) {
                fail("Story", "ask " + key, ex);
                return null;
            }
        }
        for (Map.Entry<String, TripPart> e : parts.entrySet()) {
            try {
                Object o = e.getValue().ask(key);
                if (o != null) return o;
            } catch (RuntimeException | LinkageError ex) {
                fail(e.getKey(), "ask " + key, ex);
            }
        }
        return null;
    }

    public int askInt(String key, int def) {
        Object o = ask(key);
        return o instanceof Number n ? n.intValue() : def;
    }

    public String askStr(String key, String def) {
        Object o = ask(key);
        return o == null ? def : o.toString();
    }

    /** 모든 부품의 숫자 답을 더함(예: 'disp' 디스플레이 수). */
    public double sum(String key) {
        if (!enabled) return 0;
        double s = 0;
        for (Map.Entry<String, TripPart> e : parts.entrySet()) {
            try {
                if (e.getValue().ask(key) instanceof Number n) s += n.doubleValue();
            } catch (RuntimeException | LinkageError ex) {
                fail(e.getKey(), "ask " + key, ex);
            }
        }
        return s;
    }

    /** 모든 부품의 배율을 곱함(꺼져 있거나 답이 없으면 1). 키는 {@link TripPart#mul}. */
    public double mul(String key) {
        if (!enabled) return 1;
        double m = 1;
        for (Map.Entry<String, TripPart> e : parts.entrySet()) {
            try {
                double v = e.getValue().mul(key);
                if (!Double.isNaN(v)) m *= v;
            } catch (RuntimeException | LinkageError ex) {
                fail(e.getKey(), "mul " + key, ex);
            }
        }
        return m;
    }

    /** 상점 값 배율(Shop.price 끝에 곱함): 'price' x 'price.<ware 키>'(예: price.ammo). */
    public double priceMul(Shop.Ware w) {
        return enabled ? mul("price") * mul("price." + w.key()) : 1;
    }

    /** 돕기 동작(좌클릭)을 부품이 가로채나(Players.help 맨 앞에서 부름). 처음 true 를 준 부품에서 멈춘다. 꺼져 있으면 false. */
    public boolean help(Player p) {
        if (!enabled) return false;
        for (Map.Entry<String, TripPart> e : parts.entrySet()) {
            try {
                if (e.getValue().help(p)) return true;
            } catch (RuntimeException | LinkageError ex) {
                fail(e.getKey(), "help", ex);
            }
        }
        return false;
    }

    /** 이동 연출 중인가(Travel 이 ask("travel") 에 진행 0..1 로 답하는 동안). 그동안 상점이 안 열린다. */
    public boolean traveling() {
        return enabled && ask("travel") != null;
    }

    /** 사건을 모든 부품에 돌림(낸 부품도 받는다). 키는 EV_ 상수. */
    public void fire(String event, Object arg) {
        each("on " + event, p -> p.on(event, arg));
    }

    /** 그 사람 화면에 쓸 HUD 값(모든 부품의 {@link TripPart#hud} 를 모음). 공통 값: trip.act, trip.stage, trip.left, trip.kind. 꺼져 있으면 빈 표. */
    public Map<String, Object> hud(Player p) {
        Map<String, Object> out = new HashMap<>();
        if (!enabled) {
            each("hud", part -> part.hud(p, out)); // 원정을 꺼도 도는 인트로의 화면 값만
            return out;
        }
        out.put("trip.act", act());
        out.put("trip.stage", stage());
        out.put("trip.left", stagesLeft());
        out.put("trip.kind", stageKind());
        each("hud", part -> part.hud(p, out));
        return out;
    }

    // ------------------------------------------------------------------ Game 고리(Game 만 부른다)

    public void startGame() {
        endCause = null;
        seed = game.random.nextLong();
        each("startGame", TripPart::startGame);
    }

    public void beforeRound(int n) {
        each("beforeRound", p -> p.beforeRound(n));
    }

    public void beginRound(int n) {
        each("beginRound", p -> p.beginRound(n));
    }

    /** 라운드 끝 판정: 감독 판정에 부품들이 차례로 낀다. */
    public boolean roundDone(boolean directorDone) {
        if (!enabled) return directorDone;
        boolean done = directorDone;
        for (Map.Entry<String, TripPart> e : parts.entrySet()) {
            try {
                done = e.getValue().roundDone(done);
            } catch (RuntimeException | LinkageError ex) {
                fail(e.getKey(), "roundDone", ex);
            }
        }
        // 트럭이 터지는 중(체력 0 뒤 TRUCK_DYING 틱)에는 라운드를 끝내지 않는다: 그 사이 쉬는 시간이 열려 보상과 부활을 다 한 뒤에 판이 끝나지 않게
        Truck t = part(Truck.class);
        if (done && t != null && t.dying()) return false;
        return done;
    }

    /** 판 끝 조건(웨이브 중 매 틱): 부품이 까닭을 대면 true(까닭은 {@link #endCause()}). */
    public boolean gameLost() {
        if (!enabled) return false;
        for (Map.Entry<String, TripPart> e : parts.entrySet()) {
            try {
                String why = e.getValue().lost();
                if (why != null) {
                    endCause = why;
                    return true;
                }
            } catch (RuntimeException | LinkageError ex) {
                fail(e.getKey(), "lost", ex);
            }
        }
        return false;
    }

    /** 트럭이 터져 판을 끝내야 하나(Game.tick 의 판 끝 조건. {@link #gameLost()} 와 같다). */
    public boolean truckDead() {
        return gameLost();
    }

    public void beginBreak(int n) {
        each("beginBreak", p -> p.beginBreak(n));
    }

    /** 쉬는 시간 길이: 감독 값에 부품들이 차례로 낀다. */
    public int breakTicks(int directorTicks) {
        if (!enabled) return directorTicks;
        int t = directorTicks;
        for (Map.Entry<String, TripPart> e : parts.entrySet()) {
            try {
                t = e.getValue().breakTicks(t);
            } catch (RuntimeException | LinkageError ex) {
                fail(e.getKey(), "breakTicks", ex);
            }
        }
        return Math.max(1, t);
    }

    public void tick() {
        // 판 중에 예약한 켜고 끔: 대기실로 돌아온 첫 틱에 바꾼다
        if (wanted != null && game.state == Game.State.LOBBY) {
            boolean on = wanted;
            wanted = null;
            enable(on);
            log("원정 " + (on ? "켬" : "끔") + "(판 중에 예약한 것)");
        }
        each("tick", TripPart::tick);
    }

    public void gameOver() {
        if (endCause == null) endCause = END_WIPE;
        each("gameOver", TripPart::gameOver);
    }

    /** 다시 하기(Game.retry): 부품마다 판 끝에 한 것을 되돌리고(트럭 되살림, 어두워진 하늘) 판 끝 까닭을 지운다. */
    public void retry() {
        each("retry", TripPart::retry);
        endCause = null;
    }

    public void reset() {
        each("reset", TripPart::reset);
        endCause = null;
    }

    /**
     * 운영자 명령 '/gave 원정 on|off': 대기실이면 바로 바꾸고(true), 판이 도는 중이면 그 판은 그대로 두고 대기실로 돌아간 첫 틱에 바꾸게 적어 둔다(false).
     * 판 중에 부품이 반쯤 꺼지거나 켜지는 일이 없게 바꾸는 자리는 늘 대기실이다. 지금 값과 같으면 예약만 지운다.
     */
    public boolean want(boolean on) {
        if (game.state == Game.State.LOBBY) {
            wanted = null;
            return enable(on);
        }
        wanted = on == enabled ? null : on;
        return false;
    }

    /** 대기실로 돌아가면 바꾸기로 적어 둔 값(없으면 null). */
    public Boolean wanted() {
        return wanted;
    }

    /** 켜고 끔(대기실에서만: 명령 '/gave 원정', 시험 훅). 끌 때는 부품 reset 뒤 enabled(false), 켤 때는 enabled(true). */
    public boolean enable(boolean on) {
        if (on == enabled) return true;
        if (game.state != Game.State.LOBBY) return false;
        if (!on) {
            each("reset", TripPart::reset);
            for (Map.Entry<String, TripPart> e : parts.entrySet()) call(e.getKey(), "enabled", p -> p.enabled(false));
            enabled = false;
        } else {
            enabled = true;
            for (Map.Entry<String, TripPart> e : parts.entrySet()) call(e.getKey(), "enabled", p -> p.enabled(true));
        }
        return true;
    }

    private void each(String what, Consumer<TripPart> f) {
        if (!enabled) {
            // 원정을 꺼도 판 시작 인트로는 돈다(2026-11-15 사용자 "원정 아니어도 인트로는 나와야지"): Story 는 모든 고리, 검은 덮개를 쥔 Travel 은
            // 틱과 HUD 만(이동은 원정이 꺼져 있으면 안 열려 덮개만 돈다). 차례는 켜졌을 때와 같다(Travel 다음 Story)
            if (parts.containsKey("Travel") && (what.equals("tick") || what.equals("hud"))) call("Travel", what, f);
            if (parts.containsKey("Story")) call("Story", what, f);
            return;
        }
        for (Map.Entry<String, TripPart> e : parts.entrySet()) call(e.getKey(), what, f);
    }

    private void call(String name, String what, Consumer<TripPart> f) {
        try {
            f.accept(parts.get(name));
        } catch (RuntimeException | LinkageError ex) {
            fail(name, what, ex);
        }
    }

    /** 부품 오류: 부품과 부름마다 10초에 한 번만 기록(판은 계속). */
    private void fail(String name, String what, Throwable ex) {
        String k = name + ":" + what;
        long now = game.now();
        Long last = failedAt.get(k);
        if (last != null && now - last < 200) return;
        failedAt.put(k, now);
        game.plugin.getLogger().log(Level.WARNING, "[원정] 부품 " + name + " " + what + " 오류", ex);
    }

    public void log(String msg) {
        game.plugin.getLogger().info("[원정] " + msg);
    }

    // ------------------------------------------------------------------ 상태, 시험

    /** /gave 상태 의 trip= 값과 그 뒤 부품 글(띄어쓰기로 이음). 꺼져 있으면 off. */
    public String status() {
        if (!enabled) return wanted != null ? "off next=on" : "off";
        StringBuilder sb = new StringBuilder("on act=").append(act()).append(" stage=").append(stage()).append(" kind=").append(stageKind());
        if (wanted != null) sb.append(" next=off");
        if (endCause != null) sb.append(" end=").append(endCause);
        for (Map.Entry<String, TripPart> e : parts.entrySet()) {
            try {
                String s = e.getValue().status();
                if (s != null && !s.isEmpty()) sb.append(' ').append(s);
            } catch (RuntimeException | LinkageError ex) {
                fail(e.getKey(), "status", ex);
            }
        }
        return sb.toString();
    }

    /**
     * 시험 훅. '원정 x 상태'(막, 스테이지, 종류, 부품, 디스플레이 수 disp=, 부품 상태), '원정 x 부품', '원정 x 켬|끔'(대기실에서만),
     * '원정 x 사건 &lt;키&gt; [글]'(fire), '원정 x 물음 &lt;키&gt;', '원정 x 배율 &lt;키&gt;', '원정값 x [이름 [값]]'(숫자 표). 그 밖은 부품들의 test 에 차례로 묻는다.
     */
    public String test(Player p, String[] a) {
        if (a.length < 2) return null;
        if (a[1].equals("원정")) {
            String sub = a.length > 3 ? a[3] : "상태";
            switch (sub) {
                case "상태" -> {
                    return "[원정] trip=" + status() + " round=" + game.wave + " left=" + stagesLeft() + " u=" + unit() + " people=" + people() + " seed=" + seed
                        + " disp=" + (int) sum("disp") + " parts=" + parts.keySet() + (missing.isEmpty() ? "" : " missing=" + missing);
                }
                case "부품" -> {
                    return "[원정] 있음 " + parts.keySet() + " 없음 " + missing + " enabled=" + enabled + " voteKeys=" + voteKeys + " afterLaunch=" + afterLaunch
                        + " bossFreeRevive=" + bossFreeRevive;
                }
                case "켬", "끔" -> {
                    boolean ok = enable(sub.equals("켬"));
                    return "[원정] enabled=" + enabled + (ok ? "" : " (대기실에서만 바꿀 수 있다)");
                }
                case "사건" -> {
                    if (a.length < 5) return "원정 x 사건 <키> [글]";
                    fire(a[4], a.length > 5 ? a[5] : null);
                    return "[원정] 사건 " + a[4];
                }
                case "물음" -> {
                    return a.length < 5 ? "원정 x 물음 <키>" : "[원정] " + a[4] + "=" + ask(a[4]);
                }
                case "배율" -> {
                    return a.length < 5 ? "원정 x 배율 <키>" : "[원정] " + a[4] + String.format(Locale.ROOT, "=%.3f", mul(a[4]));
                }
                case "허드" -> {
                    return p == null ? "없음" : "[원정] hud " + new java.util.TreeMap<>(hud(p));
                }
                default -> {
                    return "원정 x <상태|부품|켬|끔|사건 키 [글]|물음 키|배율 키|허드>";
                }
            }
        }
        if (a[1].equals("원정값")) {
            if (a.length < 4) return "[원정] " + tunes();
            if (a.length < 5) {
                String v = tune(a[3], null);
                return v == null ? "모르는 이름: " + a[3] : "[원정] " + a[3] + "=" + v;
            }
            String v = tune(a[3], a[4]);
            return v == null ? "모르는 이름이거나 못 읽는 값: " + a[3] + " " + a[4] : "[원정] " + a[3] + "=" + v;
        }
        if (!enabled) return null;
        for (Map.Entry<String, TripPart> e : parts.entrySet()) {
            try {
                String r = e.getValue().test(p, a);
                if (r != null) return r;
            } catch (RuntimeException | LinkageError ex) {
                fail(e.getKey(), "test", ex);
                return "[원정] 부품 " + e.getKey() + " 시험 오류: " + ex;
            }
        }
        return null;
    }

    /** 숫자 표 값 하나를 읽거나(value == null) 바꿈. 배열은 쉼표로 이은 값. 모르는 이름, 못 읽는 값이면 null. */
    public static String tune(String name, String value) {
        try {
            Field f = Tune.class.getField(name);
            if (!Modifier.isStatic(f.getModifiers())) return null;
            if (f.getType() == double.class) {
                if (value != null) f.setDouble(null, Double.parseDouble(value));
                return String.valueOf(f.getDouble(null));
            }
            if (f.getType() == double[].class) {
                if (value != null) {
                    String[] parts = value.split(",");
                    double[] v = new double[parts.length];
                    for (int i = 0; i < v.length; i++) v[i] = Double.parseDouble(parts[i].trim());
                    f.set(null, v);
                }
                return java.util.Arrays.toString((double[]) f.get(null));
            }
            return null;
        } catch (ReflectiveOperationException | NumberFormatException e) {
            return null;
        }
    }

    private static String tunes() {
        StringBuilder sb = new StringBuilder();
        for (Field f : Tune.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(f.getName()).append('=').append(tune(f.getName(), null));
        }
        return sb.toString();
    }
}
