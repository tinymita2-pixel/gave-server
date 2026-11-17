package kr.gave.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import kr.gave.Props;
import kr.gave.gun.Gun;
import kr.gave.gun.Guns;
import kr.gave.gun.Supplies;
import kr.gave.player.PState;
import kr.gave.trip.Revive;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 중앙 상점: 상자 화면 메뉴(54칸 하나, 칸마다 아이템 모델 아이콘, 이름과 설명 줄은 짧게). 무기 랜덤 상자 줄(종류와 등급을 함께 굴림: 종류는
 * Gun.rollKind 가중치, 등급은 웨이브 확률표. 2026-11-12 boxsplit: 모든 종류 상자 하나 + 갈래 상자 여덟 {@link Crate}), 지금 총 강화,
 * 권총 강화, 탄약 채우기, 소모품 7(수류탄, 조명탄, 화염병, 딸랑이, 정화, 글귀 적힌 비옷, 붕대), 스탯 4(최대 체력, 받는 피해, 걷기, 재장전),
 * 증강 뽑기, 가진 증강 한 줄.
 * <p>
 * 화면(2026-11-14 shopui, docs/모듈/총상점.md '상점 화면'): 54칸 상자 창의 여섯 줄에 그 아래 플레이어 인벤토리 세 줄(게임이 쓰지 않는 칸 9~35)을
 * 이어 아홉 줄을 한 화면으로 쓴다. 묶음마다 '그림 줄 + 그 아래 값 줄': 장비와 강화(줄 0, 1), 상자(2, 3), 소모품(4, 5), 팀(6, 7), 증강(8).
 * 값은 그림 바로 아래 칸에 굵은 숫자로 보인다(금색 = 살 수 있음, 빨강 = 돈이 모자람 + 그림이 어두워짐, 자물쇠 = 잠김, 초록 확인 표시 = 다 삼).
 * 풍선 도움말에는 이름, 지금 → 다음, 값, 못 사는 까닭만 적는다. 칸 그림과 글자는 {@link ShopUi} 가 입힌다: 팩에 상점 화면 자산이 없으면
 * (옛 팩) 옛 그림 아이템 그대로 보이고 값은 풍선 도움말에만 있다. 플레이어 인벤토리에 놓는 칸 아이템에는 표식을 달아 화면을 닫을 때와 들어올 때
 * 치운다({@link #sweep}). 고를 증강 카드가 쌓여 있으면 상점을 열 때 카드부터 띄우되, 고르지 않고 닫았으면 다음에는 상점이 열리고 '고를 카드'
 * 칸으로 다시 들어간다(카드가 상점을 막지 않게).
 * <p>
 * 열기: 카운터(중앙 원 한가운데 보급 상자 한 칸과 그 위 등불)를 보고 우클릭(GunHost.tryUse → {@link #tryOpen}), 또는 돕기(좌클릭)로
 * {@link #helpOpen}. 웨이브 중에는 보급 상자 둘레(R_SHOP)에서만, 준비와 쉬는 시간에는 중앙 원(R_SAFE) 안 어디서나. 카운터 블록이 아직 없는 맵이면
 * 준비와 쉬는 시간에만 가운데 축을 겨눈 것으로 친다. 시선에 게이브가 카운터보다 가까우면 열지 않고 쏜다(상점 지킴이). 붙잡힘, 묶임, 쓰러짐이면
 * 열지 않고, 열린 화면도 붙잡히거나 쓰러지거나 자리를 벗어나면 닫힌다. 열린 동안은 못 쏜다(Guns).
 * 뽑은 총은 바로 바꾸지 않고 '뽑은 총' 칸에 놓인다(나은 값 초록, 못한 값 빨강): 그 칸을 누르면 바꾸고, 지금 총을 누르면 버린다.
 * 뽑은 총이 놓여 있는 동안 뽑기 칸은 잠긴다(값 줄 지움). 꾸러미, 상자 종이 준 총은 차례로 기다린다.
 * 3번 칸에 다른 소모품이 있을 때 소모품을 사면 그것을 버리므로 값 아래 버릴 것을 빨갛게 보이고 두 번 눌러야 산다.
 * <p>
 * 값: {@link Ware} 표 한곳. 값 = 바탕 x (1 + 오름 x (웨이브 - 1)) x 단계배율^단계 x 등급배율(강화) x 갉힘(1.25^n), 5원 단위.
 * 상점 오염(감독): {@link #contaminate} 단계만큼 빈칸에 실눈 글리프 칸이 섞임(누르면 아무 일 없음). 상점 갉는 게이브(G14):
 * {@link #gnawStart} 로 칸 하나를 잠그고 {@link #gnawEnd} 로 풀거나, 다 갉았으면 다음 쉬는 시간에 그 칸 값 25% 오름.
 * <p>
 * 원정(docs/모듈/원정.md 4절, 7절): 트럭이 서 있으면(kr.gave.trip.Truck.live()) 카운터는 트럭 발자국(3 x 6 배리어)이고 거리도 발자국까지로 잰다.
 * 트럭이 터지는 중이면 열리지 않는다. 새 칸: 자동 탄약, 트럭 수리(팀 모금), 트럭 부품, 꺼내기(죽은 동료 머리, kr.gave.trip.Revive 가 그림). 칸 번호는 위 화면 문단.
 * 값 끝에 game.trip.priceMul(w)(정비 스테이지 할인 같은 원정 배율)을 곱한다.
 */
public final class Shop implements Listener {

    /** 파는 것 표(값 데이터 한곳): 바탕 값, 웨이브마다 오름(비율), 단계마다 곱, 상점 칸 */
    public enum Ware {
        // 무기 상자 줄(칸 18~26, 2026-11-12 boxsplit 사용자 "소총 랜덤박스, 샷건 랜덤박스 이런 식으로 분화"): BOX 는 모든 종류(옛 상자 그대로,
        // 원정 돈 단위 U 와 tools/balance_sim.py 가 이 값을 읽음), 그 뒤 여덟은 갈래 상자({@link Crate}, 바탕 값 = BOX 130 x 0.7~1.4).
        // 단계마다 곱은 그 사람이 그 갈래를 산 횟수에 붙는다(갈래마다 따로, 모든 종류 상자는 1 이라 오르지 않음)
        // 마지막 두 수: 화면 칸(2026-11-14 shopui 배치. 0~53 은 상자 창, 54~80 은 그 아래 플레이어 인벤토리 세 줄. 값은 그 아래 칸에 보임),
        // 옛 칸 번호(2026-11-12 배치: 옛 시험 대본의 '사기 <숫자>' 가 그 물건을 가리키게)
        BOX(130, 0.05, 1, 18, 18),
        BOX_PISTOL(90, 0.05, 1.1, 19, 19), BOX_SMG(110, 0.05, 1.1, 20, 20), BOX_RIFLE(130, 0.05, 1.1, 21, 21), BOX_MG(155, 0.05, 1.1, 22, 22),
        BOX_SHOTGUN(125, 0.05, 1.1, 23, 23), BOX_SNIPER(145, 0.05, 1.1, 24, 24), BOX_SPECIAL(170, 0.05, 1.1, 25, 25),
        BOX_BLAST(180, 0.05, 1.1, 26, 26),
        UP_GUN(30, 0.05, 1.25, 1, 2), UP_PISTOL(25, 0.05, 1.25, 3, 11), AMMO(15, 0.05, 1, 36, 13),
        GRENADE(20, 0.05, 1, 38, 27), FLARE(20, 0.05, 1, 39, 28), MOLOTOV(25, 0.05, 1, 40, 29), RATTLE(15, 0.05, 1, 41, 30),
        CLEANSE(25, 0.05, 1, 42, 31), RAINCOAT(35, 0.05, 1, 43, 32), BANDAGE(15, 0.05, 1, 44, 33),
        HP(35, 0.05, 1.3, 5, 4), ARMOR(40, 0.05, 1.3, 6, 5), MOVE(30, 0.05, 1.3, 7, 6), RELOAD(35, 0.05, 1.3, 8, 7),
        AUG(150, 0.05, 1.25, 62, 35),
        // 원정(트럭이 서 있을 때만 보임): 자동 탄약(한 번 사면 판 내내, 눌러 끄고 켬), 트럭 수리(팀 모금), 트럭 부품(보상으로 받은 차례가 있을 때 고름).
        // 값은 kr.gave.trip.Trip.Tune(AUTO_AMMO_U, REPAIR_U x 무기 상자 바탕값)이 정한다. 여기 바탕 값은 1웨이브 어림(표를 읽는 도구용)
        AUTO_AMMO(80, 0.05, 1, 37, 14), REPAIR(40, 0.05, 1, 54, 16), PART(0, 0, 1, 55, 17);

        public final int base, slot, oldSlot;
        public final double growth, level;

        Ware(int base, double growth, double level, int slot, int oldSlot) {
            this.base = base;
            this.growth = growth;
            this.level = level;
            this.slot = slot;
            this.oldSlot = oldSlot;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** 원정 칸(트럭이 서 있을 때만 보이고 살 수 있음, 갉히지 않음). */
        public boolean trip() {
            return this == AUTO_AMMO || this == REPAIR || this == PART;
        }

        /** 무기 상자 칸인가(모든 종류 BOX 와 갈래 상자) */
        public boolean box() {
            return this == BOX || crate() != null;
        }

        /** 갈래 상자의 갈래(모든 종류 BOX 와 그 밖은 null) */
        public Crate crate() {
            return switch (this) {
                case BOX_PISTOL -> Crate.PISTOL;
                case BOX_SMG -> Crate.SMG;
                case BOX_RIFLE -> Crate.RIFLE;
                case BOX_MG -> Crate.MG;
                case BOX_SHOTGUN -> Crate.SHOTGUN;
                case BOX_SNIPER -> Crate.SNIPER;
                case BOX_SPECIAL -> Crate.SPECIAL;
                case BOX_BLAST -> Crate.BLAST;
                default -> null;
            };
        }

        public static Ware of(String s) {
            for (Ware w : values()) if (w.name().equalsIgnoreCase(s)) return w;
            // 옛 '종류 골라 뽑기' 이름(옛 시험 대본): 그 종류가 든 갈래 상자로, 모르는 종류는 모든 종류 상자로
            String up = s.toUpperCase(Locale.ROOT);
            if (up.startsWith("ROLL_")) {
                try {
                    Crate c = Crate.of(Guns.Kind.valueOf(up.substring(5)));
                    if (c != null) return c.ware();
                } catch (IllegalArgumentException e) {
                    // 종류 이름이 아님
                }
                return BOX;
            }
            // 갈래 이름(pistol, shotgun ...)과 종류 이름(SMG, MINIGUN ...)도 그 갈래 상자로
            for (Crate c : Crate.values()) if (c.name().equalsIgnoreCase(s)) return c.ware();
            try {
                Crate c = Crate.of(Guns.Kind.valueOf(up));
                if (c != null) return c.ware();
            } catch (IllegalArgumentException e) {
                // 종류 이름이 아님
            }
            try {
                // 숫자는 옛 칸 번호(옛 시험 대본): 새 배치의 칸은 키로 부른다
                int slot = Integer.parseInt(s);
                for (Ware w : values()) if (w.oldSlot == slot) return w;
            } catch (NumberFormatException e) {
                // 칸 번호가 아님
            }
            return null;
        }

        /** 소모품 종류 키(Supplies), 아니면 null */
        public String supply() {
            return switch (this) {
                case GRENADE -> "grenade";
                case FLARE -> "flare";
                case MOLOTOV -> "molotov";
                case RATTLE -> "rattle";
                case CLEANSE -> "cleanse";
                case RAINCOAT -> "raincoat";
                case BANDAGE -> "bandage";
                default -> null;
            };
        }

        public Augments.Stat stat() {
            return switch (this) {
                case HP -> Augments.Stat.HP;
                case ARMOR -> Augments.Stat.ARMOR;
                case MOVE -> Augments.Stat.MOVE;
                case RELOAD -> Augments.Stat.RELOAD;
                default -> null;
            };
        }
    }

    /**
     * 무기 상자 갈래(2026-11-12 boxsplit). 상자에 드는 종류(권총 PISTOL 은 늘 든 보조 무기라 상자에 없다)를 빠짐없이 나눈다. 갈래 안에서는
     * 종류를 상자 가중치(Gun.boxWeight: 웨이브, 지금 종류 1/3)로 고른 뒤 그 종류 모델을 고르게(Models.pick), 등급은 웨이브 확률표 그대로.
     * icon 은 상점 그림 gave:shop/box 의 strings[0](그 갈래 대표 총 모델 키, tools/make_shop_icons.py BOXES 와 같게)
     */
    public enum Crate {
        PISTOL("권총", "revolver", Guns.Kind.REVOLVER, Guns.Kind.MACHINEPISTOL, Guns.Kind.DUAL, Guns.Kind.SILENCED),
        SMG("기관단총", "smg", Guns.Kind.SMG),
        RIFLE("소총", "rifle", Guns.Kind.RIFLE, Guns.Kind.BURST, Guns.Kind.DMR, Guns.Kind.MUSKET),
        MG("기관총", "lmg", Guns.Kind.LMG, Guns.Kind.MINIGUN),
        SHOTGUN("샷건", "shotgun", Guns.Kind.SHOTGUN, Guns.Kind.AUTOSHOT, Guns.Kind.DOUBLE),
        SNIPER("저격", "sniper", Guns.Kind.SNIPER, Guns.Kind.BOLTACTION, Guns.Kind.ANTIMATERIEL),
        SPECIAL("특수", "flamethrower", Guns.Kind.FLAME, Guns.Kind.FREEZE, Guns.Kind.TESLA, Guns.Kind.LASER, Guns.Kind.RAILGUN, Guns.Kind.BOW),
        BLAST("폭발", "rocket", Guns.Kind.LAUNCHER, Guns.Kind.ROCKET);

        public final String ko, icon;
        public final List<Guns.Kind> kinds;

        Crate(String ko, String icon, Guns.Kind... kinds) {
            this.ko = ko;
            this.icon = icon;
            this.kinds = List.of(kinds);
        }

        /** 이 갈래 상자 칸 */
        public Ware ware() {
            return Ware.valueOf("BOX_" + name());
        }

        /** 이 종류가 든 갈래(권총 PISTOL 은 null) */
        public static Crate of(Guns.Kind k) {
            for (Crate c : values()) if (c.kinds.contains(k)) return c;
            return null;
        }

        /** 이 갈래 종류 하나를 굴림(상자 가중치, 지금 종류 current 는 1/3). 무게가 모두 0 이면 고르게 */
        public Guns.Kind rollKind(int wave, Guns.Kind current, Random rnd) {
            double sum = 0;
            for (Guns.Kind k : kinds) sum += Gun.boxWeight(k, wave, current);
            if (sum <= 0) return kinds.get(rnd.nextInt(kinds.size()));
            double x = rnd.nextDouble() * sum;
            for (Guns.Kind k : kinds) {
                x -= Gun.boxWeight(k, wave, current);
                if (x < 0) return k;
            }
            return kinds.get(kinds.size() - 1);
        }

        /** 이 갈래 상자 하나: 종류(갈래 안 가중치), 모델(그 종류 안 고르게), 등급(웨이브 확률표) */
        public Gun roll(int wave, Guns.Kind current, Random rnd) {
            return Gun.roll(rollKind(wave, current, rnd), Gun.rollRarity(wave, rnd), rnd);
        }
    }

    /** 상자 칸 w 하나를 굴림(모든 종류 BOX 는 옛 Gun.rollBox, 갈래 상자는 그 갈래 안에서) */
    static Gun rollBox(Ware w, int wave, Guns.Kind current, Random rnd) {
        Crate c = w.crate();
        return c == null ? Gun.rollBox(wave, current, rnd) : c.roll(wave, current, rnd);
    }

    /** 강화 값 등급 배율(일반, 희귀, 영웅, 전설) */
    private static final double[] RARITY_PRICE = {1, 1.2, 1.45, 1.8};
    /** 화면 칸: 지금 총, 권총, 뽑은 총, 재화(머리띠 돈 표시가 걸리는 칸 = 뽑은 총 아래), 가진 증강(여덟 칸), 고를 카드 */
    public static final int SLOT_GUN = 0, SLOT_PISTOL = 2, SLOT_PENDING = 4, SLOT_MONEY = 13, ROW_AUGS = 72, AUGS = 8, SLOT_CARDS = 80;
    /** 원정 꺼내기(죽은 동료 머리 여섯 칸 56~61. 일곱 명부터는 마지막 칸이 쪽 넘김: Revive.render 가 주는 아홉 명까지). */
    public static final int ROW_REVIVE = 56, REVIVES = 6;
    /** 화면 칸 번호: 이 번호부터는 상자 창 아래 플레이어 인벤토리(인벤토리 칸 = 번호 - BELOW + 9). 값 칸 = 그림 칸 + TAG(바로 아래) */
    private static final int BELOW = 54, TAG = 9;
    /** 카운터: 가운데 칸에서 체비쇼프 거리 이 안(맵 PlazaGen 의 한가운데 보급 상자 한 칸), 발 높이~그 위 한 칸(등불) */
    public static final int COUNTER_R = 0;
    /** 카운터를 보는 거리(블록) */
    public static final double COUNTER_REACH = 4.5;
    /** 소모품 바꿈 확인: 첫 누름 뒤 이 틱 안에 다시 누르면 삼 */
    private static final int SWAP_CONFIRM = 60;

    /** 상자 화면 하나(상점, 카드). 누른 칸 → 할 일 */
    public static final class View implements InventoryHolder {
        public static final String SHOP = "shop", CARDS = "cards", PARTS = "parts";
        public final UUID who;
        public final String kind;
        /** 카드 화면: 고른 뒤 상점으로 돌아감 */
        public final boolean back;
        public Inventory inv;
        public final Map<Integer, Runnable> actions = new HashMap<>();
        /** 상점: 이번에 그린 아래 세 줄(플레이어 인벤토리 9~35). 화면이 열려 있을 때 달라진 칸만 인벤토리에 옮긴다({@link Shop#flush}) */
        final ItemStack[] below = new ItemStack[27];

        public View(UUID who, String kind, boolean back) {
            this.who = who;
            this.kind = kind;
            this.back = back;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    public final Game game;
    public final Supplies supplies;
    private final Random rnd = new Random();
    private final Map<UUID, Gun> pendingGun = new HashMap<>();
    /** 뽑은 총 칸이 차 있을 때 다른 데서 온 총(차례로 뽑은 총 칸에 놓임) */
    private final Map<UUID, java.util.ArrayDeque<Gun>> gunQueue = new HashMap<>();
    /** 소모품 바꿈 확인 중인 칸과 그 틱 */
    private final Map<UUID, Ware> swapAsk = new HashMap<>();
    private final Map<UUID, Long> swapAt = new HashMap<>();
    private final Map<UUID, Long> clickAt = new HashMap<>();
    /**
     * 탄약을 산 뒤에 불리는 고리(손으로 사든 자동 탄약이 사든 {@link #buy} 의 탄약 갈래를 지나면). 증강이 '탄약을 샀다'를 돈과 탄 수로 짐작하지
     * 않게 여기에 더하면 된다: game.shop.ammoBought.add(p -&gt; ...)
     */
    public final List<java.util.function.Consumer<Player>> ammoBought = new ArrayList<>();
    private final Map<Ware, Integer> gnawed = new EnumMap<>(Ware.class);
    private final List<Ware> gnawRaise = new ArrayList<>();
    private Ware gnawing;
    private int contamination;
    private Game.State lastState;
    private String lastLog = "없음";

    public Shop(Game game) {
        this.game = game;
        this.supplies = new Supplies(game);
        this.uiMark = new org.bukkit.NamespacedKey(game.plugin, "shopui");
    }

    // ------------------------------------------------------------------ 열기 조건

    private static double flat(Location l) {
        double dx = l.getX() - (Plaza.CX + 0.5), dz = l.getZ() - (Plaza.CZ + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 상점 자리까지 수평 거리: 트럭이 서 있으면 발자국까지, 아니면 보급 상자 칸 가운데까지 */
    private static double shopDist(Location l) {
        return Truck.live() ? Truck.dist(l.getX(), l.getZ()) : flat(l);
    }

    /** 보급 상자 둘레(반지름 R_SHOP)에 서 있나. 트럭이 서 있으면 트럭 발자국에서 R_SHOP 안 */
    public boolean inShop(Player p) {
        return p.getWorld() == game.world && shopDist(p.getLocation()) <= Plaza.R_SHOP + 0.5;
    }

    /** 열린 화면을 둘 수 있나: 서 있고 붙잡히지 않았고, 웨이브 중이면 보급 상자 둘레(R_SHOP), 준비와 쉬는 시간이면 중앙 원(R_SAFE) 안 */
    public boolean canStay(Player p) {
        if (p == null || p.getGameMode() == GameMode.SPECTATOR || game.guns.arms(p) == null) return false;
        PState s = game.players.get(p);
        if (!s.standing() || s.grabbedBy != null || p.getWorld() != game.world) return false;
        // 원정: 트럭이 터지는 중이면 상점이 닫힌다
        Truck truck = Truck.get();
        if (truck != null && !truck.alive()) return false;
        // 원정: 이동 연출(검은 화면, 달리기) 동안은 상점이 닫힌다
        if (truck != null && game.trip.traveling()) return false;
        // 원정: 등불이 다 가려지면 트럭 상점이 잠긴다(Truck.lampCover, 2026-11-12 sp_engine LAMP)
        if (truck != null && truck.lampLocked()) return false;
        // 원정 라운드 종류 '잠긴 트럭'(trip_rounds: ask shopLocked) 동안, 그리고 판 시작 인트로(원정을 꺼도 돎) 동안은 상점이 없다
        if (Boolean.TRUE.equals(game.trip.ask("shopLocked"))) return false;
        return switch (game.state) {
            case WAVE -> inShop(p);
            case PREP, BREAK -> flat(p.getLocation()) <= Plaza.R_SAFE + 0.5;
            default -> false;
        };
    }

    /** 지금 열 수 있나: {@link #canStay} 이고 쏘기를 막는 상태(붙잡힘, 묶임, 아군 탄 끊김)가 아님 */
    public boolean canOpen(Player p) {
        return canStay(p) && !game.blocked(p);
    }

    /** 상점 카운터 가운데 칸(x, z 블록 좌표). 카운터 칸은 이 칸에서 체비쇼프 거리 {@link #COUNTER_R} 안. 상점 자리를 찾는 종은 이것을 쓴다. */
    public static int[] counterCell() {
        return new int[] {Plaza.CX, Plaza.CZ};
    }

    /** 상점 카운터 가운데 점(칸 가운데, y = 바닥 윗면 FLOOR_Y). */
    public static Vector counterCenter() {
        if (Truck.live()) return Truck.center();
        return new Vector(Plaza.CX + 0.5, Plaza.FLOOR_Y, Plaza.CZ + 0.5);
    }

    /** 상점 카운터 칸들(x, z): 가운데 둘레 COUNTER_R 안(트럭이 서 있으면 발자국 18칸). 블록이 실제로 지어졌는지는 {@link #isCounter}. */
    public static java.util.List<int[]> counterCells() {
        if (Truck.live()) return Truck.cells();
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int dx = -COUNTER_R; dx <= COUNTER_R; dx++)
            for (int dz = -COUNTER_R; dz <= COUNTER_R; dz++) out.add(new int[] {Plaza.CX + dx, Plaza.CZ + dz});
        return out;
    }

    /** 이 블록이 카운터(한가운데 보급 상자, 그 위 등불)인가 */
    public static boolean isCounter(Block b) {
        if (Truck.live()) return Truck.inFootprint(b) && !b.isPassable();
        return b != null && Math.abs(b.getX() - Plaza.CX) <= COUNTER_R && Math.abs(b.getZ() - Plaza.CZ) <= COUNTER_R && b.getY() >= Plaza.FLOOR_Y
            && b.getY() <= Plaza.FLOOR_Y + 1 && !b.isPassable();
    }

    /**
     * 카운터를 보나: 4.5블록 안 시선이 카운터 블록({@link #isCounter})에 먼저 닿음. 카운터 블록이 없는 맵에서는 준비와 쉬는 시간에만 가운데 축
     * (발 높이 + 1)을 5블록 안 18도 안으로 겨눈 것으로 친다. 어느 쪽이든 그 거리 안 시선에 게이브가 더 가까우면 false(그 누름은 쏜다)
     */
    public boolean lookingAtCounter(Player p) {
        Location eye = p.getEyeLocation();
        Vector d = eye.getDirection();
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, d, COUNTER_REACH, FluidCollisionMode.NEVER, true);
        double at = -1;
        if (r != null && isCounter(r.getHitBlock())) at = r.getHitPosition().distance(eye.toVector());
        else if (game.state != Game.State.WAVE && !counterBuilt()) {
            Vector c = counterCenter().add(new Vector(0, 1.0, 0)).subtract(eye.toVector());
            double dist = c.length();
            if (dist > 5 || dist < 1e-3) return false;
            if (r != null && r.getHitPosition().distance(eye.toVector()) < dist - 1.2) return false;
            if (d.angle(c) >= Math.toRadians(18)) return false;
            at = dist;
        }
        if (at < 0) return false;
        return !gabeInWay(p, eye.toVector(), d, at);
    }

    /** 시선 at 블록 안에 게이브가 있나(상점 지킴이: 카운터 앞 게이브를 쏘는 누름은 상점을 열지 않음) */
    private boolean gabeInWay(Player p, Vector eye, Vector d, double at) {
        kr.gave.gun.HitTargets t = game.targets();
        if (t == null) return false;
        kr.gave.gun.Hit h = t.cast(p, eye.clone(), d.clone(), at + 0.3, new java.util.HashSet<>());
        return h != null && h.target() instanceof kr.gave.mob.Gabe;
    }

    /** 카운터 블록이 지어졌나(가운데 칸 발 높이가 단단함, 첫 확인 뒤 기억) */
    private Boolean counterBuilt;
    /** 시험 카운터짓기 전 블록(되돌림용) */
    private final Map<Block, org.bukkit.block.data.BlockData> counterSaved = new HashMap<>();

    private boolean counterBuilt() {
        if (counterBuilt == null || !counterBuilt) {
            Block b = game.world.getBlockAt(Plaza.CX, Plaza.FLOOR_Y, Plaza.CZ);
            counterBuilt = isCounter(b);
        }
        return counterBuilt;
    }

    /** 우클릭(GunHost.tryUse): 카운터를 보고 열 수 있으면 열고 true(그 누름은 쏘지 않음) */
    public boolean tryOpen(Player p) {
        if (!canOpen(p) || !lookingAtCounter(p)) return false;
        open(p);
        return true;
    }

    /** 돕기(좌클릭) 마지막 순서: 열 수 있는 자리면 열고 true. Players.help 가 부른다 */
    public boolean helpOpen(Player p) {
        if (!canOpen(p)) return false;
        open(p);
        return true;
    }

    // ------------------------------------------------------------------ 화면

    /** 지금 열린 이 상점(또는 카드) 화면, 없으면 null */
    public View view(Player p) {
        if (p == null) return null;
        InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder(false);
        return h instanceof View v && v.who.equals(p.getUniqueId()) ? v : null;
    }

    /** 화면을 연 판 틱(같은 틱의 우클릭이 소모품을 쓰지 않게) */
    private final Map<UUID, Long> shownAt = new HashMap<>();

    /** 화면을 다음 틱에 연다(클릭 처리 중 바로 열지 않게). 상점은 창이 실제로 열린 뒤에 아래 세 줄을 채운다(열기가 취소되면 안 채움) */
    void show(Player p, View v) {
        shownAt.put(p.getUniqueId(), game.now());
        Bukkit.getScheduler().runTask(game.plugin, () -> {
            if (!p.isOnline()) return;
            p.openInventory(v.inv);
            if (view(p) == v && v.kind.equals(View.SHOP)) flush(p, v);
        });
    }

    /** 카드 화면을 고르지 않고 닫은 사람 → 그때 쌓여 있던 묶음 수. 그 수 그대로면 상점을 열 때 카드부터 띄우지 않는다(새 묶음이 오면 다시 띄움) */
    private final Map<UUID, Integer> cardsLater = new HashMap<>();

    /**
     * 상점을 연다(조건은 보지 않음, 시험 훅과 tryOpen, helpOpen 이 부름). 고를 증강 카드가 쌓였으면 카드부터. 다만 그 카드를 고르지 않고 닫았던
     * 사람은 상점이 열리고 '고를 카드' 칸으로 다시 들어간다(받기 싫은 카드가 탄약, 수리, 상자를 막지 않게)
     */
    public void open(Player p) {
        if (game.guns.arms(p) == null) return;
        ShopUi.sync(game);
        int cards = game.augments.pending(p);
        Integer later = cardsLater.get(p.getUniqueId());
        if (cards == 0) cardsLater.remove(p.getUniqueId());
        else if ((later == null || later != cards) && game.augments.openCards(p, true)) return;
        View cur = view(p);
        if (cur != null && cur.kind.equals(View.SHOP)) {
            // 이미 열려 있으면 칸만 다시
            render(p, cur);
            return;
        }
        View v = new View(p.getUniqueId(), View.SHOP, false);
        v.inv = Bukkit.createInventory(v, 54, ShopUi.title(ShopUi.SHOP));
        render(p, v);
        show(p, v);
        Props.sound(p, null, Sound.BLOCK_BARREL_OPEN, 0.7f, 1f, 1.2f);
        lastLog = "열림 " + p.getName();
    }

    public void close(Player p) {
        if (view(p) != null) Bukkit.getScheduler().runTask(game.plugin, () -> {
            if (view(p) != null) p.closeInventory();
        });
    }

    /** 풍선 도움말 색: 덜 중요한 설명(밝은 회색), 못 사는 까닭(빨강), 값(금색), 이름(흰색). 중간 회색 글자는 쓰지 않는다 */
    private static final TextColor DIM = TextColor.color(ShopUi.SOFT), NO = TextColor.color(ShopUi.RED), GOLD = TextColor.color(ShopUi.GOLD),
        WHITE = TextColor.color(ShopUi.WHITE);

    private static Component text(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    /** 상점 아이콘(gave:shop/&lt;키&gt; 또는 주어진 모델), 이름, 줄. 바탕 아이템은 부싯돌(종이는 붕대 쿨다운 막이 같은 종이 아이콘에도 덮임) */
    private static ItemStack icon(Key model, Component name, List<Component> lore, int amount) {
        ItemStack it = ItemStack.of(Material.FLINT, Math.max(1, Math.min(64, amount)));
        it.setData(DataComponentTypes.ITEM_MODEL, model);
        it.setData(DataComponentTypes.ITEM_NAME, name);
        if (lore != null && !lore.isEmpty()) it.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        return it;
    }

    /** 풍선 도움말 없는 아이콘(갉힌 칸, 오염 실눈) */
    private static ItemStack blank(Key model) {
        ItemStack it = icon(model, Component.empty(), null, 1);
        it.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return it;
    }

    private static Key shopKey(String k) {
        return Key.key(Props.NS, "shop/" + k);
    }

    /**
     * 총 그림: 등급 테두리 뒤판 + 그 종류 총 모델(strings[0] 등급, [1] 종류). 소품 층 gave:weapon_icon 이 모든 종류를 알고(2026-11-12),
     * 없으면 옛 gave:shop/gun(다섯 종류만)
     */
    public static ItemStack gunIcon(Gun g, Component name, List<Component> lore, boolean glint) {
        ItemStack it = icon(Props.has("weapon_icon") ? Key.key(Props.NS, "weapon_icon") : shopKey("gun"), name, lore, 1);
        it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString(g == null ? "none" : g.rarity.key())
            .addString(g == null ? "pistol" : g.model != null ? g.model : Guns.SPECS.get(g.kind).key()).build());
        if (glint) it.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return it;
    }

    /** 값 줄: 살 수 있으면 금색 값, 모자라면 빨간 값과 모자란 돈 */
    private Component priceLine(Player p, int price) {
        int lack = price - money(p);
        if (lack <= 0) return ShopUi.num(price + "원", ShopUi.GOLD);
        return ShopUi.num(price + "원", ShopUi.RED).append(text("  ", NO)).append(ShopUi.num(lack + "원", ShopUi.RED)).append(text(" 모자람", NO));
    }

    private int money(Player p) {
        return game.players.get(p).money;
    }

    /** 플레이어 인벤토리에 놓은 상점 칸 아이템의 표식(닫을 때, 들어올 때 이 표식이 있는 것만 치운다) */
    private final org.bukkit.NamespacedKey uiMark;

    /** 화면 칸 slot 에 아이템을 놓는다: BELOW 부터는 아래 세 줄 그림(표식을 달아 v.below 에, 인벤토리로는 {@link #flush} 가 옮김) */
    private void set(Player p, View v, int slot, ItemStack it) {
        if (slot < BELOW) {
            v.inv.setItem(slot, it);
            return;
        }
        if (it != null) it.editPersistentDataContainer(c -> c.set(uiMark, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1));
        v.below[slot - BELOW] = it;
    }

    private boolean marked(ItemStack it) {
        return it != null && it.getPersistentDataContainer().has(uiMark);
    }

    /**
     * 아래 세 줄 그림을 인벤토리 9~35 에 옮긴다: 지금 것과 다른 칸만 쓴다(PlayerInventory.setItem 은 부를 때마다 칸 패킷을 바로 보내서,
     * 그대로인 칸을 다시 쓰면 그만큼 패킷이 나간다). 표식 없는 아이템(크리에이티브의 물건)이 있는 칸은 덮지 않고 그 칸 누름도 뺀다
     */
    private void flush(Player p, View v) {
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 27; i++) {
            ItemStack want = v.below[i], have = inv.getItem(9 + i);
            if (have != null && !marked(have)) {
                v.actions.remove(BELOW + i);
                continue;
            }
            if (want == null ? have != null : !want.equals(have)) inv.setItem(9 + i, want);
        }
    }

    /** 플레이어 인벤토리에 놓았던 상점 칸 아이템을 치움(상점 화면을 닫을 때, 들어올 때). 표식이 없는 아이템은 건드리지 않는다 */
    private void sweep(Player p) {
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        for (int i = 9; i < 36; i++) if (marked(inv.getItem(i))) inv.setItem(i, null);
    }

    /**
     * 그림 칸과 그 아래 값 칸: 누름은 둘 다 같다. 값 칸은 글자만 그리고 풍선 도움말이 없다({@link ShopUi#tag}). tag = 값 칸 글자(빈 글이면 값 칸
     * 없음), color = 그 색
     */
    private void card(Player p, View v, int slot, ItemStack tile, String tag, int color, Runnable press) {
        set(p, v, slot, tile);
        ItemStack below = ShopUi.tag(tag, color);
        if (below != null) set(p, v, slot + TAG, below);
        if (press == null) return;
        v.actions.put(slot, press);
        if (below != null) v.actions.put(slot + TAG, press);
    }

    /**
     * 총 칸: 등급 색 판 + 그 총 모델, 강화 단계는 아래 칸에 +n(흰색). fresh = 새로 뽑은 총(밝은 판 + 느낌표, 아래 칸은 돈 표시 자리라 비움).
     * 누름은 그림 칸에만(지금 총의 누름은 뽑은 총 버리기라, 풍선 도움말 없는 +n 칸을 강화로 알고 눌러 버리는 일이 없게)
     */
    private void gunCard(Player p, View v, int slot, Gun g, List<Component> lore, boolean fresh, Runnable press) {
        ItemStack tile = ShopUi.tile(gunIcon(g, g.title(), lore, fresh), g.model != null ? g.model : Guns.SPECS.get(g.kind).key(),
            g.rarity.key() + (fresh ? "_hi" : ""), false, fresh ? ShopUi.BADGE_NEW : -1);
        card(p, v, slot, tile, fresh || g.level() <= 0 ? "" : "+" + g.level(), ShopUi.WHITE, null);
        if (press != null) v.actions.put(slot, press);
    }

    private void render(Player p, View v) {
        v.inv.clear();
        java.util.Arrays.fill(v.below, null);
        v.actions.clear();
        Guns.Arms a = game.guns.arms(p);
        if (a == null) {
            if (view(p) == v) flush(p, v);
            return;
        }
        Gun cur = a.gun, pend = pendingGun.get(p.getUniqueId());
        // 화면 칸(줄마다 아홉 칸, 묶음 = 그림 줄 + 그 아래 값 줄. 왼쪽 띠의 줄 이름은 tools/make_shop_ui.py BANDS):
        // 장비와 강화 0 지금 총, 1 그 강화, 2 권총, 3 그 강화, 4 뽑은 총, 5~8 스탯 / 상자 18~26(모든 종류 + 갈래 여덟) / 소모품 36 탄약, 37 자동 탄약,
        // 38~44 소모품 / 팀(아래 인벤토리) 54 수리, 55 부품, 56~61 꺼내기, 62 증강 뽑기 / 증강 72~79 가진 증강, 80 고를 카드. 돈은 머리띠 오른쪽(걸리는 칸 13)
        if (cur != null) {
            List<Component> lore = new ArrayList<>(cur.lore(pend));
            if (pend != null) lore.add(text("누르면 새 총을 버림", NO));
            gunCard(p, v, SLOT_GUN, cur, lore, false, pend == null ? null : () -> keep(p));
        }
        if (pend != null) {
            List<Component> lore = new ArrayList<>(pend.lore(cur));
            lore.add(text("누르면 이 총으로 바꿈", GOLD));
            gunCard(p, v, SLOT_PENDING, pend, lore, true, () -> take(p));
        }
        gunCard(p, v, SLOT_PISTOL, a.pistol, a.pistol.lore(null), false, null);
        for (Ware w : Ware.values()) put(p, v, w, a);
        // 돈: 머리띠 오른쪽에 동전과 숫자(걸린 칸은 비어 보임). 옛 팩이면 동전 아이콘 이름으로
        ItemStack wallet = ShopUi.wallet(money(p));
        set(p, v, SLOT_MONEY, wallet != null ? wallet : icon(shopKey("coin"), text(money(p) + "원", GOLD), null, 1));
        renderRevive(p, v);
        // 고를 카드(고르지 않고 닫아 둔 증강 묶음): 누르면 카드 화면
        int cards = game.augments.pending(p);
        if (cards > 0) {
            ItemStack it = icon(shopKey("aug_roll"), text("고를 카드 " + cards, GOLD), List.of(text("누르면 카드를 고름", WHITE)), cards);
            set(p, v, SLOT_CARDS, ShopUi.tile(it, "aug_roll", "gold", false, ShopUi.BADGE_NEW));
            v.actions.put(SLOT_CARDS, () -> {
                cardsLater.remove(p.getUniqueId());
                game.augments.openCards(p, true);
            });
        }
        // 가진 증강(AUGS 칸, 넘치면 마지막 칸에 나머지 수, 그 칸 풍선 도움말에 나머지 목록)
        List<Map.Entry<Augments.Aug, Integer>> owned = new ArrayList<>(game.augments.owned(p).entrySet());
        int show = owned.size() > AUGS ? AUGS - 1 : owned.size();
        for (int i = 0; i < show; i++) set(p, v, ROW_AUGS + i, ShopUi.card(Augments.card(owned.get(i).getKey(), owned.get(i).getValue())));
        if (owned.size() > AUGS) {
            List<Component> rest = new ArrayList<>();
            for (Map.Entry<Augments.Aug, Integer> e : owned.subList(show, owned.size()))
                rest.add(text(e.getKey().ko + (e.getValue() > 1 ? " x" + e.getValue() : ""), e.getKey().tier.color()));
            int more = owned.size() - show;
            set(p, v, ROW_AUGS + show, ShopUi.tile(icon(shopKey("aug_roll"), text("증강 " + more + "개 더", WHITE), rest, more), "aug_roll", "tile", false, -1));
        }
        // 상점 오염: 화면의 빈칸 사이에 실눈 글리프
        if (contamination > 0) {
            List<Integer> empty = new ArrayList<>();
            for (int s = 0; s < BELOW + 27; s++) {
                ItemStack mine = s < BELOW ? null : p.getInventory().getItem(s - BELOW + 9);
                if ((s < BELOW ? v.inv.getItem(s) : v.below[s - BELOW]) == null && (mine == null || marked(mine))) empty.add(s);
            }
            Collections.shuffle(empty, rnd);
            for (int k = 0; k < Math.min(contamination, empty.size()); k++)
                set(p, v, empty.get(k), ShopUi.tile(blank(shopKey("eye")), "eye", "", false, -1));
        }
        if (view(p) == v) flush(p, v);
    }

    /** 꺼내기 칸을 그릴 때 Revive.render 가 채우는 임시 화면(머리 아이템과 누름 행동을 받아 우리 칸으로 옮긴다) */
    private final View reviveScratch = new View(new UUID(0, 0), View.SHOP, false);
    /** 꺼내기 쪽(죽은 사람이 REVIVES 명을 넘을 때 마지막 칸으로 넘김) */
    private final Map<UUID, Integer> revivePage = new HashMap<>();

    /**
     * 원정 꺼내기: 죽은 동료 머리(먼저 죽은 차례). 머리, 설명 줄, 누름은 Revive 가 만들고(아홉 명까지) 여기서는 칸 그림과 한 번 누를 값을 입힌다.
     * REVIVES 명을 넘으면 마지막 칸이 쪽 넘김(남은 사람 수)
     */
    private void renderRevive(Player p, View v) {
        Revive rv = game.trip.revive();
        if (rv == null) return;
        if (reviveScratch.inv == null) reviveScratch.inv = Bukkit.createInventory(null, 9);
        reviveScratch.inv.clear();
        reviveScratch.actions.clear();
        rv.render(p, reviveScratch, 0);
        int n = 0;
        while (n < 9 && reviveScratch.inv.getItem(n) != null) n++;
        if (n == 0) {
            revivePage.remove(p.getUniqueId());
            return;
        }
        List<Player> dead = rv.dead();
        int per = n > REVIVES ? REVIVES - 1 : REVIVES, pages = (n + per - 1) / per;
        int page = Math.min(pages - 1, revivePage.getOrDefault(p.getUniqueId(), 0));
        for (int k = 0; k < per && page * per + k < n; k++) {
            int i = page * per + k;
            ItemStack head = reviveScratch.inv.getItem(i);
            Runnable press = reviveScratch.actions.get(i);
            if (i >= dead.size()) {
                set(p, v, ROW_REVIVE + k, ShopUi.style(head));
                if (press != null) v.actions.put(ROW_REVIVE + k, press);
                continue;
            }
            int cost = rv.cost(p, rv.step(game.players.get(dead.get(i))));
            boolean can = money(p) >= cost;
            card(p, v, ROW_REVIVE + k, ShopUi.tile(head, "head", "tile", !can, -1), ShopUi.price(cost), can ? ShopUi.GOLD : ShopUi.RED, press);
        }
        if (pages > 1) {
            int rest = n - Math.min(per, n - page * per);
            ItemStack more = icon(shopKey("aug_roll"), text("죽은 동료 " + rest + "명 더", WHITE), List.of(text("누르면 다음 쪽", WHITE)), rest);
            card(p, v, ROW_REVIVE + REVIVES - 1, ShopUi.tile(more, "head", "gold", true, -1), "+" + Math.min(99, rest), ShopUi.WHITE, () -> {
                revivePage.put(p.getUniqueId(), (page + 1) % pages);
                refresh(p);
            });
        }
    }

    /** 칸 하나: 그림, 이름, 값 줄, 누르면 사기. 갉히는 칸은 갉힌 그림(못 삼) */
    private void put(Player p, View v, Ware w, Guns.Arms a) {
        if (w == Ware.UP_GUN && a.gun == null) return;
        if (w.trip()) {
            putTrip(p, v, w);
            return;
        }
        if (w == gnawing) {
            set(p, v, w.slot, ShopUi.tile(blank(shopKey("gnawed")), "gnawed", "", false, -1));
            return;
        }
        int price = price(p, w);
        List<Component> lore = new ArrayList<>();
        ItemStack it;
        // art = 칸 그림 키, plate = 판, why = 잠긴 까닭(값 대신 자물쇠, 칸이 어두워짐), done = 더 살 것이 없음(초록 확인 표시)
        String art, plate = "tile", why = null;
        boolean done = false;
        String sup = w.supply();
        Augments.Stat st = w.stat();
        if (w.box()) {
            Crate c = w.crate();
            int[] o = Gun.odds(Math.max(1, game.wave));
            Component odds = Component.empty().decoration(TextDecoration.ITALIC, false);
            for (int r = 0; r < o.length; r++) {
                if (o[r] <= 0) continue;
                odds = odds.append(ShopUi.num(o[r] + "% ", Gun.Rarity.values()[r].color().value()));
            }
            lore.add(odds);
            // 갈래 상자: 든 종류 한 줄
            if (c != null) {
                StringBuilder ks = new StringBuilder();
                for (Guns.Kind k : c.kinds) ks.append(ks.isEmpty() ? "" : ", ").append(Gun.kindName(k));
                lore.add(text(ks.toString(), DIM));
            }
            // 뽑은 총이 놓여 있으면 잠김(그 총을 고르면 풀림)
            if (pendingGun.containsKey(p.getUniqueId())) why = "뽑은 총부터 고르기";
            else if (freeBoxes(p) > 0) lore.add(text("공짜 표 " + freeBoxes(p) + "장", GOLD));
            Component name = text(c == null ? "무기 상자" : c.ko + " 상자", WHITE);
            if (c == null) it = icon(Props.has("weapon_box") ? Key.key(Props.NS, "weapon_box") : shopKey("roll_rifle"), name, null, 1);
            else {
                // 옛 그림: 갈래 대표 총 + 상자 뒤판(pack_src gave:shop/box, strings[0] = 대표 모델 키), 산 횟수만큼 쌓임 수
                it = icon(shopKey("box"), name, null, Math.max(1, boxBuys(p, w)));
                it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString(c.icon).build());
            }
            art = c == null ? "crate" : c.icon;
        } else if (w == Ware.UP_GUN || w == Ware.UP_PISTOL) {
            Gun g = w == Ware.UP_GUN ? a.gun : a.pistol;
            done = g.level() >= Gun.MAX_LEVEL;
            lore.add(ShopUi.num("+" + g.level() + (done ? "" : " → +" + (g.level() + 1)), ShopUi.WHITE));
            it = icon(shopKey("upgrade"), text((w == Ware.UP_GUN ? Gun.kindName(g.kind) : "권총") + " 강화", WHITE), null, Math.max(1, g.level()));
            art = "upgrade";
        } else if (w == Ware.AMMO) {
            lore.add(text("장전 + 예비 가득", DIM));
            it = icon(Props.has("ammo_box") ? Key.key(Props.NS, "ammo_box") : shopKey("ammo"), text("탄약", WHITE), null, 1);
            art = Props.has("ammo_box") ? "ammo_box" : "ammo";
        } else if (sup != null) {
            int have = supplies.count(p, sup);
            int max = Supplies.MAX.getOrDefault(sup, 1);
            done = have >= max;
            lore.add(text(Supplies.LINE.get(sup), DIM));
            lore.add(ShopUi.num(have + "/" + max, ShopUi.WHITE));
            // 3번 칸의 다른 소모품을 버리게 되면 버릴 것을 빨갛게(두 번 눌러 삼, 첫 누름 뒤에는 칸 판이 금색)
            String drop = done ? null : supplies.wouldDrop(p, sup);
            if (drop != null) lore.add(text(Supplies.NAME.get(drop) + " " + game.guns.arms(p).items() + " 버림", NO));
            it = icon(Supplies.model(sup), text(Supplies.NAME.get(sup), WHITE), null, Math.max(1, have));
            if (drop != null && asking(p, w)) {
                plate = "gold";
                it.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
            art = sup;
        } else if (st != null) {
            int lv = game.augments.stat(p, st);
            done = lv >= st.cap;
            lore.add(ShopUi.num(lv + "/" + st.cap, ShopUi.WHITE));
            it = icon(shopKey("stat_" + st.key()), text(st.ko, WHITE), null, Math.max(1, lv));
            art = "stat_" + st.key();
        } else {
            it = icon(shopKey("aug_roll"), text("증강 뽑기", WHITE), null, 1);
            art = "aug_roll";
        }
        // 값: 다 샀으면 확인 표시(초록), 잠겼으면 자물쇠, 아니면 값 숫자(살 수 있으면 금색, 모자라면 빨강에 그림이 어두워짐)
        boolean can = !done && why == null && money(p) >= price;
        if (why != null) lore.add(text(why, NO));
        else if (!done) lore.add(priceLine(p, price));
        if (!lore.isEmpty()) it.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        card(p, v, w.slot, ShopUi.tile(it, art, plate, !done && !can, -1), done ? ShopUi.CHECK : why != null ? ShopUi.LOCK : ShopUi.price(price),
            done ? ShopUi.GREEN : why != null ? ShopUi.SOFT : can ? ShopUi.GOLD : ShopUi.RED, () -> buy(p, w));
    }

    /** 원정 칸(트럭이 서 있을 때만): 자동 탄약, 트럭 수리, 트럭 부품 */
    private void putTrip(Player p, View v, Ware w) {
        Truck truck = game.trip.truck();
        if (truck == null) return;
        List<Component> lore = new ArrayList<>();
        if (w == Ware.AUTO_AMMO) {
            // 자동 탄약은 증강(Augments 기본 판매 autoammo)이 이름, 설명 줄, 사기, 저절로 사는 동작을 모두 쥔다. 여기서는 칸 그림과 값 칸만
            ItemStack it = game.augments.stapleIcon(p, Augments.AUTO_AMMO);
            if (it == null) return;
            boolean own = game.augments.hasStaple(p, Augments.AUTO_AMMO), on = game.augments.stapleOn(p, Augments.AUTO_AMMO);
            int price = price(p, w);
            boolean can = own ? on : money(p) >= price;
            card(p, v, w.slot, ShopUi.tile(it, Props.has("ammo_box") ? "ammo_box" : "ammo", "tile", !can, ShopUi.BADGE_AUTO),
                own ? on ? ShopUi.CHECK : "-" : ShopUi.price(price), own ? on ? ShopUi.GREEN : ShopUi.SOFT : can ? ShopUi.GOLD : ShopUi.RED,
                () -> buy(p, w));
        } else if (w == Ware.REPAIR) {
            int pct = (int) Math.round(truck.frac() * 100);
            boolean full = truck.hp() >= truck.maxHp() - 1e-6;
            int price = price(p, w);
            boolean can = !full && money(p) >= price;
            lore.add(ShopUi.num(pct + "%" + (full ? "" : " → " + (int) Math.round(Math.min(1, (truck.hp() + truck.repairAmount()) / truck.maxHp()) * 100) + "%"),
                pct < 25 ? ShopUi.RED : pct < 60 ? ShopUi.GOLD : ShopUi.GREEN));
            if (!full) lore.add(priceLine(p, price));
            // 그림은 트럭 모델 그대로(원정 층 gave:trip/truck 의 인벤토리 표시 변환)
            card(p, v, w.slot, ShopUi.tile(icon(Key.key(Props.NS, "trip/truck"), text("트럭 수리", WHITE), lore, 1), "truck", "tile", !full && !can, -1),
                full ? ShopUi.CHECK : ShopUi.price(price), full ? ShopUi.GREEN : can ? ShopUi.GOLD : ShopUi.RED, () -> buy(p, w));
        } else {
            int pend = truck.pendingParts();
            if (pend <= 0 && truck.parts().isEmpty()) return;
            for (Map.Entry<String, Integer> e : truck.parts().entrySet())
                lore.add(text(Truck.partName(e.getKey()) + (e.getValue() > 1 ? " x" + e.getValue() : ""), DIM));
            // 고를 차례가 있으면 금색 판(붙일 부품이 더 없으면 강조하지 않음)
            boolean pick = pend > 0 && !partsDry;
            if (pick) lore.add(text("누르면 부품을 고름", GOLD));
            ItemStack it = icon(Key.key(Props.NS, "trip/wheel"), text("트럭 부품", WHITE), lore, Math.max(1, pend));
            if (pick) it.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            card(p, v, w.slot, ShopUi.tile(it, "wheel", pick ? "gold" : "tile", false, pick ? ShopUi.BADGE_NEW : -1),
                pick && pend > 1 ? "x" + Math.min(99, pend) : "", ShopUi.WHITE, () -> buy(p, w));
        }
    }

    // ------------------------------------------------------------------ 값

    /** 지금 이 사람이 w 를 사는 값(원, 5 단위) */
    public int price(Player p, Ware w) {
        // 공짜 무기 상자 표가 있으면 어느 상자든 0
        if (w.box() && freeBoxes(p) > 0) return 0;
        // 원정 칸: 값은 Trip.Tune 이 정한다(U = 그 라운드 무기 상자 바탕값)
        if (w == Ware.REPAIR) {
            Truck truck = game.trip.truck();
            // 정비공 증강: 그 사람이 내는 값만 깎임
            if (truck != null) return Math.max(5, (int) Math.round(truck.repairPrice() * game.augments.repairPriceMul(p) / 5.0) * 5);
        } else if (w == Ware.AUTO_AMMO) {
            return game.augments.staplePrice(p, Augments.AUTO_AMMO);
        } else if (w == Ware.PART) return 0;
        int wave = Math.max(1, game.wave);
        double v = w.base * (1 + w.growth * (wave - 1));
        int level = 0;
        Guns.Arms a = p == null ? null : game.guns.arms(p);
        if (w == Ware.UP_GUN && a != null && a.gun != null) {
            level = a.gun.level();
            v *= RARITY_PRICE[a.gun.rarity.ordinal()];
        } else if (w == Ware.UP_PISTOL && a != null) level = a.pistol.level();
        else if (w.stat() != null && p != null) level = game.augments.stat(p, w.stat());
        else if (w == Ware.AUG && p != null) level = game.augments.rolls(p);
        else if (w.box()) level = boxBuys(p, w);
        v *= Math.pow(w.level, level);
        v *= Math.pow(1.25, gnawed.getOrDefault(w, 0));
        // 원정 값 배율(정비 스테이지 할인 같은 것). 원정이 꺼져 있으면 1
        v *= game.trip.priceMul(w);
        return Math.max(5, (int) Math.round(v / 5.0) * 5);
    }

    // ------------------------------------------------------------------ 사기

    /** 사기: 돈이 모자라거나 살 수 없으면 false(빈 소리). 사면 화면을 다시 그림 */
    public boolean buy(Player p, Ware w) {
        Guns.Arms a = game.guns.arms(p);
        if (a == null || w == gnawing) return deny(p, "없음");
        if (w.trip()) return buyTrip(p, w);
        int price = price(p, w);
        if (money(p) < price) return deny(p, "돈 모자람 " + money(p) + "/" + price);
        String sup = w.supply();
        Augments.Stat st = w.stat();
        if (w.box()) {
            // 뽑은 총이 놓여 있으면 먼저 고르게(값을 낸 총이 말없이 사라지지 않게)
            if (pendingGun.containsKey(p.getUniqueId())) return deny(p, "뽑은 총 먼저");
            // 종류(가중치, 지금 종류는 1/3, 갈래 상자는 그 갈래 안에서)와 등급(웨이브 확률표)을 함께 굴림
            Gun g = rollBox(w, Math.max(1, game.wave), a.primary, rnd);
            if (a.gun != null) g.inherit(a.gun.level());
            pay(p, price);
            if (price == 0 && freeBoxes(p) > 0) freeBoxes.merge(p.getUniqueId(), -1, (x, y) -> x + y <= 0 ? null : x + y);
            // 낸 값이 있을 때만 그 갈래 산 횟수가 늘어 다음 값이 오름(공짜 표는 안 셈)
            else if (w.crate() != null) boxBuys.computeIfAbsent(p.getUniqueId(), k -> new EnumMap<>(Ware.class)).merge(w, 1, Integer::sum);
            if (a.gun == null) {
                game.guns.givePrimary(p, g);
                pendingGun.remove(p.getUniqueId());
            } else {
                pendingGun.put(p.getUniqueId(), g);
            }
            Props.sound(p, null, g.rarity.ordinal() >= Gun.Rarity.EPIC.ordinal() ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.BLOCK_CHEST_OPEN,
                g.rarity.ordinal() >= Gun.Rarity.EPIC.ordinal() ? 0.6f : 0.8f, 1f, 1.1f);
            lastLog = "뽑음 " + g.info();
        } else if (w == Ware.UP_GUN || w == Ware.UP_PISTOL) {
            Gun g = w == Ware.UP_GUN ? a.gun : a.pistol;
            if (g == null || g.level() >= Gun.MAX_LEVEL) return deny(p, "강화 끝");
            pay(p, price);
            game.guns.upgrade(p, w == Ware.UP_PISTOL);
            Props.sound(p, null, Sound.BLOCK_ANVIL_USE, 0.6f, 1f, 1.3f);
            lastLog = "강화 " + g.info();
        } else if (w == Ware.AMMO) {
            if (!game.guns.refillAll(p)) return deny(p, "탄약 가득");
            pay(p, price);
            Props.sound(p, null, Sound.ITEM_ARMOR_EQUIP_IRON, 0.8f, 1f, 1.2f);
            lastLog = "탄약 " + a.res[a.primary == null ? 0 : a.primary.ordinal()];
            for (java.util.function.Consumer<Player> hook : ammoBought) hook.accept(p);
        } else if (sup != null) {
            if (!supplies.canTake(p, sup)) return deny(p, "가득");
            String drop = supplies.wouldDrop(p, sup);
            if (drop != null && !asking(p, w)) {
                // 다른 소모품을 버리게 됨: 첫 누름은 확인만(칸이 반짝임), SWAP_CONFIRM 틱 안에 다시 누르면 삼
                swapAsk.put(p.getUniqueId(), w);
                swapAt.put(p.getUniqueId(), game.now());
                Props.sound(p, null, Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1f, 0.8f);
                lastLog = "바꿈 확인 " + sup + " 버릴 것 " + drop;
                refresh(p);
                return false;
            }
            swapAsk.remove(p.getUniqueId());
            if (!supplies.give(p, sup, 1)) return deny(p, "못 받음");
            pay(p, price);
            Props.sound(p, null, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1f, 1f);
            lastLog = "소모품 " + sup + " " + supplies.count(p, sup);
        } else if (st != null) {
            if (!game.augments.raiseStat(p, st)) return deny(p, "스탯 끝");
            pay(p, price);
            Props.sound(p, null, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1f, 1.4f);
            lastLog = "스탯 " + st + " " + game.augments.stat(p, st);
        } else {
            pay(p, price);
            lastLog = "증강 뽑기";
            game.augments.bought(p);
            return true;
        }
        refresh(p);
        return true;
    }

    /** 원정 칸 사기: 자동 탄약(사기, 산 뒤에는 끄고 켬), 트럭 수리(한 번 누름 = 모금 한 번), 트럭 부품(차례가 있으면 카드 셋) */
    private boolean buyTrip(Player p, Ware w) {
        Truck truck = game.trip.truck();
        if (truck == null) return deny(p, "트럭 없음");
        PState s = game.players.get(p);
        if (w == Ware.AUTO_AMMO) {
            // 사기(돈은 Augments 가 뺌), 산 뒤에는 끄고 켬
            boolean had = game.augments.hasStaple(p, Augments.AUTO_AMMO);
            if (!game.augments.buyStaple(p, Augments.AUTO_AMMO)) return deny(p, "돈 모자람 " + money(p) + "/" + price(p, w));
            lastLog = "자동 탄약 " + (!had ? "삼" : game.augments.stapleOn(p, Augments.AUTO_AMMO) ? "켬" : "끔");
        } else if (w == Ware.REPAIR) {
            if (!truck.alive() || truck.hp() >= truck.maxHp() - 1e-6) return deny(p, "트럭 가득");
            int price = price(p, w);
            if (money(p) < price) return deny(p, "돈 모자람 " + money(p) + "/" + price);
            if (!truck.repairPress(p)) return deny(p, "못 고침");
            pay(p, price);
            s.repairPaid += price;
            game.augments.paid(p, "repair", price);
            lastLog = "수리 " + (int) truck.hp() + "/" + (int) truck.maxHp();
            // 모금 칸: 다른 사람 화면의 값과 비율도 바뀜
            for (Player o : game.players.inPlay()) if (o != p) refresh(o);
        } else {
            if (truck.pendingParts() <= 0) return deny(p, "부품 차례 없음");
            if (partPicker != null && !partPicker.equals(p.getUniqueId())) return deny(p, "다른 사람이 고르는 중");
            openParts(p, truck);
            return true;
        }
        refresh(p);
        return true;
    }

    /** 트럭 부품을 고르는 중인 사람(먼저 연 사람이 고른다, 닫으면 풀림)과 그 칸을 누른 틱 */
    private UUID partPicker;
    private long partPickAt;
    /** 이번 차례에 굴려 둔 부품 카드(닫았다 다시 열어도, 다른 사람이 열어도 그대로. 하나를 고르면 지워 다음 차례에 새로 굴림) */
    private List<Truck.PartDef> partOffer;
    /** 붙일 부품이 더 없음(남은 차례는 쓸 수 없다: 칸 강조를 끔). 쉬는 시간마다 다시 본다(범퍼처럼 늦게 후보가 되는 부품) */
    private boolean partsDry;

    /** 공짜 무기 상자 표(원정 스테이지 보상 box): 사람마다 남은 수. 있으면 무기 상자 값이 0 이고 한 번 뽑을 때 하나 씀. 판 끝에 지움 */
    private final Map<UUID, Integer> freeBoxes = new HashMap<>();

    /** 공짜 무기 상자 표 n 장을 줌(원정 스테이지 보상: 상자 값만큼 돈 대신 이것을 부르면 됨). */
    public void giveFreeBox(Player p, int n) {
        if (p == null || n <= 0) return;
        freeBoxes.merge(p.getUniqueId(), n, Integer::sum);
        refresh(p);
    }

    /** 남은 공짜 무기 상자 표 수. */
    public int freeBoxes(Player p) {
        return p == null ? 0 : freeBoxes.getOrDefault(p.getUniqueId(), 0);
    }

    /** 갈래 상자를 돈 내고 산 횟수(사람마다, 갈래마다 따로. 나갔다 와도 판 끝까지 남음, 판 끝에 지움) */
    private final Map<UUID, Map<Ware, Integer>> boxBuys = new HashMap<>();

    /** 이 사람이 이 갈래 상자를 산 횟수(모든 종류 상자와 그 밖은 0) */
    public int boxBuys(Player p, Ware w) {
        if (p == null || w.crate() == null) return 0;
        Map<Ware, Integer> m = boxBuys.get(p.getUniqueId());
        return m == null ? 0 : m.getOrDefault(w, 0);
    }

    /** 트럭 부품 카드 셋: 고르면 트럭에 붙고 상점으로 돌아간다. 카드는 차례마다 한 번만 굴린다(닫았다 열어 다시 굴리지 못하게) */
    private void openParts(Player p, Truck truck) {
        if (partOffer == null) partOffer = truck.partOffer(3, rnd);
        List<Truck.PartDef> offer = partOffer;
        if (offer.isEmpty()) {
            partOffer = null;
            partsDry = true;
            deny(p, "붙일 부품 없음");
            refresh(p);
            return;
        }
        partPicker = p.getUniqueId();
        partPickAt = game.now();
        View v = new View(p.getUniqueId(), View.PARTS, true);
        v.inv = Bukkit.createInventory(v, 27, ShopUi.title(ShopUi.PARTS));
        int[] at = offer.size() == 1 ? new int[] {13} : offer.size() == 2 ? new int[] {12, 14} : new int[] {11, 13, 15};
        for (int i = 0; i < offer.size(); i++) {
            Truck.PartDef d = offer.get(i);
            int have = truck.part(d.key());
            // 그림은 그 부품(증강 갈래 part)의 카드 그림: 셋이 서로 다르게 보인다. 표에 없는 부품이면 바퀴
            Augments.Aug aug = Augments.Aug.of(d.key());
            ItemStack it = aug == null ? ShopUi.tile(icon(shopKey("aug_roll"), Component.empty(), null, 1), "wheel", "gold", false, -1)
                : ShopUi.card(Augments.card(aug, 1));
            it.setData(DataComponentTypes.ITEM_NAME, text(d.name() + (have > 0 ? " x" + (have + 1) : ""), GOLD));
            it.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(text(d.line(), WHITE))));
            v.inv.setItem(at[i], it);
            v.actions.put(at[i], () -> {
                if (!truck.takePendingPart()) return;
                truck.addPart(d.key());
                // 모두에게 '트럭에 부품을 달았다'(증강 쪽 Augments.addPart 와 같은 알림. 상점 길에서는 빠져 있었다)
                if (Hud.hasPhrase("aug_part")) game.hud.notice("aug_part", 60);
                partPicker = null;
                partOffer = null;
                Props.soundAround(Truck.center().toLocation(game.world).add(0, 1, 0), null, Sound.BLOCK_ANVIL_USE, 0.7f, 1f, 0.9f, 32, -1);
                lastLog = "부품 " + d.key() + " 고른 사람 " + p.getName();
                game.plugin.getLogger().info("[상점] 트럭 부품 " + d.name() + " 고른 사람 " + p.getName());
                open(p);
                for (Player o : game.players.inPlay()) if (o != p) refresh(o);
            });
        }
        show(p, v);
    }

    /** 이 칸 바꿈 확인 중인가(첫 누름 뒤 SWAP_CONFIRM 틱 안) */
    private boolean asking(Player p, Ware w) {
        Long at = swapAt.get(p.getUniqueId());
        return w == swapAsk.get(p.getUniqueId()) && at != null && game.now() - at <= SWAP_CONFIRM;
    }

    private void pay(Player p, int price) {
        if (!game.players.spend(p, price)) game.players.addMoney(p, -price);
    }

    private boolean deny(Player p, String why) {
        Props.sound(p, null, Sound.BLOCK_DISPENSER_FAIL, 0.6f, 1f, 1.2f);
        lastLog = "못 삼 " + why;
        return false;
    }

    /** 뽑은 총으로 바꿈(기다리는 총이 있으면 다음 것이 뽑은 총 칸에 옴) */
    public boolean take(Player p) {
        Gun g = pendingGun.remove(p.getUniqueId());
        if (g == null) return false;
        Guns.Arms a = game.guns.arms(p);
        if (a != null && a.gun != null) g.inherit(a.gun.level());
        game.guns.givePrimary(p, g);
        nextPending(p);
        Props.sound(p, null, Sound.ITEM_ARMOR_EQUIP_IRON, 0.9f, 1f, 1f);
        lastLog = "바꿈 " + g.info();
        refresh(p);
        return true;
    }

    /** 지금 총을 지킴(뽑은 총 버림, 기다리는 총이 있으면 다음 것이 뽑은 총 칸에 옴) */
    public boolean keep(Player p) {
        Gun g = pendingGun.remove(p.getUniqueId());
        if (g == null) return false;
        nextPending(p);
        Props.sound(p, null, Sound.BLOCK_CHEST_CLOSE, 0.7f, 1f, 1.2f);
        lastLog = "지킴 버린 총 " + g.info();
        refresh(p);
        return true;
    }

    private void nextPending(Player p) {
        java.util.ArrayDeque<Gun> q = gunQueue.get(p.getUniqueId());
        if (q == null) return;
        Gun next = q.poll();
        if (q.isEmpty()) gunQueue.remove(p.getUniqueId());
        if (next != null) pendingGun.put(p.getUniqueId(), next);
    }

    /**
     * 다른 데서 생긴 총(꾸러미, 상자 같은 종)을 상점 '뽑은 총' 칸에 놓음(주무기가 없으면 바로 줌). 뽑은 총 칸이 차 있으면 뒤에 기다리게 하고,
     * 앞의 것을 고르면(바꾸기, 지키기) 차례로 칸에 온다
     */
    public void offerGun(Player p, Gun g) {
        Guns.Arms a = game.guns.arms(p);
        if (a == null || g == null) return;
        if (a.gun == null) game.guns.givePrimary(p, g);
        else if (pendingGun.containsKey(p.getUniqueId())) gunQueue.computeIfAbsent(p.getUniqueId(), k -> new java.util.ArrayDeque<>()).add(g);
        else pendingGun.put(p.getUniqueId(), g);
        refresh(p);
    }

    /** 뽑은 총 칸 뒤에 기다리는 총 수 */
    public int queuedGuns(Player p) {
        java.util.ArrayDeque<Gun> q = gunQueue.get(p.getUniqueId());
        return q == null ? 0 : q.size();
    }

    /** 뽑아 둔 총(바꾸기 전), 없으면 null */
    public Gun pendingGun(Player p) {
        return pendingGun.get(p.getUniqueId());
    }

    /** 다시 그릴 사람(다음 {@link #tick} 에 사람마다 한 번만 그림) */
    private final java.util.Set<UUID> dirty = new java.util.HashSet<>();

    /**
     * 열려 있으면 칸을 다시 그림. 바로 그리지 않고 표시만 해 두었다가 다음 틱에 한 번 그린다: 수리, 꺼내기처럼 한 번 누름이 모두의 화면을 다시 그리게
     * 하는 일이 같은 틱에 여러 번 와도 사람마다 한 번
     */
    public void refresh(Player p) {
        if (p != null) dirty.add(p.getUniqueId());
    }

    private void redraw(Player p) {
        View v = view(p);
        if (v != null && v.kind.equals(View.SHOP)) render(p, v);
    }

    // ------------------------------------------------------------------ 감독, 종이 부르는 것

    /** 상점 오염 단계(빈칸에 섞일 실눈 칸 수, 0 이면 없음). 감독이 웨이브마다 부름 */
    public void contaminate(int level) {
        contamination = Math.max(0, Math.min(24, level));
        for (Player p : game.players.inPlay()) refresh(p);
    }

    public int contamination() {
        return contamination;
    }

    /** 상점 갉는 게이브가 갉기 시작: 아무 칸 하나를 잠그고 그 칸 키를 돌려줌(이미 갉는 중이면 그 칸) */
    public String gnawStart() {
        if (gnawing == null) {
            // 원정 칸(자동 탄약, 수리, 부품)은 갉히지 않는다
            List<Ware> ws = new ArrayList<>();
            for (Ware w : Ware.values()) if (!w.trip()) ws.add(w);
            gnawing = ws.get(rnd.nextInt(ws.size()));
            for (Player p : game.players.inPlay()) refresh(p);
        }
        return gnawing.key();
    }

    /** 갉기 끝: done 이면 다음 쉬는 시간에 그 칸 값 25% 오름, 아니면(잡힘) 그냥 풀림 */
    public void gnawEnd(String key, boolean done) {
        Ware w = key == null ? gnawing : Ware.of(key);
        if (w == null) return;
        if (done) gnawRaise.add(w);
        if (w == gnawing) gnawing = null;
        for (Player p : game.players.inPlay()) refresh(p);
    }

    /** 지금 갉히는 칸 키(없으면 null) */
    public String gnawing() {
        return gnawing == null ? null : gnawing.key();
    }

    /** 이 점이 켜진 조명탄 빛 안인가(빛 피하는 종) */
    public boolean inFlare(Vector at, double extra) {
        return supplies.inFlare(at, extra);
    }

    /** 이 점에서 r 블록 안에 켜진 조명탄이 있나 */
    public boolean nearFlare(Vector at, double r) {
        return supplies.nearFlare(at, r);
    }

    /** 이 점이 화염병 바닥 불 위인가 */
    public boolean inFire(Vector at) {
        return supplies.inFire(at);
    }

    /** 비옷을 입었나 */
    public boolean raincoat(Player p) {
        return supplies.raincoat(p);
    }

    /** 붙기, 잠복, 덮기 전에 Players 가 부름: 비옷이 있으면 찢어지며 true(그 일을 막음) */
    public boolean tearRaincoat(Player p) {
        return supplies.tearRaincoat(p);
    }

    // ------------------------------------------------------------------ 틱, 정리

    public void tick() {
        ShopUi.sync(game);
        supplies.tick();
        long now = game.now();
        if (game.state == Game.State.BREAK && lastState != Game.State.BREAK) {
            for (Ware w : gnawRaise) gnawed.merge(w, 1, Integer::sum);
            gnawRaise.clear();
            game.augments.onBreak();
            if (partsDry) {
                Truck t = game.trip.truck();
                partsDry = t != null && t.partOffer(1, rnd).isEmpty();
            }
        }
        lastState = game.state;
        if (now % 10 == 0) for (Player p : game.players.inPlay()) game.augments.apply(p);
        // 열린 상점을 1초마다 다시 그림: 값(웨이브, 갉힘), 돈, 꺼내기와 수리 칸이 따라 바뀌게(원정이 꺼진 판에서도). 사람마다 틱을 나눠
        // (엔티티 번호 나머지) 한 틱에 몰리지 않게. 그린 결과가 같으면 칸 패킷은 나가지 않는다(위 칸은 바닐라 비교, 아래 칸은 flush)
        for (Player p : game.players.inPlay()) if (Math.floorMod(now + p.getEntityId(), 20L) == 0) refresh(p);
        // 부품 칸을 누른 사람이 고르기 화면에 없으면(누른 틱과 화면이 열리는 틱 사이에 나감) 다른 사람이 고를 수 있게 푼다
        if (partPicker != null && now - partPickAt > 2) {
            Player picker = Bukkit.getPlayer(partPicker);
            View pv = picker == null ? null : view(picker);
            if (pv == null || !pv.kind.equals(View.PARTS)) partPicker = null;
        }
        if (now % 200 == 0) shownAt.values().removeIf(at -> now - at > 1);
        // 둘 수 없는 자리(웨이브가 시작됐는데 보급 상자 둘레 밖, 쓰러짐, 붙잡힘)면 닫음(붙잡히면 좌클릭 탈출 연타가 상자 칸 클릭이 되지 않게 2 틱마다)
        if (now % 2 == 0) for (Player p : Bukkit.getOnlinePlayers()) {
            View v = view(p);
            if (v != null && !canStay(p) && !forced.contains(p.getUniqueId())) p.closeInventory();
        }
        // 소모품 바꿈 확인이 시간을 넘김: 반짝임을 지움
        if (!swapAsk.isEmpty()) for (var it = swapAsk.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            Long at = swapAt.get(e.getKey());
            if (at != null && now - at <= SWAP_CONFIRM) continue;
            it.remove();
            swapAt.remove(e.getKey());
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) refresh(p);
        }
        // 표시해 둔 다시 그림(사람마다 한 번)
        if (!dirty.isEmpty()) {
            List<UUID> ids = new ArrayList<>(dirty);
            dirty.clear();
            for (UUID id : ids) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) redraw(p);
            }
        }
    }

    /** 시험 훅으로 연 사람(자리 조건으로 닫지 않음) */
    private final java.util.Set<UUID> forced = new java.util.HashSet<>();

    /** 시험: 이 사람 화면은 자리 조건으로 닫지 않음(닫으면 풀림) */
    void force(Player p) {
        forced.add(p.getUniqueId());
    }

    public void clear() {
        for (Player p : Bukkit.getOnlinePlayers()) if (view(p) != null) p.closeInventory();
        pendingGun.clear();
        gunQueue.clear();
        swapAsk.clear();
        swapAt.clear();
        clickAt.clear();
        gnawed.clear();
        gnawRaise.clear();
        gnawing = null;
        contamination = 0;
        forced.clear();
        freeBoxes.clear();
        boxBuys.clear();
        shownAt.clear();
        cardsLater.clear();
        revivePage.clear();
        dirty.clear();
        partPicker = null;
        partOffer = null;
        partsDry = false;
        supplies.clear();
        game.augments.clear();
    }

    // ------------------------------------------------------------------ 이벤트

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof View v)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !v.who.equals(p.getUniqueId())) return;
        // 화면 칸 번호 그대로 누름 표를 찾는다(상점은 상자 창 아래 플레이어 인벤토리 세 줄도 화면이다). 표에 없는 칸은 아무 일 없음
        switch (e.getClick()) {
            case LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT -> {
            }
            default -> {
                return;
            }
        }
        long now = game.now();
        Long last = clickAt.get(p.getUniqueId());
        if (last != null && now - last < 2) return;
        clickAt.put(p.getUniqueId(), now);
        Runnable r = v.actions.get(e.getRawSlot());
        if (r != null) r.run();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof View) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof View v) {
            forced.remove(e.getPlayer().getUniqueId());
            if (v.kind.equals(View.SHOP) && e.getPlayer() instanceof Player pl) sweep(pl);
            // 카드 화면을 고르지 않고 직접 닫음: 그 묶음이 그대로인 동안은 상점을 열 때 카드부터 띄우지 않는다
            if (v.kind.equals(View.CARDS) && e.getReason() == InventoryCloseEvent.Reason.PLAYER && e.getPlayer() instanceof Player pl)
                cardsLater.put(pl.getUniqueId(), game.augments.pending(pl));
            // 트럭 부품 카드를 고르지 않고 닫음: 차례는 그대로 남고 다른 사람이 열 수 있다
            if (v.kind.equals(View.PARTS) && e.getPlayer().getUniqueId().equals(partPicker)) partPicker = null;
        }
    }

    /** 들어올 때: 서버가 상점을 연 채로 죽었으면 남았을 칸 아이템을 치운다 */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        sweep(e.getPlayer());
    }

    /** 3번 칸을 들고 우클릭: 소모품 쓰기(Guns.onInteract 가 상점을 열었으면 건너뜀) */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE || view(p) != null) return;
        if (p.getInventory().getHeldItemSlot() != Guns.SLOT_ITEM) return;
        Long shown = shownAt.get(p.getUniqueId());
        if (shown != null && game.now() - shown <= 1) return;
        if (supplies.use(p)) e.setCancelled(true);
    }

    /** F: 수류탄이 아닌 소모품이 칸에 있고 3번 칸을 들었거나 수류탄이 없으면 그것을 씀(수류탄은 Grenades 가 NORMAL 에서 받음) */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        Guns.Arms a = game.guns.arms(p);
        if (a == null || a.item == null || a.item.equals("grenade")) return;
        if (p.getInventory().getHeldItemSlot() != Guns.SLOT_ITEM && a.grenades > 0) return;
        e.setCancelled(true);
        supplies.use(p);
    }

    // ------------------------------------------------------------------ 시험

    /**
     * 상점열기 &lt;이름&gt;, 상점보기 &lt;이름&gt;(카운터를 보고 우클릭한 것처럼 tryOpen), 상점닫기 &lt;이름&gt;, 사기 &lt;이름&gt; &lt;칸 번호|ware 키&gt;,
     * 값표 [이름], 바꾸기총 &lt;이름&gt;, 지키기총 &lt;이름&gt;, 상점돈 &lt;이름&gt; &lt;수&gt;, 상점칸 &lt;이름&gt; &lt;칸&gt;(그 칸 아이템 이름, 모델), 오염 &lt;이름&gt; &lt;단계&gt;,
     * 갉기 &lt;이름&gt; [끝|잡힘], 상점상태 &lt;이름&gt;, 나갔다옴 &lt;이름&gt;(나감과 들어옴 흉내), 카운터짓기(한가운데 보급 상자와 등불),
     * 총선물 &lt;이름&gt; &lt;KIND&gt; [등급](offerGun), 상자확률 &lt;이름|x&gt; [웨이브] [수] [상자 키], 그 밖은 Supplies.test(소모품, 쓰기, 소모품상태, 비옷찢기).
     * 상자 키: box(모든 종류), box_pistol, box_smg, box_rifle, box_mg, box_shotgun, box_sniper, box_special, box_blast. 옛 roll_&lt;종류&gt;, 갈래
     * 이름(shotgun), 종류 이름(MINIGUN)도 그 갈래 상자로 읽음
     */
    public String test(Player p, String[] a) {
        if (a.length < 2) return null;
        switch (a[1]) {
            case "상점열기" -> {
                if (p == null) return "없음";
                forced.add(p.getUniqueId());
                open(p);
                return "ok canOpen=" + canOpen(p) + " pendingCards=" + game.augments.pending(p);
            }
            case "상점보기" -> {
                if (p == null) return "없음";
                boolean look = lookingAtCounter(p), can = canOpen(p);
                Location eye = p.getEyeLocation();
                RayTraceResult r = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), COUNTER_REACH, FluidCollisionMode.NEVER, true);
                boolean counter = r != null && isCounter(r.getHitBlock());
                boolean gabe = gabeInWay(p, eye.toVector(), eye.getDirection(), COUNTER_REACH);
                boolean ok = tryOpen(p);
                return "opened=" + ok + " look=" + look + " canOpen=" + can + " counter=" + counter + " built=" + counterBuilt() + " gabe=" + gabe
                    + " state=" + game.state + String.format(Locale.ROOT, " at=%.1f,%.1f,%.1f yaw=%.0f pitch=%.0f", p.getLocation().getX(),
                        p.getLocation().getY(), p.getLocation().getZ(), p.getLocation().getYaw(), p.getLocation().getPitch());
            }
            case "나갔다옴" -> {
                // 시험: Gave.onQuit 과 같은 순서로 나간 것처럼 지운 뒤 다음 틱에 Players.onJoin(들어옴)을 부름(접속을 실제로 끊지는 않음)
                if (p == null) return "없음";
                UUID id = p.getUniqueId();
                String before = game.guns.primaryGun(p) + " pistol=" + game.guns.pistolGun(p);
                if (view(p) != null) p.closeInventory();
                game.guns.forget(id);
                game.hud.forget(id);
                game.players.forget(id);
                boolean parked = game.guns.parked(id);
                Bukkit.getScheduler().runTask(game.plugin, () -> {
                    if (p.isOnline()) game.players.onJoin(new org.bukkit.event.player.PlayerJoinEvent(p, Component.empty()));
                });
                return "before=" + before + " parked=" + parked + " arms=" + (game.guns.arms(p) != null);
            }
            case "카운터짓기" -> {
                // 시험: 한가운데 보급 상자와 등불(PlazaGen 과 같은 블록)을 놓음(처음 것은 기억). 카운터짓기 x 없앰 = 공기, 카운터짓기 x 되돌림 = 처음 것
                String mode = a.length > 3 ? a[3] : "";
                boolean was = counterBuilt();
                for (int dx = -COUNTER_R; dx <= COUNTER_R; dx++) for (int dz = -COUNTER_R; dz <= COUNTER_R; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        Block b = game.world.getBlockAt(Plaza.CX + dx, Plaza.FLOOR_Y + dy, Plaza.CZ + dz);
                        if (!counterSaved.containsKey(b)) counterSaved.put(b, b.getBlockData());
                        if (mode.equals("되돌림")) b.setBlockData(counterSaved.get(b), false);
                        else if (mode.equals("없앰")) b.setType(Material.AIR, false);
                        else b.setBlockData(Bukkit.createBlockData(dy == 0 ? "minecraft:barrel[facing=up]" : "minecraft:lantern[hanging=false]"), false);
                    }
                }
                if (mode.equals("되돌림")) counterSaved.clear();
                counterBuilt = null;
                return "before=" + was + " built=" + counterBuilt() + " top=" + game.world.getBlockAt(Plaza.CX, Plaza.FLOOR_Y + 1, Plaza.CZ).getBlockData()
                    .getAsString() + " edge=" + game.world.getBlockAt(Plaza.CX + 1, Plaza.FLOOR_Y + 1, Plaza.CZ - 1).getBlockData().getAsString() + " base="
                    + game.world.getBlockAt(Plaza.CX - 1, Plaza.FLOOR_Y, Plaza.CZ + 1).getBlockData().getAsString();
            }
            case "총선물" -> {
                // 총선물 <이름> <KIND> [등급]: 꾸러미, 상자 종이 주는 총처럼 offerGun
                if (p == null || a.length < 4) return "총선물 <이름> <종류(PISTOL 빼고)> [등급]";
                Guns.Kind k;
                try {
                    k = Guns.Kind.valueOf(a[3].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return "모르는 총: " + a[3];
                }
                Gun.Rarity rr = a.length > 4 ? Gun.Rarity.of(a[4]) : Gun.Rarity.COMMON;
                if (rr == null || k == Guns.Kind.PISTOL) return "못 줌";
                offerGun(p, Gun.roll(k, rr, rnd));
                return "pending=" + pendingGun(p) + " queued=" + queuedGuns(p) + " gun=" + game.guns.primaryGun(p);
            }
            case "상점닫기" -> {
                if (p != null) p.closeInventory();
                return "ok";
            }
            case "사기" -> {
                if (p == null || a.length < 4) return "사기 <이름> <칸 번호|ware>";
                Ware w = Ware.of(a[3]);
                if (w == null) {
                    // 칸 번호가 Ware 가 아니면 화면 칸 행동(뽑은 총 1, 지금 총 0)
                    View v = view(p);
                    try {
                        // 옛 대본의 칸 번호: 0 지금 총(지키기), 1 뽑은 총(바꾸기). 그 밖의 수는 지금 화면 칸
                        int slot = Integer.parseInt(a[3]);
                        if (slot == 1) slot = SLOT_PENDING;
                        Runnable r = v == null ? null : v.actions.get(slot);
                        if (r == null) return "그 칸 없음 " + slot;
                        r.run();
                        return "칸 " + slot + " | " + lastLog;
                    } catch (NumberFormatException ex) {
                        return "모르는 칸: " + a[3];
                    }
                }
                int price = price(p, w);
                boolean ok = buy(p, w);
                return (ok ? "삼 " : "못 삼 ") + w.key() + " " + price + "원 남음=" + money(p) + " | " + lastLog;
            }
            case "상자확률" -> {
                // 상자확률 <이름|x> [웨이브] [수] [상자 키]: 무기 상자(기본 모든 종류 box, box_shotgun 같은 갈래 상자도)를 수 번 굴린 종류, 등급 분포
                // (사지 않음, 이름이 있으면 그 사람 지금 종류를 1/3 로)
                int wave = a.length > 3 ? Integer.parseInt(a[3]) : Math.max(1, game.wave);
                int n = a.length > 4 ? Integer.parseInt(a[4]) : 1000;
                Ware bw = a.length > 5 ? Ware.of(a[5]) : Ware.BOX;
                if (bw == null || !bw.box()) return "상자 아님: " + a[5];
                Guns.Arms ar = p == null ? null : game.guns.arms(p);
                Guns.Kind cur = ar == null ? null : ar.primary;
                Map<Guns.Kind, Integer> kc = new EnumMap<>(Guns.Kind.class);
                int[] rc = new int[Gun.Rarity.values().length];
                for (int i = 0; i < n; i++) {
                    Gun g = rollBox(bw, wave, cur, rnd);
                    kc.merge(g.kind, 1, Integer::sum);
                    rc[g.rarity.ordinal()]++;
                }
                StringBuilder sb = new StringBuilder(bw.key() + " wave=" + wave + " n=" + n + " cur=" + cur);
                for (Map.Entry<Guns.Kind, Integer> e : kc.entrySet()) sb.append(' ').append(e.getKey().name()).append('=').append(e.getValue());
                sb.append(" | rarity ").append(java.util.Arrays.toString(rc));
                return sb.toString();
            }
            case "값표" -> {
                StringBuilder sb = new StringBuilder("wave=" + Math.max(1, game.wave));
                for (Ware w : Ware.values()) sb.append(' ').append(w.key()).append('=').append(price(p, w));
                return sb.toString();
            }
            case "바꾸기총" -> {
                return p == null ? "없음" : "took=" + take(p) + " " + game.guns.primaryGun(p);
            }
            case "지키기총" -> {
                return p == null ? "없음" : "kept=" + keep(p) + " " + game.guns.primaryGun(p);
            }
            case "상점돈" -> {
                if (p == null || a.length < 4) return "상점돈 <이름> <수>";
                game.players.addMoney(p, Integer.parseInt(a[3]));
                refresh(p);
                return "money=" + money(p);
            }
            case "상점칸" -> {
                if (p == null || a.length < 4) return "상점칸 <이름> <칸>";
                View v = view(p);
                if (v == null) return "안 열림";
                int at = Integer.parseInt(a[3]);
                ItemStack it = at < BELOW ? v.inv.getItem(at) : p.getInventory().getItem(at - BELOW + 9);
                if (it == null) return "빈칸";
                Component n = it.getData(DataComponentTypes.ITEM_NAME);
                CustomModelData cmd = it.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
                return "model=" + it.getData(DataComponentTypes.ITEM_MODEL) + " x" + it.getAmount() + " name="
                    + (n == null ? "-" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(n))
                    + (cmd == null ? "" : " cell=" + cmd.strings() + " veil=" + cmd.flags() + " text=" + cmd.floats());
            }
            case "오염" -> {
                contaminate(a.length > 3 ? Integer.parseInt(a[3]) : 3);
                return "contamination=" + contamination;
            }
            case "갉기" -> {
                if (a.length > 3) {
                    String k = gnawing();
                    gnawEnd(k, a[3].equals("끝"));
                    return "gnawEnd " + k + " raise=" + gnawRaise;
                }
                return "gnawing=" + gnawStart();
            }
            case "상점상태" -> {
                if (p == null) return "없음";
                View v = view(p);
                return "view=" + (v == null ? "-" : v.kind) + " canOpen=" + canOpen(p) + " canStay=" + canStay(p) + " inShop=" + inShop(p) + " money="
                    + money(p) + " pending=" + pendingGun(p) + " queued=" + queuedGuns(p) + " gun=" + game.guns.primaryGun(p) + " | " + lastLog;
            }
            default -> {
                return supplies.test(p, a);
            }
        }
    }
}
