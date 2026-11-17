package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * G176 쟁기 게이브(3막 방해, 돌격). 낮게 엎드린 1 블록 얼굴이 멍에를 멘 소처럼 막대기 쟁기 날 셋을 끌고 와서, 사람이 가장 많이 선 곳(쓰러진 사람이
 * 있으면 그 자리)을 겨눠 곧게 갈고 지나간다(0.18블록/틱, 멈추지 않는다). 트럭에서 R 40 반대편까지 간 뒤 돌아서 다른 각도로 또 가른다.
 * 갈 길 2초 앞에 흙먼지 줄이 일고, 길을 정하면 그 길 끝의 사람을 보고 눈을 뜬다.
 * <ul>
 * <li>쟁기 날에 치이면 18 피해와 옆으로 3 블록 밀림. 쓰러진 사람이 날에 치이면 출혈이 3초 준다(Players.friendlyHit 의 출혈 줄이기를 빌림: 요청 참고).</li>
 * <li>날이 지나간 자리에 폭 1 블록의 그을린 고랑(5 블록 토막, 같은 종의 고랑 꼴 개체: 맞지 않음, 판 규칙 밖, 보상 없음, noHold 라 웨이브 끝 정리에서
 * 치워짐 = 웨이브 끝까지 남음, 종 전체 40 토막, 넘으면 가장 오래된 것부터 메워짐)이 남는다. 고랑을 밟거나 건너면 7 피해와 1초 50% 느려짐,
 * 서 있으면 0.5초마다 7. 뛰어 건너는 중(발이 떠 있음)이면 안 물림. 불(화염병 불길)이 닿은 토막은 메워진다.</li>
 * <li>날은 탄을 튕긴다: 뒤쪽 반구에 맞은 총탄(BULLET, PELLET, SNIPER)은 0. 폭발, 불은 그대로. 끄는 얼굴을 정면에서 쏜다.</li>
 * </ul>
 *
 * <p>팩 계약(tools/gabe/sp/g176.py): 모델 gave:gabe/g176 상태 "", 고랑 gave:gabe/g176_furrow 상태 "". 소리 plow(40), bite(16), clang(16).
 * 글귀 cause_g176, cause_g176_furrow.
 */
