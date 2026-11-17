package kr.gave.game;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Augments.Aug;
import kr.gave.gun.Gun;
import kr.gave.gun.GunMods;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Corpse;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.player.Players;
import kr.gave.world.Plaza;

/**
 * 새 증강(2026-11-12 trip_aug)의 효과. Augments 가 들고 있고 세 곳에서 불린다: 매 틱({@link #tick}, 스케줄러), 총이 맞힐 때
 * ({@link #hitMul}, {@link #onHit}), 원정 쪽 알림(트럭 피해, 꺼내기). 숫자는 모두 {@code Aug.n("이름", 기본)}(augments.json).
 * <p>
 * 다른 작업자의 파일(Players, Shop, Mobs)은 고치지 않고 공개 칸과 메서드만 쓴다: 사람 상태가 바뀐 것은 지난 틱 값과 견줘 알아채고
 * (쓰러짐, 죽음, 돌아옴, 붙잡힘, 돈, 탄, 든 칸, 웅크림), 게이브에 거는 것은 엔진 공개 칸(speedBuff, Mobs.scale 피해 배율 훅, Mobs.lure, mark)으로.
 * 게이브 멈춤과 느림은 매 틱 speedBuff 에 곱한다(이 틱의 몹 틱이 읽음). 보스에는 멈춤, 느림, 밀침이 안 걸린다.
 */
final class AugRules {

    /** 그때그때 조건 비트(총 배율이 달라지는 것만): 웅크림, 트럭 3블록 안(짐칸 사수), 합창, 피 냄새 */
    static final int D_SNEAK = 1, D_ROOF = 2, D_CHOIR = 4, D_HASTE = 8;

    /** 사람 한 명의 증강 상태(판 틱) */
    static final class Run {
        // 매 틱 다시 잼
        double truckDist = 999;
        int near3, near12, sameGun, dyn;
        boolean downedNear, outside, mateGrabbed;
        // 지난 틱 값(바뀜을 알아챔)
        Life life;
        double hp = -1;
        int money = Integer.MIN_VALUE, shots = -1, slot = -1;
        boolean sneak;
        Gabe grab;
        UUID reviver;
        long pendingGun, rootSeen;
        final Map<String, Double> slowMine = new HashMap<>();
        // 때
        long swapUntil, hasteUntil, chainUntil, returnUntil, willUntil, bondUntil, dashUntil, dashReady, shieldUntil, warmUntil, slipReady;
        long autoAt = -999, noisyAt = -999, focusAt = -999, wallAt = -999, autoNoMoneyAt = -999;
        int chain, focusN, trapTicks, corpseUsed, hoard, debtRounds, faceKills, autoBuys;
        Gabe focus;
        /** 지난 틱에 몸이 있던 자리(서 있거나 쓰러짐): 죽으면 카메라가 업은 개체 위로 옮겨 가므로 죽은 자리는 이것으로 */
        Location at;
        boolean secondUsed, downRound, spentRound;
        long spentShop, paidOthers;
        final Set<Species> grudge = EnumSet.noneOf(Species.class);
    }

    /** 게이브 하나에 걸린 것: 멈춤, 느림, 받는 피해 더하기(표식, 지목), 낙인 */
    private static final class Mark {
        long stunUntil, slowUntil, ampUntil, brandUntil, fxAt;
        double slowMul = 1, amp = 1, brandAmp = 1;
        UUID brandBy;
    }

    /** 땅에 붙은 불(불 벽) */
    private static final class Patch {
        Location at;
        Player owner;
        long until, next, nextFx;
        double r, dps;
    }

    private static final Map<String, Guns.Kind> KIND_BY_KEY = new HashMap<>();

    static {
        for (Map.Entry<Guns.Kind, Guns.Spec> e : Guns.SPECS.entrySet()) KIND_BY_KEY.put(e.getValue().key(), e.getKey());
    }

    final Game game;
    final Augments A;
    private final Random rnd = new Random();
    private final Map<UUID, Run> runs = new HashMap<>();
    private final Map<Gabe, Mark> marks = new HashMap<>();
    private final List<Patch> patches = new ArrayList<>();
    /** 죽은 사람의 얼굴을 한 게이브 → 그 사람 */
    private final Map<Gabe, UUID> faces = new HashMap<>();
    private Game.State lastState = Game.State.LOBBY;
    private boolean halfDown;
    private long speakerUntil = -1;
    /**
     * 이번 틱에 맞은 개체 → 그 피해(Mobs.scale 훅이 적음: 무엇으로, 피해 종류). 개체마다 따로 둔다: 전역 한 칸이면 맞은 개체가 죽으면서 둘레
     * 개체에 준 피해(G05 의 즙)가 그 칸을 덮어, 그 처치에서 무기 조건(탄 돌려받기, 난사)이 빠진다
     */
    private final Map<Gabe, Damage> ctx = new java.util.IdentityHashMap<>();
    private long warnAt = -1000;
    String lastLog = "없음";
    int booms, corpseBooms, turretShots, hornBlasts, shatters, wallPatches, lures;

    AugRules(Game game, Augments a) {
        this.game = game;
        this.A = a;
    }

    // ------------------------------------------------------------------ 도우미

    Run run(Player p) {
        return runs.computeIfAbsent(p.getUniqueId(), k -> new Run());
    }

    private boolean has(Player p, Aug a) {
        return A.count(p, a) > 0;
    }

    private PState ps(Player p) {
        return game.players.get(p);
    }

    int dyn(Player p) {
        Run r = runs.get(p.getUniqueId());
        return r == null ? 0 : r.dyn;
    }

    void forget(UUID id) {
        runs.remove(id);
    }

    /** 시험: 이 사람의 때와 셈(연쇄, 구두쇠, 빚 ...)을 지움. 지난 틱 값은 남겨 바뀜을 계속 알아챔 */
    void fresh(Player p) {
        Run old = runs.get(p.getUniqueId()), n = new Run();
        if (old != null) {
            n.life = old.life;
            n.hp = old.hp;
            n.money = old.money;
            n.shots = old.shots;
            n.slot = old.slot;
            n.sneak = old.sneak;
            n.grab = old.grab;
            n.reviver = old.reviver;
            n.pendingGun = old.pendingGun;
            n.truckDist = old.truckDist;
        }
        runs.put(p.getUniqueId(), n);
    }

    void clear() {
        runs.clear();
        ctx.clear();
        marks.clear();
        patches.clear();
        faces.clear();
        speakerUntil = -1;
        halfDown = false;
    }

    private void warn(String what, RuntimeException e) {
        long now = game.now();
        if (now - warnAt < 200) return;
        warnAt = now;
        game.plugin.getLogger().log(Level.WARNING, "[증강] " + what, e);
    }

    private static boolean boss(Gabe g) {
        return g.sp.is(Role.BOSS);
    }

    private static boolean elite(Gabe g) {
        return g.sp.is(Role.ELITE) || g.sp.is(Role.TANK) || g.sp.is(Role.BOSS);
    }

    /** 증강이 주는 돈(Players.addBonus: 돈 배율은 곱하고 빚의 반값에는 안 걸림). 준 만큼 지난 값을 옮겨 '상점에서 씀' 셈에 안 걸리게 한다 */
    private void grant(Player p, int amount) {
        if (amount <= 0) return;
        int before = ps(p).money;
        game.players.addBonus(p, amount);
        moved(p.getUniqueId(), ps(p).money - before);
    }

    /** 벌이가 아닌 돈 옮김(돌려받기, 합류 몫): 지난 틱 값을 같이 옮겨 빚의 반값 벌이와 '상점에서 씀' 셈에 안 걸리게 한다 */
    void moved(UUID id, int amount) {
        Run r = runs.get(id);
        if (r != null && r.money != Integer.MIN_VALUE) r.money += amount;
    }

    /** 낸 돈을 돌려받음(꺼내기 모금이 쉬는 시간 공짜 부활로 풀림): 물주(낸 돈의 몫), 단골(쓴 돈의 몫)의 셈에서 뺀다 */
    void refunded(UUID id, String what, int amount) {
        Run r = runs.get(id);
        if (r == null) return;
        if ("repair".equals(what) || "revive".equals(what)) r.paidOthers = Math.max(0, r.paidOthers - amount);
        r.spentShop = Math.max(0, r.spentShop - amount);
    }

    private Mark mark(Gabe g) {
        return marks.computeIfAbsent(g, k -> new Mark());
    }

    /** 멈춤(보스 빼고) */
    void stun(Gabe g, int ticks) {
        if (g == null || g.dead || boss(g) || ticks <= 0) return;
        Mark m = mark(g);
        m.stunUntil = Math.max(m.stunUntil, game.now() + ticks);
    }

    /** 느림(빠르기 mul 배, 보스 빼고) */
    void slow(Gabe g, int ticks, double mul) {
        if (g == null || g.dead || boss(g) || ticks <= 0) return;
        Mark m = mark(g);
        long now = game.now();
        m.slowMul = now < m.slowUntil ? Math.min(m.slowMul, mul) : mul;
        m.slowUntil = Math.max(m.slowUntil, now + ticks);
    }

