package kr.gave.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.ItemLore;
import kr.gave.Props;
import kr.gave.gun.Gun;
import kr.gave.gun.GunHooks;
import kr.gave.gun.GunMods;
import kr.gave.gun.Guns;
import kr.gave.mob.Band;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.player.Life;
import kr.gave.player.PState;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 증강(옛 31개 + 2026-11-12 trip_aug 97개)과 상점 스탯(최대 체력, 받는 피해, 걷기, 재장전), 상점 기본 판매(자동 탄약), 트럭 부품(팀 공용).
 * 뽑기는 카드 3장(상자 화면 3칸) 중 하나. 가진 증강 키는 PState.augs(같은 키가 여러 번이면 겹침). 상점 스탯 단계는 여기 Build 에.
 * <p>
 * 새 증강의 이름, 한 줄, 등급, 갈래, 묶임(무기 종류), 숫자는 jar 안 trip/augments.json({@link AugData})에서 읽는다(숫자는 그 파일 한곳). 효과는
 * {@link AugRules}(매 틱, 맞힘, 처치)와 총 쪽 고리(GunHooks 의 kindMods 등)가 낸다. 원정 부품(트럭 체력, 꺼내기)에 기대는 것은
 * {@link AugLinks}(허브 Trip 이 {@link #links} 에 닮)가 준비됐다고 할 때만 뽑기에 나온다.
 * <p>
 * 겹침(정의 표 {@link Aug}): 총 배율(GunMods)은 곱함(관통, 치명, 산탄 알은 더함), 대상 조건 피해는 곱함, 처치 회복과 출혈은 더함,
 * 처치 재화는 더함(+10% 씩), 금빛 홍조 보상은 1 + 수 배(뽑기 횟수), 받는 피해, 걷기, 일으키기는 곱함, 최대 체력, 수류탄은 더함.
 * 새 증강은 대부분 하나만 가진다(augments.json max).
 * <p>
 * mods(p) = 가진 증강 GunMods 모두 곱 x 재장전 스탯(0.95^단계) x 그때그때 조건(웅크림, 트럭 곁, 합창, 피 냄새). 종류마다 다른 배율은
 * {@link #kindMods}. PState 스탯(maxHp, armor, moveMul)은 {@link #apply} 가 다시 계산해 넣는다(매 틱). 기본 maxHp 100.
 * <p>
 * 틱: 생성자가 스케줄러에 매 틱 {@link #tick} 을 건다(Game.tick 보다 먼저 돎: 탄이 떨어진 다음 틱 맨 앞에 자동 탄약이 사서 사격이 안 끊김).
 * <p>
 * GunHooks(Guns.hooks 에 생성자에서 담): 띠, 역할, 체력 조건 피해(hitMul), 처치 회복(피 맛 3, 흡혈 특성 5), 처치 재화 +10%(쏜 사람에게 그 개체 보상의
 * 10% x 수를 바로 더 줌, 엔진 몫 분배와 따로), 아군 사격(Players.friendlyHit).
 */
public final class Augments implements GunHooks {

    /** 갈래: 카드 위아래 유리판 색과 이름. 위험은 붉은 테두리 + 붉은 이름 */
    public enum Cat {
        BASE("기본", Material.LIGHT_GRAY_STAINED_GLASS_PANE), WEAPON("무기", Material.ORANGE_STAINED_GLASS_PANE),
        TRUCK("트럭", Material.CYAN_STAINED_GLASS_PANE), PART("부품", Material.BLUE_STAINED_GLASS_PANE), TEAM("팀", Material.LIME_STAINED_GLASS_PANE),
        RISK("위험", Material.RED_STAINED_GLASS_PANE), BODY("몸", Material.PINK_STAINED_GLASS_PANE), MONEY("돈", Material.YELLOW_STAINED_GLASS_PANE),
        KILL("처치", Material.PURPLE_STAINED_GLASS_PANE), DEATH("죽음", Material.BLACK_STAINED_GLASS_PANE);

        public final String ko;
        public final Material pane;

        Cat(String ko, Material pane) {
            this.ko = ko;
            this.pane = pane;
        }

        static Cat of(String s) {
            if (s != null) for (Cat c : values()) if (c.name().equalsIgnoreCase(s)) return c;
            return BASE;
        }
    }

    /** 위험 증강 이름 색 */
    public static final TextColor RISK_COLOR = TextColor.color(0xFF4A4A);

    /**
     * 증강 정의(키 = 시험 훅, PState.augs). 앞 31개는 여기 적힌 이름, 한 줄, 등급, 총 배율을 쓰고(갈래와 그림만 augments.json),
     * 뒤는 키만 적고 모두 augments.json 에서 읽는다. 차례는 저장에 안 쓰이므로 뒤에 더한다
     */
    public enum Aug {
        DMG("dmg", "날카로운 탄", "피해 +12%", Gun.Rarity.COMMON, GunMods.NONE.mulDamage(1.12)),
        RATE("rate", "가벼운 방아쇠", "연사 +10%", Gun.Rarity.COMMON, GunMods.NONE.mulRate(1.10)),
        MAG("mag", "긴 탄창", "탄창 +25%", Gun.Rarity.COMMON, GunMods.NONE.mulMag(1.25)),
        RELOAD("reload", "빠른 손놀림", "재장전 -15%", Gun.Rarity.COMMON, GunMods.NONE.mulReload(0.85)),
        STEADY("steady", "곧은 손", "퍼짐 -20%", Gun.Rarity.COMMON, GunMods.NONE.mulSpread(0.8)),
        GRIP("grip", "단단한 손잡이", "반동 -25%", Gun.Rarity.COMMON, GunMods.NONE.mulRecoil(0.75)),
        REACH("reach", "먼 눈", "사거리 +20%", Gun.Rarity.COMMON, GunMods.NONE.mulRange(1.2)),
        RESERVE("reserve", "큰 탄띠", "예비탄 +50%", Gun.Rarity.COMMON, GunMods.NONE.mulReserve(1.5)),
        CROUCH("crouch", "낮은 자세", "웅크리면 퍼짐 -40%", Gun.Rarity.COMMON, null),
        LEECH("leech", "피 맛", "처치 시 체력 3", Gun.Rarity.COMMON, null),
        BOUNTY("bounty", "현상금", "처치 재화 +10%", Gun.Rarity.COMMON, null),
        HP("hp", "두꺼운 살", "최대 체력 +15", Gun.Rarity.COMMON, null),
        ARMOR("armor", "덧댄 옷", "받는 피해 -8%", Gun.Rarity.COMMON, null),
        MOVE("move", "가벼운 발", "걷기 +6%", Gun.Rarity.COMMON, null),
        BLEED("bleed", "질긴 목숨", "쓰러짐 출혈 +4초", Gun.Rarity.COMMON, null),
        PIERCE("pierce", "꿰뚫는 탄", "관통 +1", Gun.Rarity.RARE, GunMods.NONE.addPierce(1)),
        CRIT("crit", "급소", "치명 +8%", Gun.Rarity.RARE, GunMods.NONE.addCrit(0.08)),
        PELLETS("pellets", "넓은 산탄", "산탄 +2알", Gun.Rarity.RARE, GunMods.NONE.addPellets(2)),
        HEAD("head", "머리 위 조준", "머리 높이 게이브에 피해 +20%", Gun.Rarity.RARE, null),
        WAIST("waist", "허리 조준", "허리 높이 게이브에 피해 +20%", Gun.Rarity.RARE, null),
        GROUND("ground", "발밑 조준", "바닥 게이브에 피해 +20%", Gun.Rarity.RARE, null),
        SKY("sky", "하늘 조준", "하늘 게이브에 피해 +25%", Gun.Rarity.RARE, null),
        SWARM("swarm", "떼 사냥", "떼에 피해 +25%", Gun.Rarity.RARE, null),
        GOLD("gold", "금빛 눈", "금빛 홍조 보상 2배", Gun.Rarity.RARE, null),
        REVIVE("revive", "빠른 손", "일으키기 빠름", Gun.Rarity.RARE, null),
        NADE("nade", "주머니", "쉬는 시간마다 수류탄 1", Gun.Rarity.RARE, null),
        DMG2("dmg2", "무거운 탄", "피해 +25%", Gun.Rarity.EPIC, GunMods.NONE.mulDamage(1.25)),
        RATE2("rate2", "미친 방아쇠", "연사 +20%", Gun.Rarity.EPIC, GunMods.NONE.mulRate(1.2)),
        HOLLOW("hollow", "바닥 난 탄창", "탄창 마지막 1/4 피해 +30%", Gun.Rarity.EPIC, null),
        ELITE("elite", "큰 사냥", "정예, 튼튼이에 피해 +25%", Gun.Rarity.EPIC, null),
        EXECUTE("execute", "마무리", "체력 30% 아래에 피해 +40%", Gun.Rarity.EPIC, null),
        // ---- 2026-11-12 trip_aug(원정.md 8절): 아래는 모두 trip/augments.json 에서 읽는다
        // 무기 종류에 얽힌 것
        W_PISTOL_FAST("w_pistol_fast"), W_PISTOL_LAST("w_pistol_last"), W_SMG_RUN("w_smg_run"), W_SHOTGUN_DOOR("w_shotgun_door"),
        W_SHOTGUN_SLUG("w_shotgun_slug"), W_RIFLE_TAP("w_rifle_tap"), W_SNIPER_LINE("w_sniper_line"), W_FAR("w_far"), W_FLAME_OIL("w_flame_oil"),
        W_FLAME_WALL("w_flame_wall"), W_LAUNCHER_CLUSTER("w_launcher_cluster"), W_LAUNCHER_MINE("w_launcher_mine"), W_ROCKET_SPARE("w_rocket_spare"),
        W_ROCKET_SHOCK("w_rocket_shock"), W_MINIGUN_WARM("w_minigun_warm"), W_LMG_BRACE("w_lmg_brace"), W_REVOLVER_FAN("w_revolver_fan"),
        W_BURST_FOUR("w_burst_four"), W_DMR_MARK("w_dmr_mark"), W_RAIL_OVER("w_rail_over"), W_TESLA_NET("w_tesla_net"),
        W_FREEZE_SHATTER("w_freeze_shatter"), W_BOW_PIN("w_bow_pin"), W_SWAP("w_swap"), W_DUAL_SPREE("w_dual_spree"),
        W_SILENT_STALK("w_silent_stalk"), W_LASER_FOCUS("w_laser_focus"), W_MUSKET_SMOKE("w_musket_smoke"),
        // 트럭에 얽힌 것(개인)
        T_MECHANIC("t_mechanic"), T_GUARD("t_guard"), T_ROOF("t_roof"), T_BAIT("t_bait"), T_CASHBACK("t_cashback"),
        // 트럭 부품(팀 공용)
        P_PLATE("p_plate"), P_SPIKES("p_spikes"), P_HORN("p_horn"), P_LAMP("p_lamp"), P_TURRET("p_turret"), P_WINCH("p_winch"),
        P_MEDBAY("p_medbay"), P_TANK("p_tank"), P_SPEAKER("p_speaker"), P_CRANE("p_crane"), P_BUMPER("p_bumper"), P_COOLER("p_cooler"),
        // 팀에 얽힌 것
        M_MEDIC("m_medic"), M_SHARE("m_share"), M_BACK("m_back"), M_COVER("m_cover"), M_RANSOM("m_ransom"), M_LONE("m_lone"), M_CALL("m_call"),
        M_TIP("m_tip"), M_TETHER("m_tether"), M_CHOIR("m_choir"), M_LAST("m_last"), M_WILL("m_will"),
        // 위험을 안는 것
        X_GLASS("x_glass"), X_DEBT("x_debt"), X_BLOOD("x_blood"), X_NOISY("x_noisy"), X_HEAVY("x_heavy"), X_NOMERCY("x_nomercy"),
        X_GAMBLE("x_gamble"), X_BLEED("x_bleed"), X_ONEWAY("x_oneway"), X_FEED("x_feed"), X_MARKED("x_marked"), X_DARK("x_dark"),
        // 몸
        B_DASH("b_dash"), B_SECOND("b_second"), B_THORNS("b_thorns"), B_SLIP("b_slip"), B_SCOUT("b_scout"), B_EARS("b_ears"), B_IRON("b_iron"),
        B_BANDAGE("b_bandage"),
        // 돈
        E_INTEREST("e_interest"), E_HOARD("e_hoard"), E_SCRAP("e_scrap"), E_BET("e_bet"), E_FENCE("e_fence"), E_PATRON("e_patron"),
        // 처치로 터지는 것
        K_CHAIN("k_chain"), K_BOOM("k_boom"), K_RELOAD("k_reload"), K_FEAR("k_fear"), K_HASTE("k_haste"), K_MARK("k_mark"), K_FIRST("k_first"),
        K_CORPSE("k_corpse"),
        // 죽음과 부활
        D_GHOST("d_ghost"), D_RETURN("d_return"), D_GRUDGE("d_grudge"), D_TAG("d_tag"), D_FACE("d_face"), D_ESCAPE("d_escape");

        public final String key, ko, line;
        /** 등급(뽑기 확률, 이름 색). 위험 증강은 따로 굴리고 {@link #risk} 가 참 */
        public final Gun.Rarity tier;
        /** 한 번 가질 때마다 곱하는 총 배율(없으면 null) */
        public final GunMods mods;
        public final Cat cat;
        public final boolean risk;
        /** 이 종류 총을 든 사람에게만 카드로 뜸(비었으면 누구에게나) */
        public final Set<Guns.Kind> bind;
        /** 필요한 원정 부품(truck, revive, winch), 없으면 null */
        public final String needs;
        /** 카드 그림: aug:&lt;옛 키&gt;, gun:&lt;종류 키&gt;, shop:&lt;상점 그림&gt; */
        public final String icon;
        /** 겹침 한도(0 = 없음) */
        public final int max;
        /** 뽑기 무게 */
        public final double weight;
        /** 데이터가 있어 쓸 수 있나(없으면 뽑기에 안 나옴) */
        public final boolean enabled;
        private final Map<String, Double> n;

        /** 옛 증강: 이름, 한 줄, 등급, 배율은 여기. 갈래와 그림은 데이터에 있으면 그것 */
        Aug(String key, String ko, String line, Gun.Rarity tier, GunMods mods) {
            AugData.Meta m = AugData.of(key);
            this.key = key;
            this.ko = m != null && m.name != null ? m.name : ko;
            this.line = m != null && m.line != null ? m.line : line;
            this.tier = m != null && m.tier != null ? tierOf(m.tier) : tier;
            this.mods = mods;
            this.cat = Cat.of(m == null ? null : m.cat);
            this.risk = false;
            this.bind = Set.of();
            this.needs = null;
            this.icon = m != null && m.icon != null ? m.icon : "aug:" + key;
            this.max = m == null ? 0 : m.max;
            this.weight = (m == null ? 1 : m.w) * AugData.roll("base", 0.6);
            this.enabled = true;
            this.n = m == null ? Map.of() : m.n;
        }

        /** 새 증강: 모두 데이터에서 */
        Aug(String key) {
            AugData.Meta m = AugData.of(key);
            this.key = key;
            this.enabled = m != null && m.name != null;
            this.ko = enabled ? m.name : key;
            this.line = m == null || m.line == null ? "" : m.line;
            this.risk = m != null && "cursed".equalsIgnoreCase(m.tier);
            this.tier = m == null ? Gun.Rarity.COMMON : risk ? Gun.Rarity.LEGENDARY : tierOf(m.tier);
            this.mods = null;
            this.cat = Cat.of(m == null ? null : m.cat);
            Set<Guns.Kind> b = EnumSet.noneOf(Guns.Kind.class);
            if (m != null && m.bind != null) for (String s : m.bind.split(",")) {
                String t = s.trim();
                if (t.isEmpty()) continue;
                for (Map.Entry<Guns.Kind, Guns.Spec> e : Guns.SPECS.entrySet()) if (e.getValue().key().equals(t)) b.add(e.getKey());
            }
            this.bind = Collections.unmodifiableSet(b);
            this.needs = m == null ? null : m.needs;
            this.icon = m == null || m.icon == null ? "shop:aug_roll" : m.icon;
            this.max = m == null ? 1 : m.max;
            double w = m == null ? 1 : m.w;
            this.weight = cat == Cat.WEAPON ? w * AugData.roll("weapon", 1.6) : w;
            this.n = m == null ? Map.of() : m.n;
        }

        private static Gun.Rarity tierOf(String s) {
            Gun.Rarity r = s == null ? null : Gun.Rarity.of(s);
            return r == null ? Gun.Rarity.COMMON : r;
        }

        /** 이 증강의 숫자(augments.json n), 없으면 def */
        public double n(String name, double def) {
            Double v = n.get(name);
            return v == null ? def : v;
        }

        /** 이 종류 총에 묶였나 */
        public boolean binds(Guns.Kind k) {
            return k != null && bind.contains(k);
        }

        /** 카드 이름 색: 위험은 빨강, 아니면 등급 색 */
        public TextColor color() {
            return risk ? RISK_COLOR : tier.color();
        }

        private static final Map<String, Aug> BY = new HashMap<>();

        static {
            for (Aug a : values()) BY.put(a.key, a);
        }

        public static Aug of(String s) {
            if (s == null) return null;
            Aug a = BY.get(s);
            if (a != null) return a;
            for (Aug x : values()) if (x.key.equalsIgnoreCase(s) || x.ko.equals(s) || x.name().equalsIgnoreCase(s)) return x;
            return null;
        }
    }

    /** 상점 스탯(단계마다 값이 오름, 최대 MAX 단계) */
    public enum Stat {
        HP("최대 체력 +10", 20), ARMOR("받는 피해 -5%", 14), MOVE("걷기 +4%", 10), RELOAD("재장전 -5%", 14);

        /** 가장 큰 스탯 상한(스탯마다 상한은 {@link #cap}). 2026-11-11 모두 8 에서 올림(늦은 판에 돈 쓸 곳) */
        public static final int MAX = 20;
        public final String ko;
        /** 이 스탯 상점 상한(체력 20, 방어 14, 걷기 10, 재장전 14). 값은 단계마다 1.3 배로 계속 오름(Shop.Ware) */
        public final int cap;

        Stat(String ko, int cap) {
            this.ko = ko;
            this.cap = cap;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final double BASE_HP = 100;
    /** 기본 판매 키: 자동 탄약 */
    public static final String AUTO_AMMO = "autoammo";

    /** 고를 카드 한 묶음(team = 트럭 부품: 고르면 팀 것이 됨) */
    /** 뽑기 묶음 하나. bought = 상점에서 산 뽑기(고를 때만 산 횟수가 늘어 다음 값이 오름: 안 고르고 버리면 값이 그대로, 2026-11-16 사용자) */
    private record Offer(List<Aug> cards, boolean team, boolean bought) {
    }

    private static final class Build {
        final int[] stat = new int[Stat.values().length];
        final ArrayDeque<Offer> pending = new ArrayDeque<>();
        /** 이번 판 상점에서 산 증강 뽑기 수(값이 오름) */
        int rolls;
        int ver;
        GunMods cache;
        GunMods[] kindCache;
        long cacheKey = Long.MIN_VALUE, kindKey = Long.MIN_VALUE;
        /** 가진 수(Aug 차례), PState.augs 에서 다시 셈 */
        int[] cnt;
        int cntVer = -1, cntSize = -1;
        /** 산 기본 판매, 그 가운데 꺼 둔 것 */
        final Set<String> staples = new HashSet<>(), stapleOff = new HashSet<>();
    }

    public final Game game;
    /** 원정 부품 연결. 기본은 허브 Trip 과 트럭 부품을 이름으로 찾아 묻는 {@link AugTrip}(없으면 트럭 = 상점 카운터) */
    public AugLinks links;
    /** 원정 쪽이 한 번이라도 부른 고리 이름(truckDamageMul, bait ...): 그 고리에 기대는 증강은 고리가 실제로 불린 뒤부터 뽑기에 나온다 */
    private final Set<String> wired = new HashSet<>();
    final AugRules rules;
    private final Map<UUID, Build> builds = new HashMap<>();
    private final java.util.Random rnd = new java.util.Random();
    /** 트럭 부품(팀 공용, 판 내내), 아직 안 고른 부품 보상 수 */
    private final List<Aug> parts = new ArrayList<>();
    private final int[] partCnt = new int[Aug.values().length];
    private int partRewards;
    /** 시험: 준비된 것으로 치는 원정 부품(truck, revive, winch) */
    private final Set<String> forcedNeeds = new HashSet<>();
    private String lastLog = "없음";
    private long tickErrAt = -1000;

    public Augments(Game game) {
        this.game = game;
        this.links = new AugTrip(game);
        this.rules = new AugRules(game, this);
        // Game 생성자가 Guns 를 먼저 만든다: 총의 대상 조건 피해, 처치 회복, 아군 사격을 여기로
        if (game.guns != null) game.guns.hooks = this;
        // 게이브가 받는 피해 배율 훅(표식, 낙인)과 맞힘 문맥
        if (game.mobs != null) game.mobs.scale("aug", rules::scale);
        if (AugData.error != null) game.plugin.getLogger().warning("[증강] " + AugData.error);
        if (!AugData.META.isEmpty()) {
            int on = 0;
            for (Aug a : Aug.values()) if (a.enabled) on++;
            game.plugin.getLogger().info("[증강] " + on + "개(데이터 " + AugData.META.size() + ", 기본 판매 " + AugData.STAPLES.keySet() + ")");
        }
        // 매 틱(Game.tick 보다 먼저 등록돼 먼저 돈다)
        Bukkit.getScheduler().runTaskTimer(game.plugin, this::tick, 1, 1);
    }

    private Build build(Player p) {
        return builds.computeIfAbsent(p.getUniqueId(), k -> new Build());
    }

    private PState ps(Player p) {
        return game.players.get(p);
    }

    /** 매 틱(스케줄러): 증강 효과의 때 맞추기. 한 번 던져도 판은 계속(10초에 한 번 기록) */
    public void tick() {
        try {
            rules.tick();
        } catch (RuntimeException e) {
            long now = game.now();
            if (now - tickErrAt >= 200) {
                tickErrAt = now;
                game.plugin.getLogger().log(Level.WARNING, "[증강] 틱 오류", e);
            }
        }
    }

    // ------------------------------------------------------------------ 원정 연결

    /** 트럭 가운데(지금은 상점 카운터) */
    public Vector truckPos() {
        Vector v = links.truckPos();
        return v == null ? Shop.counterCenter() : v;
    }

    /**
     * 그 증강이 기대는 것이 준비됐나. need 는 원정 부품 이름(truck, revive: 그 부품이 올라와 있음)이거나 여기 공개 메서드 이름
     * (truckDamageMul, bait, repairPriceMul, revivePriceMul, paid, winch, ramMul: 원정 쪽이 그 메서드를 한 번이라도 불렀음)
     */
    public boolean ready(String need) {
        // 불린 고리 기록(wired)은 판을 넘어 남는다: 그 뒤 대기실에서 원정을 끄면 고리를 부를 부품이 없으니 원정이 켜져 있을 때만 믿는다
        boolean hooked = wired.contains(need) && game.trip != null && game.trip.enabled;
        return need == null || forcedNeeds.contains(need) || hooked || links.ready(need);
    }

    /** 값 단위 U(원): 지금 웨이브 무기 상자 값(연결이 주면 그 값) */
    public int unit() {
        int u = links.unit();
        return u > 0 ? u : game.shop == null ? 130 : game.shop.price(null, Shop.Ware.BOX);
    }

    // ------------------------------------------------------------------ 가진 것

    private int[] counts(Player p) {
        Build b = build(p);
        PState s = ps(p);
        if (b.cnt == null || b.cntVer != b.ver || b.cntSize != s.augs.size()) {
            int[] c = new int[Aug.values().length];
            for (String k : s.augs) {
                Aug a = Aug.of(k);
                if (a != null) c[a.ordinal()]++;
            }
            b.cnt = c;
            b.cntVer = b.ver;
            b.cntSize = s.augs.size();
        }
        return b.cnt;
    }

    /** 이 증강을 몇 개 가졌나(트럭 부품은 팀이 가진 수) */
    public int count(Player p, Aug a) {
        if (a.cat == Cat.PART) return part(a);
        if (p == null) return 0;
        return counts(p)[a.ordinal()];
    }

    public boolean has(Player p, Aug a) {
        return count(p, a) > 0;
    }

    /** 가진 증강(같은 것 겹침은 수와 함께, 얻은 차례) */
    public Map<Aug, Integer> owned(Player p) {
        Map<Aug, Integer> out = new LinkedHashMap<>();
        for (String k : ps(p).augs) {
            Aug a = Aug.of(k);
            if (a != null) out.merge(a, 1, Integer::sum);
        }
        return out;
    }

    /** 증강 하나를 줌(겹침). 트럭 부품이면 팀 것이 된다. PState 스탯도 바로 다시 계산 */
    public void give(Player p, Aug a) {
        if (a.cat == Cat.PART) {
            addPart(a, p);
            return;
        }
        ps(p).augs.add(a.key);
        build(p).ver++;
        rules.onGive(p, a);
        apply(p);
        lastLog = "증강 " + a.key + " " + p.getName();
    }

    /** 키로 줌. 모르는 키면 false */
    public boolean give(Player p, String key) {
        Aug a = Aug.of(key);
        if (a == null) return false;
        give(p, a);
        return true;
    }

    /** 증강 하나를 뺌(주사위, 시험). 없으면 false */
    public boolean take(Player p, Aug a) {
        if (!ps(p).augs.remove(a.key)) return false;
        build(p).ver++;
        apply(p);
        return true;
    }

    public int stat(Player p, Stat s) {
        return build(p).stat[s.ordinal()];
    }

    /** 상점 스탯 한 단계 올림(최대면 false) */
    public boolean raiseStat(Player p, Stat s) {
        Build b = build(p);
        if (b.stat[s.ordinal()] >= s.cap) return false;
        b.stat[s.ordinal()]++;
        b.ver++;
        apply(p);
        return true;
    }

    /** 이번 판 상점에서 산 증강 뽑기 수 */
    public int rolls(Player p) {
        // 산 뒤 아직 고르지 않은 뽑기도 센다(쌓아 두고 같은 값에 여러 번 사지 못하게). 안 고르고 버리면 빠져서 값이 돌아간다
        Build b = build(p);
        return b.rolls + (int) b.pending.stream().filter(Offer::bought).count();
    }

    // ------------------------------------------------------------------ 트럭 부품(팀 공용)

    /** 팀이 가진 트럭 부품(얻은 차례) */
    public List<Aug> parts() {
        return List.copyOf(parts);
    }

    /** 팀이 가진 그 부품 수: 트럭 부품(kr.gave.trip.Truck)이 가진 수 + 여기 목록(트럭 부품이 없는 판, 시험) */
    public int part(Aug a) {
        return partCnt[a.ordinal()] + Math.max(0, links.part(a.key));
    }

    /** 여기 목록에 든 수만(트럭이 스스로 효과를 내는 부품: 철판, 냉각기, 경적, 가시 철판, 보조 연료통, 인양기는 겹쳐 걸리지 않게 이것만 봄) */
    int ownPart(Aug a) {
        return partCnt[a.ordinal()];
    }

    /** 부품을 트럭에 붙임(by = 고른 사람, 없어도 됨) */
    public void addPart(Aug a, Player by) {
        if (a.cat != Cat.PART) return;
        parts.add(a);
        partCnt[a.ordinal()]++;
        Props.soundAround(truckPos().toLocation(game.world), null, Sound.BLOCK_ANVIL_USE, 0.8f, 1f, 1.2f, 24, -1);
        if (Hud.hasPhrase("aug_part")) game.hud.notice("aug_part", 60);
        lastLog = "부품 " + a.key + " " + (by == null ? "-" : by.getName());
        game.plugin.getLogger().info("[증강] 트럭 부품 " + a.ko + " 고른 사람 " + (by == null ? "-" : by.getName()));
    }

    /** 부품 보상 n 개를 쌓음(원정 보상 'part'). 상점 부품 칸이 {@link #partRewards} 로 켜짐을 알고 {@link #offerParts} 를 부른다 */
    public void addPartReward(int n) {
        partRewards = Math.max(0, partRewards + n);
    }

    /** 아직 안 고른 부품 보상 수 */
    public int partRewards() {
        return partRewards;
    }

    /**
     * 부품 칸을 누른 사람에게 부품 카드 셋을 띄운다(먼저 누른 사람이 고름: 보상 하나를 그 사람 카드로 옮김). 보상이 없거나 줄 부품이 없으면 false
     */
    public boolean offerParts(Player p) {
        if (p == null || partRewards <= 0) return false;
        List<Aug> pool = new ArrayList<>();
        for (Aug a : Aug.values()) if (a.enabled && a.cat == Cat.PART && ready(a.needs) && (a.max <= 0 || partCnt[a.ordinal()] < a.max)) pool.add(a);
        if (pool.isEmpty()) return false;
        Collections.shuffle(pool, rnd);
        List<Aug> cards = new ArrayList<>(pool.subList(0, Math.min(3, pool.size())));
        partRewards--;
        build(p).pending.addFirst(new Offer(cards, true, false));
        lastLog = "부품 카드 " + p.getName() + " " + cards;
        return openCards(p, true);
    }

    // ------------------------------------------------------------------ 기본 판매(상점이 늘 파는 것: 자동 탄약)

    /** 여기서 다루는 기본 판매 키(지금은 autoammo 하나). 트럭 수리, 꺼내기, 부품 칸은 원정 쪽 */
    public List<String> staples() {
        return List.of(AUTO_AMMO);
    }

    /** 샀나(산 표시는 여기 Build 에만: 상점 칸 14 는 이것과 buyStaple, stapleIcon 을 부른다) */
    public boolean hasStaple(Player p, String key) {
        if (p == null) return false;
        Build b = builds.get(p.getUniqueId());
        return b != null && b.staples.contains(key);
    }

    /** 샀고 켜져 있나 */
    public boolean stapleOn(Player p, String key) {
        if (p == null) return false;
        Build b = builds.get(p.getUniqueId());
        return b != null && b.staples.contains(key) && !b.stapleOff.contains(key);
    }

    /** 기본 판매 숫자(augments.json staples n) */
    public double stapleN(String key, String name, double def) {
        AugData.Meta m = AugData.STAPLES.get(key);
        Double v = m == null ? null : m.n.get(name);
        return v == null ? def : v;
    }

    /** 지금 값(원, 5 단위): u x U. 자동 탄약의 u 는 원정 숫자 표 Trip.Tune.AUTO_AMMO_U(파워커브가 정함) */
    public int staplePrice(Player p, String key) {
        // 상점 칸 14 의 값(Shop.price 가 이것을 부름: 여기서 Shop.price 를 부르면 돌고 돔). 원정 값 배율(정비 스테이지 등)도 곱함
        double u = AUTO_AMMO.equals(key) ? kr.gave.trip.Trip.Tune.AUTO_AMMO_U : stapleN(key, "u", 1.0);
        double mul = 1;
        try {
            if (AUTO_AMMO.equals(key) && game.trip != null) mul = game.trip.priceMul(Shop.Ware.AUTO_AMMO);
        } catch (RuntimeException e) {
            mul = 1;
        }
        return Math.max(5, (int) Math.round(u * unit() * mul / 5.0) * 5);
    }

    /**
     * 기본 판매 칸을 누름: 아직 안 샀으면 값을 내고 삼(한 번 사면 판 내내, 돈이 모자라면 false), 이미 샀으면 껐다 켬. 상점 칸에서 부른다
     */
    public boolean buyStaple(Player p, String key) {
        if (p == null || !staples().contains(key)) return false;
        Build b = build(p);
        if (b.staples.contains(key)) {
            boolean off = !b.stapleOff.remove(key);
            if (off) b.stapleOff.add(key);
            Props.sound(p, null, Sound.UI_BUTTON_CLICK, 0.6f, 1f, off ? 0.8f : 1.3f);
            lastLog = "기본 판매 " + key + " " + p.getName() + (off ? " 끔" : " 켬");
            return true;
        }
        int price = staplePrice(p, key);
        if (!game.players.spend(p, price)) {
            Props.sound(p, null, Sound.BLOCK_DISPENSER_FAIL, 0.6f, 1f, 1.2f);
            lastLog = "기본 판매 못 삼 " + key + " " + ps(p).money + "/" + price;
            return false;
        }
        rules.spent(p, price);
        b.staples.add(key);
        Props.sound(p, null, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1f, 1.4f);
        lastLog = "기본 판매 삼 " + key + " " + p.getName() + " -" + price;
        return true;
    }

    private static final TextColor DIM = TextColor.color(0x9A9A9A), OK = TextColor.color(0x7CF07C), NO = TextColor.color(0xFF7A7A),
        WHITE = TextColor.color(0xF0F0F0);

    private static Component text(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    /** 기본 판매 칸 아이콘: 이름, 한 줄, 값(샀으면 켜짐/꺼짐: 켜져 있으면 반짝임) */
    public ItemStack stapleIcon(Player p, String key) {
        AugData.Meta m = AugData.STAPLES.get(key);
        String name = m == null || m.name == null ? "자동 탄약" : m.name;
        List<Component> lore = new ArrayList<>();
        lore.add(text(m == null || m.line == null ? "트럭 곁에서 탄이 떨어지면 저절로 산다" : m.line, DIM));
        boolean own = hasStaple(p, key), on = stapleOn(p, key);
        if (!own) {
            int price = staplePrice(p, key);
            lore.add(text(price + "원", ps(p).money >= price ? OK : NO));
        } else lore.add(text(on ? "켜짐" : "꺼짐", on ? OK : DIM));
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, iconKey(m == null || m.icon == null ? "shop:ammo" : m.icon));
        it.setData(DataComponentTypes.ITEM_NAME, text(name, own && !on ? DIM : WHITE));
        it.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        if (on) it.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return it;
    }

    // ------------------------------------------------------------------ 배율, 스탯

    /**
     * 대상 조건 피해 배율(곱함): 띠(머리 위, 허리, 바닥 1.2^수, 하늘 1.25^수), 떼 1.25^수, 정예와 튼튼이 1.25^수, 체력 30% 아래 1.4^수,
     * 탄창 마지막 1/4(쏜 뒤 남은 탄 기준) 1.3^수, 그리고 새 증강의 조건 피해({@link AugRules#hitMul})
     */
    @Override
    public double hitMul(Player shooter, Object target, Guns.Spec spec) {
        if (shooter == null || !(target instanceof Gabe g)) return 1;
        PState s = ps(shooter);
        double atk = s.attackMul > 0 ? s.attackMul : 0;
        // 가진 증강이 없어도 팀 것(트럭 부품 확성기)은 걸린다
        if (s.augs.isEmpty()) return atk * rules.teamHitMul(shooter);
        double k = atk;
        Aug band = switch (g.sp.band) {
            case HEAD -> Aug.HEAD;
            case WAIST -> Aug.WAIST;
            case GROUND -> Aug.GROUND;
            case SKY -> Aug.SKY;
        };
        int n = count(shooter, band);
        if (n > 0) k *= Math.pow(g.sp.band == Band.SKY ? 1.25 : 1.2, n);
        if ((n = count(shooter, Aug.SWARM)) > 0 && g.sp.is(Role.SWARM)) k *= Math.pow(1.25, n);
        if ((n = count(shooter, Aug.ELITE)) > 0 && (g.sp.is(Role.ELITE) || g.sp.is(Role.TANK))) k *= Math.pow(1.25, n);
        if ((n = count(shooter, Aug.EXECUTE)) > 0 && g.maxHp > 0 && g.hp / g.maxHp < 0.3) k *= Math.pow(1.4, n);
        if ((n = count(shooter, Aug.HOLLOW)) > 0 && spec != null) {
            Guns.Arms a = game.guns.arms(shooter);
            if (a != null && a.mag[spec.kind().ordinal()] <= spec.mag() / 4) k *= Math.pow(1.3, n);
        }
        return k * rules.hitMul(shooter, s, g, spec);
    }

    private long modsKey(Player p, Build b, PState s) {
        return ((long) b.ver * 4099 + s.augs.size()) * 64 + rules.dyn(p);
    }

    public GunMods mods(Player p) {
        if (p == null) return GunMods.NONE;
        Build b = build(p);
        PState s = ps(p);
        long key = modsKey(p, b, s);
        if (b.cache != null && key == b.cacheKey) return b.cache;
        GunMods m = GunMods.NONE;
        int crouch = 0;
        for (String k : s.augs) {
            Aug a = Aug.of(k);
            if (a == null) continue;
            if (a.mods != null) m = m.times(a.mods);
            else if (a == Aug.CROUCH) crouch++;
            else m = rules.flatMods(a, m);
        }
        int rl = b.stat[Stat.RELOAD.ordinal()];
        if (rl > 0) m = m.mulReload(Math.pow(0.95, rl));
        int dyn = rules.dyn(p);
        if ((dyn & AugRules.D_SNEAK) != 0 && crouch > 0) m = m.mulSpread(Math.pow(0.6, crouch));
        m = rules.dynMods(dyn, m);
        b.cache = m;
        b.cacheKey = key;
        return m;
    }

    /** 종류 k 를 쏠 때의 배율: base(= mods(p)) 에 그 종류에 묶인 증강을 더함 */
    @Override
    public GunMods kindMods(Player p, Guns.Kind k, GunMods base) {
        if (p == null || k == null) return base;
        Build b = build(p);
        PState s = ps(p);
        if (s.augs.isEmpty()) return base;
        long key = modsKey(p, b, s);
        if (b.kindCache == null || key != b.kindKey) {
            GunMods all = mods(p);
            GunMods[] out = new GunMods[Guns.Kind.values().length];
            int dyn = rules.dyn(p);
            for (Guns.Kind x : Guns.Kind.values()) out[x.ordinal()] = rules.kindMods(p, x, all, dyn);
            b.kindCache = out;
            b.kindKey = key;
        }
        return b.kindCache[k.ordinal()];
    }

    @Override
    public long modsStamp(Player p) {
        return p == null ? 0 : modsKey(p, build(p), ps(p));
    }

    /** PState 스탯(maxHp, armor, moveMul, bleedBonus, reviveMul)을 증강과 상점 스탯으로 다시 계산(최대 체력이 오르면 오른 만큼 체력도 더함) */
    public void apply(Player p) {
        PState s = ps(p);
        Build b = build(p);
        double maxHp = (BASE_HP + 10 * b.stat[Stat.HP.ordinal()] + 15 * count(p, Aug.HP) + rules.hpAdd(p)) * rules.hpMul(p);
        // 종이 거는 최대 체력 빼기(PState.cutMaxHp: G181 잿빛 땅 같은 것)
        maxHp = Math.max(1, Math.round(maxHp - s.maxHpCut(game.now())));
        if (Math.abs(s.maxHp - maxHp) > 1e-6) {
            double d = maxHp - s.maxHp;
            s.maxHp = maxHp;
            if (d > 0 && s.standing()) s.hp += d;
            s.hp = Math.min(s.hp, s.maxHp);
        }
        s.armor = Math.pow(0.95, b.stat[Stat.ARMOR.ordinal()]) * Math.pow(0.92, count(p, Aug.ARMOR)) * rules.armorMul(p);
        s.moveMul = Math.pow(1.04, b.stat[Stat.MOVE.ordinal()]) * Math.pow(1.06, count(p, Aug.MOVE)) * rules.moveMul(p);
        s.bleedBonus = bleedBonus(p);
        s.reviveMul = reviveMul(p);
    }

    /** 쓰러짐 출혈 더하기(틱): 질긴 목숨 4초 x 수. Players 가 출혈 끝을 정할 때 더한다 */
    public int bleedBonus(Player p) {
        return 80 * count(p, Aug.BLEED);
    }

    /**
     * 일으키기 빠르기 배율(클수록 빠름: Players.startHelp 가 3초를 이 값으로 나눈다). 빠른 손은 걸리는 시간이 0.65^수 배가 되게 1 / 0.65^수
     * (2026-11-12 까지는 0.65^수 를 그대로 넣어 오히려 느려졌다), 들것(트럭 부품)은 트럭 곁에서 두 배
     */
    public double reviveMul(Player p) {
        return Math.pow(1 / 0.65, count(p, Aug.REVIVE)) * rules.reviveSpeed(p);
    }

    /** 붕대 감는 시간 배율(붕대 솜씨). Supplies 가 읽음 */
    public double bandageTime(Player p) {
        return p != null && has(p, Aug.B_BANDAGE) ? Aug.B_BANDAGE.n("time", 0.5) : 1;
    }

    /** 붕대 회복 배율(붕대 솜씨) */
    public double bandageHeal(Player p) {
        return p != null && has(p, Aug.B_BANDAGE) ? Aug.B_BANDAGE.n("heal", 1.5) : 1;
    }

    /** 금빛 홍조 처치 때 이 사람이 받을 증강 뽑기 수(1 + 금빛 눈 수) */
    public int goldOffers(Player p) {
        return 1 + count(p, Aug.GOLD);
    }

    /** 금빛 홍조 게이브(G08) 처치: 판에 든 모두에게 증강 뽑기(금빛 눈이면 그 수만큼 더) */
    public void goldReward() {
        for (Player p : game.players.inPlay()) for (int i = 0; i < goldOffers(p); i++) offer(p, 3);
    }

    /**
     * 쉬는 시간 시작(Shop.tick 이 부름): 주머니 증강은 수류탄을 더 줌. 3번 칸에 다른 소모품이 있으면 그것을 버리지 않고 주머니(Arms.grenades)에
     * 넣는다: F 로 던지고, 칸이 비면 칸에 보인다. 새 증강의 쉬는 시간 몫(이자, 단골, 내기, 구두쇠, 주사위, 빚)도 여기서
     */
    public void onBreak() {
        for (Player p : game.players.inPlay()) {
            int n = count(p, Aug.NADE);
            if (n > 0 && game.guns.arms(p) != null) game.shop.supplies.pocket(p, n);
            try {
                rules.onBreak(p);
            } catch (RuntimeException e) {
                game.plugin.getLogger().log(Level.WARNING, "[증강] 쉬는 시간 처리 오류 " + p.getName(), e);
            }
        }
    }

    // ------------------------------------------------------------------ 뽑기(카드)

    /** 카드 3장 등급 확률(일반, 희귀, 영웅 %): 웨이브 9 까지 60/30/10, 25 까지 50/35/15, 그 뒤 40/38/22 */
    public static int[] odds(int wave) {
        if (wave <= 9) return new int[] {60, 30, 10};
        if (wave <= 25) return new int[] {50, 35, 15};
        return new int[] {40, 38, 22};
    }

    /** 이 사람에게 지금 카드로 뜰 수 있나: 켜져 있고, 부품이 아니고, 기대는 원정 부품이 준비됐고, 한도 아래고, 묶인 총을 들었음(권총은 늘 듦) */
    public boolean offerable(Player p, Aug a) {
        if (!a.enabled || a.cat == Cat.PART || !ready(a.needs)) return false;
        if (a.max > 0 && count(p, a) >= a.max) return false;
        if (a.bind.isEmpty()) return true;
        if (a.bind.contains(Guns.Kind.PISTOL)) return true;
        Guns.Arms arms = game.guns.arms(p);
        return arms != null && arms.primary != null && a.bind.contains(arms.primary);
    }

    private Aug weighted(List<Aug> pool) {
        double sum = 0;
        for (Aug a : pool) sum += Math.max(0.01, a.weight);
        double x = rnd.nextDouble() * sum;
        for (Aug a : pool) {
            x -= Math.max(0.01, a.weight);
            if (x <= 0) return a;
        }
        return pool.get(pool.size() - 1);
    }

    /**
     * 카드 count 장을 굴림: 등급은 웨이브 확률, 갈래가 겹치지 않게(모자라면 겹침), 위험 카드는 한 묶음에 하나까지(확률 roll.risk),
     * 무기에 묶인 것은 그 총을 든 사람에게만
     */
    private List<Aug> roll(Player p, int count) {
        return roll(p, count, List.of());
    }

    /** avoid = 같은 묶음에 이미 있는 카드(한도가 찬 카드 하나를 바꿔 굴릴 때): 그 카드와 그 갈래는 피한다 */
    private List<Aug> roll(Player p, int count, List<Aug> avoid) {
        int[] o = odds(Math.max(1, game.wave));
        List<Aug> can = new ArrayList<>(), risks = new ArrayList<>();
        for (Aug a : Aug.values()) {
            if (!offerable(p, a)) continue;
            if (a.risk) risks.add(a);
            else can.add(a);
        }
        List<Aug> out = new ArrayList<>();
        // 위험 카드는 고를 다른 카드가 있는 묶음에만: 한 장짜리 묶음이 위험 카드면 그 저주를 받기 전에는 상점을 못 연다(묶음을 버리는 길이 없음)
        int riskSlot = count >= 2 && !risks.isEmpty() && rnd.nextDouble() < AugData.roll("risk", 0.22) ? rnd.nextInt(count) : -1;
        Set<Cat> used = EnumSet.noneOf(Cat.class);
        for (Aug a : avoid) used.add(a.cat);
        for (int i = 0; i < count; i++) {
            Aug pick = null;
            if (i == riskSlot) pick = weighted(risks);
            for (int tries = 0; pick == null && tries < 40; tries++) {
                int x = rnd.nextInt(100);
                Gun.Rarity tier = x < o[0] ? Gun.Rarity.COMMON : x < o[0] + o[1] ? Gun.Rarity.RARE : Gun.Rarity.EPIC;
                List<Aug> pool = new ArrayList<>();
                // 처음 스무 번은 갈래가 안 겹치게, 그래도 없으면 겹쳐도 됨
                for (Aug a : can)
                    if (a.tier == tier && !out.contains(a) && !avoid.contains(a) && (tries >= 20 || !used.contains(a.cat))) pool.add(a);
                if (!pool.isEmpty()) pick = weighted(pool);
            }
            if (pick == null) continue;
            out.add(pick);
            used.add(pick.cat);
        }
        return out;
    }

    /**
     * 증강 뽑기 한 번(카드 count 장, 기본 3). 바로 화면을 열지 않고 쌓아 두고, 상점을 열면 카드부터 보인다(웨이브 중 화면이 싸움을 막지 않게).
     * HUD 는 {@link #pending} 으로 '증강 n장 대기' 를 그린다
     */
    public void offer(Player p, int count) {
        offer(p, count, false);
    }

    private void offer(Player p, int count, boolean bought) {
        if (p == null) return;
        List<Aug> cards = roll(p, Math.max(1, Math.min(3, count)));
        if (cards.isEmpty()) return;
        build(p).pending.add(new Offer(cards, false, bought));
        Props.sound(p, null, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1f, 1.4f);
        lastLog = "뽑기 대기 " + p.getName() + " " + cards;
    }

    /** 상점에서 산 뽑기(값 올림 셈 + 바로 카드 화면) */
    public void bought(Player p) {
        offer(p, 3, true);
        openCards(p, true);
    }

    /** 고르지 않은 뽑기 수 */
    public int pending(Player p) {
        Build b = builds.get(p.getUniqueId());
        return b == null ? 0 : b.pending.size();
    }

    /** 이 카드를 지금 더 가질 수 없나(겹침 한도가 참) */
    private boolean full(Player p, Aug a) {
        return a.max > 0 && count(p, a) >= a.max;
    }

    /**
     * 맨 앞 뽑기(없으면 null). 묶음은 받는 순간 굴려 쌓아 두므로, 열 때에는 그사이 얻어 한도가 찬 카드가 섞여 있을 수 있다(쌓인 두 묶음에 같은
     * '하나만' 증강, 주사위가 준 증강): 그대로 고르면 한도를 넘겨 겹친다. 그런 카드는 같은 규칙으로 한 장을 다시 굴려 바꾸고(부품 카드와 바꿀 것이
     * 없을 때는 뺌), 카드가 다 빠진 묶음은 버린다
     */
    private Offer front(Player p) {
        Build b = builds.get(p.getUniqueId());
        if (b == null) return null;
        for (Offer o = b.pending.peek(); o != null; o = b.pending.peek()) {
            List<Aug> cards = o.cards();
            for (int i = cards.size() - 1; i >= 0; i--) {
                if (!full(p, cards.get(i))) continue;
                Aug old = cards.remove(i);
                List<Aug> again = old.cat == Cat.PART ? List.<Aug>of() : roll(p, 1, cards);
                if (!again.isEmpty()) cards.add(i, again.get(0));
                lastLog = "한도가 찬 카드 바꿈 " + p.getName() + " " + old.key + " -> " + (again.isEmpty() ? "없음" : again.get(0).key);
            }
            if (!cards.isEmpty()) return o;
            b.pending.poll();
        }
        return null;
    }

    /** 맨 앞 뽑기의 카드(없으면 빈 목록). 한도가 찬 카드는 바꿔 굴린 뒤의 것({@link #front}) */
    public List<Aug> cards(Player p) {
        Offer o = front(p);
        return o == null ? List.of() : o.cards();
    }

    /** 카드 화면 칸(27칸 상자: 11, 13, 15). 그 위아래 칸(-9, +9)에 갈래 색 유리판 */
    public static final int[] CARD_SLOTS = {11, 13, 15};

    /** 맨 앞 뽑기 카드 화면을 연다. 고를 것이 없으면 false. back = 고른 뒤 상점으로 돌아감 */
    public boolean openCards(Player p, boolean back) {
        List<Aug> cards = cards(p);
        if (cards.isEmpty()) return false;
        Shop.View v = new Shop.View(p.getUniqueId(), Shop.View.CARDS, back);
        Inventory inv = Bukkit.createInventory(v, 27, ShopUi.title(ShopUi.CARDS));
        v.inv = inv;
        for (int i = 0; i < cards.size() && i < CARD_SLOTS.length; i++) {
            Aug a = cards.get(i);
            int slot = CARD_SLOTS[i];
            inv.setItem(slot, ShopUi.card(card(a, count(p, a))));
            // 갈래 색 테두리(위아래). 위험은 붉은 판이 옆 대각선까지 둘러 한눈에 다름
            inv.setItem(slot - 9, ShopUi.bar(a.cat.pane, true));
            inv.setItem(slot + 9, ShopUi.bar(a.cat.pane, false));
            final int idx = i;
            v.actions.put(slot, () -> pick(p, idx));
        }
        for (int i = 0; i < cards.size() && i < CARD_SLOTS.length; i++) {
            if (!cards.get(i).risk) continue;
            for (int d : new int[] {-10, -8, 8, 10}) {
                int s2 = CARD_SLOTS[i] + d;
                if (inv.getItem(s2) == null) inv.setItem(s2, ShopUi.bar(Cat.RISK.pane, d < 0));
            }
        }
        // 셋 다 마음에 안 들면 안 고르고 넘김(2026-11-16 사용자). 트럭 부품 묶음(팀 보상)은 한 사람이 버리지 못하게 뺀다
        if (!front(p).team()) {
            inv.setItem(SKIP_SLOT, skipItem());
            v.actions.put(SKIP_SLOT, () -> skip(p));
        }
        game.shop.show(p, v);
        return true;
    }

    /** 카드 화면에서 '안 고르기' 칸(오른쪽 아래, 카드와 테두리 판에 안 겹침) */
    public static final int SKIP_SLOT = 26;

    private static ItemStack skipItem() {
        ItemStack it = ItemStack.of(Material.BARRIER);
        it.setData(DataComponentTypes.ITEM_NAME, Component.text("안 고르기", TextColor.color(0xD8D8D8)).decoration(TextDecoration.ITALIC, false));
        it.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(Component.text("이 세 장을 버리고 넘긴다", TextColor.color(0x9A9A9A)).decoration(TextDecoration.ITALIC, false))));
        return it;
    }

    /** 맨 앞 뽑기를 고르지 않고 버림. 그다음 뽑기가 있으면 그 카드, 아니면 상점(왔던 곳)으로 */
    public boolean skip(Player p) {
        Build b = build(p);
        if (front(p) == null || front(p).team()) return false;
        Shop.View cur = game.shop.view(p);
        boolean back = cur != null && cur.back;
        b.pending.poll();
        lastLog = "안 고름 " + p.getName();
        Props.sound(p, null, Sound.UI_BUTTON_CLICK, 0.6f, 1f, 0.8f);
        if (openCards(p, back)) return true;
        if (back) game.shop.open(p);
        else game.shop.close(p);
        return true;
    }

    /** 맨 앞 뽑기에서 index 번째 카드를 고름. 그다음 뽑기가 있으면 그 카드, 아니면 상점(왔던 곳)으로 */
    public boolean pick(Player p, int index) {
        Build b = build(p);
        Offer o = b.pending.peek();
        if (o == null || index < 0 || index >= o.cards().size()) return false;
        Shop.View cur = game.shop.view(p);
        boolean back = cur != null && cur.back;
        // 화면을 연 뒤에 한도가 찬 카드(그사이 주사위가 같은 증강을 줌)는 주지 않는다: 아래 openCards 가 바꿔 굴린 카드로 다시 보여 준다
        boolean took = !full(p, o.cards().get(index));
        if (took) {
            b.pending.poll();
            if (o.bought()) b.rolls++;
            give(p, o.cards().get(index));
            Props.sound(p, null, Sound.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1f, 1f, 1f);
        }
        if (openCards(p, back)) return took;
        if (back) game.shop.open(p);
        else game.shop.close(p);
        return took;
    }

    private static Key iconKey(String icon) {
        if (icon.startsWith("shop:")) {
            String k = icon.substring(5);
            if (k.equals("ammo") && Props.has("ammo_box")) return Key.key(Props.NS, "ammo_box");
            return Key.key(Props.NS, "shop/" + k);
        }
        if (icon.startsWith("aug:")) return Key.key(Props.NS, "shop/aug/" + icon.substring(4));
        return Key.key(Props.NS, "shop/aug_roll");
    }

    /**
     * 카드 아이템: 그림(옛 증강은 등급 테두리 카드 gave:shop/aug/&lt;키&gt;, 무기 증강은 그 총 그림 gave:weapon_icon, 그 밖은 뜻이 가까운 옛 그림),
     * 이름(등급 색, 위험은 빨강), 한 줄
     */
    public static ItemStack card(Aug a, int owned) {
        ItemStack it = ItemStack.of(Material.PAPER, Math.max(1, Math.min(64, owned)));
        if (a.icon.startsWith("gun:")) {
            it.setData(DataComponentTypes.ITEM_MODEL, Props.has("weapon_icon") ? Key.key(Props.NS, "weapon_icon") : Key.key(Props.NS, "shop/gun"));
            it.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData.customModelData().addString(a.risk ? "none" : a.tier.key()).addString(a.icon.substring(4)).build());
        } else it.setData(DataComponentTypes.ITEM_MODEL, iconKey(a.icon));
        it.setData(DataComponentTypes.ITEM_NAME, Component.text(a.ko, a.color()).decoration(TextDecoration.ITALIC, false));
        it.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(Component.text(a.line, TextColor.color(0xD8D8D8)).decoration(TextDecoration.ITALIC, false))));
        return it;
    }

    // ------------------------------------------------------------------ GunHooks

    @Override
    public void onHit(Player shooter, Object target, double amount, boolean died, Gun gun) {
        if (shooter == null) return;
        try {
            rules.onHit(shooter, target, amount, died, gun);
        } catch (RuntimeException e) {
            long now = game.now();
            if (now - tickErrAt >= 200) {
                tickErrAt = now;
                game.plugin.getLogger().log(Level.WARNING, "[증강] 맞힘 처리 오류", e);
            }
        }
        if (!died) return;
        double heal = 3 * count(shooter, Aug.LEECH) + (gun != null && gun.has(Gun.Trait.LIFESTEAL) ? Gun.LIFESTEAL_HP : 0);
        PState s = ps(shooter);
        if (heal > 0 && s.life == Life.ALIVE) game.players.heal(shooter, heal);
        // 현상금의 돈은 지갑이 준다(AugRules.earnMul: 버는 처치 돈 +10% x 장수)
    }

    @Override
    public void friendly(Player shooter, Player victim) {
        game.players.friendlyHit(shooter, victim);
        lastLog = "아군 " + shooter.getName() + " -> " + victim.getName() + " " + ps(victim).life;
    }

    @Override
    public java.util.Collection<Player> downedMates() {
        return game.players.downed();
    }

    @Override
    public boolean attached(Player victim, Object target) {
        if (target == null) return false;
        PState s = game.players.get(victim.getUniqueId());
        return s != null && (s.grabbedBy == target || s.headBy == target);
    }

    @Override
    public int burstCount(Player p, Guns.Kind k, int base) {
        return p != null && Aug.W_BURST_FOUR.binds(k) && has(p, Aug.W_BURST_FOUR) ? base + (int) Aug.W_BURST_FOUR.n("add", 1) : base;
    }

    @Override
    public double chargeMul(Player p, Guns.Kind k) {
        return p != null && Aug.W_RAIL_OVER.binds(k) && has(p, Aug.W_RAIL_OVER) ? Aug.W_RAIL_OVER.n("charge", 2) : 1;
    }

    @Override
    public boolean keepSpin(Player p) {
        return p != null && rules.keepSpin(p);
    }

    @Override
    public double moveSpread(Player p, Guns.Kind k) {
        return p != null && Aug.W_SMG_RUN.binds(k) && has(p, Aug.W_SMG_RUN) ? 0 : 1;
    }

    @Override
    public boolean dryFire(Player p, Guns.Spec s) {
        return p != null && rules.dryFire(p, s);
    }

    @Override
    public double blastMul(Player p, Guns.Kind k) {
        return p != null && Aug.W_ROCKET_SHOCK.binds(k) && has(p, Aug.W_ROCKET_SHOCK) ? Aug.W_ROCKET_SHOCK.n("r", 1.3) : 1;
    }

    @Override
    public double burnMul(Player p) {
        return p != null && has(p, Aug.W_FLAME_OIL) ? Aug.W_FLAME_OIL.n("ticks", 2) : 1;
    }

    @Override
    public int chainExtra(Player p) {
        return p != null && has(p, Aug.W_TESLA_NET) ? (int) Aug.W_TESLA_NET.n("add", 3) : 0;
    }

    @Override
    public double[] shellRule(Player p, Guns.Kind k) {
        if (p == null) return null;
        boolean cluster = Aug.W_LAUNCHER_CLUSTER.binds(k) && has(p, Aug.W_LAUNCHER_CLUSTER);
        boolean mine = Aug.W_LAUNCHER_MINE.binds(k) && has(p, Aug.W_LAUNCHER_MINE);
        if (!cluster && !mine) return null;
        Aug c = Aug.W_LAUNCHER_CLUSTER, m = Aug.W_LAUNCHER_MINE;
        return new double[] {cluster ? c.n("count", 3) : 0, c.n("dmg", 0.35), mine ? m.n("ticks", 160) : 0, m.n("max", 6), m.n("r", 2)};
    }

    @Override
    public void streamEnd(Player p, Guns.Kind k, Location end) {
        if (p != null) rules.streamEnd(p, k, end);
    }

    @Override
    public String info(Player p) {
        PState s = ps(p);
        Build b = build(p);
        return String.format(Locale.ROOT, "maxHp=%.0f hp=%.0f armor=%.3f move=%.3f money=%d stats=%d,%d,%d,%d augs=%s pending=%d", s.maxHp, s.hp, s.armor,
            s.moveMul, s.money, b.stat[0], b.stat[1], b.stat[2], b.stat[3], s.augs, b.pending.size());
    }

    // ------------------------------------------------------------------ 원정 쪽(트럭, 꺼내기, 상점)이 부르는 것

    /** 트럭이 받는 피해 배율(hpFrac = 지금 트럭 체력 몫 0..1): 철판, 냉각기, 서 있는 '먹이' 사람마다 */
    public double truckDamageMul(double hpFrac) {
        wired.add("truckDamageMul");
        return rules.truckDamageMul(hpFrac);
    }

    /** 게이브 g 가 트럭을 dmg 만큼 물었음(가시 철판이 돌려줌) */
    public void truckBitten(Gabe g, double dmg) {
        rules.truckBitten(g, dmg);
    }

    /** 트럭 체력 몫이 before 에서 after 로 바뀜(0..1). 경적이 10% 단계마다 울림 */
    public void truckHpChanged(double before, double after) {
        rules.truckHp(before, after);
    }

    /** 트럭을 물려는 g 가 대신 물 사람(미끼 증강, 트럭 곁에 서 있는 사람). 없으면 null */
    public Player bait(Gabe g) {
        wired.add("bait");
        return rules.bait(g);
    }

    /** 수리 값 배율(정비공) */
    public double repairPriceMul(Player p) {
        wired.add("repairPriceMul");
        return p != null && has(p, Aug.T_MECHANIC) ? Aug.T_MECHANIC.n("price", 0.7) : 1;
    }

    /** 수리 양 배율(정비공) */
    public double repairAmountMul(Player p) {
        return p != null && has(p, Aug.T_MECHANIC) ? Aug.T_MECHANIC.n("amount", 1.5) : 1;
    }

    /** 꺼내는 값 배율: 죽은 사람의 인식표, 편도, 내는 사람의 몸값, 트럭 부품 인양기 */
    public double revivePriceMul(Player dead, Player payer) {
        wired.add("revivePriceMul");
        double k = Math.pow(Aug.P_CRANE.n("mul", 0.8), ownPart(Aug.P_CRANE));
        if (dead != null && has(dead, Aug.D_TAG)) k *= Aug.D_TAG.n("mul", 0.6);
        if (dead != null && has(dead, Aug.X_ONEWAY)) k *= Aug.X_ONEWAY.n("price", 2);
        if (payer != null && has(payer, Aug.M_RANSOM)) k *= Aug.M_RANSOM.n("mul", 0.75);
        return k;
    }

    /** 이 사람이 일으키거나 꺼낼 수 있나('돌아보지 않는다'면 false) */
    public boolean canHelp(Player p) {
        return p == null || !has(p, Aug.X_NOMERCY);
    }

    /** 돈을 냄: what = repair(수리), revive(꺼내기), shop(상점). 물주, 단골, 구두쇠가 셈 */
    public void paid(Player p, String what, int amount) {
        if ("repair".equals(what) || "revive".equals(what)) wired.add("paid");
        if (p != null && amount > 0) rules.paid(p, what, amount);
    }

    /** 낸 돈을 돌려받음(쉬는 시간 공짜 부활로 꺼내기 모금이 풀림): 물주, 단골이 그 돈에는 보너스를 주지 않게 셈에서 뺀다 */
    public void refunded(UUID id, String what, int amount) {
        if (id != null && amount > 0) rules.refunded(id, what, amount);
    }

    /** 벌이가 아닌 돈 옮김(돌려받기, 합류 몫: Players.addMoneyRaw 가 부름). 빚의 '반값 벌이'와 '상점에서 씀' 셈이 이 돈을 세지 않는다 */
    public void moved(UUID id, int amount) {
        if (id != null && amount != 0) rules.moved(id, amount);
    }

    /** dead 를 payer 가 꺼냄(몸값 유대, 돌아온 자) */
    public void revived(Player dead, Player payer) {
        rules.revived(dead, payer);
    }

    /** 길을 떠날 때 트럭을 고칠 몫(보조 연료통 6% x 수) */
    public double travelRepair() {
        return Aug.P_TANK.n("frac", 0.06) * ownPart(Aug.P_TANK);
    }

    /** 들이받고 받히는 피해 배율(범퍼) */
    public double ramMul() {
        wired.add("ramMul");
        return part(Aug.P_BUMPER) > 0 ? Aug.P_BUMPER.n("mul", 0.5) : 1;
    }

    /** 견인줄이 있으면 쓰러진 사람을 끌 거리와 틱당 블록({r, speed}), 없으면 null */
    public double[] winch() {
        wired.add("winch");
        return part(Aug.P_WINCH) > 0 ? new double[] {Aug.P_WINCH.n("r", 12), Aug.P_WINCH.n("speed", 0.05)} : null;
    }

    /**
     * 쓰러질 피해를 한 번 버팀(두 번째 숨, 라운드에 한 번). Players.hurt 가 down 직전에 물어 true 면 체력 1 로 두고 쓰러뜨리지 않는다.
     * 이 고리가 없으면 쓰러진 다음 틱에 저절로 일으킨다(AugRules)
     */
    public boolean cheatDeath(Player p) {
        if (p == null || !rules.cheatDeath(p)) return false;
        // 무적(armor 0)을 지금 건다: 다음 틱의 apply 를 기다리면 같은 틱의 두 번째 피해(떼 여러 마리)에 체력 1 에서 그대로 쓰러진다
        apply(p);
        return true;
    }

    // ------------------------------------------------------------------ 판 정리

    public void forget(UUID id) {
        builds.remove(id);
        rules.forget(id);
    }

    /** 판 끝(Shop.clear 가 부름): 모두의 증강, 스탯, 대기 뽑기, 기본 판매, 트럭 부품을 지운다 */
    public void clear() {
        for (PState s : game.players.all()) s.augs.clear();
        builds.clear();
        parts.clear();
        java.util.Arrays.fill(partCnt, 0);
        partRewards = 0;
        rules.clear();
    }

    // ------------------------------------------------------------------ 시험

    /**
     * 증강주기 &lt;이름&gt; &lt;키&gt; [수], 증강빼기 &lt;이름&gt; &lt;키&gt;, 증강뽑기 &lt;이름&gt; [대기], 증강카드 &lt;이름&gt; &lt;키...&gt;(그 카드로 화면),
     * 증강고르기 &lt;이름&gt; &lt;0..2&gt;, 증강목록 &lt;이름&gt;, 증강표, 증강제안 &lt;이름&gt; [번], 증강상태 &lt;이름&gt;, 증강배율 &lt;이름&gt;,
     * 증강연결 x &lt;truck|revive|winch&gt; &lt;on|off&gt;, 부품주기 x &lt;키&gt;, 부품목록 x, 부품뽑기 &lt;이름&gt;, 기본판매 &lt;이름&gt; &lt;키&gt; [사기|상태],
     * 트럭알림 x &lt;배율 몫 | 물림 | 체력 전 후 | 값 죽은사람 낸사람 | 출발&gt;, 증강돈 &lt;이름&gt; &lt;수&gt;, 쉬는몫 &lt;이름&gt;, 아군기록
     */
    public String test(Player p, String[] a) {
        if (a.length < 2) return null;
        switch (a[1]) {
            case "증강주기" -> {
                if (p == null || a.length < 4) return "증강주기 <이름> <키> [수]";
                Aug g = Aug.of(a[3]);
                if (g == null) return "모르는 증강: " + a[3];
                int n = a.length > 4 ? Math.max(1, Integer.parseInt(a[4])) : 1;
                for (int i = 0; i < n; i++) give(p, g);
                return "ok " + g.key + "(" + g.ko + ") " + info(p);
            }
            case "증강빼기" -> {
                if (p == null || a.length < 4) return "증강빼기 <이름> <키|모두>";
                if (a[3].equals("모두")) {
                    ps(p).augs.clear();
                    build(p).ver++;
                    rules.fresh(p);
                    apply(p);
                    return "ok " + info(p);
                }
                Aug g = Aug.of(a[3]);
                return g == null ? "모르는 증강: " + a[3] : (take(p, g) ? "뺌 " : "없음 ") + info(p);
            }
            case "증강뽑기" -> {
                if (p == null) return "없음";
                offer(p, 3);
                boolean wait = a.length > 3 && a[3].equals("대기");
                if (!wait) game.shop.force(p);
                boolean opened = !wait && openCards(p, false);
                return "cards=" + cards(p) + " pending=" + pending(p) + " opened=" + opened;
            }
            case "증강카드" -> {
                if (p == null || a.length < 4) return "증강카드 <이름> <키> [키] [키]";
                List<Aug> cards = new ArrayList<>();
                boolean team = true;
                for (int i = 3; i < a.length && cards.size() < 3; i++) {
                    Aug g = Aug.of(a[i]);
                    if (g == null) return "모르는 증강: " + a[i];
                    cards.add(g);
                    team &= g.cat == Cat.PART;
                }
                build(p).pending.addFirst(new Offer(cards, team, false));
                game.shop.force(p);
                return "cards=" + cards + " opened=" + openCards(p, false);
            }
            case "증강고르기" -> {
                if (p == null || a.length < 4) return "증강고르기 <이름> <0..2>";
                List<Aug> c = cards(p);
                boolean ok = pick(p, Integer.parseInt(a[3]));
                return (ok ? "골랐음 " : "못 고름 ") + c + " | " + info(p) + " parts=" + parts;
            }
            case "증강목록" -> {
                return p == null ? "없음" : "owned=" + owned(p) + " " + info(p) + " mods=" + mods(p);
            }
            case "아군기록" -> {
                return "last=" + lastLog + " | rules=" + rules.lastLog;
            }
            case "증강표" -> {
                Map<Cat, Integer> by = new LinkedHashMap<>();
                int on = 0;
                StringBuilder off = new StringBuilder();
                for (Aug g : Aug.values()) {
                    by.merge(g.cat, 1, Integer::sum);
                    if (g.enabled) on++;
                    else off.append(' ').append(g.key);
                }
                StringBuilder sb = new StringBuilder("augs=" + Aug.values().length + " on=" + on + " cats=" + by + " staples=" + staples()
                    + (AugData.error == null ? "" : " error=" + AugData.error) + (off.length() == 0 ? "" : " off=" + off) + ":");
                if (a.length > 3 && a[3].equals("키")) for (Aug g : Aug.values()) sb.append(' ').append(g.key);
                return sb.toString();
            }
            case "증강제안" -> {
                // 뽑기를 여러 번 굴려 규칙을 셈(쌓지 않음): 위험 카드 수, 한 묶음에 위험 둘 이상, 갈래 겹침, 묶임 어긋남
                if (p == null) return "없음";
                int times = a.length > 3 ? Math.max(1, Integer.parseInt(a[3])) : 200;
                int risk = 0, risk2 = 0, dupCat = 0, badBind = 0, part = 0, needs = 0, small = 0;
                Map<Cat, Integer> by = new LinkedHashMap<>();
                Set<Aug> seen = EnumSet.noneOf(Aug.class);
                for (int i = 0; i < times; i++) {
                    List<Aug> c = roll(p, 3);
                    if (c.size() < 3) small++;
                    int r = 0;
                    Set<Cat> cs = EnumSet.noneOf(Cat.class);
                    for (Aug g : c) {
                        seen.add(g);
                        by.merge(g.cat, 1, Integer::sum);
                        if (g.risk) r++;
                        if (!cs.add(g.cat)) dupCat++;
                        if (!offerable(p, g)) badBind++;
                        if (g.cat == Cat.PART) part++;
                        if (g.needs != null && !ready(g.needs)) needs++;
                    }
                    if (r > 0) risk++;
                    if (r > 1) risk2++;
                }
                int could = 0;
                for (Aug g : Aug.values()) if (offerable(p, g)) could++;
                return "rolls=" + times + " risk=" + risk + " risk2=" + risk2 + " dupCat=" + dupCat + " badBind=" + badBind + " part=" + part + " needs=" + needs
                    + " short=" + small + " seen=" + seen.size() + "/" + could + " cats=" + by;
            }
            case "증강상태" -> {
                return p == null ? "없음" : rules.status(p) + " parts=" + parts + " partRewards=" + partRewards + " staples=" + build(p).staples + " off="
                    + build(p).stapleOff;
            }
            case "증강배율" -> {
                if (p == null) return "없음";
                Guns.Arms arms = game.guns.arms(p);
                Guns.Spec held = game.guns.held(p);
                Gabe g = game.mobs.nearest(p.getLocation().toVector(), 60, x -> !x.dead);
                StringBuilder sb = new StringBuilder();
                sb.append("held=").append(held == null ? "-" : held.key());
                if (held != null) sb.append(String.format(Locale.ROOT, " dmg=%.2f rps=%.2f mag=%d res=%d reload=%d range=%.0f pierce=%d pellets=%d spread=%.2f",
                    held.damage(), held.rps(), held.mag(), held.reserve(), held.reload(), held.range(), held.pierce(), held.pellets(), held.spread()));
                sb.append(" target=").append(g == null ? "-" : g.sp.name() + String.format(Locale.ROOT, "(%.1f)", g.pos.distance(p.getLocation().toVector())));
                if (g != null) sb.append(String.format(Locale.ROOT, " hitMul=%.3f", hitMul(p, g, held)));
                sb.append(String.format(Locale.ROOT, " armor=%.3f move=%.3f maxHp=%.0f revive=%.2f", ps(p).armor, ps(p).moveMul, ps(p).maxHp, ps(p).reviveMul));
                if (arms != null && arms.primary != null) sb.append(" primary=").append(arms.primary);
                return sb.toString();
            }
            case "증강연결" -> {
                if (a.length < 5) return "증강연결 x <truck|revive|고리 이름|모두> <on|off> (시험으로 켠 것 " + forcedNeeds + ", 불린 고리 " + wired + ")";
                if (a[3].equals("모두")) {
                    forcedNeeds.clear();
                    if (a[4].equals("on")) forcedNeeds.addAll(List.of("truck", "revive", "truckDamageMul", "bait", "repairPriceMul", "revivePriceMul", "paid",
                        "winch", "ramMul"));
                    return "needs=" + forcedNeeds;
                }
                if (a[4].equals("on")) forcedNeeds.add(a[3]);
                else forcedNeeds.remove(a[3]);
                return "needs=" + forcedNeeds + " wired=" + wired;
            }
            case "부품주기" -> {
                if (a.length < 4) return "부품주기 x <키|보상 n>";
                if (a[3].equals("보상")) {
                    addPartReward(a.length > 4 ? Integer.parseInt(a[4]) : 1);
                    return "partRewards=" + partRewards;
                }
                Aug g = Aug.of(a[3]);
                if (g == null || g.cat != Cat.PART) return "부품이 아님: " + a[3];
                addPart(g, p);
                return "parts=" + parts;
            }
            case "부품목록" -> {
                return "parts=" + parts + " partRewards=" + partRewards;
            }
            case "부품뽑기" -> {
                if (p == null) return "없음";
                game.shop.force(p);
                boolean ok = offerParts(p);
                return "opened=" + ok + " cards=" + cards(p) + " partRewards=" + partRewards;
            }
            case "기본판매" -> {
                if (p == null || a.length < 4) return "기본판매 <이름> <키> [사기|상태] (키 " + staples() + ")";
                String key = a[3];
                String act = a.length > 4 ? a[4] : "상태";
                boolean r = act.equals("사기") && buyStaple(p, key);
                return "staple=" + key + " did=" + r + " has=" + hasStaple(p, key) + " on=" + stapleOn(p, key) + " price=" + staplePrice(p, key) + " money="
                    + ps(p).money + " " + rules.autoStatus(p);
            }
            case "트럭알림" -> {
                if (a.length < 4) return "트럭알림 x <배율 몫 | 물림 | 체력 전 후 | 값 죽은사람 낸사람 | 출발>";
                switch (a[3]) {
                    case "배율" -> {
                        double f = a.length > 4 ? Double.parseDouble(a[4]) : 1;
                        return String.format(Locale.ROOT, "truckDamageMul(%.2f)=%.3f", f, truckDamageMul(f));
                    }
                    case "물림" -> {
                        Gabe g = game.mobs.nearest(truckPos(), 30, x -> !x.dead);
                        if (g == null) return "게이브 없음";
                        double before = g.hp;
                        Player b = bait(g);
                        truckBitten(g, a.length > 4 ? Double.parseDouble(a[4]) : 10);
                        return String.format(Locale.ROOT, "%s hp %.1f -> %.1f bait=%s", g.sp.name(), before, g.hp, b == null ? "-" : b.getName());
                    }
                    case "체력" -> {
                        if (a.length < 6) return "트럭알림 x 체력 <전> <후>";
                        int was = rules.hornBlasts;
                        truckHpChanged(Double.parseDouble(a[4]), Double.parseDouble(a[5]));
                        return "horn=" + (rules.hornBlasts - was) + " total=" + rules.hornBlasts;
                    }
                    case "값" -> {
                        Player dead = a.length > 4 ? Bukkit.getPlayerExact(a[4]) : null, payer = a.length > 5 ? Bukkit.getPlayerExact(a[5]) : null;
                        return String.format(Locale.ROOT, "revivePriceMul=%.3f repairPrice=%.2f repairAmount=%.2f canHelp=%s", revivePriceMul(dead, payer),
                            repairPriceMul(payer), repairAmountMul(payer), canHelp(payer));
                    }
                    case "출발" -> {
                        return String.format(Locale.ROOT, "travelRepair=%.3f ramMul=%.2f winch=%s", travelRepair(), ramMul(), java.util.Arrays.toString(winch()));
                    }
                    default -> {
                        return "모르는 트럭알림: " + a[3];
                    }
                }
            }
            case "증강돈" -> {
                // 증강돈 <이름> <수>: 재화를 그 값으로 맞춤(벌이로 치지 않음: 빚, 단골 셈에 안 걸림)
                if (p == null || a.length < 4) return "증강돈 <이름> <수>";
                ps(p).money = Integer.parseInt(a[3]);
                rules.run(p).money = ps(p).money;
                return "money=" + ps(p).money;
            }
            case "쉬는몫" -> {
                if (p == null) return "없음";
                int before = ps(p).money;
                List<String> was = new ArrayList<>(ps(p).augs);
                rules.onBreak(p);
                return "money " + before + " -> " + ps(p).money + " augs " + was + " -> " + ps(p).augs + " " + rules.status(p);
            }
            default -> {
                return null;
            }
        }
    }
}