public final class G176 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.18, HOVER = 0.55, BLADE = 12, FURROW = 5, FURROW_SLOW = 0.5, SEG = 5, HALF = 2.5, FURROW_W = 0.5;
    static final double FAR_R = 40, LAY_R = 46, START_R = 34, BLADE_R = 1.4, PUSH = 0.75;
    static final int TURN_T = 24, FURROW_CD = 10, FURROW_SLOW_T = 20, MAX_FURROWS = 40, PLOW_SOUND = 24;

    /** 판의 고랑 토막(오래된 차례). */
    static final List<G176> FURROWS = new ArrayList<>();
    /** 사람마다 고랑이 마지막으로 문 틱(토막 여럿이 겹쳐도 0.5초에 한 번). */
    static final Map<UUID, Long> BITTEN = new HashMap<>();

    enum Phase { ENTER, TURN, PLOW }

    private boolean furrow;
    private Phase phase = Phase.ENTER;
    private int phaseAt;
    private final Vector dir = new Vector(0, 0, 1), end = new Vector(), laidFrom = new Vector();
    private double gone, length;
    private final Set<UUID> struck = new HashSet<>();
    private int nextSound, nextDust;
    /** 고랑: 가운데에서 길이 방향(수평 단위). */
    private final Vector axis = new Vector(0, 0, 1);

    public G176(Game game, Species sp) {
        super(game, sp);
        radius = 0.55;
        hover = HOVER;
        baseSpeed = SPEED;
        noPush = true;
    }

    /** 고랑 꼴로 바꿈(place 앞에). */
    G176 asFurrow(Vector along) {
        furrow = true;
        axis.copy(along.clone().setY(0).normalize());
        yaw = (float) Math.toDegrees(Math.atan2(-axis.getX(), axis.getZ()));
        ghost = true;
        noPush = true;
        noRules = true;
        noBite = true;
        noMutate = true;
        untargetable = true;
        noHold = true;
        noReward = true;
        reward = 0;
        leaveCorpse = false;
        hover = 0.03;
        radius = 0.4;
        baseSpeed = 0;
        return this;
    }

    @Override
    protected void build() {
        body = furrow ? Body.of(this, "gabe/g176_furrow", 1.2f, 0.2f) : Body.of(this, "gabe/g176", 2.6f, 1.2f);
        if (furrow) body.viewRange(1.6f);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private static Vector center() {
        return Truck.live() ? Truck.center() : new Vector(Plaza.CX + 0.5, Plaza.FLOOR_Y, Plaza.CZ + 0.5);
    }

    private Vector fwd() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (furrow) {
            furrowTick();
            return;
        }
        switch (phase) {
            case ENTER -> enter();
            case TURN -> turn();
            case PLOW -> plow();
        }
    }

    /** 가장자리에서 싸움터 안쪽(R 34)까지 흐름장으로. */
    private void enter() {
        follow(speed());
        Player p = nearest(400);
        face(p != null ? chest(p) : pos.clone().add(vel));
        Vector c = center();
        if (Math.hypot(pos.getX() - c.getX(), pos.getZ() - c.getZ()) <= START_R || p != null && dist(p) < 24) pickPath();
    }

    /** 겨눌 자리: 쓰러진 사람(곁에서 일으키는 사람과 함께 꿰는 길), 없으면 서 있는 사람이 가장 많이 모인 곳. */
    private Vector aimPoint() {
        Player downed = null;
        double dd = Double.MAX_VALUE;
        for (PState s : game.players.all()) {
            if (s.life != Life.DOWNED) continue;
            Player p = org.bukkit.Bukkit.getPlayer(s.id);
            if (p == null || !p.isOnline()) continue;
            double d = p.getLocation().toVector().distance(pos);
            if (d < dd) {
                dd = d;
                downed = p;
            }
        }
        if (downed != null) return downed.getLocation().toVector();
        List<Player> st = game.mobs.standing();
        Vector best = null;
        int bn = -1;
        for (Player p : st) {
            Vector l = p.getLocation().toVector();
            int n = 0;
            Vector sum = new Vector();
            for (Player q : st) {
                Vector m = q.getLocation().toVector();
                if (m.distanceSquared(l) <= 25) {
                    n++;
                    sum.add(m);
                }
            }
            if (n > bn) {
                bn = n;
                best = sum.multiply(1.0 / n);
            }
        }
        return best;
    }

    /** 길을 정함: 지금 자리에서 겨눌 자리를 지나 R 40 반대편까지 곧게. 길 끝의 사람을 보고 눈을 뜸. */
    private void pickPath() {
        Vector aim = aimPoint();
        Vector c = center();
        if (aim == null) aim = c.clone();
        Vector d = aim.clone().subtract(pos).setY(0);
        if (d.lengthSquared() < 4) {
            // 겨눌 자리 위에 있음: 가운데 쪽으로 비껴 감
            d = c.clone().subtract(pos).setY(0);
            if (d.lengthSquared() < 1e-3) d = new Vector(game.random.nextDouble() - 0.5, 0, game.random.nextDouble() - 0.5);
        }
        dir.copy(d.normalize());
        // 반대편 R 40 점(지나는 직선과 원의 먼 교점)
        double px = pos.getX() - c.getX(), pz = pos.getZ() - c.getZ();
        double b = px * dir.getX() + pz * dir.getZ(), q = px * px + pz * pz - FAR_R * FAR_R;
        double disc = b * b - q;
        double t = disc >= 0 ? -b + Math.sqrt(disc) : aim.clone().subtract(pos).setY(0).length() + 20;
        length = Math.max(12, t);
        end.copy(pos.clone().add(dir.clone().multiply(length)));
        gone = 0;
        laidFrom.copy(pos).setY(0);
        struck.clear();
        Player p = null;
        double pd = Double.MAX_VALUE;
        for (Player s : game.mobs.standing()) {
            Vector l = s.getLocation().toVector();
            double along = l.clone().subtract(pos).dot(dir);
            if (along < 0) continue;
            double off = l.clone().subtract(pos.clone().add(dir.clone().multiply(along))).setY(0).length();
            if (off < 3 && along < pd) {
                pd = along;
                p = s;
            }
        }
        mark(p != null ? p : nearest(400));
        phase(Phase.TURN);
    }

    /** 돌아섬(멈추지 않고 크게 휘며 새 방향으로). */
    private void turn() {
        boolean done = turnTo(pos.clone().add(dir), 12f);
        Vector f = fwd();
        dash(f, speed() * 0.6);
        dust();
        if (done || age - phaseAt >= TURN_T) {
            laidFrom.copy(pos).setY(0);
            gone = 0;
            phase(Phase.PLOW);
        }
    }

    /** 곧게 갊: 지나간 자리에 고랑, 날에 치인 사람. */
    private void plow() {
        double v = speed();
        dash(dir, v);
        face(pos.clone().add(dir));
        gone += v;
        if (age >= nextSound) {
            nextSound = age + PLOW_SOUND;
            sound("plow", Sound.BLOCK_ROOTED_DIRT_BREAK, 0.5f, 40);
        }
        dust();
        lay();
        blades();
        if (gone >= length || wallAhead(1.0)) pickPath();
    }

    /** 갈 길 2초 앞에 이는 흙먼지 줄(덤 연출). */
    private void dust() {
        if (age < nextDust) return;
        nextDust = age + 3;
        double ahead = speed() * 40;
        for (double k : new double[] {ahead * 0.5, ahead}) {
            Vector at = pos.clone().add(dir.clone().multiply(k));
            Location l = new Location(game.world, at.getX(), game.mobs.flow.floor(at.getX(), at.getZ()) + 0.25, at.getZ());
            ItemDisplay d = game.shotFx.spawn(l, "fx_quad", ShotFx.DUST, 1, ShotFx.scale(0.9f), Display.Billboard.CENTER, false, 16, ShotFx.EXTRA);
            game.shotFx.grow(d, ShotFx.scale(1.6f), 14);
        }
    }

    /**
     * 지나온 5 블록마다 고랑 토막 하나(싸움터 안에서만). 길 앞쪽으로 간 거리로 센다. 길에서 밀려나면(넉백, 폭발, 막힌 칸에서 미끄러짐) 거기서 새로 센다:
     * 2026-11-16 실서버 멈춤(감시 장치가 60초 뒤 서버를 끔)은 laidFrom 과의 직선 거리로 세던 고리가, 쟁기가 길 옆이나 뒤로 5 블록 넘게 밀리자 끝나지 않은 것
     */
    private void lay() {
        Vector moved = pos.clone().setY(0).subtract(laidFrom);
        double along = moved.dot(dir);
        if (along < 0 || along > SEG * 4 || moved.subtract(dir.clone().multiply(along)).lengthSquared() > HALF * HALF) {
            laidFrom.copy(pos).setY(0);
            return;
        }
        for (; along >= SEG; along -= SEG) {
            Vector mid = laidFrom.clone().add(dir.clone().multiply(HALF));
            laidFrom.add(dir.clone().multiply(SEG));
            Vector c = center();
            if (Math.hypot(mid.getX() - c.getX(), mid.getZ() - c.getZ()) > LAY_R) continue;
            if (!game.mobs.flow.walkable(mid.getX(), mid.getZ())) continue;
            if (!game.mobs.budgetFor(1)) continue;
            G176 f = new G176(game, sp).asFurrow(dir);
            if (game.mobs.place(f, new Vector(mid.getX(), game.mobs.flow.floor(mid.getX(), mid.getZ()) + 0.03, mid.getZ())) == null) continue;
            FURROWS.add(f);
            FURROWS.removeIf(g -> g.dead || g.removed);
            while (FURROWS.size() > MAX_FURROWS) FURROWS.remove(0).remove();
        }
    }

    /** 쟁기 날: 날 곁 1.4 블록 안 사람은 18 피해와 옆으로 밀림(한 번 지날 때 한 번), 쓰러진 사람은 출혈이 준다. */
    private void blades() {
        Vector blade = pos.clone().subtract(dir.clone().multiply(0.6));
        for (Player p : game.mobs.standing()) {
            if (struck.contains(p.getUniqueId())) continue;
            Vector l = p.getLocation().toVector();
            if (Math.abs(l.getY() - (pos.getY() - HOVER)) > 1.6) continue;
            if (Math.hypot(l.getX() - blade.getX(), l.getZ() - blade.getZ()) > BLADE_R && Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ()) > BLADE_R)
                continue;
            struck.add(p.getUniqueId());
            hit(p, BLADE, "cause_g176");
            double side = (l.getX() - pos.getX()) * dir.getZ() - (l.getZ() - pos.getZ()) * dir.getX();
            Vector push = new Vector(dir.getZ(), 0, -dir.getX()).multiply(side >= 0 ? 1 : -1);
            knock(p, push, PUSH, 0.2);
            sound("bite", Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.7f, 16);
        }
        for (PState s : game.players.all()) {
            if (s.life != Life.DOWNED || struck.contains(s.id)) continue;
            Player p = org.bukkit.Bukkit.getPlayer(s.id);
            if (p == null) continue;
            Vector l = p.getLocation().toVector();
            if (Math.hypot(l.getX() - blade.getX(), l.getZ() - blade.getZ()) > BLADE_R) continue;
            struck.add(s.id);
            // 쓰러진 사람이 날에 치이면 남은 출혈이 준다(지금 공개된 출혈 줄이기는 아군 탄 규칙의 3초뿐: 요청)
            game.players.bleedShift(p, -60);
        }
    }

    // ------------------------------------------------------------------ 고랑

    private void furrowTick() {
        hold();
        if (game.state != Game.State.WAVE) return;
        if (every(5) && burning()) {
            remove();
            return;
        }
        long now = game.now();
        for (Player p : game.mobs.standing()) {
            if (!p.isOnGround()) continue;
            Location l = p.getLocation();
            if (Math.abs(l.getY() - pos.getY()) > 0.8) continue;
            double rx = l.getX() - pos.getX(), rz = l.getZ() - pos.getZ();
            double along = rx * axis.getX() + rz * axis.getZ();
            if (Math.abs(along) > HALF + 0.2) continue;
            double off = Math.abs(rx * axis.getZ() - rz * axis.getX());
            if (off > FURROW_W + 0.3) continue;
            Long last = BITTEN.get(p.getUniqueId());
            if (last != null && now - last < FURROW_CD) continue;
            BITTEN.put(p.getUniqueId(), now);
            hit(p, FURROW, "cause_g176_furrow");
            game.players.slow(p, FURROW_SLOW, FURROW_SLOW_T, "g176_furrow");
            if (every(2)) sound("bite", Sound.ENTITY_FOX_BITE, 1.4f, 16);
        }
    }

    /** 이 토막에 불이 닿았나(가운데와 양 끝). */
    private boolean burning() {
        for (double k : new double[] {-2, 0, 2}) {
            if (game.shop.inFire(pos.clone().add(axis.clone().multiply(k)))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (furrow) return super.spheres();
        Vector f = fwd();
        return List.of(new double[] {pos.getX() + f.getX() * 0.62, pos.getY(), pos.getZ() + f.getZ() * 0.62, 0.55},
            new double[] {pos.getX() - f.getX() * 0.9, pos.getY() - 0.15, pos.getZ() - f.getZ() * 0.9, 0.55});
    }

    @Override
    public boolean damage(Damage d) {
        if (furrow) return false;
        if (dead || unhittable()) return false;
        DamageType t = d.type();
        boolean shot = t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER;
        if (shot && d.at() != null) {
            Vector rel = d.at().clone().subtract(pos);
            if (rel.getX() * fwd().getX() + rel.getZ() * fwd().getZ() < -0.15) {
                // 날이 탄을 튕김
                if (every(3)) sound("clang", Sound.BLOCK_ANVIL_LAND, 1.8f, 16);
                return false;
            }
        }
        return super.damage(d);
    }

    @Override
    public void remove() {
        if (furrow) FURROWS.remove(this);
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + (furrow ? " furrow" : " " + phase + String.format(" gone=%.0f/%.0f furrows=%d", gone, length, FURROWS.size()));
    }
}