    /** 받는 피해 k 배(모두에게서, 걸려 있으면 큰 쪽) */
    void amp(Gabe g, int ticks, double k) {
        if (g == null || g.dead || ticks <= 0) return;
        Mark m = mark(g);
        long now = game.now();
        m.amp = now < m.ampUntil ? Math.max(m.amp, k) : k;
        m.ampUntil = Math.max(m.ampUntil, now + ticks);
    }

    private void brand(Gabe g, UUID by, int ticks, double k) {
        if (g == null || g.dead) return;
        Mark m = mark(g);
        m.brandBy = by;
        m.brandAmp = k;
        m.brandUntil = game.now() + ticks;
    }

    /** Mobs.scale 훅: 게이브가 받는 모든 피해에 곱함(표식, 지목, 낙인). 맞힘 문맥도 여기서 적는다 */
    double scale(Gabe g, Damage d) {
        ctx.put(g, d);
        if (marks.isEmpty()) return 1;
        Mark m = marks.get(g);
        if (m == null) return 1;
        long now = game.now();
        double k = 1;
        if (now < m.ampUntil) k *= m.amp;
        if (now < m.brandUntil) k *= m.brandAmp;
        return k;
    }

    /** 작은 폭발: 반경 r 안 게이브에 dmg(사람은 안 다침) */
    private void boom(Vector at, double r, double dmg, Player by, String how) {
        Location loc = at.toLocation(game.world);
        game.shotFx.blast(loc, (float) r);
        Props.soundAround(loc, null, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1f, 1.5f, 24, -1);
        for (Object o : game.mobs.inRadius(loc, r)) {
            Vector c = game.mobs.center(o);
            if (c == null) continue;
            Vector dir = c.clone().subtract(at);
            dir = dir.lengthSquared() < 1e-6 ? new Vector(0, 1, 0) : dir.normalize();
            game.mobs.damage(o, new Damage(dmg, DamageType.EXPLOSION, by, c.clone(), dir, how, 0));
        }
    }

    private double waveGrow(double grow) {
        return 1 + grow * Math.max(0, game.wave - 1);
    }

    // ------------------------------------------------------------------ 매 틱

    void tick() {
        Game.State st = game.state;
        long now = game.now();
        ctx.clear();
        if (st == Game.State.LOBBY) {
            lastState = st;
            if (!marks.isEmpty()) marks.clear();
            if (!patches.isEmpty()) patches.clear();
            return;
        }
        boolean waveStart = st == Game.State.WAVE && lastState != Game.State.WAVE;
        lastState = st;
        if (waveStart) speakerUntil = now + (long) Aug.P_SPEAKER.n("ticks", 200);
        List<Player> in = game.players.inPlay();
        int total = 0, standing = 0;
        boolean grabbed = false;
        for (Player p : in) {
            PState s = ps(p);
            if (s.life == Life.WAITING) continue;
            total++;
            if (s.standing()) {
                standing++;
                if (s.grabbedBy != null) grabbed = true;
            }
        }
        halfDown = total >= 2 && standing * 2 <= total;
        Vector truck = A.truckPos();
        for (Player p : in) {
            try {
                person(p, in, truck, grabbed, now);
            } catch (RuntimeException e) {
                warn("사람 틱 오류 " + p.getName(), e);
            }
        }
        marksTick(now);
        patchTick(now);
        if (st == Game.State.WAVE) partsTick(now, truck);
        coverTick(in, now);
    }

    private void person(Player p, List<Player> in, Vector truck, boolean anyGrabbed, long now) {
        PState s = ps(p);
        Run r = run(p);
        Guns.Arms a = game.guns.arms(p);
        Location l = p.getLocation();
        r.truckDist = A.links.onTruck(p) ? 0 : Math.hypot(l.getX() - truck.getX(), l.getZ() - truck.getZ());
        r.outside = Math.hypot(l.getX() - (Plaza.CX + 0.5), l.getZ() - (Plaza.CZ + 0.5)) > Plaza.R_SAFE + 0.5;
        // 곁의 동료
        int n3 = 0, n12 = 0, same = 0;
        boolean dn = false;
        double backR = Aug.M_BACK.n("r", 3), loneR = Aug.M_LONE.n("r", 12), choirR = Aug.M_CHOIR.n("r", 8), coverR = Aug.M_COVER.n("r", 8);
        for (Player o : in) {
            if (o == p || o.getWorld() != p.getWorld()) continue;
            PState os = ps(o);
            double d = o.getLocation().distance(l);
            if (os.standing()) {
                if (d <= backR) n3++;
                if (d <= loneR) n12++;
                if (d <= choirR && a != null && a.primary != null) {
                    Guns.Arms oa = game.guns.arms(o);
                    if (oa != null && oa.primary == a.primary) same++;
                }
            } else if (os.life == Life.DOWNED && d <= coverR) dn = true;
        }
        r.near3 = n3;
        r.near12 = n12;
        r.sameGun = same;
        r.downedNear = dn;
        r.mateGrabbed = anyGrabbed && s.grabbedBy == null;
        boolean sneak = p.isSneaking();
        int slot = p.getInventory().getHeldItemSlot();
        Gun pg = game.shop.pendingGun(p);
        long pid = pg == null ? 0 : pg.id;
        if (r.life == null) {
            // 처음 본 틱: 지난 값 채우기만
            r.life = s.life;
            r.hp = s.hp;
            r.money = s.money;
            r.shots = a == null ? 0 : a.shots;
            r.slot = slot;
            r.sneak = sneak;
            r.grab = s.grabbedBy;
            r.pendingGun = pid;
        }
        // ---- 삶이 바뀜
        if (s.life != r.life) lifeChanged(p, s, r, r.life, now);
        r.life = s.life;
        r.reviver = s.reviver;
        // ---- 웅크렸다 일어섬: 튀어 나가기
        if (r.sneak && !sneak && s.standing() && now >= r.dashReady && has(p, Aug.B_DASH)) {
            r.dashUntil = now + (long) Aug.B_DASH.n("ticks", 20);
            r.dashReady = now + (long) Aug.B_DASH.n("cd", 120);
            Props.sound(p, null, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1f, 1.6f);
        }
        r.sneak = sneak;
        // ---- 총을 바꿈: 빠른 손바꿈
        if (slot != r.slot && slot <= Guns.SLOT_PISTOL && r.slot >= 0 && r.slot <= Guns.SLOT_PISTOL && has(p, Aug.W_SWAP))
            r.swapUntil = now + (long) Aug.W_SWAP.n("ticks", 40);
        r.slot = slot;
        // ---- 쏨
        if (a != null) {
            if (a.shots > r.shots && r.shots >= 0 && s.standing()) shot(p, r, l, now);
            r.shots = a.shots;
        }
        // ---- 다침: 가시 옷
        if (r.hp >= 0 && s.hp < r.hp - 0.01 && s.lastHurtAt >= now - 1 && s.lastBy != null && has(p, Aug.B_THORNS)) thorns(p, s, l);
        r.hp = s.hp;
        // ---- 붙잡힘, 놓임
        if (s.grabbedBy != r.grab) {
            if (s.grabbedBy != null && has(p, Aug.B_SLIP)) s.escapeNeed = Math.max(1, (int) Math.ceil(s.escapeNeed * Aug.B_SLIP.n("clicks", 0.5)));
            if (s.grabbedBy == null && r.grab != null && s.standing()) tether(p, in, l);
            r.grab = s.grabbedBy;
        }
        // ---- 갇힘: 미끄러운 몸, 몸부림
        if (s.life == Life.TRAPPED && s.eater != null && !s.eater.dead) {
            r.trapTicks++;
            if (has(p, Aug.B_SLIP) && r.trapTicks >= Aug.B_SLIP.n("ticks", 120) && now >= r.slipReady) {
                r.slipReady = now + (long) Aug.B_SLIP.n("cd", 1200);
                lastLog = "미끄러운 몸 " + p.getName();
                // 본인만 빠져나온다(freeFrom 은 그 개체가 잡힌 것으로 쳐 같이 갇힌 사람도 풀고, 보상 없는 개체면 원정에서 죽음으로 친다)
                game.players.free(p);
            } else if (has(p, Aug.D_ESCAPE) && r.trapTicks >= Aug.D_ESCAPE.n("after", 240) && r.trapTicks % 20 == 0) {
                Gabe e = s.eater;
                game.mobs.damage(e, new Damage(e.maxHp * Aug.D_ESCAPE.n("dps", 0.08), DamageType.OTHER, p, e.pos.clone(), new Vector(0, 1, 0), "aug_escape", 0));
                lastLog = "몸부림 " + p.getName() + " " + e.sp.name() + String.format(Locale.ROOT, " %.0f/%.0f", e.hp, e.maxHp);
            }
        } else if (s.life != Life.TRAPPED) r.trapTicks = 0;
        // ---- 느려짐, 묶임: 무쇠 다리
        if (has(p, Aug.B_IRON)) iron(s, r, now);
        // ---- 돈이 바뀜: 상점에서 씀(단골, 구두쇠). 빚의 반값 벌이는 Players.addMoney 가 지갑 값(Wallet.earnMul)으로 건다
        int delta = s.money - r.money;
        if (r.money != Integer.MIN_VALUE && delta < 0 && game.shop.view(p) != null) spentNow(r, -delta);
        // ---- 처치 돈 배율(현상금, 얇은 피), 팁 몫, 물주 몫, 빚의 벌이 배율을 지갑에 알림(1초에 한 번)
        if (now % kr.gave.player.Wallet.EVERY == 0) game.players.wallet.rate(s.id, killMul(p) * (has(p, Aug.M_TIP) ? 1 + Aug.M_TIP.n("frac", 0.1) : 1),
            has(p, Aug.M_TIP) ? Aug.M_TIP.n("frac", 0.1) : 0,
            has(p, Aug.E_PATRON) ? Aug.E_PATRON.n("frac", 0.1) : 0, r.debtRounds > 0 ? Aug.X_DEBT.n("earn", 0.5) : 1);
        r.money = s.money;
        // ---- 뽑은 총을 정함(바꾸든 버리든 한 자루가 버려짐): 장물
        if (r.pendingGun != 0 && pid != r.pendingGun && has(p, Aug.E_FENCE)) grant(p, (int) Math.round(Aug.E_FENCE.n("u", 0.3) * A.unit()));
        r.pendingGun = pid;
        // ---- 미니건이 다 돎: 달궈진 총열
        if (has(p, Aug.W_MINIGUN_WARM) && game.guns.spin(p) >= 0.99) r.warmUntil = now + (long) Aug.W_MINIGUN_WARM.n("ticks", 160);
        // ---- 돌아보지 않는다: 일으키기 채널을 끊음
        if (s.helping != null && s.helpKind == PState.Help.REVIVE && has(p, Aug.X_NOMERCY)) {
            PState ts = game.players.get(s.helping);
            if (ts != null && p.getUniqueId().equals(ts.reviver)) {
                ts.reviver = null;
                ts.reviveUntil = 0;
            }
            s.helping = null;
            s.helpKind = null;
        }
        // ---- 가끔 하는 것(사람마다 어긋나게)
        long beat = now + (p.getEntityId() & 63);
        if (s.standing()) {
            if (beat % 10 == 0 && r.truckDist <= Aug.T_BAIT.n("r", 5) + 3 && has(p, Aug.T_BAIT)) {
                for (Gabe g : game.mobs.near(truck, Aug.T_BAIT.n("r", 5), x -> !boss(x))) game.mobs.mark(g, p);
            }
            if (beat % 20 == 0 && has(p, Aug.X_MARKED)) {
                for (Gabe g : game.mobs.near(l.toVector(), Aug.X_MARKED.n("r", 40), AugRules::elite)) if (g.target != p) game.mobs.mark(g, p);
            }
            int ears = (int) Aug.B_EARS.n("every", 40);
            if (beat % ears == 0 && has(p, Aug.B_EARS)) {
                for (Gabe g : game.mobs.near(l.toVector(), Aug.B_EARS.n("r", 24), x -> x.disguised || elite(x))) game.hud.point(p, g.pos.clone(), ears);
            }
            autoAmmo(p, s, r, a, now);
        }
        // ---- 총 배율이 달라지는 조건
        int dyn = 0;
        if (sneak && (has(p, Aug.CROUCH) || has(p, Aug.W_LMG_BRACE))) dyn |= D_SNEAK;
        if (r.truckDist <= Aug.T_ROOF.n("r", 3) && has(p, Aug.T_ROOF)) dyn |= D_ROOF;
        if (r.sameGun + 1 >= Aug.M_CHOIR.n("need", 3) && has(p, Aug.M_CHOIR)) dyn |= D_CHOIR;
        if (now < r.hasteUntil) dyn |= D_HASTE;
        r.dyn = dyn;
        if (s.life == Life.ALIVE || s.life == Life.DOWNED) r.at = l.clone();
        A.apply(p);
    }

