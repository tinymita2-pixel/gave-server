package kr.gave.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.gun.ClientClock;
import kr.gave.gun.Gun;
import kr.gave.gun.Guns;
import kr.gave.hud.HudLine;
import kr.gave.hud.HudScreen;
import kr.gave.hud.HudSpec;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.player.Players;

/**
 * 화면 HUD 합성(docs/모듈/HUD.md). 사람마다 보스바 하나의 제목 한 줄(kr.gave.hud.HudScreen)에 글리프를 싣고 리소스팩 core/text 셰이더가
 * 화면 칸으로 옮긴다(tools/make_hud.py 칸 표, pack_hud/hud.glsl). 다른 코드는 보스바를 띄우지 않는다(이 보스바가 첫째여야 자리가 맞음).
 *
 * <p>매 틱 사람마다 칸을 다시 짜고 바뀐 칸이 있을 때만 보낸다. 남의 줄에는 매 틱 바뀌는 값을 넣지 않는다(동료 줄은 상태가 바뀔 때만).
 * 시간이 흐르는 것(준비, 쉬는 시간, 재장전, 출혈, 일으키기, 적중)은 셰이더 시계(timed)로 그려 시작과 끝에만 보낸다.
 *
 * <p>칸: 맨 위 가운데 웨이브 번호와 남은 게이브 막대(쉬는 시간, 준비는 남은 시간 막대), 그 아래 알림 띠(웨이브 시작, 끝 종, 사건, 재난문자 꼴,
 * 방향 알림), 오른쪽 위 재화와 +n, 처음 보는 종, 왼쪽 위 동료 8줄(넘치면 접음), 왼쪽 아래 체력(깎인 몫 흰색), 오른쪽 아래 총(등급 밑줄, +강화,
 * 탄창, 예비, 재장전, 소모품), 가운데 적중, 출혈, 일으키기, 탈출 고리, 방향 화살표, 종 표시와 게이지, 한 줄 도움말, 죽은 이유 카드, 화면 덮개.
 *
 * <p>글귀 키(phraseKey)는 data/phrases/*.json 과 종 이름(name_g01 ..)에서 make_hud 가 그린 그림 글리프 이름이다. 없는 키는 그리지 않고
 * 서버 기록에 한 번 경고한다. 아이콘 인자(icon)는 HUD 그림 이름(ic_m_g05, gun_m_shotgun, it_molotov 같은 것) 또는 종 키(g05)다.
 */
public final class Hud {
    private static final int[] CV = slots("CV", HudSpec.COVER_ROWS), FS = slots("FS", 3), TM = slots("TM", 8), TMB = slots("TMB", 8);
    private static final int WV_BAR = HudLine.slot("WV_BAR"), WV_BAR_TIME = HudLine.slot("WV_BAR_TIME"), WV_NUM = HudLine.slot("WV_NUM"),
        WV_LEFT = HudLine.slot("WV_LEFT"), NT = HudLine.slot("NT"), MONEY = HudLine.slot("MONEY"), MONEY_GAIN = HudLine.slot("MONEY_GAIN"),
        HP_HURT = HudLine.slot("HP_HURT"), HP = HudLine.slot("HP"), HP_LOW = HudLine.slot("HP_LOW"), HP_NUM = HudLine.slot("HP_NUM"),
        W_GUN = HudLine.slot("W_GUN"), W_UL = HudLine.slot("W_UL"), W_RLD = HudLine.slot("W_RLD"), W_MAG = HudLine.slot("W_MAG"),
        W_RES = HudLine.slot("W_RES"), W_INF = HudLine.slot("W_INF"), SHOP = HudLine.slot("SHOP"), PTR_WAVE = HudLine.slot("PTR_WAVE"),
        PTR_POINT = HudLine.slot("PTR_POINT"), PTR_GRAB = HudLine.slot("PTR_GRAB"), PTR_DOWN = HudLine.slot("PTR_DOWN"),
        C_BLEED = HudLine.slot("C_BLEED"), C_BLEED_HOLD = HudLine.slot("C_BLEED_HOLD"), C_REVIVE = HudLine.slot("C_REVIVE"),
        C_ESCAPE = HudLine.slot("C_ESCAPE"), C_HIT = HudLine.slot("C_HIT"), C_KILL = HudLine.slot("C_KILL"), MARK = HudLine.slot("MARK"),
        METER = HudLine.slot("METER"), HINT = HudLine.slot("HINT"), CARD = HudLine.slot("CARD"), CARD2 = HudLine.slot("CARD2");
    /** 보스 틀(docs/모듈/보스.md 7절, 2026-11-12 boss_infra): 깎인 몫, 막대, 문턱 눈금, 단계 점, 이름, 아래로 내린 알림 띠, 큰 제목, 부제, 엔딩 숫자, 약점/안 먹힘 적중. */
    private static final int BS_HURT = HudLine.slot("BS_HURT"), BS_BAR = HudLine.slot("BS_BAR"), BS_MARK = HudLine.slot("BS_MARK"),
        BS_PH = HudLine.slot("BS_PH"), BS_NAME = HudLine.slot("BS_NAME"), NT_LOW = HudLine.slot("NT_LOW"), BS_TITLE = HudLine.slot("BS_TITLE"),
        BS_SUB = HudLine.slot("BS_SUB"), END_NUM = HudLine.slot("END_NUM"), C_WEAK = HudLine.slot("C_WEAK"), C_IMMUNE = HudLine.slot("C_IMMUNE");
    /**
     * 원정 칸(docs/모듈/원정.md 12절, 2026-11-12 trip_core): 트럭 체력(흰 깎인 몫, 지금 몫, 25% 아래 빨강 깜빡)과 아이콘, 트럭 쪽 화살촉, 스테이지 표시와 이름,
     * 길 투표 집계, 죽은 사람의 모금 막대와 글, 검은 화면 한가운데 글 한 줄. 값은 game.trip.hud(p)(원정 부품들이 내놓는 표)에서 읽는다.
     */
    private static final int TRK_HURT = HudLine.slot("TRK_HURT"), TRK_BAR = HudLine.slot("TRK_BAR"), TRK_LOW = HudLine.slot("TRK_LOW"),
        TRK_ICON = HudLine.slot("TRK_ICON"), PTR_TRUCK = HudLine.slot("PTR_TRUCK"), ST_TAG = HudLine.slot("ST_TAG"), ST_NAME = HudLine.slot("ST_NAME"),
        TALLY = HudLine.slot("TALLY"), RV_BAR = HudLine.slot("RV_BAR"), RV_TXT = HudLine.slot("RV_TXT"), STORY = HudLine.slot("STORY");
    /** 원정 라운드 목표(trip_rounds): 목표 아이콘, 차오르는 금빛 막대, 줄어드는 시간 막대, 떨어진 짐 체력. 옅은 화면 테(PState.veil) 칸. 칸이 팩에 없으면 -1(안 그림). */
    private static final int GOAL_IC = HudLine.slot("GOAL_IC"), GOAL_BAR = HudLine.slot("GOAL_BAR"), GOAL_TIME = HudLine.slot("GOAL_TIME"),
        GOAL_CARGO = HudLine.slot("GOAL_CARGO");
    private static final int[] VL = slots("VL", HudSpec.COVER_ROWS);
    /** 보스 막대 폭(bar_boss 텍셀 = GUI), 보내는 단계 수(체력이 이 칸을 넘을 때만 다시 보냄). */
    private static final int BOSS_W = 200, BOSS_STEPS = 64;
    private static final int WHITE = HudLine.pal("WHITE"), GRAY = HudLine.pal("GRAY"), DIM = HudLine.pal("DIM"), GOLD = HudLine.pal("GOLD"),
        RED = HudLine.pal("RED"), RED_BLINK = HudLine.pal("RED_BLINK"), GREEN = HudLine.pal("GREEN"), GHOST = HudLine.pal("GHOST"),
        IMG_DIM = HudLine.pal("IMG_DIM"), PANEL = HudLine.pal("PANEL"), STRIKE = HudLine.pal("STRIKE"), PURPLE = HudLine.pal("PURPLE"),
        PURPLE_BLINK = HudLine.pal("PURPLE_BLINK"), ORANGE = HudLine.pal("ORANGE"), COIN = HudLine.pal("COIN"), ALERT = HudLine.pal("ALERT"),
        PANEL_ALERT = HudLine.pal("PANEL_ALERT"), WHITE_BLINK = HudLine.pal("WHITE_BLINK"), FLASH = HudLine.pal("FLASH"),
        ANTI_FLASH = HudLine.pal("ANTI_FLASH");
    /** 남은 게이브 막대 폭(bar_phase 텍셀), 게이지 폭(bar_meter), 동료 줄 수, 방향 화살표 칸 수(한 바퀴), 한 사람 화살표 수 */
    private static final int BAR_W = 120, METER_W = 40, ROWS = 8, PTR_STEPS = 64, PTR_MAX = 6;
    /** 알림 기본 길이, +n 보이는 틱, 깎인 체력 흰 몫 틱, 죽은 이유 카드 틱, 알림 줄 최대 */
    private static final int NOTICE_TICKS = 60, GAIN_TICKS = 30, LOSS_TICKS = 8, CARD_TICKS = 200, QUEUE_MAX = 4;
    /** 뒤에 사건 알림이 기다릴 때 웨이브 시작 알림 길이 */
    private static final int WAVE_SHORT = 40;
    private static final int N_PLAIN = 0, N_WAVE = 1, N_END = 2, N_ALERT = 3, N_OVER = 4;

    private static final Map<String, Integer> PHRASE = new HashMap<>();
    private static final String[][] PARTS = new String[HudSpec.PHRASES.length][];
    private static final Set<String> COVER_SET = Set.of(HudSpec.COVERS), ICON_SET = Set.of(HudSpec.ICONS);

    static {
        for (int i = 0; i < HudSpec.PHRASES.length; i++) {
            PHRASE.put(HudSpec.PHRASES[i], i);
            PARTS[i] = HudSpec.PHRASE_PARTS[i].split(",");
        }
    }

    public final Game game;
    private final Map<UUID, HudScreen> screens = new HashMap<>();
    private final Map<UUID, View> views = new HashMap<>();
    /** 이 판에 나온 종, 처음 보는 종 안내(아직 안 잡음, 최근 것 먼저) */
    private final Set<Species> seenRound = EnumSet.noneOf(Species.class);
    private final LinkedHashSet<Species> unknown = new LinkedHashSet<>();
    private final Set<String> warned = new HashSet<>();
    private int round = -1, lastWave = -1;
    private Game.State lastState;
    private long now, wt, off;
    /** HUD 틱 수(tick() 첫머리에서 늘림). 틱 사이에 들어온 알림은 앞 틱 값을 단다 */
    private long frame;
    /** 준비, 쉬는 시간 끝(월드 틱, 흔들리지 않게 2틱 넘게 어긋날 때만 바꿈) */
    private long phaseEnd = -1;
    /** 판 끝 기록 알림을 띄울 차례(전멸 연출 director.endTicks() 가 끝난 뒤에 띄움). */
    private boolean overPending;
    /** 남은 수 흉내(G55) 끝(판 틱), 웨이브 방향(월드 yaw) 끝 */
    private long spoofUntil = -1, dirUntil = -1;
    private double[] dirYaws = new double[0];
    /** 시험용 이름표 지우기(감독 변이 대신) */
    private boolean testHideNames;
    /** 시험용 가짜 동료 줄(이름, 상태) */
    private final List<Object[]> fakeRows = new ArrayList<>();
    // 이번 틱 모두 같은 줄
    private HudLine waveBar, waveNum, waveLeft;
    /** 이번 틱 쓰러진 사람이 있나(일으키기 도움말) */
    private boolean anyDowned;
    private int waveBarSlot = -1;
    private final HudLine[] fsLines = new HudLine[3];
    // 보스 틀(모두 같은 줄, 보스가 있을 때만)
    private HudLine bsBar, bsHurt, bsMark, bsPh, bsName;
    private boolean bossFrame;
    private int bossShownK = -1, bossChipK;
    private long bossChipUntil = -1;
    // 등장 큰 제목, 엔딩 카드(모두 같은 줄)
    private HudLine titleLine, subLine, endNumLine;
    private long titleUntil = -1;

