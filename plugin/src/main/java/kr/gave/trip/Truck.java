package kr.gave.trip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Band;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.mob.boss.Boss;
import kr.gave.mob.boss.Ward;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.world.Plaza;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * 원정의 트럭(docs/모듈/원정.md 4절): 중앙 보급 상자 자리에 서는 상자 화물차. 상점은 트럭에서 열리고, 트럭에 체력이 있어 게이브가 물며, 0 이 되면 터지고 판이 끝난다.
 *
 * <p>모습: 모드 The Car Engine(MIT)의 상자 화물차(tools/trip/truck.py 가 구운 gave:trip/truck, gave:trip/wheel)를 아이템 디스플레이 다섯(몸통 + 바퀴 넷)으로.
 * 앞은 북쪽(-Z, 길이 뻗은 쪽). 발자국은 3 x 6 칸(보이지 않는 배리어 두 층: 사람과 게이브가 돌아가고 탄이 막힘, 걷기 격자 blocked 18).
 * 가운데 칸의 보급 상자와 등불은 치우고(끄면 되돌림) 지붕 위에 빛 블록을 둔다.
 *
 * <p>체력: {@link Trip.Tune}. 깎는 것은 {@link #hit}(게이브 물기, G14 갉기, 보스의 지킬 것 피해, 큰 몸)뿐이고 사람의 총, 수류탄은 안 깎는다. 저절로 차지 않는다.
 * 물기: 발자국에서 TRUCK_REACH 안에 선 개체 가운데 트럭 노림이거나 사람을 2초 동안 못 문 개체가 Gabe.cd(TRUCK_BITE_CD) 마다 한 번(동시에 TRUCK_SLOTS 마리).
 * 트럭 노림: 감독이 낸 개체의 일부({@link #share()})를 찍어 트럭으로 끌고 간다. 사람에게 맞으면 TRUCK_DISTRACT 틱 동안 제 행동(사람을 노림)으로 돌아간다.
 * <b>길찾기 자리 표시</b>: 지금은 엔진의 끌기(Mobs.lure: 곧게 걸어감, 종 행동은 멈춤)로 트럭 발자국 가장 가까운 변으로 끌고 간다. 황야가 평지라 곧은 길이 막히지 않는다.
 * 벽이 서는 맵 연출(보스 지형)이 생기면 엔진의 흐름장에 트럭 씨앗 거리장을 더해 {@link #steer} 를 그것으로 바꿀 것(Flow, Mobs).
 *
 * <p>터짐(4.6절): 체력 0 → 경적, 알림, 상점 닫힘 → 30틱 뒤 폭발 셋(둘레 사람 쓰러짐, 둘레 게이브 날아감, 뼈대 모델, 바퀴 날아감) → TRUCK_DYING 틱 뒤 판 끝
 * ({@link #lost()} 가 Trip.END_TRUCK, 서 있는 사람이 있어도 끝) → 중앙 원 빛이 꺼진다.
 */
public final class Truck implements TripPart {

    // ------------------------------------------------------------------ 치수(tools/trip/truck.py dims() 와 같아야 한다: build/trip/summary.json)

    /** 모드가 놓는 배율(2)에 곱하는 값: 길이 6.04, 폭 2.88, 높이 2.23 블록이 되어 발자국 3 x 6 에 맞는다. */
    static final double K = 1.07;
    static final float SCALE = (float) (2.0 * K);
    /** 모델 px 하나의 블록 길이. */
    static final double PX = SCALE / 16.0;
    /** 모델(px): 가운데 x, z, 땅(바퀴 밑) y, 지붕 y. 앞은 -Z. */
    static final double MODEL_CX = 8.0, MODEL_CZ = 6.4218, MODEL_GROUND = 0.8, MODEL_TOP = 17.501;
    /** 바퀴 자리(px): 가운데에서 옆으로, 높이, 앞 z, 뒤 z. 바퀴 배율(모드 값 x K). */
    static final double WHEEL_DX = 7.6, WHEEL_Y = 4.0, WHEEL_FRONT_Z = -9.76, WHEEL_REAR_Z = 20.0;
    static final float WHEEL_SX = (float) (0.95 * K), WHEEL_SYZ = (float) (0.8 * K);
    /** 트럭 높이(블록). */
    public static final double HEIGHT = (MODEL_TOP - MODEL_GROUND) * PX;

    /** 발자국(세계 좌표 변): x X0..X1, z Z0..Z1. 칸으로는 x CX-1..CX+1, z CZ-3..CZ+2. 배리어 층 수. */
    public static final int X0 = Plaza.CX - 1, X1 = Plaza.CX + 2, Z0 = Plaza.CZ - 3, Z1 = Plaza.CZ + 3, LAYERS = 2;
    /** 트럭 가운데(바닥). */
    public static final double OX = (X0 + X1) / 2.0, OZ = (Z0 + Z1) / 2.0;
    /** 차체 색(틴트 1: 먼지 낀 흰색). */
    static final int BODY_COLOR = 0xD9D3C5;
    static final String TAG = "gave_trip_truck";
    /** 폭발에 쓰러지는 사람 반경, 날아가는 게이브 반경, 화면 흔들림 반경. */
    static final double BOOM_DOWN = 20, BOOM_KILL = 9, BOOM_SHAKE = 60;
    /** 보스의 지킬 것 피해(사람 체력 100 기준 값) → 트럭 체력 비율: 피해 25 가 트럭 10%. */
    static final double WARD_FRAC = 0.004;
    /** 끌려가던 트럭 노림 개체가 막힌 것으로 치는 1초 걸음(블록)과 트럭에서의 거리(이보다 가까우면 무는 자리를 기다리는 것). */
    static final double STUCK_MOVE = 0.4, STUCK_FAR = 6;
    /** 경적(부품 p_horn, 카드 글 '둘레를 밀쳐 낸다'): 밀쳐 내는 반경과 거리(augments_final.json 의 r, push 와 같은 값). */
    static final double HORN_R = 8, HORN_PUSH = 2.5;

    /** 지금 서 있는 트럭(원정이 꺼져 있거나 부품이 없으면 null). Shop 의 정적 자리 메서드가 읽는다. */
    private static volatile Truck current;

    public final Game game;
    public final Trip trip;
    private final Random rnd = new Random();

    private double hp, maxHp;
    private boolean built;
    private ItemDisplay body;
    private final ItemDisplay[] wheels = new ItemDisplay[4];
    private TextDisplay bar;
    private int barShown = -1;
    private final Map<Block, BlockData> saved = new LinkedHashMap<>();
    private final Map<Block, BlockData> lightsOff = new LinkedHashMap<>();

    /** 개체마다: 다음에 물 틱, 지난 체력, 딴 데 보는 끝 틱, 몸 되돌릴 틱. */
    private static final class Mark {
        boolean seeker;
        long nextBite, distractUntil, unsquashAt = -1;
        double lastHp;
        boolean biting;
        /** 지난번 막힘 검사 때 자리({@link #stuck}). */
        double sx, sz;
    }

    private final Map<Gabe, Mark> marks = new IdentityHashMap<>();
    private int biters, seekers;
    private long lastHitAt = Long.MIN_VALUE / 2, lastHitSoundAt = Long.MIN_VALUE / 2, hurtFromAt = Long.MIN_VALUE / 2;
    /** 맞기 전 비율(HUD 의 흰 깎인 몫). */
    private double hurtFrom = 1;
    private long dyingAt = -1;
    private boolean boomed, dark;
    private String deathCause;
    private int repairs;
    private double dealtThisRound, dealtTotal;
    /** 트럭 부품(팀 공용): 키 → 겹친 수. 고를 차례가 남은 수. */
    private final Map<String, Integer> parts = new LinkedHashMap<>();
    private int pendingParts;
    private double hornMark = 1;
    /** 이번 라운드를 열 때의 체력 비율(다시 하기가 터진 트럭을 이 체력으로 되살림). */
    private double startFrac = 1;
    private final TruckWard ward = new TruckWard();

    public Truck(Game game, Trip trip) {
        this.game = game;
        this.trip = trip;
        maxHp = hp = Trip.Tune.TRUCK_HP;
        if (trip.enabled) build();
    }

    // ------------------------------------------------------------------ 자리(정적: Shop, G14, Players 가 읽음)

    /** 트럭이 서 있나(원정이 켜져 있고 발자국이 놓임). */
    public static boolean live() {
        Truck t = current;
        return t != null && t.built;
    }

    /** 서 있는 트럭(없으면 null). */
    public static Truck get() {
        Truck t = current;
        return t != null && t.built ? t : null;
    }

    /** (x, z) 에서 발자국까지 수평 거리(안이면 0). */
    public static double dist(double x, double z) {
        double dx = Math.max(Math.max(X0 - x, 0), x - X1), dz = Math.max(Math.max(Z0 - z, 0), z - Z1);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 이 블록이 발자국(배리어) 안인가. */
    public static boolean inFootprint(Block b) {
        return b != null && b.getX() >= X0 && b.getX() < X1 && b.getZ() >= Z0 && b.getZ() < Z1 && b.getY() >= Plaza.FLOOR_Y && b.getY() < Plaza.FLOOR_Y + LAYERS;
    }

    /** 발자국 칸들(x, z). */
    public static List<int[]> cells() {
        List<int[]> out = new ArrayList<>();
        for (int x = X0; x < X1; x++) for (int z = Z0; z < Z1; z++) out.add(new int[] {x, z});
        return out;
    }

    /** 발자국에 옆으로 맞닿은 칸들과 트럭 쪽 단위 방향: {칸 가운데 x, z, 방향 x, z}. 무는 자리, G14 의 붙는 자리(18칸). */
    public static List<double[]> slots() {
        List<double[]> out = new ArrayList<>();
        for (int z = Z0; z < Z1; z++) {
            out.add(new double[] {X0 - 0.5, z + 0.5, 1, 0});
            out.add(new double[] {X1 + 0.5, z + 0.5, -1, 0});
        }
        for (int x = X0; x < X1; x++) {
            out.add(new double[] {x + 0.5, Z0 - 0.5, 0, 1});
            out.add(new double[] {x + 0.5, Z1 + 0.5, 0, -1});
        }
        return out;
    }

    /** 가운데(바닥 높이). */
    public static Vector center() {
        return new Vector(OX, Plaza.FLOOR_Y, OZ);
    }

    /** 짐칸 뒤(남쪽) 자리: 판 시작, 부활 자리. back = 발자국 뒤 변에서 떨어진 블록. 트럭을 본다. */
    public static Location rear(org.bukkit.World w, double back) {
        return new Location(w, OX, Plaza.FLOOR_Y, Z1 + back, 180, 0);
    }

    // ------------------------------------------------------------------ 세우기, 치우기

    private Location at(double dx, double dy, double dz) {
        return new Location(game.world, OX + dx, Plaza.FLOOR_Y + dy, OZ + dz);
    }

    private static ItemStack item(String model, String state, int color) {
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key(Props.NS, model));
        it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString(state).addColor(Color.fromRGB(color)).build());
        return it;
    }

    private void build() {
        if (built) return;
        // 지난 실행이 남긴 디스플레이(꺼질 때 못 지운 것)
        for (Entity e : game.world.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
        boolean changed = false;
        BlockData barrier = Bukkit.createBlockData(Material.BARRIER);
        for (int x = X0; x < X1; x++) for (int z = Z0; z < Z1; z++) for (int y = Plaza.FLOOR_Y; y < Plaza.FLOOR_Y + LAYERS; y++) {
            Block b = game.world.getBlockAt(x, y, z);
            if (b.getType() == Material.BARRIER) continue;
            saved.put(b, b.getBlockData());
            b.setBlockData(barrier, false);
            changed = true;
        }
        light(true);
        spawnDisplays(false);
        built = true;
        current = this;
        if (changed) rescan();
        try {
            if (game.director.bosses != null) game.director.bosses.guard(ward);
        } catch (RuntimeException | LinkageError e) {
            trip.log("트럭을 보스의 지킬 것으로 못 올림: " + e);
        }
        trip.log("트럭 섬: 발자국 x " + X0 + ".." + (X1 - 1) + " z " + Z0 + ".." + (Z1 - 1) + " 체력 " + (int) maxHp + (changed ? " (배리어 놓음)" : ""));
    }

    private void unbuild() {
        if (!built) return;
        built = false;
        if (current == this) current = null;
        removeDisplays();
        light(false);
        restoreLights();
        // 발자국을 처음 블록으로(기억이 없으면 생성기와 같은 보급 상자와 등불)
        for (int x = X0; x < X1; x++) for (int z = Z0; z < Z1; z++) for (int y = Plaza.FLOOR_Y; y < Plaza.FLOOR_Y + LAYERS; y++) {
            Block b = game.world.getBlockAt(x, y, z);
            BlockData old = saved.get(b);
            if (old != null) b.setBlockData(old, false);
            else if (x == Plaza.CX && z == Plaza.CZ) b.setBlockData(Bukkit.createBlockData(y == Plaza.FLOOR_Y ? "minecraft:barrel[facing=up]" : "minecraft:lantern[hanging=false]"), false);
            else b.setType(Material.AIR, false);
        }
        saved.clear();
        rescan();
        try {
            if (game.director.bosses != null) game.director.bosses.unguard(ward);
        } catch (RuntimeException | LinkageError e) {
            // 보스 감독이 없으면 그만
        }
        trip.log("트럭 치움");
    }

    private void rescan() {
        Plaza.scan(game.world);
        try {
            game.mobs.flow.rescan();
        } catch (RuntimeException e) {
            trip.log("흐름장 다시 읽기 실패: " + e);
        }
    }

    /** 지붕 위 빛 블록(옛 등불 자리 대신): 운전석과 짐칸 위 둘. */
    private void light(boolean on) {
        int y = Plaza.FLOOR_Y + LAYERS;
        for (int z : new int[] {Z0 + 1, Z1 - 2}) {
            Block b = game.world.getBlockAt(Plaza.CX, y, z);
            if (on && b.getType().isAir()) b.setBlockData(Bukkit.createBlockData("minecraft:light[level=14]"), false);
            else if (!on && b.getType() == Material.LIGHT) b.setType(Material.AIR, false);
        }
    }

    private void spawnDisplays(boolean wreck) {
        removeDisplays();
        String state = wreck ? "wreck" : "";
        Matrix4f m = new Matrix4f().translate(0f, (float) ((8 - MODEL_GROUND) * PX), (float) ((8 - MODEL_CZ) * PX)).rotateY((float) Math.PI).scale(SCALE);
        body = game.world.spawn(at(0, 0.02, 0), ItemDisplay.class, d -> {
            setup(d, 7.5f, 3.2f);
            d.setItemStack(item("trip/truck", state, BODY_COLOR));
            d.setTransformationMatrix(m);
        });
        if (wreck) return;
        int i = 0;
        for (double wz : new double[] {WHEEL_FRONT_Z, WHEEL_REAR_Z}) {
            for (int side = -1; side <= 1; side += 2) {
                Matrix4f wm = new Matrix4f();
                // 바퀴 모델의 바깥 면은 -X: 왼쪽(서쪽) 바퀴는 그대로(디스플레이의 y 반 바퀴를 되돌림), 오른쪽은 반 바퀴 돈 채로
                if (side < 0) wm.rotateY((float) Math.PI);
                wm.scale(WHEEL_SX, WHEEL_SYZ, WHEEL_SYZ);
                wheels[i++] = game.world.spawn(at(side * WHEEL_DX * PX, (WHEEL_Y - MODEL_GROUND) * PX + 0.02, (wz - MODEL_CZ) * PX), ItemDisplay.class, d -> {
                    setup(d, 1.4f, 1.4f);
                    d.setItemStack(item("trip/wheel", state, 0xFFFFFF));
                    d.setTransformationMatrix(wm);
                });
            }
        }
    }

    private static void setup(ItemDisplay d, float w, float h) {
        d.setPersistent(false);
        d.addScoreboardTag(TAG);
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        d.setBrightness(new Display.Brightness(12, 12));
        d.setViewRange(2.0f);
        d.setDisplayWidth(w);
        d.setDisplayHeight(h);
        d.setTeleportDuration(0);
    }

    private void removeDisplays() {
        if (body != null) body.remove();
        body = null;
        for (int i = 0; i < wheels.length; i++) {
            if (wheels[i] != null) wheels[i].remove();
            wheels[i] = null;
        }
        if (bar != null) bar.remove();
        bar = null;
        barShown = -1;
    }

    /** 디스플레이 수(원정 상태의 disp=). */
    private int displays() {
        int n = body != null ? 1 : 0;
        for (ItemDisplay w : wheels) if (w != null) n++;
        if (bar != null) n++;
        return n;
    }

    // ------------------------------------------------------------------ 체력

    public double hp() {
        return hp;
    }

    public double maxHp() {
        return maxHp;
    }

    /** 남은 체력 비율 0..1. */
    public double frac() {
        return maxHp <= 0 ? 0 : Math.max(0, Math.min(1, hp / maxHp));
    }

    /** 아직 안 터졌나. */
    public boolean alive() {
        return built && dyingAt < 0;
    }

    /** 터지는 중이거나 터졌나. */
    public boolean dying() {
        return dyingAt >= 0;
    }

    /** 판이 돌 때만 깎인다(준비, 웨이브, 쉬는 시간). */
    private boolean running() {
        return game.state == Game.State.WAVE || game.state == Game.State.BREAK || game.state == Game.State.PREP;
    }

    /**
     * 트럭 체력을 amount 만큼 깎는다(트럭 체력 단위). 배율 고리 trip.mul("truckDmg") 와 부품(철판, 냉각기)을 곱한다. by 는 깎은 개체(없으면 null),
     * cause 는 원인 글귀 키. 돌려주는 값은 실제로 깎인 양. 판이 안 돌거나 이미 터지는 중이면 0.
     */
    public double hit(double amount, Gabe by, String cause) {
        if (!alive() || !running() || amount <= 0 || Double.isNaN(amount)) return 0;
        double k = trip.mul("truckDmg") * Math.pow(0.85, Math.min(3, part("p_plate")));
        if (part("p_cooler") > 0 && frac() < 0.25) k *= 0.7;
        if (part("p_bumper") > 0) k *= 0.9; // 범퍼(2026-11-16 손질): 들이받힘 말고도 늘 10% 덜 깎인다
        // 증강(먹이: 서 있는 사람마다 x1.1)
        k *= game.augments.truckDamageMul(frac());
        amount *= k;
        if (amount <= 0) return 0;
        long now = game.now();
        if (now - lastHitAt > 30) {
            hurtFrom = frac();
            hurtFromAt = now;
        }
        lastHitAt = now;
        double took = Math.min(hp, amount);
        hp -= took;
        dealtThisRound += took;
        dealtTotal += took;
        if (now - lastHitSoundAt >= 5) {
            lastHitSoundAt = now;
            Location l = by != null ? by.loc() : at(0, 1, 0);
            Props.soundAround(l, "trip.truck_hit", Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.55f, 0.9f + rnd.nextFloat() * 0.2f, 1.3f, 40, -1);
            if (by != null && game.shotFx.room(ShotFx.EXTRA)) {
                Vector c = nearestOnBox(by.pos);
                game.shotFx.spawn(new Location(game.world, c.getX(), c.getY(), c.getZ()), "fx_quad", ShotFx.SPARK, 0, ShotFx.scale(0.8f), Display.Billboard.CENTER, true, 5,
                    ShotFx.EXTRA);
            }
        }
        // 경적(부품): 10% 깎일 때마다 둘레 게이브를 밀쳐 내고 트럭 노림을 잠깐 풀어 물러나게
        if (part("p_horn") > 0 && frac() <= hornMark - 0.1) {
            hornMark = frac();
            honk(1f);
            for (Mark mk : marks.values()) mk.distractUntil = now + 30;
            Vector c = center();
            for (Gabe g : game.mobs.all()) {
                if (g.dead || g.removed || g.sp.is(Role.BOSS) || dist(g.pos.getX(), g.pos.getZ()) > HORN_R) continue;
                Vector away = g.pos.clone().subtract(c).setY(0);
                if (away.lengthSquared() < 1e-6) away = new Vector(1, 0, 0);
                Guns.shove(game.world, g, away.normalize(), HORN_PUSH);
            }
        }
        trip.fire(Trip.EV_TRUCK_HIT, took);
        if (hp <= 0) die(cause);
        return took;
    }

    /** 최대 체력의 비율로 깎기. */
    public double hitFrac(double frac, Gabe by, String cause) {
        return hit(maxHp * frac, by, cause);
    }

    /** 고치기(트럭 체력 단위). 돌려주는 값은 실제로 찬 양. 터지는 중이면 0. */
    public double repair(double amount) {
        if (!alive() || amount <= 0) return 0;
        double got = Math.min(maxHp - hp, amount);
        hp += got;
        // 경적(부품)의 다음 울림은 고친 체력에서 다시 10%: 안 올리면 고친 뒤 예전 바닥 밑으로 갈 때까지 안 울린다
        if (frac() > hornMark) hornMark = frac();
        return got;
    }

    /** 최대 체력의 비율로 고치기(정비 스테이지 +30% 같은 것). */
    public double repairFrac(double frac) {
        return repair(maxHp * frac);
    }

    /** 수리 칸 한 번의 값(원, 5 단위): REPAIR_U x U x REPAIR_STEP^(판 전체 누른 수) x trip.mul("repair"). */
    public int repairPrice() {
        double v = Trip.Tune.REPAIR_U * trip.unit() * Math.pow(Trip.Tune.REPAIR_STEP, repairs) * trip.mul("repair");
        return Math.max(5, (int) Math.round(v / 5.0) * 5);
    }

    /** 수리 칸 한 번에 차는 양(트럭 체력): 최대 체력 x REPAIR_FRAC / 사람 수^REPAIR_POW. */
    public double repairAmount() {
        return maxHp * Trip.Tune.REPAIR_FRAC / Math.pow(trip.people(), Trip.Tune.REPAIR_POW);
    }

    /** 수리 칸을 누름(돈은 Shop 이 뺌): 가득이거나 터지는 중이면 false. 누른 사람의 증강(정비공)이 차는 양에 곱해진다. */
    public boolean repairPress(Player payer) {
        if (!alive() || hp >= maxHp - 1e-6) return false;
        double want = repairAmount() * game.augments.repairAmountMul(payer);
        double got = repair(want);
        // 지갑은 이 누름의 값 가운데 실제로 찬 몫만 갚는다(거의 찬 트럭에 눌러 팀 몫을 쓰지 않게)
        if (payer != null) game.players.wallet.repairFill(payer.getUniqueId(), want > 0 ? got / want : 1);
        repairs++;
        Props.soundAround(at(0, 1, 0), "trip.truck_repair", Sound.BLOCK_ANVIL_USE, 0.6f, 1f, 1.5f, 24, -1);
        trip.log("수리 " + (payer == null ? "-" : payer.getName()) + " → " + (int) hp + "/" + (int) maxHp);
        return true;
    }

    /** 한 번 무는 피해(트럭 체력): 위협 등급 값 x 감독 웨이브 피해 배율 Director.dmgWave() x TRUCK_DMG / 사람 수^TRUCK_POW. */
    public double biteDamage(Species sp) {
        double[] t = Trip.Tune.TRUCK_BITE;
        double base = t[Math.max(0, Math.min(t.length - 1, sp.threat - 1))];
        // 감독의 웨이브 피해 배율(적은 인원 배율을 뺀 것: 난이도 손잡이와 원정 'dmg' 배율이 들어 있다)
        // 등불이 꺼진 동안 무는 피해 LAMP_OFF_BITE 배(LAMP, 2026-11-12 sp_engine)
        return base * game.director.dmgWave() * Trip.Tune.TRUCK_DMG / Math.pow(trip.people(), Trip.Tune.TRUCK_POW) * (lampOffs.isEmpty() ? 1 : LAMP_OFF_BITE);
    }

    /** 감독이 낸 개체 가운데 트럭을 노리는 몫(0..0.9: 건조 방어는 trip.mul("truckShare") 1.8): TRUCK_SHARE + TRUCK_SHARE_PER_WAVE x (라운드 - 1), TRUCK_SHARE_MAX 까지, x trip.mul("truckShare"). */
    public double share() {
        double s = Math.min(Trip.Tune.TRUCK_SHARE_MAX, Trip.Tune.TRUCK_SHARE + Trip.Tune.TRUCK_SHARE_PER_WAVE * Math.max(0, game.wave - 1));
        // 등불이 꺼진 동안 노림 몫 LAMP_OFF_SHARE 배(LAMP)
        if (!lampOffs.isEmpty()) s *= LAMP_OFF_SHARE;
        return Math.max(0, Math.min(0.9, s * trip.mul("truckShare")));
    }

    // ------------------------------------------------------------------ 등불 갈고리(LAMP, 2026-11-12 sp_engine, docs/모듈/API.md '새 종 갈고리')

    /** 등불을 이만큼(앉은 수 합) 가리면 빛이 0 이 되고 트럭 상점이 잠긴다. */
    public static final int LAMP_COVER_FULL = 8;
    /** 빛 단계 14 일 때 등불 빛 반경(블록, 발자국에서). Mobs.lit 가 단계 몫만큼 줄여 읽는다. */
    public static final double LAMP_R = 10;
    /** 등불이 꺼진 동안 트럭 노림 몫 배율, 무는 피해 배율. */
    public static final double LAMP_OFF_SHARE = 2.0, LAMP_OFF_BITE = 1.5;
    private final Map<Object, Integer> lampCovers = new LinkedHashMap<>();
    private final java.util.Set<Object> lampOffs = new java.util.HashSet<>();

    /** LAMP: 등불 가리기(키마다 앉은 수, 0 이면 뗌). 합이 늘수록 지붕 빛 단계가 14 에서 0 으로 내려가고 LAMP_COVER_FULL 이상이면 트럭 상점이 잠긴다. */
    public void lampCover(Object key, int n) {
        if (key == null) return;
        if (n <= 0) lampCovers.remove(key);
        else lampCovers.put(key, n);
        applyLamp();
    }

    /** LAMP: 등불 끄기(키마다, 모두 풀어야 켜짐). 꺼진 동안 빛 0, 트럭 노림 몫 x LAMP_OFF_SHARE, 무는 피해 x LAMP_OFF_BITE. */
    public void lampOff(Object key, boolean off) {
        if (key == null) return;
        if (off) lampOffs.add(key);
        else lampOffs.remove(key);
        applyLamp();
    }

    /** 가린 수 합(LAMP). */
    public int lampCovered() {
        int n = 0;
        for (int v : lampCovers.values()) n += v;
        return n;
    }

    /** 등불이 꺼졌나(lampOff 가 하나라도). LAMP */
    public boolean lampDown() {
        return !lampOffs.isEmpty();
    }

    /** 지금 지붕 빛 단계(0..14): 꺼졌거나 트럭이 탔으면 0. LAMP */
    public int lampLevel() {
        if (dark || dyingAt >= 0 || !lampOffs.isEmpty()) return 0;
        return Math.max(0, 14 - (int) Math.round(14.0 * Math.min(LAMP_COVER_FULL, lampCovered()) / LAMP_COVER_FULL));
    }

    /** 가린 수가 가득해 트럭 상점이 잠겼나(LAMP, Shop.canStay 가 읽음). */
    public boolean lampLocked() {
        return lampCovered() >= LAMP_COVER_FULL;
    }

    /** 지붕 빛 블록 두 칸을 지금 단계로. */
    private void applyLamp() {
        if (!built || dark || dyingAt >= 0) return;
        int lv = lampLevel(), y = Plaza.FLOOR_Y + LAYERS;
        for (int z : new int[] {Z0 + 1, Z1 - 2}) {
            Block b = game.world.getBlockAt(Plaza.CX, y, z);
            if (lv <= 0) {
                if (b.getType() == Material.LIGHT) b.setType(Material.AIR, false);
            } else if (b.getType().isAir() || b.getType() == Material.LIGHT) {
                b.setBlockData(Bukkit.createBlockData("minecraft:light[level=" + lv + "]"), false);
            }
        }
    }

    // ------------------------------------------------------------------ 트럭 노림, 물기

    /** 이 개체를 트럭 노림으로 찍음(감독의 spawnEntry 고리, 라운드 종류, 시험). 보스와 깔린 종은 안 찍힌다. */
    public boolean mark(Gabe g) {
        if (g == null || g.dead || !canSeek(g)) return false;
        Mark m = marks.computeIfAbsent(g, k -> fresh(g));
        m.seeker = true;
        return true;
    }

    /** 트럭 노림인가. */
    public boolean isSeeker(Gabe g) {
        Mark m = marks.get(g);
        return m != null && m.seeker;
    }

    private Mark fresh(Gabe g) {
        Mark m = new Mark();
        m.lastHp = g.hp;
        m.nextBite = game.now() + 10;
        return m;
    }

    /**
     * 트럭 노림이 될 수 있는 종: 고리에서 걸어 몰려오는 것. 보스, 보상 종, 깔린 종, 죽음 결과, 제 길이 있는 목표 압박 종(G14), 흉내 중인 것은 뺌.
     * 하늘 띠(Band.SKY)와 하늘에서 내려앉는 종(Spawn.SKY: G62, G64, G70 ...)도 뺌(엔진 끌기가 내려앉기, 덮치기 같은 종 행동을 멈추게 했음, 2026-11-12 hooks3).
     */
    private static boolean canSeek(Gabe g) {
        Species sp = g.sp;
        if (sp.is(Role.BOSS) || sp.is(Role.BONUS) || sp.is(Role.OBJECTIVE) || sp.is(Role.TRAP)) return false;
        if (sp.spawn != Species.Spawn.RING || sp.band == Band.SKY) return false;
        return !g.disguised && !g.noRules;
    }

    private void mobsTick(long now) {
        boolean wave = game.state == Game.State.WAVE;
        double share = wave ? share() : 0;
        biters = 0;
        seekers = 0;
        int slots = (int) Trip.Tune.TRUCK_SLOTS;
        // 지난 틱에 물던 개체가 자리를 지킨다
        for (Mark m : marks.values()) if (m.biting) biters++;
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.removed) continue;
            Mark m = marks.get(g);
            if (m == null) {
                m = fresh(g);
                marks.put(g, m);
                // 감독이 낸 개체의 일부를 트럭 노림으로(감독 고리 trip.truck().mark(g) 가 달리면 이 줄은 뺀다)
                if (wave && canSeek(g) && game.director.fromDirector(g) && rnd.nextDouble() < share) m.seeker = true;
            }
            if (g.sp.is(Role.BOSS) || game.director.inCage(g)) continue;
            if (m.unsquashAt >= 0 && now >= m.unsquashAt) {
                m.unsquashAt = -1;
                if (g.body != null) g.body.squash(1f, 1f, 1f, 3);
            }
            if (g.hp < m.lastHp - 1e-6 && m.seeker) m.distractUntil = now + (long) Trip.Tune.TRUCK_DISTRACT;
            m.lastHp = g.hp;
            boolean distracted = now < m.distractUntil;
            if (m.seeker) {
                seekers++;
                if (!distracted) {
                    steer(g);
                    // 곧게 끌려가다 막힌 칸(보스가 세운 벽, 둔덕) 앞에 서 버리면 잠깐 끌기를 풀어 제 걸음(흐름장: 막힌 칸을 돌아감)으로 돌린다
                    if (stuck(g, m, now)) m.distractUntil = now + (long) Trip.Tune.TRUCK_DISTRACT;
                }
            }
            double d = dist(g.pos.getX(), g.pos.getZ()) - g.radius;
            boolean near = d <= Trip.Tune.TRUCK_REACH && g.pos.getY() <= Plaza.FLOOR_Y + HEIGHT + 1.5;
            // 무는 조건: 트럭 노림(딴 데 보는 중이 아님)이거나, 사람을 TRUCK_IDLE_TICKS 동안 못 문 채 트럭 곁에 선 개체
            boolean idle = g.sinceHit() >= (long) Trip.Tune.TRUCK_IDLE_TICKS && (g.target == null || g.dist(g.target) > 4) && !g.untargetable;
            boolean can = near && !g.sp.is(Role.OBJECTIVE) && (m.seeker ? !distracted : idle && canSeek(g));
            if (!can) {
                if (m.biting) {
                    m.biting = false;
                    biters--;
                }
                // 큰 몸이 발자국에 닿아 있으면 초당 TRUCK_BIG
                if (near && g.big && now % 20 == 0) hitFrac(Trip.Tune.TRUCK_BIG, g, "cause_trip_big");
                continue;
            }
            if (!m.biting) {
                if (biters >= slots) continue;
                m.biting = true;
                biters++;
                m.nextBite = Math.max(m.nextBite, now + 6);
            }
            if (now < m.nextBite) continue;
            m.nextBite = now + Math.max(4, g.cd((int) Trip.Tune.TRUCK_BITE_CD));
            // 증강 '미끼': 트럭 곁의 그 사람을 먼저 노린다(트럭은 안 묾, 잠깐 트럭 노림을 풂)
            Player lure = game.augments.bait(g);
            if (lure != null) {
                game.mobs.mark(g, lure);
                m.distractUntil = now + (long) Trip.Tune.TRUCK_DISTRACT;
                m.biting = false;
                biters--;
                continue;
            }
            double took = hit(biteDamage(g.sp), g, "cause_trip_bite");
            if (took > 0 && g.body != null && !g.big) {
                g.body.squash(1.2f, 0.85f, 1.2f, 2);
                m.unsquashAt = now + 3;
            }
            // 가시 철판(부품): 문 것이 세 배로 돌려받는다(게이브 체력으로는 제 최대 체력의 15%)
            // 엔진을 거쳐 맞힘(how trip_spikes: 사람 편 장치라 /gave 공격력 배율이 듦)
            if (took > 0 && part("p_spikes") > 0) game.mobs.damage(g, new Damage(g.maxHp * 0.15, DamageType.OTHER, null, g.pos.clone(), null, "trip_spikes", 0));
        }
        if (now % 40 == 0) for (Iterator<Gabe> it = marks.keySet().iterator(); it.hasNext();) {
            Gabe g = it.next();
            if (g.dead || g.removed) it.remove();
        }
    }

    /**
     * 트럭 노림 개체를 트럭으로 끌고 감. 지금은 엔진의 끌기(곧게 걸어감, 그동안 종 행동은 멈춤)로 발자국의 가장 가까운 변 안쪽 점을 겨눈다
     * (끌기는 1.5블록 앞에서 멈추므로 변에서 0.6블록 밖에 선다). 나중에 흐름장의 트럭 씨앗 거리장으로 바꿀 자리.
     */
    private void steer(Gabe g) {
        if ((game.now() + g.id) % 5 != 0) return;
        double x = Math.max(X0 + 0.9, Math.min(X1 - 0.9, g.pos.getX())), z = Math.max(Z0 + 0.9, Math.min(Z1 - 0.9, g.pos.getZ()));
        double y = g.flying ? Plaza.FLOOR_Y + 1.3 : g.pos.getY();
        game.mobs.lure(g, new Vector(x, y, z), 8);
    }

    /**
     * 끌려가던 개체가 막혔나: 1초 동안 STUCK_MOVE 도 못 갔고 트럭에서 아직 STUCK_FAR 넘게 멀다(트럭 곁에서 무는 자리를 기다리는 개체는 아님).
     * 개체마다 1초에 한 번만 잰다(그 밖의 틱은 false).
     */
    private boolean stuck(Gabe g, Mark m, long now) {
        if ((now + g.id) % 20 != 0 || g.flying) return false;
        double dx = g.pos.getX() - m.sx, dz = g.pos.getZ() - m.sz;
        m.sx = g.pos.getX();
        m.sz = g.pos.getZ();
        return dx * dx + dz * dz < STUCK_MOVE * STUCK_MOVE && dist(g.pos.getX(), g.pos.getZ()) > STUCK_FAR;
    }

    /** 이 점에서 가장 가까운 트럭 겉면 점(불꽃 자리). */
    private Vector nearestOnBox(Vector p) {
        double x = Math.max(X0, Math.min(X1, p.getX())), z = Math.max(Z0, Math.min(Z1, p.getZ()));
        double y = Math.max(Plaza.FLOOR_Y + 0.3, Math.min(Plaza.FLOOR_Y + HEIGHT - 0.2, p.getY()));
        return new Vector(x, y, z);
    }

    // ------------------------------------------------------------------ 부품(팀 공용)

    /** 부품을 가진 수(없으면 0). 키는 data/trip/augments 의 갈래 part(p_plate, p_crane ...). */
    public int part(String key) {
        return parts.getOrDefault(key, 0);
    }

    /** 부품을 붙임. */
    public void addPart(String key) {
        parts.merge(key, 1, Integer::sum);
        trip.log("트럭 부품 " + key + " x" + parts.get(key));
    }

    /** 붙은 부품(키 → 겹친 수). */
    public Map<String, Integer> parts() {
        return java.util.Collections.unmodifiableMap(parts);
    }

    /** 보상 '부품': 상점 부품 칸에서 고를 차례를 하나 더한다. */
    public void grantPart() {
        pendingParts++;
    }

    /** 고를 차례가 남은 부품 수. */
    public int pendingParts() {
        return pendingParts;
    }

    /** 고를 차례를 하나 씀(상점 부품 칸이 카드를 열 때). 없으면 false. */
    public boolean takePendingPart() {
        if (pendingParts <= 0) return false;
        pendingParts--;
        return true;
    }

    /** 부품 하나의 이름과 한 줄(resources/trip/augments.json 의 갈래 part). */
    public record PartDef(String key, String name, String line) {
    }

    private static List<PartDef> partDefs;

    /** 부품 표(jar 의 trip/augments_final.json(증강 담당이 굳힌 것), 없으면 trip/augments.json(초안)에서 갈래 part 만. 못 읽으면 빈 목록). */
    public static List<PartDef> partDefs() {
        if (partDefs != null) return partDefs;
        List<PartDef> out = new ArrayList<>();
        java.io.InputStream src = Truck.class.getResourceAsStream("/trip/augments_final.json");
        if (src == null) src = Truck.class.getResourceAsStream("/trip/augments.json");
        try (java.io.InputStream in = src) {
            if (in != null) {
                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                for (com.google.gson.JsonElement e : root.getAsJsonArray("augments")) {
                    com.google.gson.JsonObject o = e.getAsJsonObject();
                    if (!"part".equals(o.get("cat").getAsString())) continue;
                    out.add(new PartDef(o.get("key").getAsString(), o.get("name").getAsString(), o.get("line").getAsString()));
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            out.clear();
        }
        partDefs = out;
        return out;
    }

    /** 부품 이름(표에 없으면 키 그대로). */
    public static String partName(String key) {
        for (PartDef d : partDefs()) if (d.key().equals(key)) return d.name();
        return key;
    }

    /** 겹칠 수 있는 한도(철판 셋, 인양기 둘, 나머지 하나). */
    public static int partCap(String key) {
        return key.equals("p_plate") ? 3 : key.equals("p_crane") ? 2 : 1;
    }

    /** 효과를 낼 쪽이 아직 없는 부품은 안 뜬다. 지금은 없다(범퍼도 2026-11-16 부터 늘 트럭 피해 -10%, 증강 표의 needs 도 뺌) */
    private boolean partReady(String key) {
        return true; // 범퍼도 이제 늘 효과가 있다(트럭 피해 -10%)
    }

    /** 고를 부품 n 개(한도가 안 찬 것, 효과가 달린 것 가운데 무작위). */
    public List<PartDef> partOffer(int n, Random r) {
        List<PartDef> pool = new ArrayList<>();
        for (PartDef d : partDefs()) if (part(d.key()) < partCap(d.key()) && partReady(d.key())) pool.add(d);
        java.util.Collections.shuffle(pool, r);
        return pool.size() > n ? new ArrayList<>(pool.subList(0, n)) : pool;
    }

    // ------------------------------------------------------------------ 터짐

    private void die(String cause) {
        if (dyingAt >= 0) return;
        hp = 0;
        dyingAt = game.now();
        deathCause = cause;
        boomed = false;
        game.hud.notice("trip_truck_dying", 60);
        honk(1.6f);
        for (Player p : game.players.inPlay()) game.shop.close(p);
        trip.fire(Trip.EV_TRUCK_DOWN, cause);
        trip.log("트럭 체력 0(" + cause + "), 라운드 " + game.wave);
    }

    private void honk(float len) {
        Props.soundAround(at(0, 1.2, Z0 - OZ), "trip.truck_horn", Sound.ITEM_GOAT_HORN_SOUND_2, 1f, 1f, len > 1.2f ? 0.7f : 1.1f, 96, -1);
    }

    private void dyingTick(long now) {
        long t = now - dyingAt;
        if (t < 30 && t % 3 == 0 && game.shotFx.room(ShotFx.NORMAL)) game.shotFx.flame(at(rnd.nextDouble() - 0.5, 1.2, Z0 - OZ + 0.8), 0.9f + t * 0.03f, 10, ShotFx.NORMAL);
        if (t == 30 || t == 33 || t == 36) {
            double z = t == 30 ? Z0 - OZ + 1 : t == 33 ? 0 : Z1 - OZ - 1;
            game.shotFx.explosion(at(0, 0.6, z));
            Props.soundAround(at(0, 1, z), "trip.truck_boom", Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f, 0.6f, 128, -1);
        }
        if (t >= 30 && !boomed) boom();
        if (t >= 40 && !dark) lightsOut();
        // 뼈대가 탄다
        if (t > 40 && t % 8 == 0 && game.shotFx.room(ShotFx.EXTRA)) {
            game.shotFx.flame(at((rnd.nextDouble() - 0.5) * 2, 1.0 + rnd.nextDouble(), (rnd.nextDouble() - 0.5) * 5), 0.8f, 16, ShotFx.EXTRA);
        }
        // 웨이브 밖(쉬는 시간, 준비)에서 터지면 Game 이 판 끝 조건을 안 보므로 여기서 끝낸다
        if (t >= (long) Trip.Tune.TRUCK_DYING && (game.state == Game.State.BREAK || game.state == Game.State.PREP)) {
            trip.endCause(Trip.END_TRUCK);
            game.gameOver();
        }
    }

    /** 폭발: 뼈대 모델, 바퀴와 문이 날아감, 둘레 사람 쓰러짐, 둘레 게이브 날아감, 화면 흔들림. */
    private void boom() {
        boomed = true;
        // 바퀴가 바깥으로 날아간 뒤 사라짐
        ItemDisplay[] old = wheels.clone();
        for (int i = 0; i < old.length; i++) {
            ItemDisplay w = old[i];
            wheels[i] = null;
            if (w == null) continue;
            Location l = w.getLocation();
            double dx = l.getX() - OX, dz = l.getZ() - OZ, len = Math.max(0.1, Math.hypot(dx, dz));
            w.setTeleportDuration(12);
            Location to = l.clone().add(dx / len * 7, 0.1, dz / len * 7);
            Bukkit.getScheduler().runTask(game.plugin, () -> {
                if (w.isValid()) w.teleport(to);
            });
            Bukkit.getScheduler().runTaskLater(game.plugin, w::remove, 60);
        }
        if (body != null) body.setItemStack(item("trip/truck", "wreck", BODY_COLOR));
        if (bar != null) bar.remove();
        bar = null;
        Vector c = center().add(new Vector(0, 1, 0));
        for (Player p : game.players.standing()) {
            Location l = p.getLocation();
            double d = dist(l.getX(), l.getZ());
            if (d <= BOOM_SHAKE) Guns.kick(p, (rnd.nextFloat() - 0.5f) * 2.4f, (rnd.nextFloat() - 0.5f) * 2.4f);
            if (d <= BOOM_DOWN) game.players.down(p, null, "cause_trip_truck");
        }
        for (Gabe g : new ArrayList<>(game.mobs.all())) {
            if (g.dead || g.removed || g.sp.is(Role.BOSS)) continue;
            if (dist(g.pos.getX(), g.pos.getZ()) > BOOM_KILL) continue;
            Vector dir = g.pos.clone().subtract(c);
            if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 1, 0);
            try {
                g.damage(new Damage(1e7, DamageType.EXPLOSION, null, c.clone(), dir.normalize(), "explosive", 0));
            } catch (RuntimeException e) {
                // 그 개체는 건너뜀
            }
        }
        trip.log("트럭 폭발");
    }

    /** 등불과 중앙 원 빛이 꺼진다(빛 블록을 기억해 두고 치움: reset 에서 되돌림). */
    private void lightsOut() {
        dark = true;
        int r = Plaza.R_SAFE + 1;
        for (int x = Plaza.CX - r; x <= Plaza.CX + r; x++) for (int z = Plaza.CZ - r; z <= Plaza.CZ + r; z++) {
            if (Math.hypot(x - Plaza.CX, z - Plaza.CZ) > r) continue;
            for (int y = Plaza.FLOOR_Y - 1; y <= Plaza.FLOOR_Y + LAYERS + 1; y++) {
                Block b = game.world.getBlockAt(x, y, z);
                if (b.getType() != Material.LIGHT) continue;
                lightsOff.put(b, b.getBlockData());
                b.setType(Material.AIR, false);
            }
        }
    }

    private void restoreLights() {
        for (Map.Entry<Block, BlockData> e : lightsOff.entrySet()) if (e.getKey().getType().isAir()) e.getKey().setBlockData(e.getValue(), false);
        lightsOff.clear();
        dark = false;
        // 치운 빛의 기억은 메모리에만 있다: 터진 뒤 판 정리 전에 서버가 강제로 꺼졌으면 원이 계속 어둡다. 생성기 자리(PlazaGen.above)의 빈칸을 다시 채운다
        int r = Plaza.R_SAFE;
        for (int x = Plaza.CX - r; x <= Plaza.CX + r; x++) for (int z = Plaza.CZ - r; z <= Plaza.CZ + r; z++) {
            String up = kr.gave.world.PlazaGen.above(x, z);
            if (up == null || !up.startsWith("light")) continue;
            Block b = game.world.getBlockAt(x, Plaza.FLOOR_Y + 1, z);
            if (b.getType().isAir()) b.setBlockData(kr.gave.world.PlazaGen.bd(up), false);
        }
    }

    // ------------------------------------------------------------------ 월드 표시(지붕 위 막대, 연기, 불)

    private void showTick(long now) {
        boolean play = running() && dyingAt < 0;
        if (!play) {
            if (bar != null && dyingAt < 0) {
                bar.remove();
                bar = null;
                barShown = -1;
            }
            return;
        }
        int cells = 20, fill = (int) Math.ceil(frac() * cells - 1e-9);
        boolean flash = now - lastHitAt < 4;
        int key = fill * 2 + (flash ? 1 : 0);
        if (bar == null || !bar.isValid()) {
            bar = game.world.spawn(at(0, HEIGHT + 1.2, 0), TextDisplay.class, d -> {
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setBillboard(Display.Billboard.CENTER);
                d.setViewRange(2.0f);
                d.setSeeThrough(false);
                d.setShadowed(false);
                d.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformationMatrix(new Matrix4f().scale(1.5f, 0.9f, 1.5f));
            });
            barShown = -1;
        }
        if (key != barShown) {
            barShown = key;
            double f = frac();
            TextColor on = flash ? TextColor.color(0xFFFFFF) : f < 0.25 ? TextColor.color(0xE8403A) : f < 0.6 ? TextColor.color(0xF0B429) : TextColor.color(0x7CD67C);
            bar.text(Component.text("█".repeat(fill), on).append(Component.text("█".repeat(cells - fill), TextColor.color(0x2A2A2A))));
        }
        double f = frac();
        // 66% 아래 보닛에서 연기, 33% 아래 불, 10% 아래 끊기는 경적
        if (f < 0.66 && now % 14 == 0 && game.shotFx.room(ShotFx.EXTRA)) {
            ItemDisplay sm = game.shotFx.spawn(at((rnd.nextDouble() - 0.5) * 0.8, 1.35, Z0 - OZ + 0.8), "fx_quad", ShotFx.SMOKE, 0, ShotFx.scale(0.7f), Display.Billboard.CENTER, false, 34,
                ShotFx.EXTRA);
            if (sm != null) {
                game.shotFx.grow(sm, ShotFx.scale(1.7f), 30);
                game.shotFx.drift(sm, at(0.4, 3.6, Z0 - OZ + 0.6), 32);
            }
        }
        if (f < 0.33 && now % 7 == 0 && game.shotFx.room(ShotFx.EXTRA)) game.shotFx.flame(at((rnd.nextDouble() - 0.5) * 1.2, 1.25, Z0 - OZ + 0.9), 0.55f, 12, ShotFx.EXTRA);
        if (f < 0.10 && now % 70 == 0) honk(0.6f);
    }

    // ------------------------------------------------------------------ TripPart

    @Override
    public void startGame() {
        if (!built) build();
        lampCovers.clear();
        lampOffs.clear();
        restoreLights();
        light(true);
        maxHp = Trip.Tune.TRUCK_HP * Math.pow(trip.people(), Trip.Tune.TRUCK_HP_POW);
        hp = maxHp;
        hurtFrom = 1;
        hornMark = 1;
        startFrac = 1;
        dyingAt = -1;
        boomed = false;
        deathCause = null;
        repairs = 0;
        dealtThisRound = dealtTotal = 0;
        parts.clear();
        pendingParts = 0;
        marks.clear();
        spawnDisplays(false);
        Props.soundAround(at(0, 1, 0), "trip.truck_start", Sound.ENTITY_MINECART_RIDING, 0.7f, 1f, 0.6f, 32, -1);
    }

    @Override
    public void beginRound(int n) {
        dealtThisRound = 0;
        startFrac = frac();
    }

    /** 다시 하기: 무는 표시를 지우고, 터진 트럭은 그 라운드를 열 때의 체력으로 되살린다(부품, 수리 횟수는 그대로). 안 터졌으면 그 체력보다 낮을 때만 채운다. */
    @Override
    public void retry() {
        marks.clear();
        lampCovers.clear();
        lampOffs.clear();
        seekers = biters = 0;
        double back = Math.max(1, maxHp * startFrac);
        if (dyingAt < 0) {
            hp = Math.max(hp, back);
            return;
        }
        dyingAt = -1;
        boomed = false;
        deathCause = null;
        hp = back;
        hurtFrom = hornMark = frac();
        if (built) {
            restoreLights();
            light(true);
            spawnDisplays(false);
        }
        trip.log("트럭 되살림(다시 하기) 체력 " + (int) hp + "/" + (int) maxHp);
    }

    @Override
    public void beginBreak(int n) {
        // 보스를 잡은 쉬는 시간: 트럭 +30%(파워커브 6절)
        if (Trip.bossRound(n) && alive()) repairFrac(Trip.Tune.BOSS_REPAIR);
        for (Mark m : marks.values()) m.biting = false;
    }

    @Override
    public String lost() {
        return dyingAt >= 0 && game.now() - dyingAt >= (long) Trip.Tune.TRUCK_DYING ? Trip.END_TRUCK : null;
    }

    @Override
    public void tick() {
        if (!built) return;
        long now = game.now();
        if (game.state == Game.State.LOBBY) return;
        if (dyingAt >= 0) {
            dyingTick(now);
            return;
        }
        if (running()) {
            // 사람 수 비례 체력(TRUCK_HP_POW 가 0 이 아닐 때): 비율을 지킨 채 다시 잼
            if (now % 100 == 0 && Trip.Tune.TRUCK_HP_POW != 0) {
                double f = frac();
                maxHp = Trip.Tune.TRUCK_HP * Math.pow(trip.people(), Trip.Tune.TRUCK_HP_POW);
                hp = maxHp * f;
            }
            mobsTick(now);
        }
        showTick(now);
    }

    @Override
    public void reset() {
        marks.clear();
        lampCovers.clear();
        lampOffs.clear();
        seekers = biters = 0;
        parts.clear();
        pendingParts = 0;
        dyingAt = -1;
        boomed = false;
        deathCause = null;
        maxHp = hp = Trip.Tune.TRUCK_HP;
        hurtFrom = 1;
        if (built) {
            restoreLights();
            light(true);
            spawnDisplays(false);
        }
    }

    @Override
    public void enabled(boolean on) {
        if (!on) {
            unbuild();
            return;
        }
        build();
        // 대기실에서 켤 때(명령 '/gave 원정 on') 발자국 자리에 서 있던 사람은 배리어 속에 끼지 않게 대기실 자리로 옮긴다
        for (Player p : game.world.getPlayers()) {
            Location l = p.getLocation();
            if (l.getX() > X0 - 0.3 && l.getX() < X1 + 0.3 && l.getZ() > Z0 - 0.3 && l.getZ() < Z1 + 0.3 && l.getY() < Plaza.FLOOR_Y + LAYERS) p.teleport(Plaza.lobbySpot(game.world));
        }
    }

    @Override
    public void on(String event, Object arg) {
        // 길을 떠날 때마다 보조 연료통(부품) +6%
        if (Trip.EV_DEPART.equals(event) && part("p_tank") > 0) repairFrac(0.06 * part("p_tank"));
    }

    @Override
    public double mul(String key) {
        // 인양기(부품): 꺼내는 값 -20%, 둘까지
        if (key.equals("revive")) return Math.pow(0.8, Math.min(2, part("p_crane")));
        return 1;
    }

    @Override
    public Object ask(String key) {
        return switch (key) {
            case "disp" -> displays();
            case "truckFrac" -> frac();
            default -> null;
        };
    }

    @Override
    public String status() {
        return "truck=" + (int) Math.ceil(hp) + "/" + (int) maxHp + (dyingAt >= 0 ? " dying=" + (game.now() - dyingAt) : "") + " seek=" + seekers + " bite=" + biters;
    }

    @Override
    public void hud(Player p, Map<String, Object> out) {
        if (!built) return;
        long now = game.now();
        out.put("truck.frac", frac());
        // 흰 깎인 몫: 마지막으로 맞고 30틱 동안 맞기 전 비율을 보여 줌
        out.put("truck.hurt", now - lastHitAt <= 30 ? Math.max(frac(), hurtFrom) : frac());
        out.put("truck.hitAgo", Math.min(10000, now - lastHitAt));
        out.put("truck.dying", dyingAt >= 0);
        Location l = p.getLocation();
        out.put("truck.dist", dist(l.getX(), l.getZ()));
        // 트럭 쪽 월드 yaw(마크 약속: 0 남, 90 서)
        out.put("truck.yaw", Math.toDegrees(Math.atan2(-(OX - l.getX()), OZ - l.getZ())));
    }

    // ------------------------------------------------------------------ 보스의 지킬 것

    private final class TruckWard implements Ward {
        @Override
        public String key() {
            return "truck";
        }

        @Override
        public Vector pos() {
            return center();
        }

        @Override
        public double radius() {
            return 3.4;
        }

        @Override
        public boolean alive() {
            return Truck.this.alive() && trip.enabled && running();
        }

        @Override
        public void hurt(double amount, Boss by, String cause) {
            hitFrac(amount * WARD_FRAC, by, cause == null ? "cause_trip_boss" : cause);
        }
    }

    /** 보스의 지킬 것으로 올린 트럭(보스 기술이 겨눔). */
    public Ward ward() {
        return ward;
    }

    // ------------------------------------------------------------------ 시험

    /**
     * 트럭 x 상태 | 체력 [n] | 피해 n | 비율피해 f | 수리 [f] | 터뜨리기 | 부품 &lt;키&gt; | 부품주기 | 노림 [수] | 몫 | 물기 &lt;종&gt; | 다시(디스플레이 다시 놓기).
     */
    @Override
    public String test(Player p, String[] a) {
        if (a.length < 2 || !a[1].equals("트럭")) return null;
        String sub = a.length > 3 ? a[3] : "상태";
        switch (sub) {
            case "체력" -> {
                if (a.length > 4) hp = Math.max(1, Math.min(maxHp, Double.parseDouble(a[4])));
                return String.format(Locale.ROOT, "[트럭] hp=%.1f/%.0f frac=%.3f round=%.1f total=%.1f", hp, maxHp, frac(), dealtThisRound, dealtTotal);
            }
            case "피해" -> {
                double took = hit(a.length > 4 ? Double.parseDouble(a[4]) : 50, null, "cause_trip_test");
                return String.format(Locale.ROOT, "[트럭] took=%.1f hp=%.1f/%.0f dying=%s", took, hp, maxHp, dyingAt >= 0);
            }
            case "비율피해" -> {
                double took = hitFrac(a.length > 4 ? Double.parseDouble(a[4]) : 0.1, null, "cause_trip_test");
                return String.format(Locale.ROOT, "[트럭] took=%.1f hp=%.1f/%.0f", took, hp, maxHp);
            }
            case "수리" -> {
                double got = a.length > 4 ? repairFrac(Double.parseDouble(a[4])) : repair(repairAmount());
                return String.format(Locale.ROOT, "[트럭] +%.1f hp=%.1f/%.0f price=%d amount=%.1f", got, hp, maxHp, repairPrice(), repairAmount());
            }
            case "터뜨리기" -> {
                hit(maxHp * 1000, null, "cause_trip_test");
                return "[트럭] dying=" + (dyingAt >= 0) + " state=" + game.state.name().toLowerCase(Locale.ROOT);
            }
            case "부품" -> {
                if (a.length < 5) return "[트럭] parts=" + parts + " pending=" + pendingParts;
                addPart(a[4]);
                return "[트럭] parts=" + parts;
            }
            case "부품주기" -> {
                grantPart();
                return "[트럭] pending=" + pendingParts;
            }
            case "노림" -> {
                // 가까운 개체부터 n 마리를 트럭 노림으로
                int n = a.length > 4 ? Integer.parseInt(a[4]) : 1000, made = 0;
                List<Gabe> all = new ArrayList<>(game.mobs.all());
                all.sort(java.util.Comparator.comparingDouble(g -> dist(g.pos.getX(), g.pos.getZ())));
                for (Gabe g : all) {
                    if (made >= n) break;
                    if (!isSeeker(g) && mark(g)) made++;
                }
                return "[트럭] 노림 +" + made + " seek=" + (seekers + made);
            }
            case "몫" -> {
                return String.format(Locale.ROOT, "[트럭] share=%.3f bite(G01)=%.2f bite(threat5)=%.2f people=%d", share(), biteDamage(Species.G01),
                    Trip.Tune.TRUCK_BITE[4] / Trip.Tune.TRUCK_BITE[Math.max(0, Species.G01.threat - 1)] * biteDamage(Species.G01), trip.people());
            }
            case "다시" -> {
                spawnDisplays(dyingAt >= 0 && boomed);
                return "[트럭] disp=" + displays();
            }
            default -> {
                StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "[트럭] built=%s hp=%.1f/%.0f frac=%.3f seek=%d bite=%d marks=%d disp=%d repairs=%d price=%d parts=%s pending=%d",
                    built, hp, maxHp, frac(), seekers, biters, marks.size(), displays(), repairs, repairPrice(), parts, pendingParts));
                if (dyingAt >= 0) sb.append(" dying=").append(game.now() - dyingAt).append(" cause=").append(deathCause).append(" boomed=").append(boomed).append(" dark=").append(dark);
                sb.append(" walk(").append(Plaza.CX).append(',').append(Plaza.CZ).append(")=").append(Plaza.walkable(Plaza.CX, Plaza.CZ));
                int blocked = 0;
                for (int[] c : cells()) if (!Plaza.walkable(c[0], c[1])) blocked++;
                sb.append(" blocked=").append(blocked);
                return sb.toString();
            }
        }
    }

    /** 죽은 사람, 쓰러진 사람을 뺀 서 있는 사람 가운데 트럭에서 r 블록 안인 수(부품, 증강이 읽음). */
    public int standingNear(double r) {
        int n = 0;
        for (PState s : game.players.all()) {
            if (s.life != Life.ALIVE || s.offline) continue;
            Player p = Bukkit.getPlayer(s.id);
            if (p != null && dist(p.getLocation().getX(), p.getLocation().getZ()) <= r) n++;
        }
        return n;
    }

    /** 사람이 트럭에서 r 블록 안인가(자동 탄약, 문지기 같은 '트럭 곁' 조건). */
    public boolean near(Player p, double r) {
        return built && p.getWorld() == game.world && dist(p.getLocation().getX(), p.getLocation().getZ()) <= r;
    }

    /** 하늘 종 높이 띠인가(시험 기록용). */
    static boolean sky(Gabe g) {
        return g.sp.band == Band.SKY;
    }
}