    private void lifeChanged(Player p, PState s, Run r, Life from, long now) {
        Life to = s.life;
        // 내기: 쓰러지지 않고 바로 먹힌 라운드도 진 것으로
        if (to == Life.TRAPPED && from == Life.ALIVE) r.downRound = true;
        if (to == Life.DOWNED && from == Life.ALIVE) {
            r.downRound = true;
            // 원한: 나나 동료를 쓰러뜨린 종을 원한을 가진 모두가 기억한다
            if (s.lastBy != null) for (Player q : game.players.inPlay()) {
                Run rq = runs.get(q.getUniqueId());
                if (rq != null && has(q, Aug.D_GRUDGE)) rq.grudge.add(s.lastBy);
            }
            // 미련: 쓰러지는 순간 곁의 것들이 잠깐 멈춘다(일으킬 틈)
            if (has(p, Aug.D_GHOST)) {
                int ticks = (int) Aug.D_GHOST.n("ticks", 80);
                for (Gabe g : game.mobs.near(p.getLocation().toVector(), Aug.D_GHOST.n("r", 6), x -> true)) stun(g, ticks);
            }
            if (has(p, Aug.B_SECOND) && !r.secondUsed) {
                // Players.hurt 에 cheatDeath 고리가 없을 때의 대체: 쓰러진 다음 틱 맨 앞에 바로 일으킨다
                r.secondUsed = true;
                game.players.reviveHere(p, null);
                r.shieldUntil = now + (long) Aug.B_SECOND.n("ticks", 20);
                lastLog = "두 번째 숨 " + p.getName();
                return;
            }
            if (has(p, Aug.X_BLEED)) {
                long left = s.bleedUntil - now;
                s.bleedUntil = now + Math.max(20, Math.round(left * Aug.X_BLEED.n("bleed", 0.5)));
            }
        }
        if ((to == Life.DEAD || to == Life.TRAPPED) && from != Life.DEAD && from != Life.TRAPPED) {
            r.trapTicks = 0;
            if (s.deathMob != null) faces.put(s.deathMob, p.getUniqueId());
            if (has(p, Aug.M_WILL)) will(p, now);
        }
        if (to == Life.ALIVE && (from == Life.DEAD || from == Life.TRAPPED) && has(p, Aug.D_RETURN)) {
            r.returnUntil = now + (long) Aug.D_RETURN.n("ticks", 400);
            lastLog = "돌아온 자 " + p.getName();
        }
        if (to == Life.ALIVE && from == Life.DOWNED) {
            Player helper = r.reviver == null ? null : Bukkit.getPlayer(r.reviver);
            if (helper != null && has(helper, Aug.M_MEDIC)) {
                s.hp = Math.max(s.hp, s.maxHp * Aug.M_MEDIC.n("hp", 1.0));
                r.shieldUntil = now + (long) Aug.M_MEDIC.n("ticks", 100);
                lastLog = "위생병 " + helper.getName() + " -> " + p.getName();
            }
            if (A.part(Aug.P_MEDBAY) > 0 && r.truckDist <= Aug.P_MEDBAY.n("r", 4)) s.hp = Math.max(s.hp, s.maxHp * Aug.P_MEDBAY.n("hp", 0.6));
        }
    }

    /** 유언: 죽은 자리 곁의 서 있는 동료는 탄약 가득 + 잠깐 피해 */
    private void will(Player p, long now) {
        Run me = run(p);
        Location l = me.at != null ? me.at : p.getLocation();
        int n = 0;
        for (Player o : game.players.standing()) {
            if (o == p || o.getWorld() != l.getWorld() || o.getLocation().distance(l) > Aug.M_WILL.n("r", 6)) continue;
            game.guns.refillAll(o);
            run(o).willUntil = now + (long) Aug.M_WILL.n("ticks", 200);
            Props.sound(o, null, Sound.ITEM_ARMOR_EQUIP_IRON, 0.8f, 1f, 0.9f);
            n++;
        }
        lastLog = "유언 " + p.getName() + " 받은 사람 " + n;
    }

    /** 한 발 쏜 틱: 시끄러운 총, 화약 연기, 사체 폭탄 */
    private void shot(Player p, Run r, Location l, long now) {
        Guns.Spec held = game.guns.held(p);
        Guns.Kind k = held == null ? null : held.kind();
        if (now - r.noisyAt >= 10 && has(p, Aug.X_NOISY)) {
            r.noisyAt = now;
            for (Gabe g : game.mobs.near(l.toVector(), Aug.X_NOISY.n("r", 12), x -> !boss(x))) game.mobs.mark(g, p);
        }
        if (Aug.W_MUSKET_SMOKE.binds(k) && has(p, Aug.W_MUSKET_SMOKE)) {
            int ticks = (int) Aug.W_MUSKET_SMOKE.n("ticks", 20);
            for (Gabe g : game.mobs.near(l.toVector(), Aug.W_MUSKET_SMOKE.n("r", 4), x -> true)) stun(g, ticks);
            Location eye = p.getEyeLocation();
            game.shotFx.pop(eye.add(eye.getDirection().multiply(1.2)));
        }
        if (held != null && r.corpseUsed < Aug.K_CORPSE.n("uses", 10) && has(p, Aug.K_CORPSE)) corpseShot(p, r, held);
    }