    /** 사람 하나의 HUD 상태 */
    private static final class View {
        long hitEnd = -1, killEnd = -1, weakEnd = -1, immuneEnd = -1;
        int gain;
        long gainUntil = -1;
        final ArrayDeque<Notice> queue = new ArrayDeque<>();
        Notice cur;
        long curUntil;
        Card card;
        String cover;
        long coverUntil;
        double lastHp = -1;
        int lossCells;
        long lossUntil = -1;
        String mark;
        long markUntil = -1;
        String meter;
        double meterFrac;
        long meterUntil = -1;
        long blinkUntil = -1;
        /** 한 줄 도움말: 가운데가 비면(죽은 이유 카드가 없으면) 그때부터 hintLen 틱. hintUntil &lt; 0 이면 아직 못 띄움, hintBy 까지 기다림 */
        String hint;
        int hintLen;
        long hintUntil = -1, hintBy = -1;
        final Map<String, Integer> hinted = new HashMap<>();
        /** 화살표 각도 칸(사람 UUID, 종이 가리킨 곳 "p0".., 웨이브 방향 "w0"..) */
        final Map<Object, Integer> ptrStep = new HashMap<>();
        final List<Point> points = new ArrayList<>();
        Life lastLife;
        boolean lastGrabbed, lastCanOpen;
    }

    /** 알림 하나. frame = 넣은 HUD 틱(웨이브 시작 알림이 이번 틱에 들어온 사건 알림은 남기고 지난 것만 밀어내게) */
    private record Notice(String key, String icon, int num, int level, int style, int ticks, long frame) {
        boolean same(Notice o) {
            return key.equals(o.key) && java.util.Objects.equals(icon, o.icon) && num == o.num && level == o.level && style == o.style;
        }

        Notice withTicks(int t) {
            return new Notice(key, icon, num, level, style, t, frame);
        }
    }

    private record Card(Species sp, String cause, long until) {
    }

    private record Point(Vector at, long until) {
    }

    private Notice nt(String key, String icon, int num, int level, int style, int ticks) {
        return new Notice(key, icon, num, level, style, ticks, frame);
    }

    public Hud(Game game) {
        this.game = game;
    }

    private static int[] slots(String prefix, int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = HudLine.slot(prefix + i);
        return out;
    }

    // ------------------------------------------------------------------ 틱