    /** 사체 폭탄: 시선 위(0.7 안)에 사체가 있고 블록에 안 가렸으면 터뜨림 */
    private void corpseShot(Player p, Run r, Guns.Spec held) {
        Location eye = p.getEyeLocation();
        Vector o = eye.toVector(), d = eye.getDirection();
        Corpse best = null;
        double bt = Math.min(held.range(), 60);
        for (Corpse c : game.mobs.corpses()) {
            if (c.gone()) continue;
            Vector to = c.pos.clone().add(new Vector(0, 0.2, 0)).subtract(o);
            double t = to.dot(d);
            if (t < 1 || t > bt) continue;
            if (to.clone().subtract(d.clone().multiply(t)).lengthSquared() > 0.7 * 0.7) continue;
            best = c;
            bt = t;
        }
        if (best == null) return;
        RayTraceResult rb = game.world.rayTraceBlocks(eye, d, Math.max(0.1, bt - 0.8), FluidCollisionMode.NEVER, true);
        if (rb != null) return;
        Vector at = best.pos.clone().add(new Vector(0, 0.3, 0));
        game.mobs.eat(best);
        r.corpseUsed++;
        corpseBooms++;
        boom(at, Aug.K_CORPSE.n("r", 3), Aug.K_CORPSE.n("dmg", 60) * waveGrow(Aug.K_CORPSE.n("grow", 0.08)), p, "aug_corpse");
        lastLog = "사체 폭탄 " + p.getName() + " " + r.corpseUsed;
    }

    /** 가시 옷: 나를 다치게 한 종의 가장 가까운 개체에 돌려줌 */
    private void thorns(Player p, PState s, Location l) {
        Species by = s.lastBy;
        Gabe g = game.mobs.nearest(l.toVector(), Aug.B_THORNS.n("r", 3.5), x -> x.sp == by && !boss(x));
        if (g == null) return;
        double dmg = Aug.B_THORNS.n("dmg", 8) * waveGrow(Aug.B_THORNS.n("grow", 0.08));
        Vector dir = g.pos.clone().subtract(l.toVector());
        dir = dir.lengthSquared() < 1e-6 ? new Vector(0, 1, 0) : dir.normalize();
        game.mobs.damage(g, new Damage(dmg, DamageType.OTHER, p, g.pos.clone(), dir, "aug_thorns", 0));
        lastLog = "가시 옷 " + p.getName() + " " + g.sp.name() + String.format(Locale.ROOT, " %.1f", dmg);
    }

    /** 끈: 붙잡혔다 풀린 사람 곁에 끈을 가진 동료가 있으면 둘 다 낫는다 */
    private void tether(Player p, List<Player> in, Location l) {
        for (Player o : in) {
            if (o == p || o.getWorld() != l.getWorld() || !ps(o).standing() || !has(o, Aug.M_TETHER)) continue;
            if (o.getLocation().distance(l) > Aug.M_TETHER.n("r", 3.5)) continue;
            double heal = Aug.M_TETHER.n("heal", 15);
            game.players.heal(p, heal);
            game.players.heal(o, heal);
            lastLog = "끈 " + o.getName() + " -> " + p.getName();
            return;
        }
    }

    /** 무쇠 다리: 새로 걸린 느려짐은 세기를, 묶임은 남은 시간을 줄임 */
    private void iron(PState s, Run r, long now) {
        double mul = Aug.B_IRON.n("mul", 0.5);
        for (Map.Entry<String, Long> e : s.slowUntil.entrySet()) {
            if (e.getValue() <= now) continue;
            Double cur = s.slowPct.get(e.getKey());
            if (cur == null) continue;
            Double mine = r.slowMine.get(e.getKey());
            if (mine != null && Math.abs(cur - mine) < 1e-9) continue;
            double v = cur * mul;
            s.slowPct.put(e.getKey(), v);
            r.slowMine.put(e.getKey(), v);
        }
        if (s.rootUntil > now && s.rootUntil != r.rootSeen) {
            s.rootUntil = now + Math.max(1, Math.round((s.rootUntil - now) * mul));
            r.rootSeen = s.rootUntil;
        }
    }

    /** 상점 고리 {@link Shop#ammoBought}(손으로 사든 자동 탄약이 사든 탄약을 산 뒤): 나눠 쓰기. Game 생성자가 단다 */
    void ammoBought(Player p) {
        if (has(p, Aug.M_SHARE)) share(p, game.players.inPlay(), p.getLocation());
    }

    /** 나눠 쓰기: 곁의 가까운 동료 count 명의 탄약도 채움 */
    private void share(Player p, List<Player> in, Location l) {
        double rr = Aug.M_SHARE.n("r", 6);
        List<Player> near = new ArrayList<>();
        for (Player o : in) if (o != p && o.getWorld() == l.getWorld() && ps(o).standing() && o.getLocation().distance(l) <= rr) near.add(o);
        near.sort((x, y) -> Double.compare(x.getLocation().distanceSquared(l), y.getLocation().distanceSquared(l)));
        int n = 0;
        for (Player o : near) {
            if (n >= Aug.M_SHARE.n("count", 2)) break;
            if (!game.guns.refillAll(o)) continue;
            Props.sound(o, null, Sound.ITEM_ARMOR_EQUIP_IRON, 0.8f, 1f, 1.2f);
            n++;
        }
        lastLog = "나눠 쓰기 " + p.getName() + " " + n + "명";
    }

    // ------------------------------------------------------------------ 자동 탄약(상점 기본 판매)

    /**
     * 자동 탄약: 샀고 켜져 있고, 서 있고, 트럭 r 블록 안(추격전 판 위는 늘 곁)이고, 주무기의 탄창과 예비탄이 모두 0 이면 탄약 칸을 그때 값으로 산다.
     * 돈이 모자라거나 그 칸이 갉히는 중이면 안 삼. cd 틱에 한 번까지. 살 때 그 사람에게만 소리와 HUD 한 줄(글귀 aug_autoammo)
     */
    private boolean autoAmmo(Player p, PState s, Run r, Guns.Arms a, long now) {
        if (a == null || a.primary == null || !A.stapleOn(p, Augments.AUTO_AMMO)) return false;
        if (game.state != Game.State.WAVE && game.state != Game.State.BREAK && game.state != Game.State.PREP) return false;
        int k = a.primary.ordinal();
        if (a.mag[k] > 0 || a.res[k] > 0) return false;
        Guns.Spec spec = a.spec(a.primary);
        if (spec == null || spec.reserve() < 0) return false;
        if (now - r.autoAt < A.stapleN(Augments.AUTO_AMMO, "cd", 60)) return false;
        if (r.truckDist > A.stapleN(Augments.AUTO_AMMO, "r", 8)) return false;
        if (Shop.Ware.AMMO.key().equals(game.shop.gnawing())) return false;
        int price = game.shop.price(p, Shop.Ware.AMMO);
        if (s.money < price) {
            // 돈이 모자람: 3초에 한 번만 빈 소리
            if (now - r.autoNoMoneyAt >= 60) {
                r.autoNoMoneyAt = now;
                Props.sound(p, null, Sound.BLOCK_DISPENSER_FAIL, 0.5f, 1f, 1.4f);
                lastLog = "자동 탄약 돈 모자람 " + p.getName() + " " + s.money + "/" + price;
            }
            return false;
        }
        if (!game.shop.buy(p, Shop.Ware.AMMO)) return false;
        r.autoAt = now;
        r.autoBuys++;
        spentNow(r, price);
        r.money = s.money;
        Props.sound(p, null, Sound.BLOCK_DISPENSER_DISPENSE, 0.7f, 1f, 1.5f);
        if (Hud.hasPhrase("aug_autoammo")) game.hud.notice(p, "aug_autoammo", 40);
        lastLog = "자동 탄약 " + p.getName() + " -" + price + " 남은 돈 " + s.money;
        return true;
    }

    String autoStatus(Player p) {
        Run r = run(p);
        Guns.Arms a = game.guns.arms(p);
        int k = a == null || a.primary == null ? -1 : a.primary.ordinal();
        return String.format(Locale.ROOT, "autoBuys=%d truckDist=%.1f mag=%d res=%d ammoPrice=%d last=%s", r.autoBuys, r.truckDist, k < 0 ? -1 : a.mag[k],
            k < 0 ? -1 : a.res[k], game.shop.price(p, Shop.Ware.AMMO), lastLog);
    }

    // ------------------------------------------------------------------ 돈

    /** 처치 돈 배율(지갑이 팀 몫과 때린 몫에 곱함): 현상금 장마다 +10%, 얇은 피 +30%. 빚의 반값은 모든 벌이에(Players.addMoney) */
    private double killMul(Player p) {
        double k = 1 + 0.10 * A.count(p, Aug.BOUNTY);
        if (has(p, Aug.X_BLEED)) k += Aug.X_BLEED.n("reward", 0.3);
        return k;
    }

    private void spentNow(Run r, int amount) {
        r.spentShop += amount;
        r.spentRound = true;
        r.hoard = 0;
    }

    /** 상점 밖에서 여기로 알려 주는 씀(기본 판매 사기) */
    void spent(Player p, int amount) {
        Run r = run(p);
        spentNow(r, amount);
        r.money = ps(p).money;
    }

    /**
     * 원정 쪽이 알려 주는 씀(단골, 구두쇠가 셈). 트럭 수리 값은 지갑이 찬 만큼 갚으므로(Wallet.repairFill) 단골 셈에 안 넣고 '씀'(구두쇠)으로만 친다.
     * 꺼내기 값은 넣어 두었다가 쉬는 시간 환불(Revive.refund)이나 팀이 갚기로 할 때(Wallet.owe) refunded 로 뺀다. 물주는 지갑이 갚을 때 준다
     */
    void paid(Player p, String what, int amount) {
        Run r = run(p);
        if ("repair".equals(what)) {
            r.spentRound = true;
            r.hoard = 0;
        } else {
            if ("revive".equals(what)) r.paidOthers += amount;
            spentNow(r, amount);
        }
        r.money = ps(p).money;
    }

    void revived(Player dead, Player payer) {
        long now = game.now();
        if (dead != null && has(dead, Aug.D_RETURN)) run(dead).returnUntil = now + (long) Aug.D_RETURN.n("ticks", 400);
        if (dead != null && payer != null && has(payer, Aug.M_RANSOM)) {
            long until = now + (long) Aug.M_RANSOM.n("ticks", 6000);
            run(dead).bondUntil = until;
            run(payer).bondUntil = until;
        }
    }

    /** 쉬는 시간 시작(사람마다): 이자, 단골, 내기, 구두쇠, 주사위, 빚(물주는 지갑이 갚을 때 준다) */
    void onBreak(Player p) {
        Run r = run(p);
        PState s = ps(p);
        int u = A.unit();
        if (s.life != Life.WAITING) {
            if (has(p, Aug.E_INTEREST)) grant(p, (int) Math.min(Math.floor(s.money * Aug.E_INTEREST.n("frac", 0.06)), Math.round(Aug.E_INTEREST.n("cap", 0.5) * u)));
            if (r.spentShop > 0 && has(p, Aug.T_CASHBACK)) grant(p, (int) Math.floor(r.spentShop * Aug.T_CASHBACK.n("frac", 0.08)));
            if (!r.downRound && s.standing() && has(p, Aug.E_BET)) grant(p, (int) Math.round(Aug.E_BET.n("u", 0.5) * u));
            if (has(p, Aug.E_HOARD)) r.hoard = r.spentRound ? r.hoard / 2 : (int) Math.min(Aug.E_HOARD.n("max", 10), r.hoard + 1);
            if (has(p, Aug.X_GAMBLE)) gamble(p);
        }
        if (r.debtRounds > 0) r.debtRounds--;
        r.spentShop = 0;
        r.paidOthers = 0;
        r.spentRound = false;
        r.downRound = false;
        r.secondUsed = false;
        r.corpseUsed = 0;
    }

    /** 주사위: 뜰 수 있는 것(위험 빼고) 하나를 얻고, 가진 것(주사위와 방금 얻은 것 빼고) 하나를 잃음 */
    private void gamble(Player p) {
        List<Aug> can = new ArrayList<>();
        for (Aug a : Aug.values()) if (!a.risk && A.offerable(p, a)) can.add(a);
        if (can.isEmpty()) return;
        Aug gain = can.get(rnd.nextInt(can.size()));
        List<Aug> lose = new ArrayList<>();
        for (String k : ps(p).augs) {
            Aug a = Aug.of(k);
            if (a != null && a != Aug.X_GAMBLE) lose.add(a);
        }
        Aug lost = lose.isEmpty() ? null : lose.get(rnd.nextInt(lose.size()));
        if (lost != null) A.take(p, lost);
        A.give(p, gain);
        Props.sound(p, null, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1f, 0.8f);
        lastLog = "주사위 " + p.getName() + " +" + gain.key + " -" + (lost == null ? "없음" : lost.key);
    }

    /** 증강을 얻은 순간: 빚은 지금 큰돈 */
    void onGive(Player p, Aug a) {
        if (a == Aug.X_DEBT) {
            Run r = run(p);
            grant(p, (int) Math.round(a.n("u", 4) * A.unit()));
            r.debtRounds += (int) a.n("rounds", 5);
        }
    }

    // ------------------------------------------------------------------ 스탯(Augments.apply 가 곱함)

    double hpAdd(Player p) {
        Run r = runs.get(p.getUniqueId());
        return r == null ? 0 : Aug.D_FACE.n("hp", 5) * r.faceKills;
    }

    double hpMul(Player p) {
        return has(p, Aug.X_GLASS) ? Aug.X_GLASS.n("hp", 0.6) : 1;
    }

    double armorMul(Player p) {
        Run r = runs.get(p.getUniqueId());
        if (r == null) return 1;
        long now = game.now();
        if (now < r.shieldUntil) return 0;
        double k = 1;
        if (r.truckDist <= Aug.T_GUARD.n("r", 6) && has(p, Aug.T_GUARD)) k *= Aug.T_GUARD.n("armor", 0.9);
        if (halfDown && has(p, Aug.M_LAST)) k *= Aug.M_LAST.n("armor", 0.85);
        if (now < r.returnUntil) k *= Aug.D_RETURN.n("armor", 0.7);
        if (now < r.warmUntil && has(p, Aug.W_MINIGUN_WARM)) k *= Aug.W_MINIGUN_WARM.n("armor", 0.85);
        if (r.outside && has(p, Aug.B_SCOUT)) k *= Aug.B_SCOUT.n("armor", 0.9);
        return k;
    }

    double moveMul(Player p) {
        Run r = runs.get(p.getUniqueId());
        double k = 1;
        if (has(p, Aug.X_HEAVY)) k *= Aug.X_HEAVY.n("move", 0.88);
        if (has(p, Aug.X_ONEWAY)) k *= Aug.X_ONEWAY.n("move", 1.08);
        if (r == null) return k;
        long now = game.now();
        if (r.near12 == 0 && has(p, Aug.M_LONE)) k *= Aug.M_LONE.n("move", 1.1);
        if (now < r.dashUntil) k *= Aug.B_DASH.n("move", 1.6);
        if (r.outside && has(p, Aug.B_SCOUT)) k *= Aug.B_SCOUT.n("move", 1.12);
        if (r.mateGrabbed && has(p, Aug.M_TETHER)) k *= Aug.M_TETHER.n("move", 1.25);
        if (now < r.hasteUntil) k *= Aug.K_HASTE.n("move", 1.15);
        if (has(p, Aug.W_SMG_RUN)) {
            Guns.Spec held = game.guns.held(p);
            if (held != null && Aug.W_SMG_RUN.binds(held.kind())) k *= Aug.W_SMG_RUN.n("move", 1.1);
        }
        return k;
    }

    /** 일으키기 빠르기 배율(들것: 트럭 곁에서 두 배) */
    double reviveSpeed(Player p) {
        Run r = runs.get(p.getUniqueId());
        if (r != null && A.part(Aug.P_MEDBAY) > 0 && r.truckDist <= Aug.P_MEDBAY.n("r", 4)) return 1 / Math.max(0.1, Aug.P_MEDBAY.n("time", 0.5));
        return 1;
    }

    boolean keepSpin(Player p) {
        Run r = runs.get(p.getUniqueId());
        return r != null && game.now() < r.warmUntil && has(p, Aug.W_MINIGUN_WARM);
    }

    /** 피로 사는 탄: 탄이 없을 때 체력으로 한 발(체력이 floor 아래로는 안 내려감) */
    boolean dryFire(Player p, Guns.Spec spec) {
        PState s = ps(p);
        // 자동 탄약: 빈 총을 당긴 그 순간에도 삼(연사가 빨라 한 틱에 여러 발인 총이 권총으로 넘어가기 전에). 샀으면 탄창이 찼으니 그대로 쏜다
        if (A.stapleOn(p, Augments.AUTO_AMMO) && autoAmmo(p, s, run(p), game.guns.arms(p), game.now())) return false;
        if (!has(p, Aug.X_BLOOD)) return false;
        double cost = Aug.X_BLOOD.n("hp", 2);
        if (!s.standing() || s.hp - cost < Aug.X_BLOOD.n("floor", 10)) return false;
        s.hp -= cost;
        run(p).hp = s.hp;
        return true;
    }

    boolean cheatDeath(Player p) {
        Run r = run(p);
        if (r.secondUsed || !has(p, Aug.B_SECOND)) return false;
        r.secondUsed = true;
        r.shieldUntil = game.now() + (long) Aug.B_SECOND.n("ticks", 20);
        lastLog = "두 번째 숨 " + p.getName();
        return true;
    }

    // ------------------------------------------------------------------ 총 배율

    /** 늘 걸리는 총 배율(한 번 가질 때마다) */
    GunMods flatMods(Aug a, GunMods m) {
        return switch (a) {
            case X_GLASS, X_NOISY, X_NOMERCY, X_ONEWAY, X_FEED -> m.mulDamage(a.n("dmg", 1));
            case X_HEAVY -> m.mulReserve(a.n("reserve", 2));
            default -> m;
        };
    }

    /** 그때그때 조건으로 걸리는 총 배율 */
    GunMods dynMods(int dyn, GunMods m) {
        if ((dyn & D_ROOF) != 0) m = m.mulRange(Aug.T_ROOF.n("range", 1.3)).mulSpread(Aug.T_ROOF.n("spread", 0.7));
        if ((dyn & D_CHOIR) != 0) m = m.mulRate(Aug.M_CHOIR.n("rate", 1.12));
        if ((dyn & D_HASTE) != 0) m = m.mulReload(Aug.K_HASTE.n("reload", 0.8));
        return m;
    }