    public void tick() {
        frame++;
        now = game.now();
        wt = game.worldTime();
        off = wt - now;
        if (game.round != round) {
            round = game.round;
            newRound();
        }
        edges();
        if (overPending && game.state == Game.State.OVER && game.stateTicks() >= game.director.endTicks()) {
            overPending = false;
            int left = (int) Math.max(40, game.overTicks() - game.stateTicks());
            // 원정: 판 끝 연출(떼가 원을 메움) 뒤에는 맵 전환과 같은 검은 화면 + 가운데 한 줄('모두 게이브가 되었다')로 대기실까지
            if (game.trip.enabled && hasCover("black") && hasPhrase(overKey())) story(List.of(overKey()), left, 0);
            else pushAll(nt(overKey(), null, -1, 0, N_OVER, left));
        }
        autoSeen();
        shared();
        for (Player p : Bukkit.getOnlinePlayers()) {
            HudScreen s = screens.computeIfAbsent(p.getUniqueId(), k -> new HudScreen());
            if (!game.inGame(p)) {
                s.hide(p);
                continue;
            }
            s.show(p);
            s.reset();
            compose(p, view(p.getUniqueId()), s);
            s.flush();
        }
        screens.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        views.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private View view(UUID id) {
        return views.computeIfAbsent(id, k -> new View());
    }

    private void newRound() {
        seenRound.clear();
        unknown.clear();
        for (View v : views.values()) v.hinted.clear();
    }

    /** 판 상태가 바뀐 순간의 알림(웨이브 시작, 끝 종, 판 끝) */
    private void edges() {
        Game.State st = game.state;
        if (st != lastState || (st == Game.State.WAVE && game.wave != lastWave)) {
            phaseEnd = -1;
            switch (st) {
                case WAVE -> pushAll(nt("wave_start", null, game.wave, 0, N_WAVE, NOTICE_TICKS));
                case BREAK -> {
                    if (lastState == Game.State.WAVE) pushAll(nt("wave_clear", "bell", -1, 0, N_END, NOTICE_TICKS));
                }
                case OVER -> overPending = true;
                default -> {
                }
            }
            if (st != Game.State.WAVE) spoofUntil = -1;
            if (st != Game.State.OVER) overPending = false;
            lastState = st;
            lastWave = game.wave;
        }
    }

    /** 처음 나온 종을 저절로 안내(흉내 중인 것, 맞지 않는 것은 드러날 때까지 기다림) */
    private void autoSeen() {
        // 이름표 지우기 변이 중에는 새로 올리지 않는다(풀린 뒤 처음 보일 때 안내)
        if (game.state == Game.State.LOBBY || namesHidden()) return;
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.disguised || g.untargetable || g.sp.is(Role.BOSS)) continue;
            if (seenRound.add(g.sp)) unknown.add(g.sp);
        }
    }

    /** 지금 보스 틀(보스 감독), 없으면 null. */
    private kr.gave.mob.boss.BossDirector.Frame bossFrameNow() {
        try {
            return game.director.bosses == null ? null : game.director.bosses.frame();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 보스 틀 줄(7.1): 막대(64 단계), 깎인 흰 몫(LOSS_TICKS), 문턱 눈금, 단계 점, 이름(화남 빨강 깜빡, 죽음 연출 회색). */
    private void bossLines(kr.gave.mob.boss.BossDirector.Frame f) {
        int k = (int) Math.round(Math.max(0, Math.min(1, f.frac())) * BOSS_STEPS);
        if (bossShownK >= 0 && k < bossShownK) {
            bossChipK = now < bossChipUntil ? Math.max(bossChipK, bossShownK) : bossShownK;
            bossChipUntil = now + LOSS_TICKS;
        }
        bossShownK = k;
        int under = now < bossChipUntil ? Math.max(bossChipK, k) : k;
        bsHurt = new HudLine().shape("bar_boss", HudLine.cells((int) Math.round(under * BOSS_W / (double) BOSS_STEPS), BOSS_W));
        bsBar = new HudLine().shape("bar_boss", HudLine.cells((int) Math.round(k * BOSS_W / (double) BOSS_STEPS), BOSS_W));
        HudLine mk = new HudLine();
        double[] cuts = f.cuts() == null ? new double[0] : f.cuts();
        for (double c : cuts) mk.at((int) Math.round(BOSS_W * c)).img("bs_notch", WHITE);
        bsMark = mk;
        HudLine ph = new HudLine();
        for (int i = 0; i < f.phases(); i++) ph.at(6 * i).img("pip", i < f.phase() ? WHITE : GHOST);
        bsPh = ph;
        String nk = hasPhrase(f.nameKey()) ? f.nameKey() : "name_" + f.key();
        int pal = f.dying() ? GRAY : f.enraged() ? RED_BLINK : WHITE;
        bsName = phrase(new HudLine().at(-(phraseWidth(nk) / 2)), nk, pal);
    }

    /** 모두 같은 줄: 웨이브 막대, 번호, 남은 수, 처음 보는 종 */
    private void shared() {
        waveBar = waveNum = waveLeft = null;
        waveBarSlot = -1;
        bsBar = bsHurt = bsMark = bsPh = bsName = null;
        anyDowned = game.state != Game.State.LOBBY && !game.players.downed().isEmpty();
        kr.gave.mob.boss.BossDirector.Frame bf = game.state == Game.State.WAVE ? bossFrameNow() : null;
        bossFrame = bf != null;
        if (!bossFrame) {
            bossShownK = -1;
            bossChipUntil = -1;
        }
        switch (game.state) {
            case PREP -> timeBar(game.prepSeconds * 20, 1);
            case BREAK -> {
                timeBar(game.director.breakTicks(), game.wave + 1);
                // 다음 웨이브가 보스면 숫자 빨강 + 그 보스 아이콘(7.3절)
                Director.Plan np = game.director.nextPlan();
                if (np != null && np.boss) waveNum = bossNumber(game.wave + 1, RED, kr.gave.mob.boss.BossDirector.bossFor(game.wave + 1));
            }
            case WAVE -> {
                int total = game.director.total(), left = game.director.remaining();
                double f = total <= 0 ? 1 : Math.min(1, left / (double) total);
                waveBarSlot = WV_BAR;
                boolean bossWave = game.director.bossWave();
                boolean gold = game.director.bosses != null && game.director.bosses.endingSeen();
                waveNum = number(game.wave, bossWave ? RED : gold ? GOLD : WHITE);
                if (bf != null) {
                    // 보스 틀이 있는 동안 웨이브 막대와 남은 수는 숨김(목표는 보스)
                    bossLines(bf);
                } else if (now < spoofUntil) {
                    // 가짜 종소리(G55): 막대 비고 0, 진짜 수는 0.5초마다 잠깐 비침(팔레트 효과라 보내지 않음)
                    waveBar = new HudLine().shape("bar_phase", 0);
                    waveLeft = new HudLine().text(HudLine.S, "0", ANTI_FLASH).at(0).text(HudLine.S, Integer.toString(left), FLASH);
                } else {
                    waveBar = new HudLine().shape("bar_phase", HudLine.cells((int) Math.ceil(f * BAR_W), BAR_W));
                    waveLeft = new HudLine().text(HudLine.S, Integer.toString(left), GRAY);
                }
            }
            case OVER -> waveNum = number(game.wave, GOLD);
            default -> {
            }
        }
        // 처음 보는 종(최근 것 먼저) 오른쪽 위 세 줄: [이름][아이콘]. 이름표 지우기 변이 중에는 아이콘만
        List<Species> list = new ArrayList<>(unknown);
        boolean hide = namesHidden();
        for (int i = 0; i < fsLines.length; i++) {
            fsLines[i] = null;
            int k = list.size() - 1 - i;
            if (k < 0) continue;
            Species sp = list.get(k);
            String ic = icon("m", sp);
            int iw = HudLine.ink(ic);
            HudLine l = new HudLine().at(-iw).img(ic, WHITE);
            String nk = "name_" + sp.key();
            if (!hide) phrase(l.at(-iw - 3 - phraseWidth(nk)), nk, WHITE);
            fsLines[i] = l;
        }
    }

    /** 감독의 이름표 지우기 변이(5웨이브 한정) 중: 종 이름을 그리지 않는다 */
    private boolean namesHidden() {
        if (testHideNames) return true;
        try {
            return game.director.hideNames();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 준비, 쉬는 시간: 줄어드는 남은 시간 막대(셰이더 시계), 다음 웨이브 번호 흐리게, 마지막 5초는 초를 금색으로 */
    private void timeBar(int len, int nextWave) {
        long cand = wt + Math.max(0, len - game.stateTicks());
        if (phaseEnd < 0 || Math.abs(cand - phaseEnd) > 2) phaseEnd = cand;
        waveBarSlot = WV_BAR_TIME;
        waveBar = new HudLine().timed("bar_phase", phaseEnd, Math.max(1, len));
        waveNum = number(nextWave, GRAY);
        long left = phaseEnd - wt;
        if (left > 0 && left <= 100) waveLeft = new HudLine().text(HudLine.S, Long.toString((left + 19) / 20), GOLD);
    }

    private static HudLine number(int n, int pal) {
        String s = Integer.toString(Math.max(0, n));
        return new HudLine().at(-((HudLine.width(HudLine.M, s) - 1) / 2)).text(HudLine.M, s, pal);
    }

    /** 웨이브 숫자(가운데) + 오른쪽에 보스 아이콘(그 보스 아이콘이 없으면 공용 보스 그림 ic_boss, 그것도 없으면 기본 얼굴). */
    private static HudLine bossNumber(int n, int pal, Species boss) {
        String s = Integer.toString(Math.max(0, n));
        int w = HudLine.width(HudLine.M, s) - 1;
        HudLine l = new HudLine().at(-(w / 2)).text(HudLine.M, s, pal);
        String ic = boss != null && hasIcon(boss) ? "ic_s_" + boss.key() : HudLine.has("ic_boss") ? "ic_boss" : "ic_s_default";
        return l.at(w - w / 2 + 4).img(ic, WHITE);
    }

    private void compose(Player p, View v, HudScreen s) {
        PState st = game.players.get(p.getUniqueId());
        // 원정: 부품들이 내놓는 값(꺼져 있으면 빈 표). 검은 화면(맵 전환, 엔딩)이면 덮개와 가운데 글 한 줄만 그리고 나머지 HUD 는 가린다
        Map<String, Object> tv = game.trip.hud(p);
        // 이동 연출(trip_route 의 Travel)은 다 검은 동안 screen.black, screen.line 을 내놓는다(검어지고 밝아지는 흐림은 Travel 이 제목 글로)
        String line = storyActive() ? storyLine() : tv.get("screen.line") instanceof String k ? k : null;
        boolean black = now < blackUntil || storyActive() || Boolean.TRUE.equals(tv.get("screen.black"));
        // 인트로(kr.gave.trip.Story): screen.alt = 0.5초마다 잠깐 비치는 다른 줄(본 줄과 같은 왼쪽 끝에서 시작해 바뀐 글자만 어긋나 깜빡인다. 팔레트 효과라
        // 다시 보내는 것이 없다), screen.cover = 검은 화면 위에 얹는 덮개 그림(맨 위 VL 칸: 글 위에 그려진다)
        if (line != null && hasPhrase(line)) {
            String altKey = tv.get("screen.alt") instanceof String ak && hasPhrase(ak) ? ak : null;
            int x0 = -(phraseWidth(line) / 2);
            HudLine l = phrase(new HudLine().at(x0), line, altKey == null ? WHITE : ANTI_FLASH);
            if (altKey != null) phrase(l.at(x0), altKey, FLASH);
            s.set(STORY, l);
        }
        if (black && hasCover("black")) {
            drawCover("black", s);
            if (tv.get("screen.cover") instanceof String ck && hasCover(ck) && VL.length > 0 && VL[0] >= 0) drawCover(ck, s, VL, WHITE);
            // 원정 엔딩 크레딧(trip_launch: Launch 의 hud 값 credits.lines = 지금 쪽 이름 줄, 위에서 아래로): 검은 화면 가운데 칸 넷에 바닐라 글자로
            if (tv.get("credits.lines") instanceof List<?> cl) {
                int[] rows = {BS_TITLE, BS_SUB, END_NUM, STORY};
                for (int i = 0; i < cl.size() && i < rows.length; i++) {
                    String t = String.valueOf(cl.get(i));
                    s.set(rows[i], new HudLine().at(-(HudLine.width(HudLine.N, t) / 2)).text(HudLine.N, t, WHITE));
                }
            }
            return;
        }
        // 원정 발사 초읽기(trip_launch: hud 값 launch.count 10..1): 큰 숫자
        if (tv.get("launch.count") instanceof Integer cn && cn > 0) s.set(BS_TITLE, number(cn, cn <= 3 ? GOLD : WHITE));
        boolean slit = v.cover == null && st != null && st.life == Life.DEAD && game.trip.paidRevive() && game.state != Game.State.OVER && hasCover("slit");
        if (slit) drawCover("slit", s);
        else cover(v, s);
        veil(st, s);
        notice(v, s);
        boolean play = st != null && st.life != Life.LOBBY && game.state != Game.State.LOBBY;
        if (!play) return;
        trip(p, st, v, s, tv);
        // 보스 등장 큰 제목과 부제, 엔딩 카드(모두 같은 줄)
        if (now < titleUntil) {
            if (titleLine != null) s.set(BS_TITLE, titleLine);
            if (subLine != null) s.set(BS_SUB, subLine);
            if (endNumLine != null) s.set(END_NUM, endNumLine);
        }
        if (waveBar != null && !bossFrame) s.set(waveBarSlot, waveBar);
        if (waveNum != null) s.set(WV_NUM, waveNum);
        if (waveLeft != null && !bossFrame) s.set(WV_LEFT, waveLeft);
        // 원정 발사 연출(B5 가 쓰러진 뒤 탑승~엔딩, launch.phase): 웨이브 숫자는 마지막 웨이브(35) 그대로, 쉬는 시간 막대와 초는 숨김(탑승 막대는 goal 이 그림)
        if (tv.get("launch.phase") instanceof String) {
            s.set(WV_NUM, number(game.wave, GOLD));
            if (game.state == Game.State.BREAK) {
                s.set(WV_BAR_TIME, null);
                s.set(WV_LEFT, null);
            }
        }
        // 원정 라운드 목표(버티기, 자리, 줍기 ...): 웨이브 막대 자리를 목표 막대로
        if (!bossFrame) goal(s, tv);
        if (bossFrame) {
            s.set(BS_HURT, bsHurt);
            s.set(BS_BAR, bsBar);
            s.set(BS_MARK, bsMark);
            s.set(BS_PH, bsPh);
            s.set(BS_NAME, bsName);
        }
        // 처음 본 종 목록은 길 투표 판이 떠 있는 동안 숨김(셋째 갈래 오른쪽 끝과 포개짐)
        boolean voting = tv.get("route.fork") instanceof String fork && !fork.isEmpty();
        if (!voting) for (int i = 0; i < fsLines.length; i++) if (fsLines[i] != null) s.set(FS[i], fsLines[i]);
        money(st, v, s);
        roster(p, v, s);
        edgesFor(p, st, v);
        switch (st.life) {
            case ALIVE -> {
                health(st, v, s);
                weapon(p, s);
                shop(p, v, s);
                if (st.helping != null) s.set(C_REVIVE, new HudLine().at(-20).timed("ring_a", st.helpUntil + off, (int) Math.max(1, st.helpUntil - st.helpStart)));
                if (st.grabbedBy != null) s.set(C_ESCAPE, new HudLine().at(-24).shape("ring_b", Math.max(0, game.players.escapeFrac(p))));
                pointers(p, v, s);
            }
            case DOWNED -> {
                s.set(HP_HURT, new HudLine().shape("bar_hp", 0));
                downed(st, s);
            }
            default -> {
            }
        }
        if (v.hitEnd > wt - 2) s.set(C_HIT, new HudLine().at(-12).timed("hit", v.hitEnd, HudSpec.HIT_TICKS));
        if (v.killEnd > wt - 2) s.set(C_KILL, new HudLine().at(-12).timed("hit", v.killEnd, HudSpec.HIT_TICKS));
        if (v.weakEnd > wt - 2) s.set(C_WEAK, new HudLine().at(-12).timed("hit", v.weakEnd, HudSpec.HIT_TICKS));
        if (v.immuneEnd > wt - 2) s.set(C_IMMUNE, new HudLine().at(-12).timed("hit", v.immuneEnd, HudSpec.HIT_TICKS));
        waveDirs(p, s);
        marks(v, s);
        if (v.card != null && (now >= v.card.until || st.life == Life.ALIVE || st.life == Life.TRAPPED)) v.card = null;
        if (v.card != null) card(v.card, s);
        else if (v.hint != null) {
            // 도움말은 가운데가 빈 뒤에야 시간이 흐른다(카드 밑에서 다 지나가 버리지 않게)
            if (v.hintUntil < 0) {
                if (now > v.hintBy) v.hint = null;
                else v.hintUntil = now + v.hintLen;
            }
            if (v.hint != null && now < v.hintUntil) s.set(HINT, panelPhrase(v.hint, WHITE));
            else v.hint = null;
        }
    }

    /** 도움말이 떠 있거나 띄울 차례를 기다리는 중 */
    private boolean hintBusy(View v) {
        return v.hint != null && (v.hintUntil < 0 ? now <= v.hintBy : now < v.hintUntil);
    }

    /** 상태가 바뀐 순간: 처음 쓰는 순간의 한 줄 도움말, 살아나면 카드와 흰 몫 지움 */
    private void edgesFor(Player p, PState st, View v) {
        boolean grabbed = st.grabbedBy != null;
        if (grabbed && !v.lastGrabbed) hint(v, "hint_escape", 60, 2);
        if (st.life != v.lastLife) {
            // 죽은 사람은 쉬는 시간에 모두 공짜로 일어난다(2026-11-12 사용자, 원정도: 라운드 중 꺼내기는 그대로). 엔딩 라운드(35)는 발사 차례라 안 띄움
            if (st.life == Life.DEAD && !(game.trip.has("Launch") && game.wave == kr.gave.trip.Trip.FINAL_ROUND)) hint(v, "dead_wait", 80, 1);
            if (st.life == Life.TRAPPED) hint(v, "trapped", 80, 2);
            if (st.life == Life.ALIVE) v.lossUntil = -1;
            v.lastHp = st.hp;
        }
        v.lastGrabbed = grabbed;
        v.lastLife = st.life;
        if (st.life == Life.ALIVE && !hintBusy(v)) {
            boolean can = game.state != Game.State.WAVE && game.shop.canOpen(p);
            if (can && !v.lastCanOpen) hint(v, "hint_shop", 60, 1);
            v.lastCanOpen = can;
            if (anyDowned) hint(v, "hint_revive", 60, 1);
        }
    }

    private boolean hint(View v, String key, int ticks, int max) {
        int n = v.hinted.getOrDefault(key, 0);
        if (n >= max || !hasPhrase(key)) return false;
        v.hinted.put(key, n + 1);
        v.hint = key;
        v.hintLen = Math.max(1, ticks);
        v.hintUntil = -1;
        v.hintBy = game.now() + CARD_TICKS + 20;
        return true;
    }

    // ------------------------------------------------------------------ 칸

    private void cover(View v, HudScreen s) {
        if (v.cover != null && now >= v.coverUntil) v.cover = null;
        if (v.cover == null) return;
        drawCover(v.cover, s);
    }

    /** 덮개 조각을 화면 칸에 놓음(키는 HudSpec.COVERS 에 있는 것). */
    private void drawCover(String key, HudScreen s) {
        drawCover(key, s, CV, WHITE);
    }

    /** 덮개 조각을 칸 묶음 slots 에 팔레트 pal 로(옅은 화면 테는 VL 칸에 IMG_DIM). */
    private void drawCover(String key, HudScreen s, int[] slots, int pal) {
        HudLine[] rows = new HudLine[slots.length];
        for (int t = 0; t < HudSpec.COVER_COLS * HudSpec.COVER_ROWS; t++) {
            String n = "cv_" + key + "_" + t;
            if (!HudLine.has(n)) continue;
            int r = t / HudSpec.COVER_COLS, c = t % HudSpec.COVER_COLS;
            if (rows[r] == null) rows[r] = new HudLine();
            rows[r].at(HudSpec.COVER_X0 + HudSpec.COVER_STEP * c).img(n, pal);
        }
        for (int r = 0; r < rows.length; r++) s.set(slots[r], rows[r]);
    }

    /** 옅은 화면 테(Players.veil: 덮개와 따로 하나, sp_engine VEIL). 팩에 VL 칸이나 그 덮개가 없으면 안 그림. */
    private void veil(PState st, HudScreen s) {
        if (st == null || st.veil == null || st.veilUntil <= game.now() || VL.length == 0 || VL[0] < 0 || !hasCover(st.veil)) return;
        drawCover(st.veil, s, VL, IMG_DIM);
    }

    private void notice(View v, HudScreen s) {
        if (v.cur != null && now >= v.curUntil) v.cur = null;
        if (v.cur == null && !v.queue.isEmpty()) {
            v.cur = v.queue.poll();
            v.curUntil = now + v.cur.ticks;
        }
        // 보스 틀이 있는 동안은 알림 띠를 틀 아래(NT_LOW)로
        if (v.cur != null) s.set(bossFrame && NT_LOW >= 0 ? NT_LOW : NT, noticeLine(v.cur));
    }

    /** 알림 띠 한 줄(가운데 맞춤): [아이콘][글귀][수] 또는 재난문자 꼴 [붉은 머리 !][글귀][짙기 칸] */
    private HudLine noticeLine(Notice n) {
        HudLine l = new HudLine();
        int pw = phraseWidth(n.key);
        String ic = n.icon == null ? null : iconImage(n.icon, "m");
        int iw = ic == null ? 0 : HudLine.ink(ic);
        String num = n.num >= 0 ? Integer.toString(n.num) : null;
        int nw = num == null ? 0 : HudLine.width(HudLine.M, num) - 1;
        if (n.style == N_ALERT) {
            int lead = 18, pips = n.level > 0 ? 6 + 5 * 6 - 2 : 0;
            int w = lead + pw + pips, x0 = -(w / 2);
            l.at(x0 - 4).rect("band", w + 8, PANEL_ALERT);
            l.at(x0 - 4).rect("band", lead, ALERT);
            l.at(x0 - 4 + (lead - HudLine.ink("alert")) / 2).img("alert", WHITE);
            phrase(l.at(x0 + lead), n.key, WHITE);
            for (int i = 0; i < 5 && n.level > 0; i++) l.at(x0 + lead + pw + 6 + 6 * i).img("pip", i < n.level ? (n.level >= 5 ? RED : ORANGE) : GHOST);
            return l;
        }
        int w = (iw > 0 ? iw + 4 : 0) + pw + (nw > 0 ? (pw > 0 ? 5 : 0) + nw : 0), x0 = -(w / 2);
        l.at(x0 - 5).rect("band", w + 10, PANEL);
        int x = x0;
        if (ic != null) {
            l.at(x).img(ic, n.style == N_END ? GREEN : WHITE);
            x += iw + 4;
        }
        phrase(l.at(x), n.key, n.style == N_OVER ? RED : WHITE);
        x += pw;
        if (num != null) l.at(x + (pw > 0 ? 5 : 0)).text(HudLine.M, num, GOLD);
        return l;
    }

    private void money(PState st, View v, HudScreen s) {
        String m = Integer.toString(st.money);
        HudLine l = new HudLine().textRight(0, HudLine.M, m, COIN);
        l.at(-(HudLine.width(HudLine.M, m) - 1) - 3 - HudLine.ink("coin")).img("coin", WHITE);
        s.set(MONEY, l);
        if (now < v.gainUntil) s.set(MONEY_GAIN, new HudLine().textRight(0, HudLine.S, "+" + v.gain, GREEN));
    }

    /** 왼쪽 위 동료: 쓰러짐(빨강 깜빡, 출혈 막대, 일으키는 중이면 초록), 갇힘(보라 + 먹은 종), 붙잡힘(보라 깜빡), 죽음(흐림 + 줄), 서 있음(나는 금색) */
    private void roster(Player viewer, View v, HudScreen s) {
        List<PState> all = new ArrayList<>();
        for (PState x : game.players.all()) if (x.life != Life.LOBBY && !x.offline) all.add(x);
        UUID me = viewer.getUniqueId();
        all.sort(Comparator.comparingInt((PState x) -> rank(x, me)).thenComparing(x -> x.name == null ? "" : x.name));
        List<Object[]> rows = new ArrayList<>();
        for (PState x : all) rows.add(new Object[] {x.name, x});
        for (Object[] f : fakeRows) rows.add(f);
        int standing = 0;
        for (Object[] r : rows) if (r[1] instanceof PState x ? x.life == Life.ALIVE : r[1] == Life.ALIVE) standing++;
        boolean fold = rows.size() > ROWS;
        boolean blink = now < v.blinkUntil;
        int i = 0;
        for (Object[] r : rows) {
            if (i >= (fold ? ROWS - 1 : ROWS)) break;
            PState x = r[1] instanceof PState ps ? ps : null;
            Life life = x != null ? x.life : (Life) r[1];
            boolean grabbed = x != null && x.grabbedBy != null && life == Life.ALIVE;
            if (fold && life != Life.DOWNED && life != Life.TRAPPED && !grabbed) continue;
            String name = r[0] == null ? "?" : (String) r[0];
            boolean self = x != null && x.id.equals(me);
            int pal = switch (life) {
                case DOWNED -> x != null && x.reviver != null ? GREEN : RED_BLINK;
                case TRAPPED -> PURPLE;
                case DEAD -> DIM;
                case WAITING -> GHOST;
                default -> grabbed ? PURPLE_BLINK : self ? GOLD : blink ? WHITE_BLINK : WHITE;
            };
            int w = HudLine.width(HudLine.N, name);
            HudLine l = new HudLine().text(HudLine.N, name, pal);
            if (life == Life.DEAD && game.trip.paidRevive() && x != null) {
                // 원정: 죽은 사람은 게이브가 되었다(이름 흐림 + 그 게이브 아이콘, '살려 달라' 신호 3초 동안 깜빡)
                boolean calling = now - x.callAt < 60;
                l = new HudLine().text(HudLine.N, name, calling ? WHITE_BLINK : DIM).at(w + 2).img(icon("s", x.deathResult != null ? x.deathResult : Species.G38), calling ? WHITE_BLINK : IMG_DIM);
            } else if (life == Life.DEAD) l.at(-1).rect("strike", w, STRIKE);
            if (life == Life.TRAPPED && x != null && x.eater != null) l.at(w + 2).img(icon("s", x.eater.sp), WHITE);
            s.set(TM[i], l);
            if (life == Life.DOWNED && x != null && x.reviver == null)
                s.set(TMB[i], new HudLine().at(w + 2).timed("bar_mini", x.bleedUntil + off, Players.BLEED + Math.max(0, x.bleedBonus)));
            i++;
        }
        if (fold) {
            s.set(TM[i], new HudLine().img("person", blink ? WHITE_BLINK : WHITE).at(10)
                .text(HudLine.S, standing + "/" + rows.size(), blink ? WHITE_BLINK : WHITE));
        }
    }

    private static int rank(PState x, UUID me) {
        return switch (x.life) {
            case DOWNED -> 0;
            case TRAPPED -> 1;
            case ALIVE -> x.grabbedBy != null ? 2 : x.id.equals(me) ? 4 : 5;
            case DEAD -> 3;
            default -> 6;
        };
    }

    /** 왼쪽 아래 체력: 밑 막대(흰 = 방금 깎인 몫, 그 뒤 빈 몫), 위 막대(지금, 25% 아래 빨강 깜빡), 숫자 */
    private void health(PState st, View v, HudScreen s) {
        double max = Math.max(1, st.maxHp);
        int k = cellsOf(st.hp, max);
        if (v.lastHp >= 0 && st.hp < v.lastHp - 1e-6) {
            int prev = cellsOf(v.lastHp, max);
            v.lossCells = now < v.lossUntil ? Math.max(v.lossCells, prev) : prev;
            v.lossUntil = now + LOSS_TICKS;
        }
        v.lastHp = st.hp;
        int under = now < v.lossUntil ? Math.max(v.lossCells, k) : k;
        boolean low = st.hp <= max * 0.25;
        s.set(HP_HURT, new HudLine().shape("bar_hp", HudLine.cells(under, HudSpec.HP_W)));
        s.set(low ? HP_LOW : HP, new HudLine().shape("bar_hp", HudLine.cells(k, HudSpec.HP_W)));
        s.set(HP_NUM, new HudLine().text(HudLine.S, Integer.toString((int) Math.ceil(st.hp)), low ? RED : GRAY));
    }

    private static int cellsOf(double hp, double max) {
        if (hp <= 0) return 0;
        return Math.max(1, (int) Math.round(Math.min(1, hp / max) * HudSpec.HP_W));
    }

    /** 오른쪽 아래: [소모품 + 수] [+강화] [총 실루엣(등급 밑줄, 재장전 중이면 탄창 뺀 모양 흐리게 + 막대)] [탄창] [예비 또는 무한] */
    private void weapon(Player p, HudScreen s) {
        Guns.Arms a = game.guns.arms(p);
        if (a == null) return;
        Guns.Spec sp = game.guns.held(p);
        double[] am = game.guns.ammo(p);
        HudLine l = new HudLine();
        int left = -40;
        if (sp != null && am != null) {
            boolean rl = am[2] >= 0, blocked = game.blocked(p);
            Gun gun = game.guns.gun(p);
            String icon = "gun_l_" + sp.key();
            if (rl && HudLine.has(icon + "_nomag")) icon += "_nomag";
            int gi;
            if (HudLine.has(icon)) {
                gi = HudLine.ink(icon);
                l.at(-gi).img(icon, rl || blocked ? IMG_DIM : WHITE);
            } else {
                String k = sp.key().toUpperCase(Locale.ROOT);
                gi = HudLine.width(HudLine.S, k) - 1;
                l.textRight(0, HudLine.S, k, GRAY);
            }
            left = -gi;
            if (gun != null && gun.level() > 0) {
                String lv = "+" + gun.level();
                int lw = HudLine.width(HudLine.S, lv) - 1;
                left -= 3 + lw;
                l.at(left).text(HudLine.S, lv, GOLD);
            }
            if (rl) {
                int[] ph = game.guns.reloadPhase(p);
                if (ph != null) s.set(W_RLD, new HudLine().at(-60).timed("bar_reload", wt + ph[0], ph[1]));
            } else if (gun != null) {
                s.set(W_UL, new HudLine().at(-gi).rect("uline", gi, HudLine.pal("R_" + gun.rarity.name())));
            }
            int mag = (int) am[0];
            // 한 발씩 쏘는 무기(활, 로켓 발사기: 탄창 1~2)는 가득 차 있어도 빨갛지 않게(2026-11-12)
            int pal = rl || blocked ? GRAY : mag == 0 ? RED_BLINK : sp.mag() > 2 && mag <= Math.max(1, sp.mag() / 4) ? RED : WHITE;
            s.set(W_MAG, new HudLine().textRight(0, HudLine.M, Integer.toString(mag), pal));
            if (am[1] < 0) s.set(W_INF, new HudLine().shape("inf", 1));
            else s.set(W_RES, new HudLine().text(HudLine.S, Integer.toString((int) am[1]), am[1] == 0 ? RED : GRAY));
            // 레일건 모으기, 미니건 총열 회전(2026-11-12 새 무기): 조준점 아래 게이지(그 총 작은 실루엣 + 막대)
            double ch = game.guns.charge(p), sn = game.guns.spin(p);
            if (ch >= 0) meter(p, "gun_m_" + sp.key(), ch, 3);
            else if (sn > 0.01) meter(p, "gun_m_" + sp.key(), sn, 3);
        }
        int x = left - 7;
        if (a.item != null) {
            int n = a.items();
            String ns = Integer.toString(n), ic = "it_" + a.item;
            int cw = HudLine.width(HudLine.S, ns) - 1;
            boolean holding = p.getInventory().getHeldItemSlot() == Guns.SLOT_ITEM;
            int pal = n == 0 ? GHOST : holding ? GOLD : WHITE;
            l.at(x - cw).text(HudLine.S, ns, pal);
            if (HudLine.has(ic)) l.at(x - cw - 2 - HudLine.ink(ic)).img(ic, n == 0 ? GHOST : WHITE);
            x -= cw + (HudLine.has(ic) ? 2 + HudLine.ink(ic) : 0) + 7;
        }
        // 주머니 수류탄(3번 칸이 다른 소모품이어도 F 로 던짐)
        if (a.grenades > 0 && !"grenade".equals(a.item)) {
            String gs = Integer.toString(a.grenades);
            int gw = HudLine.width(HudLine.S, gs) - 1;
            l.at(x - gw).text(HudLine.S, gs, WHITE);
            if (HudLine.has("it_grenade")) l.at(x - gw - 2 - HudLine.ink("it_grenade")).img("it_grenade", WHITE);
        }
        s.set(W_GUN, l);
    }

    /** 핫바 위: 상점을 열 수 있는 자리면 상점 그림(카운터를 보면 금색), 고르지 않은 증강 뽑기 수 */
    private void shop(Player p, View v, HudScreen s) {
        // 원정: 트럭 막대가 있으면 트럭 아이콘이 상점 표시를 겸한다(열 수 있으면 밝게, 겨누면 금색). 고를 증강 카드 수만 막대 오른쪽에
        if (game.trip.truck() != null) {
            int n = game.augments.pending(p);
            if (n > 0) s.set(SHOP, new HudLine().at(54).img("card", GOLD).at(54 + HudLine.ink("card") + 2).text(HudLine.S, Integer.toString(n), GOLD));
            return;
        }
        boolean can = game.shop.canOpen(p);
        int pend = game.augments.pending(p);
        if (!can && pend <= 0) return;
        boolean look = can && game.shop.lookingAtCounter(p);
        String ps = Integer.toString(pend);
        int w = (can ? HudLine.ink("shop") : 0) + (pend > 0 ? (can ? 8 : 0) + HudLine.ink("card") + 2 + HudLine.width(HudLine.S, ps) - 1 : 0);
        int x = -(w / 2);
        HudLine l = new HudLine();
        if (can) {
            l.at(x).img("shop", look ? GOLD : WHITE);
            x += HudLine.ink("shop") + 8;
        }
        if (pend > 0) l.at(x).img("card", GOLD).at(x + HudLine.ink("card") + 2).text(HudLine.S, ps, GOLD);
        s.set(SHOP, l);
    }

    /** 쓰러짐: 출혈 고리(줄어듦, 일으키는 동안은 멈춘 몫 그대로) + 일으키기 고리(초록, 차오름) */
    private void downed(PState st, HudScreen s) {
        PState h = st.reviver != null ? game.players.get(st.reviver) : null;
        if (h != null && h.helping != null) {
            double f = Math.max(0, Math.min(1, (st.bleedUntil - now) / (double) (Players.BLEED + Math.max(0, st.bleedBonus))));
            s.set(C_BLEED_HOLD, new HudLine().at(-24).shape("ring_b", Math.round(f * 64) / 64.0));
            s.set(C_REVIVE, new HudLine().at(-20).timed("ring_a", h.helpUntil + off, (int) Math.max(1, h.helpUntil - h.helpStart)));
        } else {
            s.set(C_BLEED, new HudLine().at(-24).timed("ring_b", st.bleedUntil + off, Players.BLEED + Math.max(0, st.bleedBonus)));
        }
    }

    /** 가운데 아래 죽은 이유 카드: [종 아이콘] 종 이름 / 원인 글귀(없으면 이름만, 종을 모르면 기본 얼굴 + '쓰러졌다') */
    private void card(Card c, HudScreen s) {
        String icon = icon("l", c.sp);
        String causeKey = c.sp != null && c.cause != null && hasPhrase(c.cause) ? c.cause : null;
        String nameKey = c.sp == null ? "cause_none" : "name_" + c.sp.key();
        int namePal = WHITE;
        if (c.sp != null && namesHidden()) {
            // 이름표 지우기 변이 중: 아이콘과 원인만(원인 글귀가 없으면 '쓰러졌다')
            if (causeKey != null) namePal = RED;
            nameKey = causeKey != null ? causeKey : "cause_none";
            causeKey = null;
        }
        int iw = HudLine.ink(icon), tw = Math.max(phraseWidth(nameKey), phraseWidth(causeKey));
        int w = iw + 5 + tw, x0 = -(w / 2);
        HudLine l = new HudLine().at(x0 - 6).rect("plate", w + 12, PANEL).at(x0).img(icon, WHITE);
        phrase(l.at(x0 + iw + 5), nameKey, namePal);
        s.set(CARD, l);
        if (causeKey != null) s.set(CARD2, phrase(new HudLine().at(x0 + iw + 5), causeKey, RED));
    }

    /** 종 표시(가운데 오른쪽 위)와 게이지(가운데 아래) */
    private void marks(View v, HudScreen s) {
        if (v.mark != null && now < v.markUntil) {
            String img = iconImage(v.mark, "s");
            if (img != null) s.set(MARK, new HudLine().img(img, RED_BLINK));
            else if (hasPhrase(v.mark)) s.set(MARK, phrase(new HudLine(), v.mark, RED));
        }
        if (v.meter != null && now < v.meterUntil) {
            String img = iconImage(v.meter, "s");
            int lw = img != null ? HudLine.ink(img) : phraseWidth(v.meter);
            int w = lw + 2 + METER_W, x0 = 26 - w / 2;
            HudLine l = new HudLine().at(x0);
            if (img != null) l.img(img, WHITE);
            else phrase(l, v.meter, WHITE);
            l.at(x0 + lw + 2).shape("bar_meter", HudLine.cells((int) Math.round(v.meterFrac * METER_W), METER_W));
            s.set(METER, l);
        }
    }

    /** 방향 화살표(서 있는 사람만): 쓰러진 동료(빨강), 붙잡히거나 먹힌 동료(보라, 먹혔으면 먹은 게이브 쪽), 종이 가리킨 곳(금색) */
    private void pointers(Player p, View v, HudScreen s) {
        Location eye = p.getLocation();
        List<Object[]> ts = new ArrayList<>();
        for (PState x : game.players.all()) {
            if (x.id.equals(p.getUniqueId()) || x.offline) continue;
            Player tp = Bukkit.getPlayer(x.id);
            if (x.life == Life.DOWNED && tp != null && tp.getWorld() == p.getWorld()) ts.add(new Object[] {x.id, tp.getLocation().toVector(), PTR_DOWN});
            else if (x.life == Life.ALIVE && x.grabbedBy != null && tp != null && tp.getWorld() == p.getWorld())
                ts.add(new Object[] {x.id, tp.getLocation().toVector(), PTR_GRAB});
            else if (x.life == Life.TRAPPED && x.eater != null && !x.eater.dead) ts.add(new Object[] {x.id, x.eater.pos.clone(), PTR_GRAB});
        }
        Vector me = eye.toVector();
        ts.sort(Comparator.comparingDouble(o -> ((Vector) o[1]).distanceSquared(me)));
        HudLine down = new HudLine(), grab = new HudLine(), point = new HudLine();
        Set<UUID> live = new HashSet<>();
        for (int i = 0; i < Math.min(PTR_MAX, ts.size()); i++) {
            Object[] o = ts.get(i);
            UUID id = (UUID) o[0];
            live.add(id);
            Vector at = (Vector) o[1];
            ((Integer) o[2] == PTR_DOWN ? down : grab).at(-6).shape("ptr", step(v, id, rel(eye, at.getX(), at.getZ())) * 4 / 255.0);
        }
        v.ptrStep.keySet().removeIf(k -> k instanceof UUID && !live.contains(k));
        v.points.removeIf(pt -> now >= pt.until);
        for (int i = 0; i < v.points.size(); i++) {
            Vector at = v.points.get(i).at;
            point.at(-6).shape("ptr", step(v, "p" + i, rel(eye, at.getX(), at.getZ())) * 4 / 255.0);
        }
        s.set(PTR_DOWN, down);
        s.set(PTR_GRAB, grab);
        s.set(PTR_POINT, point);
    }

    /** 웨이브 방향 알림 화살표(판에 든 모두): 월드 yaw 방향 */
    private void waveDirs(Player p, HudScreen s) {
        if (now >= dirUntil || dirYaws.length == 0) return;
        View v = view(p.getUniqueId());
        float yaw = p.getLocation().getYaw();
        HudLine l = new HudLine();
        for (int i = 0; i < dirYaws.length; i++) {
            double r = ((dirYaws[i] - yaw) % 360 + 360) % 360;
            l.at(-6).shape("ptr", step(v, "w" + i, r) * 4 / 255.0);
        }
        s.set(PTR_WAVE, l);
    }

    /** 보는 사람 기준 각도(0 = 앞, 시계 방향 도) */
    private static double rel(Location from, double tx, double tz) {
        double dx = tx - from.getX(), dz = tz - from.getZ();
        double r = Math.toDegrees(Math.atan2(-dx, dz)) - from.getYaw();
        return (r % 360 + 360) % 360;
    }

    /** 각도를 64 칸으로(앞 칸에서 3/4 칸 안이면 그대로: 고개를 조금 돌릴 때마다 보내지 않게) */
    private static int step(View v, Object id, double deg) {
        double unit = 360.0 / PTR_STEPS;
        Integer prev = v.ptrStep.get(id);
        if (prev != null) {
            double d = Math.abs(((deg - prev * unit) % 360 + 540) % 360 - 180);
            if (d <= unit * 0.75) return prev;
        }
        int k = (int) Math.round(deg / unit) % PTR_STEPS;
        v.ptrStep.put(id, k);
        return k;
    }

    // ------------------------------------------------------------------ 글귀, 아이콘

    /** 글귀 글(시험, 기록용). 없으면 null */
    public static String phrase(String key) {
        Integer i = key == null ? null : PHRASE.get(key);
        return i == null ? null : HudSpec.PHRASE_TEXT[i];
    }

    public static boolean hasPhrase(String key) {
        return key != null && PHRASE.containsKey(key);
    }

    /** 글귀 폭(GUI), 없으면 0 */
    public static int phraseWidth(String key) {
        Integer i = key == null ? null : PHRASE.get(key);
        return i == null ? 0 : HudSpec.PHRASE_W[i];
    }

    /** 줄의 지금 펜 자리에 글귀 그림(조각을 이어서), 펜은 글귀 오른쪽 끝으로. 없는 키면 그대로 */
    public static HudLine phrase(HudLine l, String key, int pal) {
        Integer i = key == null ? null : PHRASE.get(key);
        if (i == null) return l;
        int x0 = l.pen();
        for (int j = 0; j < PARTS[i].length; j++) l.at(x0 + HudSpec.PHRASE_X[i][j]).img(PARTS[i][j], pal);
        return l.at(x0 + HudSpec.PHRASE_W[i]);
    }

    private HudLine panelPhrase(String key, int pal) {
        int w = phraseWidth(key), x0 = -(w / 2);
        return phrase(new HudLine().at(x0 - 5).rect("panel", w + 10, PANEL).at(x0), key, pal);
    }

    /** 이 종의 HUD 아이콘이 팩에 있나(없으면 기본 얼굴) */
    public static boolean hasIcon(Species sp) {
        return sp != null && ICON_SET.contains(sp.key());
    }

    public static boolean hasCover(String key) {
        return key != null && COVER_SET.contains(key);
    }

    /** 종 아이콘 그림 이름(크기 l 20, m 14, s 10 GUI), 없으면 기본 얼굴 */
    private static String icon(String size, Species sp) {
        return "ic_" + size + "_" + (hasIcon(sp) ? sp.key() : "default");
    }

    /** 아이콘 인자: 종 키(g05 → 그 크기 아이콘) 또는 그림 이름. 없으면 null */
    private static String iconImage(String key, String size) {
        if (key == null) return null;
        Species sp = key.length() >= 3 && key.length() <= 4 && key.charAt(0) == 'g' ? Species.of(key) : null;
        // 보스 키(b1 .. b5): 그 보스 아이콘, 없으면 공용 보스 그림
        if (sp == null && key.length() == 2 && key.charAt(0) == 'b' && Character.isDigit(key.charAt(1))) {
            Species b = Species.of(key);
            if (b != null) return hasIcon(b) || !HudLine.has("ic_boss") ? icon(size, b) : "ic_boss";
        }
        if (sp != null) return icon(size, sp);
        return HudLine.has(key) ? key : null;
    }

    private void warnOnce(String what) {
        if (warned.add(what)) game.plugin.getLogger().warning("[허드] " + what);
    }

    // ------------------------------------------------------------------ 다른 모듈이 부르는 것

    /** 모두에게 알림 한 줄(글귀 키, 띠 가운데). 떠 있는 것과 같으면 시간만 늘리고, 아니면 줄 서서 하나씩(최대 4). */
    public void notice(String phraseKey, int ticks) {
        pushAll(nt(phraseKey, null, -1, 0, N_PLAIN, ticks));
    }

    /** 한 사람에게 알림. */
    public void notice(Player p, String phraseKey, int ticks) {
        push(p, nt(phraseKey, null, -1, 0, N_PLAIN, ticks));
    }

    /** 모두에게 [종 아이콘] 글귀 알림(이마 글자, 배운 게이브처럼 종을 짚는 것). */
    public void notice(String phraseKey, Species icon, int ticks) {
        pushAll(nt(phraseKey, icon == null ? null : icon.key(), -1, 0, N_PLAIN, ticks));
    }

    public void notice(Player p, String phraseKey, Species icon, int ticks) {
        push(p, nt(phraseKey, icon == null ? null : icon.key(), -1, 0, N_PLAIN, ticks));
    }

    /** 모두에게 [그림] 글귀 알림. icon = HUD 그림 이름(gun_m_shotgun, it_molotov, ic_m_g05 ...) 또는 종 키(g05). */
    public void notice(String phraseKey, String icon, int ticks) {
        pushAll(nt(phraseKey, icon, -1, 0, N_PLAIN, ticks));
    }

    /** 재난문자 꼴 띠(붉은 머리 + 글귀 + 짙기 칸 level 1..5, 0 이면 칸 없음): 환경 사건(안개 짙기, 배관 범람, 소리 없는 웨이브 ...). */
    public void alert(String phraseKey, int level, int ticks) {
        pushAll(nt(phraseKey, null, -1, Math.max(0, Math.min(5, level)), N_ALERT, ticks));
    }

    public void alert(Player p, String phraseKey, int level, int ticks) {
        push(p, nt(phraseKey, null, -1, Math.max(0, Math.min(5, level)), N_ALERT, ticks));
    }

    /**
     * 웨이브 방향 알림: 화면 가운데 둘레에 그 월드 방향(마크 yaw 도: 0 = +Z 남, 90 = -X 서, 180 = 북, 270 = 동)을 가리키는 주황 화살표가
     * ticks 동안(사람이 돌면 따라 돎). phraseKey 가 있으면 그 알림도(보통 "ev_dir").
     */
    public void waveFrom(String phraseKey, int ticks, double... worldYaws) {
        dirYaws = worldYaws == null ? new double[0] : worldYaws.clone();
        dirUntil = now + ticks;
        if (phraseKey != null) notice(phraseKey, ticks);
    }

    /** 한 사람(null 이면 판에 든 모두)에게 월드 자리 쪽 금색 화살표(ticks 동안). 우는 아기, 노크처럼 '저쪽'을 짚을 때. */
    public void point(Player p, Vector at, int ticks) {
        if (at == null) return;
        if (p == null) {
            for (Player o : Bukkit.getOnlinePlayers()) point(o, at, ticks);
            return;
        }
        View v = view(p.getUniqueId());
        v.points.removeIf(pt -> now >= pt.until);
        if (v.points.size() >= 4) v.points.remove(0);
        v.points.add(new Point(at.clone(), now + ticks));
    }

    /** 적중 표시(kill 이면 빨간 처치 표시). 끝 틱은 셰이더 도장이라 클라 시계 어림(ClientClock)으로 찍는다. */
    public void hit(Player p, boolean kill) {
        if (p == null) return;
        View v = view(p.getUniqueId());
        long end = ClientClock.time(game.world) + HudSpec.HIT_TICKS;
        if (kill) v.killEnd = end;
        else v.hitEnd = end;
    }

    /** 재화 얻음(+n 이 잠깐, 연달아 얻으면 더해짐). Players.addMoney 가 부른다. */
    public void money(Player p, int gained) {
        if (p == null || gained <= 0) return;
        View v = view(p.getUniqueId());
        v.gain = now < v.gainUntil ? v.gain + gained : gained;
        v.gainUntil = now + GAIN_TICKS;
    }

    /** 쓰러졌을 때 죽은 이유 카드(종 아이콘, 이름, 원인 글귀). Players.down 이 부른다. sp 가 null 이면 기본 얼굴 + '쓰러졌다'. */
    public void cause(Player p, Species sp, String causeKey) {
        if (p == null) return;
        if (causeKey != null && !hasPhrase(causeKey)) warnOnce("없는 원인 글귀 " + causeKey + "(data/phrases 에 넣고 make_hud)");
        view(p.getUniqueId()).card = new Card(sp, causeKey, now + CARD_TICKS);
    }

    /** 화면 덮개 그림(키, 틱). 보통은 Players.cover(한 번에 하나 규칙)가 부른다. 같은 사람에게 새 키면 바꿔 그린다. */
    public void cover(Player p, String key, int ticks) {
        if (p == null || key == null) return;
        if (!hasCover(key)) {
            warnOnce("없는 덮개 " + key + "(build/gabe/covers.json 에 넣고 make_hud)");
            return;
        }
        View v = view(p.getUniqueId());
        v.cover = key;
        v.coverUntil = now + Math.max(1, ticks);
    }

    /** 덮개 지움. */
    public void uncover(Player p) {
        if (p == null) return;
        View v = views.get(p.getUniqueId());
        if (v != null) v.cover = null;
    }

    /** 이 판에서 그 종이 처음 나옴(처음 잡을 때까지 오른쪽 위에 아이콘과 이름). 게이브가 나오면 저절로도 부른다(흉내 중인 것은 드러날 때). */
    public void firstSeen(Species sp) {
        // 보스는 처음 보는 종 칸에 안 올림(보스 틀이 있음, 보스.md 7.5)
        if (sp == null || namesHidden() || sp.is(Role.BOSS)) return;
        seenRound.add(sp);
        if (!unknown.contains(sp)) unknown.add(sp);
    }

    /** 그 종을 처음 잡음(구석 안내 지움). Mobs 가 부른다. */
    public void firstKilled(Species sp) {
        if (sp == null) return;
        seenRound.add(sp);
        unknown.remove(sp);
    }

    /** 가운데 오른쪽 위 작은 표시(ticks 동안 빨갛게 깜빡): key = 종 키(g33 → 그 종 아이콘), HUD 그림 이름, 또는 글귀 키. 표적이 됐다는 표시 따위. */
    public void mark(Player p, String key, int ticks) {
        if (p == null) return;
        View v = view(p.getUniqueId());
        v.mark = key;
        v.markUntil = now + ticks;
    }

    /**
     * 가운데 아래 작은 게이지(0..1, 40 칸): key = 종 키(아이콘) 또는 글귀 키(앞에 글). 같은 키로 다시 부르면 값과 시간만 바뀜(값이 같은 칸이면
     * 보내지 않음). frac &lt; 0 이면 지움. 녹음, 혹 단계, 꼬르륵 듣기 같은 종별 게이지.
     */
    public void meter(Player p, String key, double frac, int ticks) {
        if (p == null) return;
        View v = view(p.getUniqueId());
        if (frac < 0 || key == null) {
            v.meter = null;
            return;
        }
        v.meter = key;
        v.meterFrac = Math.max(0, Math.min(1, frac));
        v.meterUntil = now + ticks;
    }

    /**
     * 한 사람에게 한 줄 도움말(가운데 아래, 이 판에서 그 키는 한 번만). 처음 쓰는 순간의 조작 안내용. 받았으면 true(죽은 이유 카드가 떠 있으면
     * 카드가 걷힌 뒤부터 ticks 동안 보임, 11초 안에 못 띄우면 버림).
     */
    public boolean hint(Player p, String phraseKey, int ticks) {
        return p != null && hint(view(p.getUniqueId()), phraseKey, ticks, 1);
    }

    /** 남은 게이브 수를 0 으로 흉내(막대 빔, 진짜 수는 0.5초마다 잠깐 비침). 가짜 종소리 게이브(G55). 웨이브가 끝나면 풀림. */
    public void fakeRemaining(int ticks) {
        spoofUntil = now + ticks;
    }

    /** 동료 목록 깜빡임(ticks 동안). 축복 주는 게이브처럼 동료 수를 의심하게 하는 종. */
    public void blinkRoster(Player p, int ticks) {
        if (p == null) return;
        view(p.getUniqueId()).blinkUntil = now + ticks;
    }

    // ------------------------------------------------------------------ 원정(docs/모듈/원정.md 12절)

    private List<String> storyKeys = List.of();
    private long storyAt = -1, blackUntil = -1;
    private int storyEach = 70, storyHold = 40, storyGap = 8;

    /**
     * 검은 화면에 글귀를 한 줄씩 차례로(모두에게: 엔딩, 맵 전환, 판 시작과 끝). 줄마다 each 틱(끝 gap 틱은 글 없이 검정), 마지막 줄 뒤 hold 틱 더 검은 채.
     * 그동안 다른 HUD 는 가린다. 없는 글귀 키는 건너뛴다. 전체 길이(틱)를 돌려준다.
     */
    public int story(List<String> keys, int each, int hold) {
        List<String> ok = new ArrayList<>();
        for (String k : keys) {
            if (hasPhrase(k)) ok.add(k);
            else warnOnce("없는 글귀 " + k + "(data/phrases 에 넣고 make_hud)");
        }
        storyKeys = ok;
        storyEach = Math.max(10, each);
        storyHold = Math.max(0, hold);
        storyGap = Math.min(10, storyEach / 6);
        storyAt = game.now();
        return storyKeys.size() * storyEach + storyHold;
    }

    /** 글 없이 온 화면 검정 ticks 틱(모두에게). 0 이하면 지움. */
    public void blackout(int ticks) {
        blackUntil = ticks <= 0 ? -1 : game.now() + ticks;
    }

    /** 검은 화면 글 차례를 끊음(판이 바뀔 때). */
    public void storyStop() {
        storyAt = -1;
        storyKeys = List.of();
        blackUntil = -1;
    }

    /** 다시 하기(Game.retry): 판 끝 검은 화면 글과 '판 끝' 알림을 거둔다. */
    public void retry() {
        storyStop();
        overPending = false;
        for (View v : views.values()) {
            v.queue.removeIf(n -> n.style() == N_OVER);
            if (v.cur != null && v.cur.style() == N_OVER) v.cur = null;
        }
    }

    /** 검은 화면 글 차례가 도는 중인가. */
    public boolean storyActive() {
        return storyAt >= 0 && game.now() - storyAt < (long) storyKeys.size() * storyEach + storyHold;
    }

    /** 지금 보일 줄(줄 사이 틈, 마지막 머무는 동안은 null). */
    private String storyLine() {
        long t = game.now() - storyAt;
        int i = (int) (t / storyEach);
        if (i < 0 || i >= storyKeys.size() || t % storyEach >= storyEach - storyGap) return null;
        return storyKeys.get(i);
    }

    /** 판 끝 알림 글귀: 원정(트럭 폭발, 전멸)이면 '모두 게이브가 되었다', 아니면 예전 글. */
    private String overKey() {
        return game.trip.enabled && hasPhrase("trip_all_gabe") && !kr.gave.trip.Trip.END_LAUNCH.equals(game.trip.endCause()) ? "trip_all_gabe" : "game_over";
    }

    private static int truckCells(double frac) {
        if (frac <= 0) return 0;
        return Math.max(1, (int) Math.round(Math.min(1, frac) * HudSpec.HP_W));
    }

    /**
     * 원정 칸. 읽는 값(game.trip.hud): truck.frac, truck.hurt, truck.hitAgo, truck.dist, truck.dying(트럭), revive.me.frac(죽은 사람의 모금),
     * st.show(Boolean: 스테이지 표시를 켬) + trip.left, st.name(도착한 스테이지 이름 글귀 키), vote.tally(길 투표 집계 글: 숫자와 빈칸), vote.pick(내 표 번호).
     */
    private void trip(Player p, PState st, View v, HudScreen s, Map<String, Object> tv) {
        if (tv.isEmpty()) return;
        roundBand(s, tv);
        if (tv.get("truck.frac") instanceof Number fr && !Boolean.TRUE.equals(tv.get("truck.dying"))) {
            double f = fr.doubleValue(), hurt = tv.get("truck.hurt") instanceof Number h ? h.doubleValue() : f;
            int k = truckCells(f), under = Math.max(k, truckCells(hurt));
            boolean low = f <= 0.25;
            long ago = tv.get("truck.hitAgo") instanceof Number a ? a.longValue() : 10000;
            s.set(TRK_HURT, new HudLine().shape("bar_hp", HudLine.cells(under, HudSpec.HP_W)));
            s.set(low ? TRK_LOW : TRK_BAR, new HudLine().shape("bar_hp", HudLine.cells(k, HudSpec.HP_W)));
            String ti = truckIcon(tv);
            if (ti != null) {
                boolean can = st.life == Life.ALIVE && game.shop.canOpen(p);
                boolean look = can && game.shop.lookingAtCounter(p);
                // 맞는 순간 아이콘이 떨린다. 상점을 열 수 있으면 밝게(겨누면 금색)
                int shake = ago < 6 ? (now % 2 == 0 ? 1 : -1) : 0;
                s.set(TRK_ICON, new HudLine().at(shake).img(ti, low ? RED_BLINK : look ? GOLD : can ? WHITE : GRAY));
            }
            // 트럭이 맞고 있는데 20블록 넘게 떨어져 있거나 등진 사람에게 트럭 쪽 흰 화살촉
            if (ago < 40 && st.life == Life.ALIVE && tv.get("truck.dist") instanceof Number d) {
                Vector c = kr.gave.trip.Truck.center();
                double r = rel(p.getLocation(), c.getX(), c.getZ());
                boolean behind = r > 100 && r < 260;
                if (d.doubleValue() > 20 || behind && d.doubleValue() > 4) s.set(PTR_TRUCK, new HudLine().at(-6).shape("ptr", step(v, "truck", r) * 4 / 255.0));
            }
        }
        if (st.life == Life.DEAD && tv.get("revive.me.frac") instanceof Number rf) {
            s.set(RV_BAR, new HudLine().shape("bar_meter", Math.max(0, Math.min(1, rf.doubleValue()))));
            if (hasPhrase("trip_trapped")) s.set(RV_TXT, phrase(new HudLine().at(-(phraseWidth("trip_trapped") / 2)), "trip_trapped", GRAY));
        }
        if (Boolean.TRUE.equals(tv.get("st.show")) && tv.get("trip.left") instanceof Number left) {
            HudLine l = new HudLine();
            int x = 0;
            String ti = truckIcon(tv);
            if (ti != null) {
                l.img(ti, GRAY);
                x = HudLine.ink(ti) + 3;
            }
            s.set(ST_TAG, l.at(x).text(HudLine.S, Integer.toString(left.intValue()), GRAY));
        }
        if (tv.get("st.name") instanceof String nk && hasPhrase(nk) && now >= titleUntil)
            s.set(ST_NAME, phrase(new HudLine().at(-(phraseWidth(nk) / 2)), nk, WHITE));
        // 갈림길 투표 판(숫자키 1, 2, 3): 세 칸이 나란히. 갈래 값이 없으면 예전 집계 한 줄
        if (tv.get("route.fork") instanceof String fork && !fork.isEmpty()) {
            HudLine vp = votePanel(fork, tv);
            if (vp != null) s.set(TALLY, vp);
        } else if (tv.get("vote.tally") instanceof String tally && !tally.isEmpty())
            s.set(TALLY, new HudLine().at(-(HudLine.width(HudLine.M, tally) / 2)).text(HudLine.M, tally, GOLD));
    }

    /** 트럭 아이콘: 4막 발사장(launch.rocket)이면 로켓 그림 ti_rocket(팩에 있을 때), 아니면 ti_truck. 둘 다 없으면 null. */
    private static String truckIcon(Map<String, Object> tv) {
        if (Boolean.TRUE.equals(tv.get("launch.rocket")) && HudLine.has("ti_rocket")) return "ti_rocket";
        return HudLine.has("ti_truck") ? "ti_truck" : null;
    }

    /**
     * 라운드 이름 띠(trip_rounds, 원정.md 5.0절): round.show(남은 틱) 동안 알림 띠 자리에 [목표 아이콘][라운드 이름 글귀 round.name], 그 아래(NT_LOW)에 한 줄 round.line.
     * 그동안 알림 띠의 다른 알림은 가려진다(알림 시간은 흐름). 보스 틀이 있으면 안 그림(보스 라운드는 라운드 종류가 비켜 줌).
     */
    private void roundBand(HudScreen s, Map<String, Object> tv) {
        if (bossFrame || !(tv.get("round.show") instanceof Number show) || show.intValue() <= 0) return;
        String name = tv.get("round.name") instanceof String n && hasPhrase(n) ? n : null;
        if (name == null) return;
        String ic = tv.get("goal.icon") instanceof String i && HudLine.has(i) ? i : null;
        int iw = ic == null ? 0 : HudLine.ink(ic), pw = phraseWidth(name);
        int w = (iw > 0 ? iw + 4 : 0) + pw, x0 = -(w / 2);
        String ln = tv.get("round.line") instanceof String t && hasPhrase(t) && NT_LOW >= 0 ? t : null;
        // 한 줄이 있으면 띠 바로 아래 판(sub)에 얹어 한 장으로: 판 없이는 트럭 지붕 막대(월드 글 표시) 같은 뒤 그림과 겹쳐 안 읽혔다
        int lw = ln == null ? 0 : phraseWidth(ln), cw = Math.max(w, lw), c0 = -(cw / 2);
        HudLine l = new HudLine().at(c0 - 5).rect("band", cw + 10, PANEL);
        if (ic != null) l.at(x0).img(ic, GOLD);
        phrase(l.at(x0 + (iw > 0 ? iw + 4 : 0)), name, WHITE);
        s.set(NT, l);
        if (ln != null) s.set(NT_LOW, phrase(new HudLine().at(c0 - 5).rect("sub", cw + 10, PANEL).at(-(lw / 2)), ln, GRAY));
    }

    /**
     * 라운드 목표 막대(trip_rounds): goal.frac(0..1)이 있으면 웨이브 막대 대신 goal.fill 이 true 면 차오르는 금빛 막대, false 면 줄어드는 시간 막대.
     * 왼쪽에 goal.icon(ti_goal_*), 오른쪽(WV_LEFT)에 goal.text(남은 초, 3/6, 퍼센트), 그 오른쪽에 goal.cargo(떨어진 짐 체력 0..1). 이루면 초록, 놓치면 빨강.
     */
    private void goal(HudScreen s, Map<String, Object> tv) {
        boolean hasFrac = tv.get("goal.frac") instanceof Number, hasText = tv.get("goal.text") instanceof String t0 && !t0.isEmpty();
        if (!hasFrac && !hasText) return;
        boolean met = Boolean.TRUE.equals(tv.get("goal.met")), failed = Boolean.TRUE.equals(tv.get("goal.failed"));
        if (tv.get("goal.frac") instanceof Number f && GOAL_BAR >= 0) {
            boolean fill = !Boolean.FALSE.equals(tv.get("goal.fill"));
            s.set(WV_BAR, null);
            s.set(WV_BAR_TIME, null);
            int k = (int) Math.round(Math.max(0, Math.min(1, f.doubleValue())) * BAR_W);
            s.set(fill ? GOAL_BAR : GOAL_TIME, new HudLine().shape("bar_phase", HudLine.cells(k, BAR_W)));
        }
        String ic = tv.get("goal.icon") instanceof String i && HudLine.has(i) ? i : null;
        if (ic != null && GOAL_IC >= 0) s.set(GOAL_IC, new HudLine().at(-HudLine.ink(ic)).img(ic, met ? GREEN : failed ? RED : GOLD));
        if (tv.get("goal.text") instanceof String txt && !txt.isEmpty()) s.set(WV_LEFT, new HudLine().text(HudLine.S, txt, met ? GREEN : failed ? RED : WHITE));
        if (tv.get("goal.cargo") instanceof Number c && GOAL_CARGO >= 0)
            s.set(GOAL_CARGO, new HudLine().shape("bar_meter", HudLine.cells((int) Math.round(Math.max(0, Math.min(1, c.doubleValue())) * METER_W), METER_W)));
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    /**
     * 갈림길 투표 판(원정 길 투표, 맨 위 가운데 TALLY 칸 한 줄). 칸마다 [어두운 판][번호(큰 글자)][갈래 종류 글귀][표 수(작은 글자)], 맨 끝에 남은 초.
     * 내 표(정해진 뒤에는 뽑힌 갈래)는 왼쪽 금빛 띠와 금빛 글, 정해진 뒤 나머지는 흐리게.
     * 읽는 값: route.fork(갈래 종류 키 셋, 쉼표: fight, cache ... → 글귀 rt_type_&lt;키&gt;), route.names(있으면 글귀 키 셋, 쉼표: 종류 대신 이 이름),
     * route.votes(표 수 셋, 쉼표), route.mine(내 표 1..3, 없으면 0), route.pick(정해진 갈래 1..3), route.open(Boolean: 표를 받는 중), route.left(남은 틱).
     */
    private HudLine votePanel(String fork, Map<String, Object> tv) {
        String[] kinds = fork.split(",", -1);
        String[] names = tv.get("route.names") instanceof String ns && !ns.isEmpty() ? ns.split(",", -1) : null;
        String[] votes = tv.get("route.votes") instanceof String vs ? vs.split(",", -1) : new String[0];
        int n = Math.min(3, kinds.length);
        if (n <= 0) return null;
        int mine = intOf(tv.get("route.mine")), pick = intOf(tv.get("route.pick"));
        boolean open = Boolean.TRUE.equals(tv.get("route.open"));
        String[] label = new String[n], count = new String[n], num = new String[n];
        boolean[] phr = new boolean[n];
        int inner = 0;
        for (int i = 0; i < n; i++) {
            String k = kinds[i].trim();
            String nk = names != null && i < names.length ? names[i].trim() : "";
            label[i] = hasPhrase(nk) ? nk : hasPhrase("rt_type_" + k) ? "rt_type_" + k : k;
            phr[i] = hasPhrase(label[i]);
            count[i] = i < votes.length && !votes[i].isBlank() ? votes[i].trim() : "0";
            num[i] = Integer.toString(i + 1);
            int w = HudLine.width(HudLine.M, num[i]) + 4 + (phr[i] ? phraseWidth(label[i]) : HudLine.width(HudLine.S, label[i])) + 5
                + HudLine.width(HudLine.S, count[i]);
            inner = Math.max(inner, w);
        }
        int cell = inner + 10, gap = 5;
        String time = open && tv.get("route.left") instanceof Number t && t.intValue() > 0 ? Integer.toString((int) Math.ceil(t.intValue() / 20.0)) : null;
        int total = n * cell + (n - 1) * gap + (time != null ? gap + 2 + HudLine.width(HudLine.S, time) : 0);
        int x = -(total / 2);
        int lit = pick > 0 ? pick : mine;
        HudLine l = new HudLine();
        for (int i = 0; i < n; i++) {
            boolean me = lit == i + 1, gone = pick > 0 && !me;
            int main = gone ? DIM : me ? GOLD : WHITE, sub = gone ? DIM : me ? GOLD : GRAY;
            l.at(x).rect("band", cell, PANEL);
            if (me) l.at(x).rect("band", 2, GOLD);
            l.at(x + 5).text(HudLine.M, num[i], main);
            l.at(l.pen() + 3);
            if (phr[i]) phrase(l, label[i], sub);
            else l.text(HudLine.S, label[i], sub);
            l.at(x + cell - 5 - HudLine.width(HudLine.S, count[i]) + 1).text(HudLine.S, count[i], "0".equals(count[i]) ? DIM : sub);
            x += cell + gap;
        }
        if (time != null) l.at(x + 2).text(HudLine.S, time, GRAY);
        return l;
    }

    // ------------------------------------------------------------------ 보스(docs/모듈/보스.md 7절, 보스 감독이 부름)

    /**
     * 보스 등장 큰 제목(화면 3분의 1 높이, 2배 글귀: 키가 t_ 로 시작하면 make_hud 가 18 GUI 로 그림)과 그 아래 부제를 판에 든 모두에게 ticks 동안.
     * 제목 키가 없으면 부제만, 둘 다 없으면 아무것도 안 함.
     */
    public void bossTitle(String titleKey, String subKey, int ticks) {
        titleLine = hasPhrase(titleKey) ? phrase(new HudLine().at(-(phraseWidth(titleKey) / 2)), titleKey, RED) : null;
        subLine = hasPhrase(subKey) ? phrase(new HudLine().at(-(phraseWidth(subKey) / 2)), subKey, WHITE) : null;
        endNumLine = null;
        if (titleKey != null && !hasPhrase(titleKey)) warnOnce("없는 보스 제목 글귀 " + titleKey);
        titleUntil = titleLine == null && subLine == null ? -1 : game.now() + Math.max(1, ticks);
    }

    /** 엔딩 카드(7.6절): 큰 제목(금색), 부제, 그 아래 웨이브, 판 시간 mm:ss, 사람 아이콘 + 수(금색 숫자). */
    public void endingCard(String titleKey, String subKey, int wave, long seconds, int people, int ticks) {
        titleLine = hasPhrase(titleKey) ? phrase(new HudLine().at(-(phraseWidth(titleKey) / 2)), titleKey, GOLD) : null;
        subLine = hasPhrase(subKey) ? phrase(new HudLine().at(-(phraseWidth(subKey) / 2)), subKey, WHITE) : null;
        String w = Integer.toString(Math.max(0, wave)), t = String.format(Locale.ROOT, "%d:%02d", Math.max(0, seconds) / 60, Math.max(0, seconds) % 60),
            n = Integer.toString(Math.max(0, people));
        int ww = HudLine.width(HudLine.M, w) - 1, tw = HudLine.width(HudLine.M, t) - 1, nw = HudLine.width(HudLine.M, n) - 1, pi = HudLine.ink("person");
        int gap = 14, total = ww + gap + tw + gap + pi + 3 + nw, x = -(total / 2);
        HudLine l = new HudLine().at(x).text(HudLine.M, w, GOLD);
        x += ww + gap;
        l.at(x).text(HudLine.M, t, GOLD);
        x += tw + gap;
        l.at(x).img("person", GOLD);
        l.at(x + pi + 3).text(HudLine.M, n, GOLD);
        endNumLine = l;
        titleUntil = game.now() + Math.max(1, ticks);
    }

    /** 보스 맞음 표시(가운데 적중 표시를 색만 바꿔): weak 면 금색(약점), 아니면 회색(안 먹힘). Boss 바탕이 부른다. */
    public void bossHit(Player p, boolean weak) {
        if (p == null) return;
        View v = view(p.getUniqueId());
        long end = ClientClock.time(game.world) + HudSpec.HIT_TICKS;
        if (weak) v.weakEnd = end;
        else v.immuneEnd = end;
    }

    /**
     * 치명타 적중 표시: 보스 약점과 같은 금색 칸(C_WEAK, 칸 차례가 C_HIT, C_KILL 뒤). 같은 발로 처치 표시가 막 켜졌으면(끝 틱이 한 틱 안) 빨간 처치
     * 표시를 덮지 않게 건너뜀. Game.crit 이 부른다.
     */
    public void crit(Player p) {
        if (p == null) return;
        View v = view(p.getUniqueId());
        long end = ClientClock.time(game.world) + HudSpec.HIT_TICKS;
        if (v.killEnd < end - 1) v.weakEnd = end;
    }

    private void pushAll(Notice n) {
        for (Player p : Bukkit.getOnlinePlayers()) push(p, n);
    }

    private void push(Player p, Notice n) {
        if (p == null || n.key == null) return;
        if (!hasPhrase(n.key)) {
            warnOnce("없는 글귀 " + n.key + "(data/phrases 에 넣고 make_hud)");
            if (n.icon == null && n.num < 0) return;
        }
        View v = view(p.getUniqueId());
        if (n.style == N_OVER) {
            // 판 끝은 모두 밀어냄
            v.queue.clear();
            v.cur = null;
            v.queue.add(n);
            return;
        }
        if (n.style == N_WAVE) {
            // 웨이브 시작은 지난 틱까지 쌓인 것만 밀어내고 맨 앞에 선다. 이번 틱에 들어온 것(감독이 웨이브 시작에 넣는 방향, 안개,
            // 범람, 변이 알림)은 그 뒤에 남는다. 뒤에 기다리는 것이 있으면 웨이브 알림은 짧게
            long fresh = frame - 1;
            v.queue.removeIf(q -> q.frame < fresh || q.same(n));
            if (v.cur != null && v.cur.frame >= fresh && !v.cur.same(n)) v.queue.offerFirst(v.cur);
            v.cur = null;
            v.queue.offerFirst(v.queue.isEmpty() ? n : n.withTicks(Math.min(n.ticks, WAVE_SHORT)));
            return;
        }
        if (v.cur != null && v.cur.same(n)) {
            v.curUntil = Math.max(v.curUntil, now + n.ticks);
            return;
        }
        for (Notice q : v.queue) if (q.same(n)) return;
        while (v.queue.size() >= QUEUE_MAX) v.queue.poll();
        v.queue.add(n);
    }

    /** 시험용: 지금 띠와 줄 선 알림 키 */
    private String queueText(View v) {
        StringBuilder b = new StringBuilder(v.cur == null ? "-" : v.cur.key + (v.cur.num >= 0 ? " " + v.cur.num : ""));
        for (Notice q : v.queue) b.append(" | ").append(q.key).append(q.num >= 0 ? " " + q.num : "");
        return b.toString();
    }

    // ------------------------------------------------------------------ 정리

    public void forget(UUID id) {
        screens.remove(id);
        views.remove(id);
    }

    public void clear() {
        for (View v : views.values()) {
            v.queue.clear();
            v.cur = null;
            v.card = null;
            v.cover = null;
            v.hint = null;
            v.mark = v.meter = null;
            v.points.clear();
            v.ptrStep.clear();
            v.gainUntil = v.lossUntil = v.blinkUntil = -1;
            v.lastHp = -1;
        }
        spoofUntil = dirUntil = -1;
        titleUntil = -1;
        titleLine = subLine = endNumLine = null;
        storyStop();
        bossShownK = -1;
        bossChipUntil = -1;
        testHideNames = false;
        dirYaws = new double[0];
        unknown.clear();
        seenRound.clear();
        phaseEnd = -1;
    }

    // ------------------------------------------------------------------ 시험

    /**
     * 시험 훅(/gave 시험 &lt;행동&gt; &lt;이름&gt; ...): 허드 &lt;이름&gt;(보낸 수와 칸 설명), 알림 &lt;이름|all&gt; &lt;키&gt; [틱] [짙기 → 재난문자 꼴],
     * 카드 &lt;이름&gt; &lt;종|-&gt; [원인 키], 허드덮기 &lt;이름&gt; &lt;키|off&gt; [틱], 표시 &lt;이름&gt; &lt;키&gt; [틱], 게이지 &lt;이름&gt; &lt;키&gt; &lt;0..1&gt; [틱],
     * 적중 &lt;이름&gt; [처치], 처음 x &lt;종&gt;, 처음잡음 x &lt;종&gt;, 가짜남은 x [틱], 허드방향 x &lt;틱&gt; &lt;yaw...&gt;(감독 '방향' 과 갈림),
     * 가리키기 &lt;이름&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; [틱], 깜빡 &lt;이름&gt; [틱], 허드동료 x &lt;수&gt;(가짜 동료 줄, 0 이면 지움), 도움말 &lt;이름&gt; &lt;키&gt;,
     * 알림줄 &lt;이름&gt;(띠와 줄 선 알림 키), 웨이브알림 x &lt;웨이브&gt; &lt;키[:짙기]...&gt;(알림을 넣고 곧바로 웨이브 시작), 이름숨김 x &lt;on|off&gt;.
     * 처리하면 결과 글, 아니면 null
     */
    public String test(Player p, String[] a) {
        if (a.length < 3) return null;
        try {
            switch (a[1]) {
                case "허드" -> {
                    if (p == null) return "[허드] 사람 없음";
                    HudScreen s = screens.get(p.getUniqueId());
                    return s == null ? "[허드] 화면 없음" : "[허드] " + p.getName() + " 보냄 " + s.sends() + " " + s.describe();
                }
                case "알림" -> {
                    if (a.length < 4) return "[허드] 알림 <이름|all> <키> [틱] [짙기]";
                    int t = a.length > 4 ? Integer.parseInt(a[4]) : 80;
                    if (a.length > 5) {
                        if (p == null) alert(a[3], Integer.parseInt(a[5]), t);
                        else alert(p, a[3], Integer.parseInt(a[5]), t);
                    } else if (p == null) notice(a[3], t);
                    else notice(p, a[3], t);
                    return "[허드] 알림 " + a[3] + " " + (hasPhrase(a[3]) ? phrase(a[3]) : "(없는 글귀)");
                }
                case "카드" -> {
                    if (p == null || a.length < 4) return "[허드] 카드 <이름> <종|-> [원인 키]";
                    Species sp = a[3].equals("-") ? null : Species.of(a[3]);
                    cause(p, sp, a.length > 4 ? a[4] : sp == null ? null : "cause_" + sp.key());
                    return "[허드] 카드 " + (sp == null ? "-" : sp.name()) + " 아이콘=" + hasIcon(sp);
                }
                case "허드덮기" -> {
                    if (p == null || a.length < 4) return "[허드] 허드덮기 <이름> <키|off> [틱]";
                    if (a[3].equals("off")) uncover(p);
                    else cover(p, a[3], a.length > 4 ? Integer.parseInt(a[4]) : 100);
                    return "[허드] 덮개 " + a[3] + " 있음=" + hasCover(a[3]);
                }
                case "표시" -> {
                    if (p == null || a.length < 4) return "[허드] 표시 <이름> <키> [틱]";
                    mark(p, a[3], a.length > 4 ? Integer.parseInt(a[4]) : 100);
                    return "[허드] 표시 " + a[3];
                }
                case "게이지" -> {
                    if (p == null || a.length < 5) return "[허드] 게이지 <이름> <키> <0..1> [틱]";
                    meter(p, a[3], Double.parseDouble(a[4]), a.length > 5 ? Integer.parseInt(a[5]) : 100);
                    return "[허드] 게이지 " + a[3] + " " + a[4];
                }
                case "적중" -> {
                    if (p == null) return "[허드] 사람 없음";
                    hit(p, a.length > 3);
                    return "[허드] 적중";
                }
                case "처음", "처음잡음" -> {
                    if (a.length < 4) return "[허드] " + a[1] + " x <종>";
                    Species sp = Species.of(a[3]);
                    if (sp == null) return "[허드] 없는 종 " + a[3];
                    if (a[1].equals("처음")) firstSeen(sp);
                    else firstKilled(sp);
                    return "[허드] " + a[1] + " " + sp.name() + " 안내=" + unknown;
                }
                case "가짜남은" -> {
                    fakeRemaining(a.length > 3 ? Integer.parseInt(a[3]) : 100);
                    return "[허드] 가짜남은";
                }
                case "허드방향" -> {
                    if (a.length < 5) return "[허드] 허드방향 x <틱> <yaw...>";
                    double[] y = new double[a.length - 4];
                    for (int i = 4; i < a.length; i++) y[i - 4] = Double.parseDouble(a[i]);
                    waveFrom("ev_dir", Integer.parseInt(a[3]), y);
                    return "[허드] 허드방향 " + y.length;
                }
                case "가리키기" -> {
                    if (p == null || a.length < 6) return "[허드] 가리키기 <이름> <x> <y> <z> [틱]";
                    point(p, new Vector(Double.parseDouble(a[3]), Double.parseDouble(a[4]), Double.parseDouble(a[5])), a.length > 6 ? Integer.parseInt(a[6]) : 100);
                    return "[허드] 가리키기";
                }
                case "깜빡" -> {
                    if (p == null) return "[허드] 사람 없음";
                    blinkRoster(p, a.length > 3 ? Integer.parseInt(a[3]) : 100);
                    return "[허드] 깜빡";
                }
                case "허드동료" -> {
                    int n = a.length > 3 ? Integer.parseInt(a[3]) : 0;
                    fakeRows.clear();
                    // 실제 작품(에일리언 2) 속 이름(Hicks 는 사람 담당 판끝막기 가짜 사람이 씀). 상태는 돌려 가며
                    String[] names = {"Vasquez", "Hudson", "Apone", "Gorman", "Drake", "Frost", "Dietrich", "Crowe", "Wierzbowski", "Spunkmeyer",
                        "Ferro", "Burke", "Newt"};
                    Life[] lives = {Life.ALIVE, Life.DEAD, Life.ALIVE, Life.DOWNED, Life.ALIVE, Life.TRAPPED, Life.ALIVE};
                    for (int i = 0; i < n; i++) fakeRows.add(new Object[] {names[i % names.length], lives[i % lives.length]});
                    return "[허드] 가짜 동료 " + n;
                }
                case "알림줄" -> {
                    if (p == null) return "[허드] 사람 없음";
                    return "[허드] 알림줄 " + p.getName() + " " + queueText(view(p.getUniqueId()));
                }
                case "웨이브알림" -> {
                    // 감독이 웨이브 시작(beginWave 안)에 사건 알림을 넣는 것과 같은 차례: 알림을 넣고 곧바로 웨이브 시작
                    if (a.length < 5) return "[허드] 웨이브알림 x <웨이브> <키[:짙기]...>";
                    if (game.state == Game.State.LOBBY) return "[허드] 판이 안 열림";
                    int n = Integer.parseInt(a[3]);
                    for (int i = 4; i < a.length; i++) {
                        int c = a[i].indexOf(':');
                        if (c > 0) alert(a[i].substring(0, c), Integer.parseInt(a[i].substring(c + 1)), 100);
                        else notice(a[i], 100);
                    }
                    game.mobs.clear();
                    game.beginWave(n);
                    return "[허드] 웨이브알림 " + n + " 알림 " + (a.length - 4);
                }
                case "이름숨김" -> {
                    testHideNames = a.length > 3 && a[3].equals("on");
                    return "[허드] 이름숨김 " + testHideNames;
                }
                case "도움말" -> {
                    if (p == null || a.length < 4) return "[허드] 도움말 <이름> <키>";
                    View v = view(p.getUniqueId());
                    v.hinted.remove(a[3]);
                    return "[허드] 도움말 " + hint(v, a[3], 80, 1);
                }
                default -> {
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            return "[허드] 수가 아님: " + e.getMessage();
        }
    }
}