    /** 종류 k 에만 걸리는 총 배율 */
    GunMods kindMods(Player p, Guns.Kind k, GunMods m, int dyn) {
        Aug a = Aug.W_PISTOL_FAST;
        if (a.binds(k) && has(p, a)) m = m.mulRate(a.n("rate", 1.6)).mulMag(a.n("mag", 2));
        a = Aug.W_SHOTGUN_SLUG;
        if (a.binds(k) && has(p, a)) {
            // 알 수를 하나로 줄이고 그 한 알에 알 모두의 피해 x k 를 싣는다(넓은 산탄으로 는 알도 셈)
            int pellets = Math.max(1, Guns.SPECS.get(k).pellets() + m.pellets());
            m = m.addPellets(-1000).mulDamage(a.n("k", 0.9) * pellets).addPierce((int) a.n("pierce", 3)).mulRange(a.n("range", 3)).mulSpread(a.n("spread", 0.2));
        }
        a = Aug.W_ROCKET_SPARE;
        if (a.binds(k) && has(p, a)) m = m.mulMag(a.n("mag", 2));
        a = Aug.W_LMG_BRACE;
        if (a.binds(k) && (dyn & D_SNEAK) != 0 && has(p, a)) m = m.mulRate(a.n("rate", 1.15)).mulRecoil(a.n("recoil", 0.1));
        a = Aug.W_REVOLVER_FAN;
        if (a.binds(k) && has(p, a)) m = m.mulRate(a.n("rate", 2.9)).mulSpread(a.n("spread", 2.2)).mulReload(a.n("reload", 1.5));
        a = Aug.W_RAIL_OVER;
        if (a.binds(k) && has(p, a)) m = m.mulDamage(a.n("dmg", 2));
        return m;
    }

    // ------------------------------------------------------------------ 맞힘

    /**
     * 남이 걸어 준 피해 배율(가진 증강이 없는 사람에게도 걸림): 확성기(라운드 첫 10초, 트럭 곁), 유언을 받음(willUntil), 몸값 유대(bondUntil:
     * 꺼내진 사람은 증강이 없을 수 있음)
     */
    double teamHitMul(Player p) {
        Run r = runs.get(p.getUniqueId());
        if (r == null) return 1;
        long now = game.now();
        double k = 1;
        if (now < speakerUntil && A.part(Aug.P_SPEAKER) > 0 && r.truckDist <= Aug.P_SPEAKER.n("r", 12)) k *= Aug.P_SPEAKER.n("dmg", 1.2);
        if (now < r.willUntil) k *= Aug.M_WILL.n("dmg", 1.3);
        if (now < r.bondUntil) k *= Aug.M_RANSOM.n("bond", 1.1);
        return k;
    }

    /** 새 증강의 조건 피해 배율(곱함) */
    double hitMul(Player p, PState s, Gabe g, Guns.Spec spec) {
        Run r = runs.get(p.getUniqueId());
        if (r == null) return 1;
        long now = game.now();
        Guns.Kind kind = spec == null ? null : spec.kind();
        Guns.Arms arms = game.guns.arms(p);
        double dist = g.pos.distance(p.getEyeLocation().toVector());
        double k = 1;
        Aug a;
        // ---- 무기
        if (kind != null) {
            a = Aug.W_PISTOL_LAST;
            if (a.binds(kind) && has(p, a) && arms != null && (arms.primary == null || arms.mag[arms.primary.ordinal()] + arms.res[arms.primary.ordinal()] <= 0))
                k *= a.n("dmg", 1.8);
            a = Aug.W_SHOTGUN_DOOR;
            if (a.binds(kind) && dist <= a.n("r", 4) && has(p, a)) k *= a.n("dmg", 1.5);
            a = Aug.W_RIFLE_TAP;
            if (a.binds(kind) && arms != null && arms.streak() < a.n("shots", 3) && has(p, a)) k *= a.n("dmg", 1.35);
            a = Aug.W_SNIPER_LINE;
            if (a.binds(kind) && game.guns.hitIndex > 0 && has(p, a)) k *= Math.min(a.n("max", 2.5), 1 + a.n("step", 0.3) * game.guns.hitIndex);
            a = Aug.W_FAR;
            if (a.binds(kind) && dist >= a.n("r", 40) && has(p, a)) k *= a.n("dmg", 1.5);
            a = Aug.W_SILENT_STALK;
            if (a.binds(kind) && g.target != p && has(p, a)) k *= a.n("dmg", 1.4);
            a = Aug.W_LASER_FOCUS;
            if (a.binds(kind) && r.focus == g && now - r.focusAt <= a.n("gap", 15) && has(p, a)) k *= 1 + Math.min(a.n("max", 0.8), a.n("step", 0.08) * r.focusN);
        }
        if (now < r.swapUntil) k *= Aug.W_SWAP.n("dmg", 1.2);
        // ---- 트럭
        if (r.truckDist <= Aug.T_GUARD.n("r", 6) && has(p, Aug.T_GUARD)) k *= Aug.T_GUARD.n("dmg", 1.15);
        if (has(p, Aug.T_BAIT)) {
            Vector t = A.truckPos();
            if (Math.hypot(g.pos.getX() - t.getX(), g.pos.getZ() - t.getZ()) <= Aug.T_BAIT.n("r", 5) + 1) k *= Aug.T_BAIT.n("dmg", 1.3);
        }
        k *= teamHitMul(p);
        // ---- 팀
        if (r.near3 > 0 && has(p, Aug.M_BACK)) k *= 1 + Aug.M_BACK.n("step", 0.04) * Math.min(Aug.M_BACK.n("max", 5), r.near3);
        if (r.downedNear && has(p, Aug.M_COVER)) k *= Aug.M_COVER.n("dmg", 1.25);
        if (r.near12 == 0 && has(p, Aug.M_LONE)) k *= Aug.M_LONE.n("dmg", 1.3);
        if (halfDown && has(p, Aug.M_LAST)) k *= Aug.M_LAST.n("dmg", 1.2);
        // ---- 위험(늘 걸리는 피해는 총 배율에)
        if (elite(g) && has(p, Aug.X_MARKED)) k *= Aug.X_MARKED.n("dmg", 1.4);
        if (has(p, Aug.X_DARK)) k *= dark() ? Aug.X_DARK.n("dark", 1.35) : clearSky() ? Aug.X_DARK.n("clear", 0.8) : 1;
        // ---- 돈, 처치, 죽음
        if (r.hoard > 0 && has(p, Aug.E_HOARD)) k *= 1 + Aug.E_HOARD.n("step", 0.04) * r.hoard;
        if (r.chain > 0 && now < r.chainUntil && has(p, Aug.K_CHAIN)) k *= 1 + Aug.K_CHAIN.n("step", 0.04) * r.chain;
        if (g.hp >= g.maxHp - 1e-6 && has(p, Aug.K_FIRST)) k *= Aug.K_FIRST.n("dmg", 1.4);
        if (now < r.returnUntil) k *= Aug.D_RETURN.n("dmg", 1.4);
        if (!r.grudge.isEmpty() && r.grudge.contains(g.sp) && has(p, Aug.D_GRUDGE)) k *= Aug.D_GRUDGE.n("dmg", 1.5);
        if (r.truckDist <= Aug.P_LAMP.n("r", 18) && A.part(Aug.P_LAMP) > 0) k *= Aug.P_LAMP.n("dmg", 1.1);
        return k;
    }

    /** 어두운가: 안개 짙기가 문턱 이상이거나 밤 사건이거나 3막부터 */
    private boolean dark() {
        return game.director.fog() >= Aug.X_DARK.n("fog", 3) || game.director.night() || A.links.act() >= 3;
    }

    /** 맑은가: 안개 사건이 맑음(1) */
    private boolean clearSky() {
        return game.director.fog() == 1 && !game.director.night();
    }

    /** 한 번 맞힘(died = 이번에 죽음) */
    void onHit(Player p, Object target, double amount, boolean died, Gun gun) {
        if (!(target instanceof Gabe g)) return;
        PState s = ps(p);
        if (s.augs.isEmpty()) return;
        Run r = run(p);
        long now = game.now();
        Damage hit = ctx.get(g);
        String how = hit == null ? null : hit.how();
        Guns.Kind kind = how == null ? null : KIND_BY_KEY.get(how);
        Guns.Arms arms = game.guns.arms(p);
        Aug a;
        if (!died) {
            a = Aug.W_DMR_MARK;
            if (a.binds(kind) && has(p, a)) amp(g, (int) a.n("ticks", 80), a.n("amp", 1.15));
            a = Aug.M_CALL;
            if (elite(g) && has(p, a)) {
                Mark m = marks.get(g);
                boolean fresh = m == null || now >= m.ampUntil;
                amp(g, (int) a.n("ticks", 120), a.n("amp", 1.1));
                if (fresh) game.hud.point(null, g.pos.clone(), (int) a.n("ticks", 120));
            }
            a = Aug.W_BOW_PIN;
            if (a.binds(kind) && has(p, a)) stun(g, (int) a.n("ticks", 40));
            a = Aug.W_ROCKET_SHOCK;
            if (a.binds(kind) && has(p, a)) slow(g, (int) a.n("ticks", 40), a.n("slow", 0.5));
            a = Aug.W_TESLA_NET;
            if ("chain".equals(how) && arms != null && a.binds(arms.primary) && has(p, a)) stun(g, (int) a.n("ticks", 10));
            a = Aug.W_SHOTGUN_DOOR;
            if (a.binds(kind) && !boss(g) && has(p, a)) {
                Vector eye = p.getEyeLocation().toVector();
                if (g.pos.distance(eye) <= a.n("r", 4)) Guns.shove(game.world, g, g.pos.clone().subtract(eye), a.n("push", 0.5));
            }
            a = Aug.K_MARK;
            if (game.guns.critNow && has(p, a)) brand(g, p.getUniqueId(), (int) a.n("ticks", 120), a.n("amp", 1.25));
            a = Aug.W_LASER_FOCUS;
            if (a.binds(kind) && has(p, a)) {
                if (r.focus == g && now - r.focusAt <= a.n("gap", 15)) r.focusN++;
                else {
                    r.focus = g;
                    r.focusN = 0;
                }
                r.focusAt = now;
            }
            return;
        }
        // ---- 처치
        if (has(p, Aug.K_CHAIN)) {
            r.chain = now < r.chainUntil ? (int) Math.min(Aug.K_CHAIN.n("max", 10), r.chain + 1) : 1;
            r.chainUntil = now + (long) Aug.K_CHAIN.n("gap", 40);
        }
        a = Aug.K_BOOM;
        if (!"aug_boom".equals(how) && has(p, a) && rnd.nextDouble() < a.n("chance", 0.15)) {
            booms++;
            boom(g.pos.clone(), a.n("r", 2.5), Math.min(a.n("cap", 300), g.maxHp * a.n("frac", 0.5)), p, "aug_boom");
        }
        a = Aug.K_RELOAD;
        if (kind != null && arms != null && has(p, a) && rnd.nextDouble() < a.n("chance", 0.25)) {
            Guns.Spec sp = arms.spec(kind);
            if (sp != null) arms.mag[kind.ordinal()] = Math.min(sp.mag(), arms.mag[kind.ordinal()] + (int) a.n("shots", 2));
        }
        a = Aug.K_FEAR;
        if (elite(g) && has(p, a)) fear(p, g, a);
        if (has(p, Aug.K_HASTE)) r.hasteUntil = now + (long) Aug.K_HASTE.n("ticks", 60);
        a = Aug.W_FREEZE_SHATTER;
        if (game.guns.chilledOn(g) && has(p, a)) {
            shatters++;
            Location loc = g.loc();
            game.shotFx.frost(loc.clone().add(0, 0.3, 0));
            Props.soundAround(loc, null, Sound.BLOCK_GLASS_BREAK, 0.9f, 1f, 1.3f, 20, -1);
            double dmg = Math.min(a.n("cap", 200), g.maxHp * a.n("frac", 0.5));
            for (Gabe o : game.mobs.near(g.pos, a.n("r", 3), x -> x != g)) {
                Vector dir = o.pos.clone().subtract(g.pos);
                dir = dir.lengthSquared() < 1e-6 ? new Vector(0, 1, 0) : dir.normalize();
                game.shotFx.frost(o.loc().add(0, 0.2, 0));
                game.mobs.damage(o, new Damage(dmg, DamageType.OTHER, p, o.pos.clone(), dir, "aug_shatter", 0));
                slow(o, (int) a.n("ticks", 40), a.n("slow", 0.45));
            }
        }
        a = Aug.W_FLAME_OIL;
        if ((hit != null && hit.type() == DamageType.FIRE || game.guns.burningOn(g)) && has(p, a)) {
            Gabe next = game.mobs.nearest(g.pos, a.n("r", 3), x -> x != g && !x.dead && !game.guns.burningOn(x));
            if (next != null) {
                Guns.Spec fl = game.guns.spec(p, Guns.Kind.FLAME);
                Guns.Mode md = Guns.mode(Guns.Kind.FLAME);
                game.guns.burn(next, p, fl.damage() * md.effect(), md.ticks());
                game.shotFx.flame(next.loc().add(0, 0.2, 0), 0.7f, 14, ShotFx.EXTRA);
            }
        }
        a = Aug.W_DUAL_SPREE;
        if (a.binds(kind) && arms != null && has(p, a)) {
            Guns.Spec sp = arms.spec(kind);
            if (sp != null) arms.mag[kind.ordinal()] = sp.mag();
        }
        // 팁, 얇은 피, 현상금의 돈은 지갑이 정산 때 준다(earnMul, Wallet.rate): 막타를 따지지 않는다
        a = Aug.E_SCRAP;
        if (elite(g) && has(p, a) && rnd.nextDouble() < a.n("chance", 0.2)) {
            if (rnd.nextBoolean() && game.guns.refillAll(p)) Props.sound(p, null, Sound.ITEM_ARMOR_EQUIP_IRON, 0.8f, 1f, 1.2f);
            else game.players.addMoney(p, (int) Math.round(a.n("u", 0.3) * A.unit()));
        }
        faces.remove(g);
        if (elite(g) && has(p, Aug.D_FACE) && r.faceKills < Aug.D_FACE.n("max", 6)) {
            r.faceKills++;
            lastLog = "내 얼굴 " + p.getName() + " " + r.faceKills;
        }
    }

    /** 겁: 정예가 죽은 자리 둘레의 것들이 죽인 사람 반대쪽으로 물러남 */
    private void fear(Player p, Gabe dead, Aug a) {
        Vector from = p.getLocation().toVector();
        int ticks = (int) a.n("ticks", 40);
        for (Gabe o : game.mobs.near(dead.pos, a.n("r", 8), x -> x != dead && !elite(x))) {
            Vector away = o.pos.clone().subtract(from).setY(0);
            if (away.lengthSquared() < 1e-6) continue;
            away.normalize();
            Guns.shove(game.world, o, away, a.n("push", 1.5));
            game.mobs.lure(o, o.pos.clone().add(away.multiply(8)), ticks);
            lures++;
        }
    }

    // ------------------------------------------------------------------ 게이브에 걸린 것, 불 벽

    private void marksTick(long now) {
        if (marks.isEmpty() && faces.isEmpty()) return;
        List<Map.Entry<Gabe, Mark>> jumped = null;
        for (Iterator<Map.Entry<Gabe, Mark>> it = marks.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Gabe, Mark> e = it.next();
            Gabe g = e.getKey();
            Mark m = e.getValue();
            if (g.dead || g.removed) {
                // 낙인이 찍힌 채 잡혔으면(치워진 것은 빼고) 아래에서 곁으로 옮긴다
                if (!g.removed && now < m.brandUntil) {
                    if (jumped == null) jumped = new ArrayList<>();
                    jumped.add(Map.entry(g, m));
                }
                it.remove();
                continue;
            }
            boolean live = false;
            if (now < m.stunUntil) {
                g.speedBuff = 0;
                live = true;
            } else if (now < m.slowUntil) {
                g.speedBuff *= m.slowMul;
                live = true;
            }
            if (now < m.ampUntil) live = true;
            if (now < m.brandUntil) {
                live = true;
                if (now - m.fxAt >= 12) {
                    m.fxAt = now;
                    game.shotFx.flame(g.loc().add(0, g.radius + 0.4, 0), 0.35f, 10, ShotFx.EXTRA);
                }
            }
            if (!live) it.remove();
        }
        // 낙인이 찍힌 채 죽으면 곁의 다른 게이브로 옮겨 감. 맞힘 고리(onHit)가 아니라 여기서 한다: 누가 무엇으로 잡았든(증강 없는 동료,
        // 지붕 기관총, 수류탄, 불 벽) 옮겨 가게. 죽은 다음 틱 맨 앞이라 한 틱 늦다
        if (jumped != null) for (Map.Entry<Gabe, Mark> e : jumped) {
            Gabe g = e.getKey();
            Gabe next = game.mobs.nearest(g.pos, Aug.K_MARK.n("r", 5), x -> x != g && !x.dead);
            if (next == null) continue;
            brand(next, e.getValue().brandBy, (int) Aug.K_MARK.n("ticks", 120), e.getValue().brandAmp);
            game.shotFx.streak(g.loc(), next.loc());
        }
        if (!faces.isEmpty() && now % 40 == 0) faces.keySet().removeIf(g -> g.dead || g.removed);
    }

    /** 불길, 냉기 한 번의 끝 자리: 불 벽이면 그 땅에 불을 붙임 */
    void streamEnd(Player p, Guns.Kind k, Location end) {
        Aug a = Aug.W_FLAME_WALL;
        if (!a.binds(k) || !has(p, a)) return;
        Run r = run(p);
        long now = game.now();
        if (now - r.wallAt < a.n("every", 8)) return;
        r.wallAt = now;
        Location at = ShotFx.ground(end, 3.0);
        int mine = 0;
        Patch oldest = null;
        for (Patch x : patches) {
            if (x.owner != p) continue;
            mine++;
            if (oldest == null || x.until < oldest.until) oldest = x;
        }
        if (mine >= a.n("max", 4) && oldest != null) patches.remove(oldest);
        Patch x = new Patch();
        x.at = at;
        x.owner = p;
        x.until = now + (long) a.n("ticks", 40);
        x.next = now + 4;
        x.r = a.n("r", 1.6);
        x.dps = game.guns.spec(p, Guns.Kind.FLAME).damage() * Guns.mode(Guns.Kind.FLAME).effect();
        patches.add(x);
        wallPatches++;
        game.shotFx.decal(at, new Vector(0, 1, 0), ShotFx.DECAL_SCORCH, (float) (x.r * 2), 100);
    }

    private void patchTick(long now) {
        if (patches.isEmpty()) return;
        for (Iterator<Patch> it = patches.iterator(); it.hasNext();) {
            Patch x = it.next();
            if (now >= x.until) {
                it.remove();
                continue;
            }
            if (now >= x.nextFx) {
                x.nextFx = now + 5;
                double rr = Math.sqrt(rnd.nextDouble()) * x.r, ang = rnd.nextDouble() * Math.PI * 2;
                game.shotFx.flame(x.at.clone().add(Math.cos(ang) * rr, 0.3, Math.sin(ang) * rr), 0.7f, 14, ShotFx.EXTRA);
            }
            if (now < x.next) continue;
            x.next = now + 10;
            for (Object o : game.mobs.inRadius(x.at, x.r + 1)) {
                Vector c = game.mobs.center(o);
                if (c == null) continue;
                double dx = c.getX() - x.at.getX(), dz = c.getZ() - x.at.getZ(), dy = c.getY() - x.at.getY();
                if (dx * dx + dz * dz > x.r * x.r || dy > 2.5 || dy < -1) continue;
                game.mobs.damage(o, new Damage(x.dps * 0.5, DamageType.FIRE, x.owner, c.clone(), new Vector(0, 1, 0), "aug_firewall", 0));
            }
        }
    }

    // ------------------------------------------------------------------ 트럭 부품(팀)

    private long turretAt = -999, lampAt = -999;

    /** 웨이브 중 매 틱: 지붕 기관총, 큰 등 */
    private void partsTick(long now, Vector truck) {
        Aug a = Aug.P_TURRET;
        int n = A.part(a);
        if (n > 0 && now - turretAt >= Math.max(1, a.n("every", 5))) {
            turretAt = now;
            Vector from = truck.clone().add(new Vector(0, 2.4, 0));
            double rr = a.n("r", 16);
            Gabe best = null;
            double bd = rr * rr;
            for (Gabe g : game.mobs.near(truck, rr, x -> !x.disguised && !x.untargetable)) {
                double d = g.pos.distanceSquared(from);
                if (d < bd && game.mobs.clearLine(from, g.pos)) {
                    bd = d;
                    best = g;
                }
            }
            if (best != null) {
                Vector dir = best.pos.clone().subtract(from);
                dir = dir.lengthSquared() < 1e-6 ? new Vector(0, -1, 0) : dir.normalize();
                double dmg = a.n("dmg", 14) * waveGrow(a.n("grow", 0.06)) * n;
                game.shotFx.streak(from.toLocation(game.world), best.loc());
                game.mobs.damage(best, new Damage(dmg, DamageType.BULLET, null, best.pos.clone(), dir, "aug_turret", 0));
                turretShots++;
                if (turretShots % 3 == 0)
                    Props.soundAround(from.toLocation(game.world), "gun.lmg.fire", Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.7f, 1f, 1.3f, 48, -1);
            }
        }
        a = Aug.P_LAMP;
        if (A.part(a) > 0 && now - lampAt >= a.n("every", 40)) {
            lampAt = now;
            game.mobs.reveal(truck, a.n("r", 18));
        }
    }

    /** 엄호: 쓰러진 사람 곁에 엄호를 가진 서 있는 동료가 있으면 출혈이 느리게 간다(두 틱에 한 틱) */
    private void coverTick(List<Player> in, long now) {
        double keep = 1 - Aug.M_COVER.n("bleed", 0.5);
        if (keep <= 0) return;
        int every = (int) Math.max(1, Math.round(1 / keep));
        if (now % every != 0) return;
        double rr = Aug.M_COVER.n("r", 8);
        for (Player d : in) {
            PState ds = ps(d);
            if (ds.life != Life.DOWNED || ds.reviver != null) continue;
            for (Player o : in) {
                if (o == d || o.getWorld() != d.getWorld() || !ps(o).standing() || !has(o, Aug.M_COVER)) continue;
                if (o.getLocation().distance(d.getLocation()) > rr) continue;
                ds.bleedUntil++;
                break;
            }
        }
    }

    double truckDamageMul(double hpFrac) {
        // 철판, 냉각기는 트럭 부품(kr.gave.trip.Truck)이 스스로 건다: 여기서는 여기 목록 것만(트럭 부품이 없는 판)
        double k = Math.pow(Aug.P_PLATE.n("mul", 0.85), A.ownPart(Aug.P_PLATE));
        if (A.ownPart(Aug.P_COOLER) > 0 && hpFrac < Aug.P_COOLER.n("below", 0.25)) k *= Aug.P_COOLER.n("mul", 0.7);
        for (Player p : game.players.standing()) if (has(p, Aug.X_FEED)) k *= Aug.X_FEED.n("truck", 1.1);
        return k;
    }

    void truckBitten(Gabe g, double dmg) {
        if (g == null || g.dead || A.ownPart(Aug.P_SPIKES) <= 0 || dmg <= 0) return;
        Vector t = A.truckPos();
        Vector dir = g.pos.clone().subtract(t);
        dir = dir.lengthSquared() < 1e-6 ? new Vector(0, 1, 0) : dir.normalize();
        game.mobs.damage(g, new Damage(dmg * Aug.P_SPIKES.n("mul", 3), DamageType.OTHER, null, g.pos.clone(), dir, "aug_spikes", 0));
    }

    /** 트럭 체력 몫이 내려가며 10% 단계를 넘을 때마다 경적: 둘레를 밀쳐 내고 잠깐 멈춤 */
    void truckHp(double before, double after) {
        Aug a = Aug.P_HORN;
        if (A.ownPart(a) <= 0 || after >= before) return;
        double step = Math.max(0.01, a.n("step", 0.1));
        // 0.9, 0.8 ... 경계를 아래로 넘었을 때만(1.0 에서 0.95 로는 안 울림)
        if (Math.ceil(after / step - 1e-9) >= Math.ceil(before / step - 1e-9)) return;
        hornBlasts++;
        Vector t = A.truckPos();
        Location loc = t.toLocation(game.world).add(0, 1.2, 0);
        Props.soundAround(loc, "trip.horn", Sound.ITEM_GOAT_HORN_SOUND_2, 1f, 1f, 0.8f, 64, -1);
        for (Gabe g : game.mobs.near(t, a.n("r", 8), x -> !boss(x))) {
            Vector away = g.pos.clone().subtract(t).setY(0);
            if (away.lengthSquared() < 1e-6) away = new Vector(1, 0, 0);
            Guns.shove(game.world, g, away.normalize(), a.n("push", 2.5));
            stun(g, (int) a.n("ticks", 30));
        }
        lastLog = "경적 " + String.format(Locale.ROOT, "%.2f -> %.2f", before, after);
    }

    /** 미끼: 트럭 곁(r 안)에 서 있는 미끼 증강 사람 가운데 g 에 가장 가까운 사람 */
    Player bait(Gabe g) {
        if (g == null) return null;
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : game.players.standing()) {
            if (!has(p, Aug.T_BAIT)) continue;
            Run r = runs.get(p.getUniqueId());
            if (r == null || r.truckDist > Aug.T_BAIT.n("r", 5)) continue;
            double d = p.getLocation().toVector().distanceSquared(g.pos);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ 시험

    String status(Player p) {
        Run r = run(p);
        long now = game.now();
        return String.format(Locale.ROOT,
            "truck=%.1f near3=%d near12=%d same=%d downedNear=%s outside=%s halfDown=%s dyn=%d swap=%d haste=%d chain=%d(%d) return=%d will=%d shield=%d warm=%d dash=%d "
                + "hoard=%d debt=%d face=%d grudge=%s second=%s downRound=%s spent=%d paid=%d corpse=%d focus=%d speaker=%d marks=%d patches=%d booms=%d corpseBooms=%d "
                + "turret=%d horn=%d shatter=%d walls=%d lures=%d last=%s",
            r.truckDist, r.near3, r.near12, r.sameGun, r.downedNear, r.outside, halfDown, r.dyn, Math.max(0, r.swapUntil - now), Math.max(0, r.hasteUntil - now),
            r.chain, Math.max(0, r.chainUntil - now), Math.max(0, r.returnUntil - now), Math.max(0, r.willUntil - now), Math.max(0, r.shieldUntil - now),
            Math.max(0, r.warmUntil - now), Math.max(0, r.dashUntil - now), r.hoard, r.debtRounds, r.faceKills, r.grudge, r.secondUsed, r.downRound, r.spentShop,
            r.paidOthers, r.corpseUsed, r.focusN, Math.max(0, speakerUntil - now), marks.size(), patches.size(), booms, corpseBooms, turretShots, hornBlasts,
            shatters, wallPatches, lures, lastLog);
    }
}
