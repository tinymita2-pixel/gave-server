package kr.gave.gun;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.SwingAnimation;
import kr.gave.Props;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import net.kyori.adventure.text.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;

/**
 * 총(좀비런 Guns 를 옮김, 좀비 쪽 연결은 GunHost 로 바꿈). 조작: 우클릭 누르고 있기 = 사격(짧게 = 한 발), Q = 재장전,
 * 좌클릭 = 돕기(host.onHelp), 숫자키 1 주무기 / 2 권총 / 3 소모품 칸(총 아님). F 수류탄은 {@link Grenades} 가 받는다.
 * 누르고 있기: 우클릭(아이템 사용 패킷)이 오면 서버가 그 사람의 '사용 중'을 켠다(startUsingItem). 그 값이 클라에 닿으면 클라도 사용 중이
 * 되어 손을 떼는 순간 뗌을 알려 준다(use_effects 로 걷기, 달리기는 그대로). 총 아이템에 소모품(consumable) 컴포넌트를 주어 클라가 스스로
 * 사용을 시작하게 하면 바닐라가 누를 때마다 손 아이템을 화면 아래로 내렸다 올려서(ItemInHandRenderer.itemUsed) 쏘는 총이 푹 꺼진다: 쓰지 않는다.
 * 채팅, Esc 로 화면이 열리면 클라가 뗌을 안 보내므로 10 틱마다 클라에만 '사용 안 함'을 보내 다시 누름이 오는지 본다(scpsl Firearms 와
 * 같은 방법). 다시 누름이 오면 '사용 중'을 다시 보내 준다. 답이 올 시간(핑 + 2 틱)까지만 계속 쏘고, 그 뒤로는 답이 올 때까지 쏘지 않는다
 * (뗐는데 계속 쏘는 일을 줄임).
 * 반동({@link Recoil}, recoilShot, recoilTick): 발마다 위로 차고(쏜 틱에 65%, 다음 틱에 35%) 옆으로는 총마다 정해진 무늬 + 무작위. 이어 쏘면
 * 오름이 쌓이되 총마다 한도(cap)에 가까워질수록 줄어 그 뒤로는 옆 흔들림만 남는다. 클라 시점에 더하는 상대 회전이라 마우스를 빼앗지 않는다.
 * 방아쇠를 놓으면 연사를 시작한 시점으로 부드럽게 돌아가는데, 사람이 마우스로 스스로 내린 몫은 빼고 남은 만큼만 되돌린다(서버가 아는
 * 지금 시점과 연사 시작 시점을 견줌: 반동을 잡으며 쏜 사람의 조준이 표적 아래로 꺼지지 않는다). 한 사람에게 가는 회전 패킷은 틱마다 많아야 하나.
 * 쏠 때마다 시야가 살짝 좁아짐(클라에만 걷기 속도를 바꿔 FOV 배율을 움직임). 퍼짐은 연사 누적 + 이동 + 공중 - 웅크림.
 * 총구 불꽃은 손에 든 총 아이템 모델 안(build_pack.py 의 muzzle_&lt;총&gt;)에 있고, 쏜 틱 도장을 custom_model_data 색으로 넣으면
 * 팩 셰이더가 2 틱 그린다(1인칭, 3인칭 모두 총구 끝). 재장전 중 탄창이 빠진 동안은 깃발 0 으로 탄창 뺀 모델을 보인다.
 * 소리: 총소리는 총마다 들리는 거리(64~128 블록) 안 사람에게 따로 보내고, 재장전은 박자에 맞춘 부품 소리(탄창 빠짐, 꽂음, 슬라이드, 노리쇠,
 * 산탄 한 발씩, 펌프), 탄착은 재질 소리, 곁을 스친 사람에겐 스침 소리.
 * <p>
 * 맞힘: 블록 광선을 먼저 쏘고 그 거리 안에서 host.targets().cast 로 가장 가까운 게이브를 찾는다. 피해 = 총 피해 x 배율 x 거리 감쇠
 * (사거리 60% 까지 1, 사거리에서 0.5), 치명(배율 crit 확률)은 2배. 관통 수만큼 이어 간다(블록은 못 뚫음).
 * 배율(GunMods)은 매 틱(그리고 쏠 때마다) host.mods(p) 로 읽어 그 사람의 실제 총 값(eff)을 다시 만든다: 탄창, 예비탄, 연사, 퍼짐, 반동,
 * 사거리, 관통, 산탄 알 수, 재장전 시간(박자 소리 시각도 같은 비율).
 * <p>
 * 굴린 총: 주무기는 {@link Gun}(종류 + 등급 + 굴린 스탯 + 특성 + 강화 단계, Arms.gun), 권총은 굴리지 않는 기본 권총(Arms.pistol, 강화만).
 * 유효 총 = 바탕 Spec x Gun.mods() x host.mods(p) ({@link #effective}). 쏘기 파이프라인(누름, 박자, 반동, 재장전, 불꽃 도장)은 그대로다.
 * 특성(폭발탄, 화염탄, 연쇄, 흡혈, 관통, 넉백, 치명)과 증강의 대상 조건 피해, 처치 회복, 아군 사격 알림은 {@link #hooks}(Augments)로 묻고 알린다.
 * 아군 사격: 탄 광선이 쏜 사람 말고 서 있는 동료 몸 상자(또는 쓰러진 동료의 좌석 자리 낮은 상자)에 몹, 블록보다 먼저 닿으면 종 갈고리
 * ({@link #addAllyShot}, 감염자 등 막대기 등)에 먼저 묻고, 아무도 받지 않으면 hooks.friendly 를 한 발에 한 사람 한 번 부르고 탄은 거기서 멈춘다.
 * 맞힐 게이브가 그 동료에게 붙은 것(붙잡음, 머리 부착, hooks.attached)이면 그 동료 몸은 탄을 막지 않는다.
 * <p>
 * 판 중에 나가면({@link #forget}) 무기 상태(굴린 총, 강화, 탄, 소모품 칸)를 맡아 두고, 같은 판에 다시 들어와 {@link #equip} 을 부르면 되살린다.
 */
public final class Guns implements Listener {
    /**
     * 무기 종류. 앞 다섯은 처음부터 있던 총, FLAME 부터는 2026-11-12 무기 랜덤 상자로 늘린 것(쏘는 방식은 {@link #MODES}).
     * 차례가 배열 칸 번호라 뒤에만 더한다
     */
    public enum Kind {
        PISTOL, SMG, SHOTGUN, RIFLE, SNIPER, FLAME, LAUNCHER, ROCKET, MINIGUN, LMG, REVOLVER, BURST, DMR, RAILGUN, TESLA, FREEZE, BOW,
        // 2026-11-12 wpn_max: 모델 표(Models)의 새 종류 아홉
        AUTOSHOT, DOUBLE, BOLTACTION, MACHINEPISTOL, DUAL, ANTIMATERIEL, SILENCED, MUSKET, LASER
    }

    /**
     * 쏘는 방식: HITSCAN 광선(옛 총 모두), BURST 한 번 당기면 count 발 뒤 ticks 쉼, SPIN 누르는 동안 ticks 에 걸쳐 돌아 빨라짐(effect 이상 돌아야 쏨),
     * CHARGE ticks 모아 쏘는 관통 광선, FLAME 불길 원뿔(cone 도, 불붙음 ticks, 불 피해 배율 effect), FREEZE 냉기 원뿔(cone, 느려짐 ticks, 빠르기
     * 배율 effect, count 틱 넘게 맞으면 얼어붙음), CHAIN 맞은 데서 radius 안으로 count 번 번지는 번개(번질 때마다 effect 배), LOB 포물선 유탄
     * (speed 블록/틱, gravity, 폭발 반경 radius, 가장자리 피해 몫 effect), ROCKET 곧은 로켓(같은 칸), BOLT 관통 화살(맞을 때마다 effect 블록 밀침),
     * BEAM 광선총(광선 판정은 HITSCAN 과 같고 곡선 탄 줄 대신 빛줄기를 그림)
     */
    public enum Fire {
        HITSCAN, BURST, SPIN, CHARGE, FLAME, FREEZE, CHAIN, LOB, ROCKET, BOLT, BEAM
    }

    /** 종류마다 쏘는 방식과 그 값(쓰지 않는 칸은 0, 뜻은 {@link Fire}) */
    public record Mode(Fire fire, int count, int ticks, double cone, double radius, double speed, double gravity, double effect) {
        static final Mode HITSCAN = new Mode(Fire.HITSCAN, 0, 0, 0, 0, 0, 0, 0);
    }

    public static final Map<Kind, Mode> MODES = new EnumMap<>(Kind.class);

    static {
        MODES.put(Kind.BURST, new Mode(Fire.BURST, 3, 7, 0, 0, 0, 0, 0));
        MODES.put(Kind.MINIGUN, new Mode(Fire.SPIN, 0, 24, 0, 0, 0, 0, 0.3));
        MODES.put(Kind.RAILGUN, new Mode(Fire.CHARGE, 0, 16, 0, 0, 0, 0, 0));
        MODES.put(Kind.FLAME, new Mode(Fire.FLAME, 0, 60, 13, 0, 0, 0, 2.4));
        MODES.put(Kind.FREEZE, new Mode(Fire.FREEZE, 30, 40, 12, 0, 0, 0, 0.45));
        MODES.put(Kind.TESLA, new Mode(Fire.CHAIN, 4, 0, 0, 6.5, 0, 0, 0.7));
        MODES.put(Kind.LAUNCHER, new Mode(Fire.LOB, 0, 70, 0, 3.4, 1.35, 0.05, 0.35));
        MODES.put(Kind.ROCKET, new Mode(Fire.ROCKET, 0, 80, 0, 4.6, 1.9, 0.0, 0.3));
        MODES.put(Kind.BOW, new Mode(Fire.BOLT, 0, 0, 0, 0, 0, 0, 0.45));
        // 새 종류(2026-11-12 wpn_max): 쌍발은 한 번 당기면 두 방(양 총열) 뒤 쉼, 대물 저격총과 머스킷은 꿰뚫고 크게 밀침, 광선총은 빛줄기
        MODES.put(Kind.DOUBLE, new Mode(Fire.BURST, 2, 20, 0, 0, 0, 0, 0));
        MODES.put(Kind.ANTIMATERIEL, new Mode(Fire.BOLT, 0, 0, 0, 0, 0, 0, 0.9));
        MODES.put(Kind.MUSKET, new Mode(Fire.BOLT, 0, 0, 0, 0, 0, 0, 0.5));
        MODES.put(Kind.LASER, new Mode(Fire.BEAM, 0, 0, 0, 0, 0, 0, 0));
    }

    /** 그 종류가 쏘는 방식(없으면 광선) */
    public static Mode mode(Kind k) {
        return k == null ? Mode.HITSCAN : MODES.getOrDefault(k, Mode.HITSCAN);
    }

    /**
     * 반동 모양(한 발의 위, 옆 킥은 Spec.up, side): 연사 n 발째 위 킥 배율 1 + climb x min(n-1, climbMax), cap = 이어 쏴서 오를 수 있는 총 오름
     * 한도(도: 가까워질수록 킥이 줄고 다 차면 옆 흔들림과 잔떨림만), 옆 무늬 진폭(0..1)과 위상, settle = 놓은 뒤 틱마다 되돌리는 몫(클수록 빨리
     * 제자리: 권총은 톡 치고 바로, 무거운 총은 천천히), 쏠 때 FOV 배율
     */
    public record Recoil(double climb, int climbMax, double cap, double pattern, double phase, double settle, double fov) {
    }

    /**
     * 총 하나. fire, pitch = 바닐라 대체 총소리와 그 높이(팩에 gun.&lt;총&gt;.fire 가 있으면 그 사건을 흔들림만으로 울려 높이는 sounds.json 이 정함),
     * soundRange = 총소리가 들리는 거리(바닐라 대체 소리는 이 거리만큼 음량을 키움, Props.soundAround), damage = 한 발(산탄은 한 알) 피해.
     * 단위: spread, bloom, move, up, side 는 도, reload, draw 는 틱(산탄총 reload 는 한 발 넣는 틱), range 는 블록, reserve -1 은 무한
     */
    public record Spec(Kind kind, String key, String name, int mag, int reserve, double rps, double spread, double bloom, double bloomMax,
        double move, double up, double side, int reload, int pellets, double range, int pierce, boolean shells, String vanillaModel,
        Sound fire, float pitch, int draw, double soundRange, Recoil recoil, double damage, String model, String snd) {

        /** 종류 바탕 값(모델 = 종류 키, 소리 사건 = 종류 사건) */
        public Spec(Kind kind, String key, String name, int mag, int reserve, double rps, double spread, double bloom, double bloomMax, double move,
            double up, double side, int reload, int pellets, double range, int pierce, boolean shells, String vanillaModel, Sound fire, float pitch,
            int draw, double soundRange, Recoil recoil, double damage) {
            this(kind, key, name, mag, reserve, rps, spread, bloom, bloomMax, move, up, side, reload, pellets, range, pierce, shells, vanillaModel, fire,
                pitch, draw, soundRange, recoil, damage, key, null);
        }

        /** 팩 아이템 모델 키(gave:&lt;모델&gt;, Models 표). 종류 바탕 값이면 종류 키 */
        public String model() {
            return model;
        }

        /** 이 총의 사격 소리 사건(모델 소리가 있으면 그것, 없으면 종류 사건) */
        public String fireEvent() {
            return snd != null ? snd : "gun." + key + ".fire";
        }

        /** 모델 키와 그 모델 총소리 사건만 바꾼 총(모델의 성능 배율은 Models.Model.mods() 를 {@link #with} 로 다른 배율과 한 번에 건다) */
        public Spec named(String model, String snd) {
            return new Spec(kind, key, name, mag, reserve, rps, spread, bloom, bloomMax, move, up, side, reload, pellets, range, pierce, shells, vanillaModel,
                fire, pitch, draw, soundRange, recoil, damage, model, snd);
        }

        /** 배율을 건 총(키, 이름, 소리, 모델은 그대로) */
        public Spec with(GunMods m) {
            if (m == null || m.equals(GunMods.NONE)) return this;
            double sp = Math.max(0, m.spread()), rc = Math.max(0, m.recoil());
            return new Spec(kind, key, name, Math.max(1, (int) Math.round(mag * m.mag())),
                reserve < 0 ? -1 : Math.max(0, (int) Math.round(reserve * m.reserve())), Math.max(0.1, rps * m.rate()), spread * sp, bloom * sp,
                bloomMax * sp, move * sp, up * rc, side * rc, Math.max(1, (int) Math.round(reload * reloadMul(m))), Math.max(1, pellets + m.pellets()),
                Math.max(1, range * m.range()), Math.max(0, pierce + m.pierce()), shells, vanillaModel, fire, pitch, draw, soundRange, recoil,
                damage * Math.max(0, m.damage()), model, snd);
        }
    }

    /** 재장전 시간 배율(박자 소리 시각에도 같은 값을 씀): 0.2..5 로 자름 */
    static double reloadMul(GunMods m) {
        return m == null ? 1 : Math.max(0.2, Math.min(5, m.reload()));
    }

    /**
     * 물량을 막기에 맞지 않는 총의 단발 배율(2026-11-15 사용자 '단발 화력 1.5~4 배'): {@link #add} 가 SPECS 의 바탕 한 발 피해에 곱한다.
     * 배율은 떼 막기 효율(tools/balance_sim.py --horde, 30명 15, 25웨이브 초당 없앤 단위)이 물량 총 가운데 값의 몇 분의 1 인지의 역수
     * (1.5 아래는 1.5). 복합궁, 지정사수는 떼를 저격소총보다 잘 막아 없음. 총 값을 빌려 사람을 쏘는 크리처(G52)는 {@link #singleShot} 로
     * 나눠 예전 피해를 지킨다(크리처는 그대로라는 방침). 이 줄은 tools/balance_sim.py 가 읽는다
     */
    private static final Map<Kind, Double> SINGLE_SHOT = Map.of(Kind.SNIPER, 1.5, Kind.RAILGUN, 1.5, Kind.REVOLVER, 1.5, Kind.BOLTACTION, 1.5,
        Kind.ANTIMATERIEL, 1.8, Kind.MUSKET, 2.9);

    /** 그 종류의 단발 배율(없으면 1) */
    public static double singleShot(Kind k) {
        return k == null ? 1 : SINGLE_SHOT.getOrDefault(k, 1.0);
    }

    public static final Map<Kind, Spec> SPECS = new EnumMap<>(Kind.class);

    static {
        add(new Spec(Kind.PISTOL, "pistol", "권총", 15, -1, 5, 1.2, 0.5, 3.0, 1.5, 1.0, 0.5, 28, 1, 80, 0, false, "flint_and_steel",
            Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.7f, 8, 64, new Recoil(0.05, 4, 3.5, 0.3, 0.0, 0.34, 0.975), 14));
        add(new Spec(Kind.SMG, "smg", "기관단총", 40, 240, 13, 1.6, 0.25, 3.5, 1.2, 0.35, 0.3, 36, 1, 70, 0, false, "iron_hoe",
            Sound.ENTITY_FIREWORK_ROCKET_BLAST, 2.0f, 9, 80, new Recoil(0.07, 10, 3.2, 0.35, 1.3, 0.28, 0.985), 9.5));
        add(new Spec(Kind.SHOTGUN, "shotgun", "산탄총", 8, 48, 1.3, 5.0, 0, 0, 1.0, 4.0, 1.0, 6, 9, 25, 0, true, "iron_shovel",
            Sound.ENTITY_GENERIC_EXPLODE, 1.8f, 10, 80, new Recoil(0, 1, 7.0, 0, 0, 0.2, 0.95), 8));
        add(new Spec(Kind.RIFLE, "rifle", "소총", 30, 180, 10, 1.0, 0.3, 3.0, 1.6, 0.6, 0.35, 44, 1, 120, 0, false, "crossbow",
            Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.4f, 10, 96, new Recoil(0.06, 10, 5.0, 0.6, 0.4, 0.24, 0.98), 14));
        // 저격소총: 10 발, 한 발 70(단발 배율 x1.5 = 105, SINGLE_SHOT), 관통 4, 웅크리면 조준경
        add(new Spec(Kind.SNIPER, "sniper", "저격소총", 10, 50, 1.6, 0.1, 1.5, 3.0, 4.0, 3.0, 0.8, 52, 1, 160, 4, false, "spyglass",
            Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.7f, 12, 128, new Recoil(0, 1, 6.0, 0, 0, 0.2, 0.94), 70));
        // ---- 2026-11-12 무기 랜덤 상자: 쏘는 방식은 MODES. 피해는 한 번 맞힘(불길, 냉기는 한 번 쏠 때 원뿔 안 모두, 폭발은 가운데) 기준.
        // 2026-11-14 화력 고르기(build/notes/gun_작업기록.md 표): 기준은 한 대상 지속 화력(sustained, 소총 82). 흔한 총(기관단총, 기관권총,
        // 소음기 총, 산탄총, 쌍발)은 소총 둘레로 올리고, 한 대상 값까지 높던 범위 무기(전기총, 활, 로켓, 레일건, 유탄, 지정사수)는 내렸다
        // 화염방사기: 연료 100(초당 16 칸), 9 블록 원뿔, 맞을 때 5 + 불붙음 3초(초당 12)
        add(new Spec(Kind.FLAME, "flamethrower", "화염방사기", 100, 300, 16, 0, 0, 0, 0, 0.12, 0.08, 70, 1, 9, 0, false, "blaze_rod",
            Sound.ITEM_FIRECHARGE_USE, 1.2f, 12, 48, new Recoil(0, 1, 0.8, 0.2, 0, 0.3, 0.995), 5));
        // 유탄발사기: 6 발, 포물선, 반경 3.4 폭발 가운데 85
        add(new Spec(Kind.LAUNCHER, "launcher", "유탄발사기", 6, 30, 1.6, 0.8, 0, 0, 1.0, 3.5, 0.8, 60, 1, 60, 0, false, "crossbow",
            Sound.ENTITY_GENERIC_EXPLODE, 1.8f, 12, 64, new Recoil(0, 1, 6.0, 0, 0, 0.2, 0.95), 85));
        // 로켓 발사기: 1 발, 곧게 날아가 반경 4.6 폭발 가운데 210
        add(new Spec(Kind.ROCKET, "rocket", "로켓 발사기", 1, 12, 1.0, 0.4, 0, 0, 1.0, 4.5, 1.0, 66, 1, 120, 0, false, "spyglass",
            Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.5f, 14, 96, new Recoil(0, 1, 6.0, 0, 0, 0.16, 0.93), 210));
        // 미니건: 200 발, 다 돌면 초당 22 발
        add(new Spec(Kind.MINIGUN, "minigun", "미니건", 200, 600, 22, 2.2, 0.08, 3.5, 1.4, 0.22, 0.25, 110, 1, 70, 0, false, "iron_hoe",
            Sound.ENTITY_FIREWORK_ROCKET_BLAST, 2.2f, 16, 96, new Recoil(0.02, 10, 4.0, 0.6, 0.7, 0.2, 0.99), 9));
        // 경기관총: 100 발 탄통, 관통 1
        add(new Spec(Kind.LMG, "lmg", "경기관총", 100, 300, 12, 1.5, 0.22, 3.2, 1.6, 0.42, 0.3, 90, 1, 100, 1, false, "crossbow",
            Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.3f, 14, 96, new Recoil(0.06, 10, 5.5, 0.6, 0.9, 0.2, 0.982), 13));
        // 리볼버: 6 발, 한 발 62(x1.5 = 93), 관통 2
        add(new Spec(Kind.REVOLVER, "revolver", "리볼버", 6, 48, 2.6, 0.5, 1.2, 3.0, 1.4, 2.4, 0.7, 44, 1, 90, 2, false, "flint_and_steel",
            Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.2f, 9, 80, new Recoil(0, 1, 5.5, 0.3, 0, 0.26, 0.965), 62));
        // 점사 소총: 3 점사(점사 안 초당 14 발, 점사 뒤 7 틱 쉼), 곧음
        add(new Spec(Kind.BURST, "burst", "점사 소총", 36, 216, 14, 0.6, 0.15, 2.0, 1.4, 0.45, 0.25, 40, 1, 110, 0, false, "crossbow",
            Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.5f, 10, 96, new Recoil(0.05, 6, 3.0, 0.4, 0.2, 0.28, 0.98), 24));
        // 지정사수 소총: 15 발, 한 발 42, 관통 2, 웅크리면 조준경
        add(new Spec(Kind.DMR, "dmr", "지정사수 소총", 15, 90, 3.5, 0.25, 0.9, 2.5, 2.4, 1.6, 0.5, 50, 1, 140, 2, false, "spyglass",
            Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.0f, 11, 112, new Recoil(0, 1, 5.0, 0.3, 0, 0.26, 0.96), 42));
        // 레일건: 0.8초 모아 블록 앞까지 모두 꿰뚫는 광선 200(x1.5 = 300, 꿰뚫을 때마다 0.9 배). 쏜 뒤 쉼과 모으기가 겹쳐 실제로 초당 0.9 발
        add(new Spec(Kind.RAILGUN, "railgun", "레일건", 4, 24, 0.9, 0, 0, 0, 3.0, 3.6, 0.8, 70, 1, 160, 99, false, "spyglass",
            Sound.ENTITY_GUARDIAN_ATTACK, 1.8f, 14, 128, new Recoil(0, 1, 6.0, 0, 0, 0.18, 0.93), 200));
        // 전기총: 24 블록 안 첫 대상 22 + 6.5 블록 안으로 네 번 번짐(번질 때마다 0.7 배)
        add(new Spec(Kind.TESLA, "tesla", "전기총", 40, 200, 6, 0.4, 0, 0, 1.0, 0.5, 0.2, 50, 1, 24, 0, false, "lightning_rod",
            Sound.ENTITY_BREEZE_SHOOT, 1.6f, 11, 64, new Recoil(0, 1, 1.5, 0.3, 0, 0.3, 0.99), 22));
        // 냉동총: 10 블록 원뿔 냉기, 맞을 때 5 + 2초 느려짐(빠르기 0.45 배), 1.5초 넘게 맞으면 얼어붙음(얼어붙은 것은 모든 총에 1.2 배 아픔)
        add(new Spec(Kind.FREEZE, "freeze", "냉동총", 120, 360, 14, 0, 0, 0, 0, 0.1, 0.06, 70, 1, 10, 0, false, "prismarine_shard",
            Sound.BLOCK_POWDER_SNOW_BREAK, 0.8f, 12, 40, new Recoil(0, 1, 0.6, 0.2, 0, 0.3, 0.995), 5));
        // 복합궁: 한 발씩 시위에 걸어(18 틱) 80, 여덟 마리까지 꿰뚫고(꿰뚫을 때마다 0.8 배) 밀침
        add(new Spec(Kind.BOW, "bow", "복합궁", 1, 40, 1.5, 0.15, 0, 0, 1.2, 1.2, 0.3, 18, 1, 90, 8, false, "bow",
            Sound.ENTITY_ARROW_SHOOT, 1.0f, 10, 32, new Recoil(0, 1, 2.5, 0.2, 0, 0.3, 0.97), 80));
        // ---- 2026-11-12 wpn_max 새 종류 아홉(모델 하나하나의 배율은 Models). 화력은 소총(초당 140) 둘레, 범위, 관통 무기는 그만큼 낮춤
        // 자동 산탄총: 20 발 탄창, 초당 4.5 번 7 알(알당 4.2): 연사로 가까이서 가장 센 대신 멀면 흩어짐
        add(new Spec(Kind.AUTOSHOT, "autoshotgun", "자동 산탄총", 20, 100, 4.5, 5.5, 0.3, 3.0, 1.2, 1.4, 0.5, 70, 7, 25, 0, false, "iron_shovel",
            Sound.ENTITY_GENERIC_EXPLODE, 1.9f, 12, 80, new Recoil(0.05, 6, 5.5, 0.4, 0.3, 0.22, 0.97), 4.2));
        // 쌍발 산탄총: 두 발이 한 번에(BURST 2, 연속 초당 16), 쏜 뒤 두 발을 다시 채워야 함(탄창 2, 36 틱), 한 방 9 알 x 7.5
        add(new Spec(Kind.DOUBLE, "doublebarrel", "쌍발 산탄총", 2, 24, 16, 5.2, 0, 0, 1.2, 3.5, 1.0, 36, 9, 25, 0, false, "iron_shovel",
            Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 11, 80, new Recoil(0, 1, 8.0, 0, 0, 0.18, 0.95), 7.5));
        // 볼트액션 소총: 5 발, 한 발 120(x1.5 = 180), 관통 5, 쏘면 노리쇠 당김, 웅크리면 조준경, 치명 +10%
        add(new Spec(Kind.BOLTACTION, "boltaction", "볼트액션 소총", 5, 40, 1.2, 0.05, 1.5, 3.0, 4.0, 3.2, 0.6, 60, 1, 180, 5, false, "spyglass",
            Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.6f, 14, 112, new Recoil(0, 1, 6.0, 0, 0, 0.2, 0.93), 120));
        // 기관권총: 30 발, 초당 17 발, 한 발 8.5: 가까이서 빠름
        add(new Spec(Kind.MACHINEPISTOL, "machinepistol", "기관권총", 30, 210, 17, 2.4, 0.35, 4.5, 1.8, 0.4, 0.35, 32, 1, 45, 0, false, "iron_hoe",
            Sound.ENTITY_FIREWORK_ROCKET_BLAST, 2.2f, 8, 64, new Recoil(0.08, 12, 4.5, 0.3, 1.1, 0.3, 0.985), 8.5));
        // 쌍권총: 한 번에 두 발(양손 한 발씩, 서로 다른 퍼짐), 초당 8 번, 36 발
        add(new Spec(Kind.DUAL, "dualpistol", "쌍권총", 36, 216, 8, 2.0, 0.3, 3.5, 1.5, 0.7, 0.5, 40, 2, 55, 0, false, "flint_and_steel",
            Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.7f, 9, 64, new Recoil(0.04, 6, 3.5, 0.5, 0.0, 0.3, 0.98), 8));
        // 대물 저격총: 5 발, 한 발 215(x1.8 = 387), 줄 선 열두 마리까지 꿰뚫고 크게 밀침, 웅크리면 조준경
        add(new Spec(Kind.ANTIMATERIEL, "antimateriel", "대물 저격총", 5, 30, 0.9, 0.1, 2.0, 3.5, 5.0, 4.5, 1.0, 90, 1, 200, 12, false, "spyglass",
            Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 16, 144, new Recoil(0, 1, 7.0, 0, 0, 0.14, 0.92), 215));
        // 소음기 총: 32 발, 초당 12 발 10.5, 총소리가 20 블록 안에만 들리고 총구 불꽃 없음, 치명 +10%
        add(new Spec(Kind.SILENCED, "silenced", "소음기 총", 32, 224, 12, 1.2, 0.25, 3.0, 1.2, 0.32, 0.25, 36, 1, 70, 0, false, "iron_hoe",
            Sound.ENTITY_ARROW_SHOOT, 1.6f, 9, 20, new Recoil(0.04, 8, 2.6, 0.4, 0.9, 0.3, 0.99), 10.5));
        // 머스킷: 한 발 200(x2.9 = 580), 줄 선 넷을 꿰뚫고 밀침, 한 발마다 장전(44 틱)
        add(new Spec(Kind.MUSKET, "musket", "머스킷", 1, 30, 1.0, 0.3, 0, 0, 3.0, 4.0, 1.0, 44, 1, 100, 3, false, "crossbow",
            Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 14, 96, new Recoil(0, 1, 6.0, 0, 0, 0.16, 0.94), 200));
        // 광선총: 50 발, 초당 9 번 13, 관통 3, 반동 거의 없는 빛줄기
        add(new Spec(Kind.LASER, "laser", "광선총", 50, 300, 9, 0.2, 0, 0, 0.6, 0.05, 0.05, 40, 1, 90, 3, false, "lightning_rod",
            Sound.BLOCK_BEACON_POWER_SELECT, 2.0f, 10, 64, new Recoil(0, 1, 0.4, 0, 0, 0.3, 0.995), 13));
    }

    /** 종류마다 늘 붙는 치명 확률(리볼버, 지정사수 소총) */
    static double baseCrit(Kind k) {
        return k == Kind.REVOLVER || k == Kind.BOLTACTION || k == Kind.SILENCED ? 0.1 : k == Kind.DMR ? 0.05 : 0;
    }

    /**
     * 웅크리면 조준경(시야 배율). 1 = 없음. 걷기 속도 신호로 낼 수 있는 시야는 0.5 배 위(0.53 쯤)까지라 저격류는 모두 0.55
     * (그 밑으로 주면 fov() 가 못 맞춰 조준경이 안 켜진다)
     */
    static float scope(Kind k) {
        return switch (k) {
            case SNIPER, RAILGUN, BOLTACTION, ANTIMATERIEL -> 0.55f;
            case DMR -> 0.7f;
            default -> 1f;
        };
    }

    /** 종류 바탕 값 하나: 단발 배율({@link #SINGLE_SHOT})을 한 발 피해에 곱해 넣는다 */
    private static void add(Spec s) {
        SPECS.put(s.kind(), s.with(GunMods.NONE.mulDamage(singleShot(s.kind()))));
    }

    /** 이 총이 주는 피해 종류(흉내, 배우는 게이브가 읽음) */
    public static DamageType damageType(Kind k) {
        if (k == null) return DamageType.BULLET;
        return switch (k) {
            case SNIPER, RAILGUN, BOLTACTION, ANTIMATERIEL -> DamageType.SNIPER;
            case SHOTGUN, AUTOSHOT, DOUBLE -> DamageType.PELLET;
            case FLAME -> DamageType.FIRE;
            case LAUNCHER, ROCKET -> DamageType.EXPLOSION;
            case TESLA, FREEZE, LASER -> DamageType.OTHER;
            default -> DamageType.BULLET;
        };
    }

    /** 총구 불꽃을 그리는 종류(냉동총, 전기총, 레일건, 활은 그 무기 효과가 따로) */
    static boolean flashes(Kind k) {
        return k != Kind.FREEZE && k != Kind.TESLA && k != Kind.RAILGUN && k != Kind.BOW && k != Kind.LASER && k != Kind.SILENCED;
    }

    /** 거리 감쇠: 사거리 60% 까지 1, 사거리에서 0.5(그 사이 곧게) */
    public static double falloff(double dist, double range) {
        if (range <= 0) return 1;
        double k = (dist / range - 0.6) / 0.4;
        return 1 - 0.5 * Math.max(0, Math.min(1, k));
    }

    /** 꿰뚫을 때마다 줄어드는 피해 몫: n 번째(0 부터) 대상은 0.8^n, 아래끝 0.3. 떼가 빽빽할수록 관통 무기가 곱으로 세지는 것을 누른다 */
    public static double pierced(int n) {
        return Math.max(0.3, Math.pow(0.8, n));
    }

    /** 빈 총을 다 채우는 데 걸리는 틱: 탄창 총은 재장전 값, 한 발씩 넣는 총(산탄총)은 시작 자세 + 한 발 x 탄창 + 끝 펌프 */
    public static int reloadTicks(Spec s) {
        if (!s.shells()) return s.reload();
        double mul = cueMul(s);
        return scaled(SHELL_START, mul) + s.mag() * s.reload() + scaled(SHELL_PUMP, mul);
    }

    /**
     * 누르고 있을 때 실제로 나가는 초당 발 수: 점사는 발 사이 박자와 점사 뒤 쉼, 레일건은 모으는 시간과 연사 박자 가운데 긴 쪽.
     * 탄창이 한 발인 총(활, 로켓, 머스킷)은 쏠 때마다 재장전이라 한 바퀴({@link #cycleTicks})로 센다
     */
    public static double shotsPerSecond(Spec s) {
        if (s.mag() <= 1) return 20.0 / cycleTicks(s);
        Mode md = mode(s.kind());
        double gap = 20.0 / s.rps();
        return switch (md.fire()) {
            case BURST -> 20.0 * md.count() / (md.count() * gap + md.ticks());
            case CHARGE -> 20.0 / Math.max(gap, md.ticks());
            default -> s.rps();
        };
    }

    /**
     * 탄창 하나를 다 쏘고 재장전해 다음 탄창 첫 발까지 한 바퀴(틱). 박자는 pull() 과 같다: 재장전은 마지막 발 직후 시작해 쏜 뒤 쉼과 겹치고,
     * 점사는 점사 뒤 쉼, 레일건은 재장전 뒤 다시 모으기. 미니건은 누른 채 재장전하면 총열이 도는 채로 남아(pull 이 재장전 중에는 회전을
     * 건드리지 않음) 이어 쏘는 동안은 다 돈 빠르기다
     */
    static double cycleTicks(Spec s) {
        return cycle(s.kind(), 20.0 / s.rps(), reloadTicks(s), s.mag());
    }

    /** 한 바퀴(틱): 발 사이 gap 틱, 빈 총을 다 채우는 reload 틱, 탄창 mag 발 */
    private static double cycle(Kind k, double gap, double reload, int mag) {
        Mode md = mode(k);
        return switch (md.fire()) {
            case BURST -> {
                int n = Math.max(1, md.count()), bursts = (mag + n - 1) / n;
                yield (mag - 1) * gap + (bursts - 1) * md.ticks() + Math.max(reload, gap + (mag % n == 0 ? md.ticks() : 0));
            }
            case CHARGE -> (mag - 1) * Math.max(gap, md.ticks()) + Math.max(reload + md.ticks(), gap);
            default -> (mag - 1) * gap + Math.max(reload, gap);
        };
    }

    /**
     * 한 대상에 계속 쏠 때의 초당 피해: 한 바퀴({@link #cycleTicks}) 동안 탄창을 다 맞힘(치명, 거리 감쇠, 범위 덤은 안 셈). 불길은 불붙음 피해를
     * 더한다. 피해에 비례하므로 반올림 갚기(effective)가 피해 배율 하나로 맞출 수 있다. 상점 '화력' 줄, 밸런스 표(tools/balance_sim.py 도 같은 식)
     */
    public static double sustained(Spec s) {
        double dps = s.mag() * s.damage() * s.pellets() / cycleTicks(s) * 20;
        return mode(s.kind()).fire() == Fire.FLAME ? dps + s.damage() * mode(s.kind()).effect() : dps;
    }

    /**
     * 반올림 전 지속 화력: base 에 배율 m 을 곱한 탄창과 재장전 틱을 소수 그대로 둔 {@link #sustained}. 탄창은 이웃한 두 정수의 한 바퀴를 곧게
     * 잇는다(점사의 한 바퀴는 정수 탄창에서만 뜻이 있어서). {@link #effective} 가 반올림한 총의 한 발 피해를 이 값에 맞춘다
     */
    static double smoothSustained(Spec base, GunMods m) {
        double mag = Math.max(1, base.mag() * m.mag()), mul = reloadMul(m), rl = base.reload() * mul, gap = 20.0 / Math.max(0.1, base.rps() * m.rate());
        double dmg = base.damage() * Math.max(0, m.damage());
        int lo = (int) mag;
        double f = mag - lo, cyc = 0;
        for (int n = lo; n <= lo + 1; n++) {
            double w = n == lo ? 1 - f : f;
            if (w > 0) cyc += w * cycle(base.kind(), gap, base.shells() ? SHELL_START * mul + n * rl + SHELL_PUMP * mul : rl, n);
        }
        double dps = mag * dmg * Math.max(1, base.pellets() + m.pellets()) / cyc * 20;
        return mode(base.kind()).fire() == Fire.FLAME ? dps + dmg * mode(base.kind()).effect() : dps;
    }

    /** 쏘는 방식 한 줄(상점 줄 맨 위, 보통 총은 null): 값은 Mode 에서 읽어 숫자가 바뀌면 글도 따라온다 */
    public static String modeLine(Spec s) {
        Mode md = mode(s.kind());
        return switch (md.fire()) {
            case FLAME -> "불길 원뿔 " + Math.round(s.range()) + "블록, 불붙음";
            case FREEZE -> "냉기 원뿔 " + Math.round(s.range()) + "블록, 느려짐";
            case CHAIN -> "번개 " + md.count() + "번 번짐";
            case LOB -> "포물선, 폭발 반경 " + md.radius();
            case ROCKET -> "폭발 반경 " + md.radius();
            case CHARGE -> "모아 쏨, 일직선 모두";
            case BOLT -> "꿰뚫고 밀침";
            case BURST -> md.count() == 2 ? "두 방씩" : md.count() + "점사";
            case SPIN -> "돌수록 빨라짐";
            default -> null;
        };
    }

    /** 쏘는 동안 걷기 빠르기 배율(무거운 총은 쏘면서 느려진다. 안 쏠 때는 모두 같음): 아이템의 use_effects 로 클라가 건다 */
    static float useSpeed(Kind k) {
        return switch (k) {
            case MINIGUN -> 0.6f;
            case RAILGUN -> 0.7f;
            case ROCKET, ANTIMATERIEL -> 0.75f;
            case LMG -> 0.8f;
            default -> 1f;
        };
    }

    /** 냉동총으로 얼어붙은 대상이 모든 총에서 더 받는 피해 배율(냉동총의 팀 지원 몫) */
    static final double FROZEN_BONUS = 1.2;

    // ------------------------------------------------------------------ 재장전 박자

    private static final int NONE = 0, MAG_OUT = 1, MAG_IN = 2;

    /**
     * 재장전 박자 하나: 시작 뒤 at 틱(재장전 배율을 곱함)에 소리(events 중 팩에 있는 첫 것, 없으면 fallback)를 내고 act(탄창 빠짐, 꽂힘)를 보인다.
     * 소리 시각은 파형의 큰 소리(탄창 딸깍) 자리가 탄창이 보이고 사라지는 틱에 오게 앞당겨 둠. emptyOnly = 빈 총 재장전에서만.
     * pitch 는 바닐라 대체 소리 높이(모드 사건은 흔들림만, 높이는 sounds.json). events 는 종류 사건으로 적고, 울릴 때 그 모델 전용 사건이
     * 팩에 있으면 그것으로 바뀐다({@link #partEvent})
     */
    private record Cue(int at, int act, boolean emptyOnly, float pitch, Sound fallback, String... events) {
    }

    /**
     * 총 부품 소리(탄창 뺌, 꽂음, 장전 손잡이, 산탄 넣기, 펌프, 노리쇠, 꺼냄, 빈 총) 사건 이름: 팩에 그 모델 전용 사건
     * gun.&lt;모델 키&gt;.&lt;부품&gt;(부품 = 종류 사건의 마지막 마디, 예: gun.gcb_rampage.mag_out)이 있으면 그것, 없으면 종류 사건 그대로.
     * 모델 키가 종류 키와 같은 총(종류 바탕 값, 옛 기본 모델)은 늘 종류 사건이다. 팩에 있는지는 맵 조회 한 번(Props.hasSound)이고
     * 재장전 박자, 쏜 뒤 손질, 꺼낼 때에만 부른다(틱마다 돌지 않음)
     */
    private static String partEvent(Spec s, String kindEvent) {
        if (kindEvent == null || s.model().equals(s.key())) return kindEvent;
        String own = "gun." + s.model() + kindEvent.substring(kindEvent.lastIndexOf('.'));
        return Props.hasSound(own) ? own : kindEvent;
    }

    /** 재장전 박자 c 에서 울릴 사건: events 차례대로 그 부품의 모델 전용 사건, 없으면 그 종류 사건 가운데 팩에 있는 첫 것. 다 없으면 null(대체 소리) */
    private static String cueEvent(Spec s, Cue c) {
        for (String e : c.events()) {
            String ev = partEvent(s, e);
            if (Props.hasSound(ev)) return ev;
        }
        return null;
    }

    private static final Map<Kind, List<Cue>> CUES = new EnumMap<>(Kind.class);
    /** 산탄총: 재장전 시작 자세, 끝 펌프(빈 총에서 시작했을 때) 틱(재장전 배율을 곱함) */
    private static final int SHELL_START = 7, SHELL_PUMP = 8;

    static {
        CUES.put(Kind.PISTOL, List.of(new Cue(0, NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_OPEN, "gun.pistol.mag_out"),
            new Cue(5, MAG_OUT, false, 1f, null), new Cue(14, NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, "gun.pistol.mag_in"),
            new Cue(18, MAG_IN, false, 1f, null), new Cue(22, NONE, true, 1f, Sound.BLOCK_PISTON_CONTRACT, "gun.pistol.slide")));
        CUES.put(Kind.SMG, List.of(new Cue(3, NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_OPEN, "gun.smg.mag_out"),
            new Cue(4, MAG_OUT, false, 1f, null), new Cue(22, NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, "gun.smg.mag_in"),
            new Cue(24, MAG_IN, false, 1f, null),
            new Cue(28, NONE, true, 0.85f, Sound.BLOCK_PISTON_CONTRACT, "gun.smg.charge", "gun.pistol.slide")));
        CUES.put(Kind.RIFLE, List.of(new Cue(4, NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_OPEN, "gun.rifle.mag_out"),
            new Cue(5, MAG_OUT, false, 1f, null), new Cue(28, NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, "gun.rifle.mag_in"),
            new Cue(30, MAG_IN, false, 1f, null),
            new Cue(32, NONE, true, 1.1f, Sound.BLOCK_PISTON_CONTRACT, "gun.rifle.charge", "gun.sniper.bolt_release")));
        CUES.put(Kind.SNIPER, List.of(new Cue(0, NONE, true, 1f, Sound.BLOCK_PISTON_EXTEND, "gun.sniper.bolt_pull"),
            new Cue(8, NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_OPEN, "gun.sniper.mag_out"), new Cue(9, MAG_OUT, false, 1f, null),
            new Cue(30, NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, "gun.sniper.mag_in"), new Cue(32, MAG_IN, false, 1f, null),
            new Cue(38, NONE, true, 1f, Sound.BLOCK_PISTON_CONTRACT, "gun.sniper.bolt_release")));
        // 새 무기(2026-11-12): 옛 총의 부품 소리 사건을 그대로 부른다(새 소리 파일 없음). 시각은 그 종류 바탕 재장전 틱에 맞춤
        CUES.put(Kind.LMG, mag(SPECS.get(Kind.LMG).reload(), "gun.rifle.mag_out", "gun.rifle.mag_in", "gun.rifle.charge"));
        CUES.put(Kind.MINIGUN, mag(SPECS.get(Kind.MINIGUN).reload(), "gun.rifle.mag_out", "gun.rifle.mag_in", "gun.smg.charge"));
        CUES.put(Kind.BURST, mag(SPECS.get(Kind.BURST).reload(), "gun.rifle.mag_out", "gun.rifle.mag_in", "gun.rifle.charge"));
        CUES.put(Kind.DMR, mag(SPECS.get(Kind.DMR).reload(), "gun.sniper.mag_out", "gun.sniper.mag_in", "gun.rifle.charge"));
        CUES.put(Kind.RAILGUN, mag(SPECS.get(Kind.RAILGUN).reload(), "gun.sniper.mag_out", "gun.sniper.mag_in", "gun.sniper.bolt_release"));
        CUES.put(Kind.TESLA, mag(SPECS.get(Kind.TESLA).reload(), "gun.smg.mag_out", "gun.smg.mag_in", "gun.smg.charge"));
        CUES.put(Kind.FLAME, mag(SPECS.get(Kind.FLAME).reload(), "gun.rifle.mag_out", "gun.rifle.mag_in", null));
        CUES.put(Kind.FREEZE, mag(SPECS.get(Kind.FREEZE).reload(), "gun.rifle.mag_out", "gun.rifle.mag_in", null));
        CUES.put(Kind.LAUNCHER, breakOpen(SPECS.get(Kind.LAUNCHER).reload()));
        CUES.put(Kind.ROCKET, mag(SPECS.get(Kind.ROCKET).reload(), "gun.sniper.mag_out", "gun.sniper.mag_in", "gun.sniper.bolt_release"));
        CUES.put(Kind.REVOLVER, List.of(new Cue(2, NONE, false, 1.2f, Sound.BLOCK_IRON_TRAPDOOR_OPEN, "gun.pistol.mag_out"), new Cue(4, MAG_OUT, false, 1f, null),
            new Cue(30, NONE, false, 1.1f, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, "gun.pistol.mag_in"), new Cue(32, MAG_IN, false, 1f, null),
            new Cue(38, NONE, false, 1f, Sound.BLOCK_PISTON_CONTRACT, "gun.pistol.slide")));
        CUES.put(Kind.BOW, List.of(new Cue(4, NONE, false, 1f, Sound.ITEM_CROSSBOW_LOADING_MIDDLE), new Cue(14, NONE, false, 1f, Sound.ITEM_CROSSBOW_LOADING_END)));
        // 새 종류(2026-11-12 wpn_max): 옛 부품 소리 사건을 그대로, 시각은 그 종류 바탕 재장전 틱에 맞춤
        CUES.put(Kind.AUTOSHOT, mag(SPECS.get(Kind.AUTOSHOT).reload(), "gun.rifle.mag_out", "gun.rifle.mag_in", "gun.rifle.charge"));
        CUES.put(Kind.DOUBLE, breakOpen(SPECS.get(Kind.DOUBLE).reload()));
        CUES.put(Kind.BOLTACTION, mag(SPECS.get(Kind.BOLTACTION).reload(), "gun.sniper.mag_out", "gun.sniper.mag_in", "gun.sniper.bolt_release"));
        CUES.put(Kind.MACHINEPISTOL, mag(SPECS.get(Kind.MACHINEPISTOL).reload(), "gun.pistol.mag_out", "gun.pistol.mag_in", "gun.pistol.slide"));
        CUES.put(Kind.DUAL, mag(SPECS.get(Kind.DUAL).reload(), "gun.pistol.mag_out", "gun.pistol.mag_in", "gun.pistol.slide"));
        CUES.put(Kind.ANTIMATERIEL, mag(SPECS.get(Kind.ANTIMATERIEL).reload(), "gun.sniper.mag_out", "gun.sniper.mag_in", "gun.sniper.bolt_release"));
        CUES.put(Kind.SILENCED, mag(SPECS.get(Kind.SILENCED).reload(), "gun.smg.mag_out", "gun.smg.mag_in", "gun.smg.charge"));
        CUES.put(Kind.MUSKET, List.of(new Cue(8, NONE, false, 1f, Sound.ITEM_CROSSBOW_LOADING_START), new Cue(22, NONE, false, 1f, Sound.ITEM_CROSSBOW_LOADING_MIDDLE),
            new Cue(38, NONE, false, 1f, Sound.ITEM_CROSSBOW_LOADING_END)));
        CUES.put(Kind.LASER, mag(SPECS.get(Kind.LASER).reload(), "gun.smg.mag_out", "gun.smg.mag_in", null));
    }

    /** 꺾어 여는 총(쌍발 산탄총, 유탄발사기) 재장전 박자(바탕 len 틱): 엶 8%, 넣음 40%, 62%, 닫음 84%(펌프 소리가 아니라 닫는 소리, 늘) */
    private static List<Cue> breakOpen(int len) {
        return List.of(new Cue(Math.round(len * 0.08f), NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_OPEN, "gun.shotgun.reload_start"),
            new Cue(Math.round(len * 0.1f), MAG_OUT, false, 1f, null),
            new Cue(Math.round(len * 0.4f), NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_OPEN, "gun.shotgun.shell"),
            new Cue(Math.round(len * 0.62f), NONE, false, 1.05f, Sound.BLOCK_IRON_TRAPDOOR_OPEN, "gun.shotgun.shell"),
            new Cue(Math.round(len * 0.72f), MAG_IN, false, 1f, null),
            new Cue(Math.round(len * 0.84f), NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, "gun.shotgun.reload_end"));
    }

    /** 탄창 총 재장전 박자(바탕 재장전 len 틱): 뺌 10%, 꽂음 72%, 빈 총이면 끝에 장전 손잡이(charge 가 null 이면 없음) */
    private static List<Cue> mag(int len, String out, String in, String charge) {
        List<Cue> l = new ArrayList<>(List.of(new Cue(Math.round(len * 0.08f), NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_OPEN, out),
            new Cue(Math.round(len * 0.1f), MAG_OUT, false, 1f, null), new Cue(Math.round(len * 0.66f), NONE, false, 1f, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, in),
            new Cue(Math.round(len * 0.72f), MAG_IN, false, 1f, null)));
        if (charge != null) l.add(new Cue(Math.round(len * 0.82f), NONE, true, 1f, Sound.BLOCK_PISTON_CONTRACT, charge));
        return List.copyOf(l);
    }

    /** 쏜 뒤 총구 불꽃 도장을 남겨 두는 틱(셰이더는 2.3 틱까지 그림) */
    private static final int FLASH_KEEP = 4;
    /**
     * 연사 중 진짜 총 아이템의 도장을 바꾸는 가장 짧은 간격(틱). 손에 든 아이템이 바뀔 때마다 서버가 둘레 모든 사람에게 장비 패킷(아이템 통째)을
     * 보내므로, 30명이 초당 10발씩 쏘면 클라마다 초당 300개 가까운 장비 패킷이 총구 불꽃만으로 갔다. 진짜 아이템은 이 간격으로만 바꾸고
     * (남이 보는 총구 불꽃은 초당 5번까지) 그 사이 발의 도장은 쏜 사람에게만 칸 패킷으로 보낸다(1인칭 불꽃과 몸짓은 발마다 그대로)
     */
    private static final int THIRD_GAP = 4;
    public static final int FX_MUZZLE_FACE = 11, FX_MUZZLE_SIDE = 12, FX_MUZZLE_SIDE_V = 13;
    private static final int[] MUZZLE_KINDS = {FX_MUZZLE_FACE, FX_MUZZLE_SIDE, FX_MUZZLE_SIDE_V};
    /** 총구 불꽃 씨앗의 '1인칭 반동 따라감' 비트(셰이더 zr_kick 의 색 비트 17) */
    private static final int KICK_BIT = 1 << 5;
    /** 핫바 칸: 0 주무기, 1 권총, 2 소모품(총 아님, 다른 담당이 채움) */
    public static final int SLOT_PRIMARY = 0, SLOT_PISTOL = 1, SLOT_ITEM = 2;

    /** 한 사람의 무기 상태 */
    public static final class Arms {
        public Kind primary;
        public final int[] mag = new int[Kind.values().length], res = new int[Kind.values().length];
        public int grenades;
        /** 쏜 발 수, 죽인 수(총과 수류탄, damage 가 true 를 돌려준 것만), 맞힌 알 수 */
        public int shots, kills, hits;
        /** 마지막으로 맞힌, 죽인 판 틱(HUD 적중 표시가 읽음). 없으면 -1 */
        public long hitAt = -1, killAt = -1;
        /** 맞힘 소리, 처치 소리, 아군에 막힘 소리를 마지막으로 낸 틱(쏜 사람만 듣는 소리를 틱마다 겹치지 않게) */
        long hitSoundAt = -99, killSoundAt = -99, blockSoundAt = -99;
        /** 지금 배율과 그 배율을 건 총(종류 차례) */
        GunMods mods = GunMods.NONE;
        final Spec[] eff = new Spec[Kind.values().length];
        /** 총마다 다음 발 틱(바꿔 들어도 그 총 박자는 그대로: 산탄총을 쏘고 두 번 바꿔 펌프 시간을 건너뛰지 못하게) */
        final double[] next = new double[Kind.values().length];
        double bloom;
        long reloadUntil = -1, lastShot = -99, tapUntil = -1, confirmedAt = -99, probeAt = -1, drawUntil;
        Kind reloading;
        boolean holdNoFire;
        long simUntil = -1;
        double lx, ly, lz, speed;
        /** 공중인가(위아래로 움직이는 중): 반동 회전의 클라 답이 서버의 '땅 위' 값을 잠깐 끄므로 그 값만으로는 못 믿는다 */
        boolean airborne;
        // 재장전 진행
        long reloadStart, phaseStart;
        boolean reloadEmpty, magOut;
        /** 이번 재장전의 시간 배율(박자 시각에 곱함, 시작할 때 정함) */
        double reloadMul = 1;
        /** 빈 산탄총을 넣다가 당김: 펌프가 끝나면 그 한 발을 쏜다(탭은 4 틱만 살아 펌프 8 틱보다 짧음) */
        boolean fireAfterPump;
        int cueIdx, shellPhase;
        // 쏜 뒤 펌프, 노리쇠(다 못 하고 바꿨으면 그 총을 다음에 꺼낼 때 함)
        Kind cycleKind;
        long cycleAt;
        int cycleStep;
        final boolean[] cycleOwed = new boolean[Kind.values().length];
        /** 시험: 마지막 쏜 틱 4 개(돌림) */
        final long[] shotLog = {-1, -1, -1, -1};
        int shotLogAt;
        // 반동: 이어 쏜 발 수, 아직 안 돌려준 오름(도)과 옆 쏠림, 연사를 시작한 시점(pitch), 이번 틱에 보낼 회전, 다음 틱 몫(35%),
        // 놓은 뒤 되돌림(쏜 뒤 settleDelay 틱부터 틱마다 남은 몫의 settle), 최근 보낸 pitch(아직 클라 답이 안 온 몫을 셈)
        int burst;
        double climb, drift, lastInterval = 4, settle = 0.25;
        float anchorPitch, outYaw, outPitch, restYaw, restPitch;
        long restAt = -1;
        int settleDelay = 4;
        final long[] sentAt = new long[16];
        final float[] sentPitch = new float[16];
        /** 시험: 서버 '땅 위' 값이 꺼진 채(위아래 움직임 없이) 쏜 발 수 */
        int groundFlicker;
        /** 땅 위 값 바로잡기(Guns.ground): 지난 서버 틱의 높이 */
        double groundY;
        int groundTick = -1;
        long fovUntil = -1;
        float fovMul = 1, sentWalk = -1;
        // 총구 불꽃(세계 시각), 손 총 표시 상태
        long flashAt = -99999;
        int flashSeed;
        final long[] sentStamp = {-1, -1};
        final boolean[] sentMag = new boolean[2];
        /** 칸마다 진짜 아이템의 도장을 마지막으로 바꾼 판 틱(THIRD_GAP) */
        final long[] realAt = {-99, -99};
        long debugFlashUntil = -1, debugMagUntil = -1, debugKickUntil = -1;
        /** 꺼낸 세계 시각(1인칭 올라오는 모습), 재장전 시작, 재장전 끝(자세 풀기) 세계 시각 */
        long drawAt = -99999, reloadWt = -1, poseEndWt = -99999;
        Kind poseEndKind;
        boolean dryClicked;
        /** 엉킴(JAM, Guns.jam): 끝 틱, 재장전 시간 배율, 헛방 확률, 퍼짐 배율. 주무기 잠금(WEAPONLOCK, Guns.lockPrimary) 끝 틱. 2026-11-12 sp_engine */
        long jamUntil = -1, primaryLockUntil = -1;
        double jamReload = 1, jamMisfire = 0, jamSpread = 1;
        /** 주무기 굴린 총(종류 = primary, 없으면 null), 권총(굴리지 않음, 강화는 됨) */
        public Gun gun;
        public Gun pistol = Gun.basic(Kind.PISTOL);
        /**
         * 소모품 칸(핫바 3, SLOT_ITEM) 종류 키(grenade, flare, molotov, rattle, cleanse, bandage, 없으면 null)와 수.
         * 수류탄은 grenades 를 쓴다(item 이 grenade 면 itemCount 는 안 씀). kr.gave.gun.Supplies 가 칸 아이템을 맞춤
         */
        public String item;
        public int itemCount;
        /** 총마다 치명 확률(Gun 특성 + 사람 배율) */
        final double[] crit = new double[Kind.values().length];
        long gunStamp = Long.MIN_VALUE;
        // ---- 새 쏘는 방식(2026-11-12, Arsenal): 미니건 회전 0..1, 점사 남은 발, 레일건 모으기 시작 틱(-1 아님), 줄기 무기 소리, 불 붙인 틱
        double spin;
        int burstLeft;
        long chargeAt = -1;
        Kind chargeKind;
        long streamSoundAt = -99, streamStartAt = -99, spinSoundAt = -99, streamFxAt = -99;

        Arms() {
            for (Kind k : Kind.values()) eff[k.ordinal()] = SPECS.get(k);
        }

        /** 배율을 건 이 사람의 총 */
        public Spec spec(Kind k) {
            return k == null ? null : eff[k.ordinal()];
        }

        /** 그 종류의 굴린 총: 권총은 pistol, 주무기 종류면 gun, 아니면 null */
        public Gun gun(Kind k) {
            if (k == Kind.PISTOL) return pistol;
            return k != null && k == primary ? gun : null;
        }

        /** 소모품 칸의 수(수류탄은 grenades) */
        public int items() {
            if (item == null) return 0;
            return item.equals("grenade") ? grenades : itemCount;
        }

        /** 지금 배율 */
        public GunMods mods() {
            return mods;
        }

        /** trip_aug: 이어 쏜 발 수(쉬면 0 으로) */
        public int streak() {
            return burst;
        }

        /** trip_aug: 종류 배율 도장(GunHooks.modsStamp) */
        long kindStamp = Long.MIN_VALUE;
    }

    private static final NamespacedKey RANGE_ENTITY = new NamespacedKey(Props.NS, "gun_entity_range");
    private final GunHost host;
    private final Random rnd = new Random();
    private final Map<UUID, Arms> arms = new HashMap<>();
    private final Map<UUID, Long> whizAt = new HashMap<>();
    /** Grenades 가 만들어질 때 이어 준다(시험 훅, 연출). 없으면 연출은 스스로 만든 것을 쓴다 */
    private Grenades grenades;
    private ShotFx fx;
    private boolean ownFx;
    /** tick() 이 마지막으로 돈 서버 틱, 그 뒤 한 번 정리했는지 */
    private long tickedAt = -1000;
    private boolean settled = true;
    /** 시험: 이 판 틱까지 tick() 을 건너뜀(판 끝처럼 Game 이 tick() 을 안 부르는 때를 흉내) */
    private long pauseUntil = -1;
    /** 증강, 특성, 아군 사격 연결(Augments 가 생성자에서 단다) */
    public GunHooks hooks = GunHooks.NONE;
    /** trip_aug: 지금 맞힘의 문맥(hooks.hitMul, onHit 가 읽음): 한 줄기에서 몇 번째로 맞았나(0 부터), 이번 맞힘이 치명이었나 */
    public int hitIndex;
    public boolean critNow;
    /** 판 중에 나간 사람의 무기 상태(같은 판에 돌아오면 equip 이 되살림, clearAll 이 비움) */
    private final Map<UUID, Arms> parked = new HashMap<>();

    /**
     * 탄이 동료 몸 상자에 닿았을 때 종이 먼저 받는 갈고리. from 에서 dir(단위)로 dist 만큼 가서 victim 몸 상자 겉면에 닿음.
     * true 면 그 탄은 거기서 멈추고(종이 처리) 아군 알림은 없다. 예: 감염자 등 막대기를 겨눠 쏘기(막대기만 맞음)
     */
    @FunctionalInterface
    public interface AllyShot {
        boolean take(Player shooter, Player victim, Vector from, Vector dir, double dist, Spec spec);
    }

    private final List<AllyShot> allyShots = new ArrayList<>();

    /** 아군 몸에 닿은 탄 갈고리를 단다(종 담당, 한 번만 달 것. 판이 끝나도 남음) */
    public void addAllyShot(AllyShot f) {
        if (f != null && !allyShots.contains(f)) allyShots.add(f);
    }

    public void removeAllyShot(AllyShot f) {
        allyShots.remove(f);
    }

    /** 화염탄 불붙음 하나(대상마다 하나, 다시 맞으면 시간만 새로) */
    private static final class Burn {
        Player by;
        Gun gun;
        double dps;
        long until, next;
    }

    private final Map<Object, Burn> burns = new HashMap<>();
    /** 불붙음 틱이 도는 동안 새로 붙은 불(증강 '기름'이 처치 알림 안에서 옆 대상에 붙임): 돌고 난 뒤 burns 에 합친다 */
    private final Map<Object, Burn> burnsNew = new HashMap<>();
    private boolean burnLoop;
    /** 폭발탄 연출을 사람마다 3 틱에 한 번만(피해는 늘) */
    private final Map<UUID, Long> popAt = new HashMap<>();
    /**
     * 남의 총소리, 탄착 소리를 솎는 몫(0..1). 한 사람이 틱마다 듣는 남의 총소리가 평균 HEARD 개를 넘지 않게 판 전체 사격 양(틱마다 발 수의
     * 흐르는 평균)으로 정한다: 서너 명이 쏠 때는 1(다 들림), 서른 명이 함께 연사하면 0.2 안팎. 자기 총소리와 맞힘 소리는 솎지 않는다.
     * 솎지 않으면 30명 판에서 클라마다 초당 수백 개의 소리가 겹쳐 소리 자리(247)가 차고 자기 총소리까지 끊긴다
     */
    private static final double HEARD = 2.5;
    private double shotRate, othersHear = 1;
    /** 이번 틱의 서 있는 사람, 쓰러진 동료(아군 탄 검사가 탄 줄기마다 목록을 새로 만들지 않게 틱 맨 앞에서 한 번) */
    private List<Player> mates = List.of();
    private java.util.Collection<Player> downed = List.of();
    private int shotsThisTick;
    /** 시험: 총소리를 실제로 보낸 시각(나노초, 돌림) */
    private final long[] soundLog = new long[16];
    private int soundLogAt;
    /** 총 틱이 도는 시각의 고른 눈금(나노초): 틱마다 50ms 씩 가되 실제 시각 쪽으로 조금씩 당김(틱이 들쭉날쭉 시작해도 소리 박자는 고르게) */
    private long grid;
    private final SoundClock clock;

    /**
     * 틱 안 시각에 맞춰 소리를 보내는 스레드. 가장 이른 것의 때가 될 때까지 1ms 안쪽으로 끊어 잔다(Paper 비동기 스케줄러와 자바 예약 실행기는
     * Windows 에서 기다림이 15ms 쯤으로 거칠어 박자가 흔들렸다). 하는 일은 미리 만든 소리 패킷 보내기뿐이라 세계를 건드리지 않는다.
     * 보낼 것이 없으면 2초마다 깨어 플러그인이 꺼졌는지 보고 꺼졌으면 끝난다(플러그인을 다시 읽어도 스레드가 남지 않게)
     */
    private static final class SoundClock extends Thread {
        private record Timed(long at, Runnable run) implements Comparable<Timed> {
            @Override
            public int compareTo(Timed o) {
                return Long.compare(at, o.at);
            }
        }

        private final java.util.concurrent.PriorityBlockingQueue<Timed> queue = new java.util.concurrent.PriorityBlockingQueue<>();
        private final org.bukkit.plugin.Plugin plugin;

        SoundClock(org.bukkit.plugin.Plugin plugin) {
            super("Gave-gun-sound");
            this.plugin = plugin;
            setDaemon(true);
            start();
        }

        void at(long nano, Runnable run) {
            queue.add(new Timed(nano, run));
        }

        @Override
        public void run() {
            while (true) {
                try {
                    Timed t = queue.poll(2, java.util.concurrent.TimeUnit.SECONDS);
                    if (t == null) {
                        if (!plugin.isEnabled()) return;
                        continue;
                    }
                    long wait = t.at - System.nanoTime();
                    if (wait > 150_000) {
                        // 아직 멀었다: 도로 넣고(그사이 더 이른 것이 들어올 수 있음) 잠깐 잠
                        queue.add(t);
                        Thread.sleep(0, (int) Math.min(wait, 900_000));
                        continue;
                    }
                    t.run.run();
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    // 소리 하나를 건너뜀
                }
            }
        }
    }
    /** 서 있는 동료가 아군 탄에 사격이 끊기는 것은 사람마다 ALLY_GAP 틱에 한 번(등 뒤에서 연사해도 앞사람이 계속 못 쏘지 않게) */
    private static final int ALLY_GAP = 20;
    private final Map<UUID, Long> allyHitAt = new HashMap<>();

    /** 한 발(산탄 알 모두)의 특성 기록: 폭발탄, 연쇄는 한 발에 한 번, 아군 사격 알림도 한 사람에 한 번 */
    private static final class Shot {
        boolean boom, chain;
        final Set<Player> allies = new HashSet<>();
    }

    /** 총 말고 다른 쏘는 방식(불길, 냉기, 번개, 레일 광선, 유탄, 로켓)과 날아가는 탄, 느려짐 */
    final Arsenal arsenal;

    public Guns(GunHost host) {
        this.host = host;
        this.arsenal = new Arsenal(this, host);
        this.clock = new SoundClock(host.plugin());
        // 대기실처럼 Game 이 tick() 을 부르지 않으면 재장전 자세, 탄창 뺀 모델, 쏠 때 시야 킥이 그대로 남는다: 한 번 정리
        Bukkit.getScheduler().runTaskTimer(host.plugin(), this::watch, 1, 1);
    }

    /** Grenades 생성자가 부른다: 시험 훅을 넘길 곳과 연출 */
    void link(Grenades g, ShotFx f) {
        this.grenades = g;
        if (f != null) {
            if (ownFx && fx != null) fx.clear();
            this.fx = f;
            this.ownFx = false;
        }
    }

    /** 연출(Grenades 가 이어 준 판의 것, 없으면 스스로 만들어 tick, clear 도 직접 함) */
    ShotFx fx() {
        if (fx == null) {
            fx = new ShotFx(host);
            ownFx = true;
        }
        return fx;
    }

    /** tick() 이 두 틱 넘게 안 돌았음(재장전 등이 나아가지 못함) */
    private boolean stale() {
        return Bukkit.getCurrentTick() - tickedAt >= 2;
    }

    private void watch() {
        ClientClock.tick(host.world());
        if (Bukkit.getCurrentTick() < lagUntil) {
            // 시험: 서버 틱을 일부러 늘림(TPS 가 떨어진 서버 흉내. 클라 시계는 그대로 초당 20틱을 감)
            try {
                Thread.sleep(lagMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ground();
        if (settled || !stale()) return;
        settled = true;
        settle();
    }

    /** 사람마다 마지막으로 시점 회전(반동, 흔들림)을 보낸 서버 틱 */
    private static final Map<UUID, Integer> kickedAt = new HashMap<>();

    /**
     * 바닐라 클라는 시점 회전 패킷마다 '땅 위 = 거짓'을 실은 회전 패킷으로 답한다(ClientPacketListener.handleRotatePlayer). 그 답이 닿으면 서버의
     * 땅 위 값이 클라의 다음 이동 패킷까지 꺼져서, 사격 중인 사람이 '땅에 있나'를 읽는 판정(땅 파도, 발 구르기, 공중 퍼짐)에서 공중으로 잡힌다
     * (재 보니 가만히 서서 쏜 발의 30%). 방금 회전을 받았고 지난 틱부터 위아래로 안 움직였고 발밑이 막힌 블록인 사람은 매 틱 맨 앞(판 틱보다
     * 먼저)에서 다시 땅 위로 돌려놓는다. 뛰는 중이면 발밑이 비어 있고 꼭대기에서도 틱마다 0.003 블록은 움직이므로 건드리지 않는다
     */
    private void ground() {
        int tick = Bukkit.getCurrentTick();
        for (Map.Entry<UUID, Arms> e : arms.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            Arms a = e.getValue();
            double y = p.getY();
            Integer at = kickedAt.get(e.getKey());
            if (at != null && tick - at <= lagTicks(p) + 3 && a.groundTick == tick - 1 && Math.abs(y - a.groundY) < 1e-4 && !p.isOnGround()
                && p.getVehicle() == null && !p.getLocation().subtract(0, 0.01, 0).getBlock().isPassable()) {
                ((CraftPlayer) p).getHandle().setOnGround(true);
                groundFixes++;
            }
            a.groundY = y;
            a.groundTick = tick;
        }
    }

    /** 시험: 땅 위 값을 바로잡은 수(누적) */
    private long groundFixes;
    /** 시험: 이 서버 틱까지 틱마다 lagMs 를 잠(틱재우기) */
    private int lagUntil = -1, lagMs;

    /**
     * 손 총을 쉬는 모습으로: 재장전, 쏜 뒤 동작, 반동 되돌림을 끝내고 손 총 도장(몸짓, 탄창 빠짐, 불꽃)을 지우고 시야 배율을 원래대로.
     * tick() 이 멈추면 다음 틱에 저절로 부른다. Game.gameOver 에서 바로 불러도 된다(여러 번 불러도 같음)
     */
    public void settle() {
        long now = host.now();
        // 땅 위 바로잡기의 '최근 흔들림' 기록도 판과 함께 끝(나간 사람은 drop 이 지움)
        kickedAt.clear();
        for (Map.Entry<UUID, Arms> e : arms.entrySet()) {
            Arms a = e.getValue();
            a.reloading = null;
            a.reloadUntil = -1;
            a.magOut = false;
            a.fireAfterPump = false;
            a.poseEndKind = null;
            a.cycleKind = null;
            a.simUntil = a.tapUntil = -1;
            a.debugFlashUntil = a.debugMagUntil = a.debugKickUntil = -1;
            a.flashAt = a.drawAt = -99999;
            calm(a);
            a.fovUntil = -1;
            a.spin = 0;
            a.burstLeft = 0;
            a.chargeAt = -1;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            fov(p, a, 1f);
            visual(p, a, now);
        }
    }

    public Arms arms(Player p) {
        return p == null ? null : arms.get(p.getUniqueId());
    }

    /**
     * 나감(Gave.onQuit): 시야 걷기 속도를 되돌리고 무기 상태를 뺀다. 판 중(host.running())이면 버리지 않고 맡아 두어 같은 판에 다시 들어와
     * equip 을 부르면 굴린 총, 강화, 탄, 소모품 칸이 그대로 돌아온다
     */
    public void forget(UUID id) {
        Arms a = drop(id);
        if (a != null && host.running()) {
            quiet(a);
            parked.put(id, a);
        }
    }

    /** 판 중에 나가서 맡아 둔 무기 상태가 있나 */
    public boolean parked(UUID id) {
        return parked.containsKey(id);
    }

    private Arms drop(UUID id) {
        Player p = Bukkit.getPlayer(id);
        // 칸 옮김 중이면 아이템을 원래 칸으로(나갈 때 저장되는 인벤토리, 대기실 인벤토리가 원래대로)
        if (p != null) unstow(p);
        aside.remove(id);
        Arms a = arms.remove(id);
        whizAt.remove(id);
        kickedAt.remove(id);
        if (a != null && p != null && a.sentWalk >= 0) sendWalk(p, p.getWalkSpeed() / 2f);
        return a;
    }

    /** 맡아 두거나 되살릴 때: 재장전, 쏜 뒤 동작, 반동, 시야, 손 총 도장 같은 지나가는 상태를 지움(탄, 총, 소모품은 그대로) */
    private static void quiet(Arms a) {
        a.reloading = null;
        a.reloadUntil = -1;
        a.magOut = false;
        a.fireAfterPump = false;
        a.poseEndKind = null;
        a.cycleKind = null;
        a.simUntil = a.tapUntil = a.probeAt = -1;
        a.confirmedAt = -99;
        a.holdNoFire = false;
        a.dryClicked = false;
        a.debugFlashUntil = a.debugMagUntil = a.debugKickUntil = -1;
        a.flashAt = a.drawAt = -99999;
        calm(a);
        a.bloom = 0;
        a.fovUntil = -1;
        a.fovMul = 1;
        a.sentWalk = -1;
        a.sentStamp[0] = a.sentStamp[1] = -1;
        a.sentMag[0] = a.sentMag[1] = false;
        a.gunStamp = Long.MIN_VALUE;
        a.spin = 0;
        a.burstLeft = 0;
        a.chargeAt = -1;
        a.chargeKind = null;
    }

    public void clearAll() {
        for (UUID id : new ArrayList<>(arms.keySet())) drop(id);
        for (UUID id : new ArrayList<>(aside.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) unstow(p);
        }
        aside.clear();
        arms.clear();
        parked.clear();
        whizAt.clear();
        kickedAt.clear();
        burns.clear();
        burnsNew.clear();
        popAt.clear();
        allyHitAt.clear();
        arsenal.clear();
        if (ownFx && fx != null) fx.clear();
    }

    private long worldTime() {
        return host.worldTime();
    }

    // ------------------------------------------------------------------ 배율

    /** host.mods(p) 나 굴린 총(바꿈, 강화)이 바뀌었으면 이 사람의 총(eff)을 다시 만든다 */
    private void refreshMods(Player p, Arms a) {
        GunMods m = host.mods(p);
        if (m == null) m = GunMods.NONE;
        long stamp = (a.gun == null ? 0 : a.gun.id * 1000003L + a.gun.ver()) * 31 + a.pistol.id * 7919 + a.pistol.ver();
        long ks = hooks.modsStamp(p);
        if (m.equals(a.mods) && stamp == a.gunStamp && ks == a.kindStamp) return;
        a.mods = m;
        a.gunStamp = stamp;
        a.kindStamp = ks;
        for (Kind k : Kind.values()) {
            Gun g = a.gun(k);
            // trip_aug: 종류마다 증강 배율이 다를 수 있음
            GunMods mk = hooks.kindMods(p, k, m);
            if (mk == null) mk = m;
            a.eff[k.ordinal()] = effective(k, g, mk);
            a.crit[k.ordinal()] = Math.min(1, mk.crit() + (g == null ? 0 : g.mods().crit()) + baseCrit(k));
        }
    }

    /**
     * 유효 총 = 바탕 Spec x 모델 배율 x 총 배율(등급, 강화) x 사람 배율. 배율을 먼저 다 곱한 뒤 한 번만 반올림한다. 산탄 알 더하기는
     * 산탄총(바탕 알 2 이상)에만 건다(소총이 세 알 쏘지 않게). 모델마다 표의 배율(피해, 연사, 탄창, 재장전 ...)이 그대로 걸려 화력이 다르고,
     * 같은 모델 같은 등급 같은 증강이면 누구 손에서나 같다.
     * 반올림 갚기: 한 발 피해를 그 총 자신의 반올림 전 지속 화력({@link #smoothSustained})에 맞춘다. 탄창 1~2 총은 등급, 증강의 탄창 배율이
     * 반올림으로 한 발을 통째로 늘리거나 없애는데(쌍발 블런더버스 2 x 0.66 = 1.32 는 한 발, 영웅 x 1.2 = 1.58 은 두 발), 화력은 배율만큼만 움직인다
     */
    public static Spec effective(Kind k, Gun g, GunMods m) {
        Spec base = SPECS.get(k);
        Models.Model md = g == null ? null : Models.of(g.model);
        GunMods all = m == null ? GunMods.NONE : m;
        if (g != null) all = g.mods().times(all);
        if (md != null) all = md.mods().times(all);
        // 산탄 알 더하기는 산탄류(바탕 알 2 이상)에만: 쌍권총의 두 발은 알이 아니라 양손이라 더하지 않음
        if ((base.pellets() <= 1 || k == Kind.DUAL) && all.pellets() != 0) all = all.withPellets(0);
        Spec s = base.with(all);
        double want = smoothSustained(base, all), got = sustained(s);
        if (want > 0 && got > 0 && Math.abs(want - got) > 1e-9 * got) s = s.with(GunMods.NONE.mulDamage(want / got));
        return md == null ? s : s.named(md.key(), md.sound());
    }

    /** 이 사람이 그 총으로 쏠 때 치명 확률(Gun 특성 + 사람 배율) */
    public double crit(Player p, Kind k) {
        Arms a = arms(p);
        return a == null || k == null ? 0 : a.crit[k.ordinal()];
    }

    /** 굴린 총이 바뀌었으면 지금 다시 만든다(강화 직후 HUD, 상점이 새 값을 읽게) */
    public void refresh(Player p) {
        Arms a = arms(p);
        if (a != null) refreshMods(p, a);
    }

    /** 이 사람의 배율을 건 총(배율은 지난 틱에 읽은 것) */
    public Spec spec(Player p, Kind k) {
        Arms a = arms(p);
        return a == null ? SPECS.get(k) : a.spec(k);
    }

    // ------------------------------------------------------------------ 지급

    /**
     * 판에 들 때: 무기 상태를 새로 만들고 인벤토리를 비운 뒤 권총(칸 1)을 쥐여 준다. 소모품은 이 뒤에 칸 2 에 넣을 것.
     * 같은 판에 나갔다 돌아온 사람(맡아 둔 상태가 있고 판이 돌고 있음)이면 그 상태를 되살려 총 칸을 다시 만든다(소모품 칸은 Supplies 가 맞춤)
     */
    public void equip(Player p) {
        // 인벤토리를 새로 만드니 칸 옮김(갈림길 투표)도 끝
        aside.remove(p.getUniqueId());
        Arms old = arms.get(p.getUniqueId());
        if (old != null && old.sentWalk >= 0) sendWalk(p, p.getWalkSpeed() / 2f);
        Arms back = parked.remove(p.getUniqueId());
        if (back != null && host.running()) {
            restore(p, back);
            return;
        }
        Arms a = new Arms();
        arms.put(p.getUniqueId(), a);
        refreshMods(p, a);
        a.mag[Kind.PISTOL.ordinal()] = a.spec(Kind.PISTOL).mag();
        p.getInventory().clear();
        p.getInventory().setItem(SLOT_PISTOL, item(SPECS.get(Kind.PISTOL)));
        p.getInventory().setHeldItemSlot(SLOT_PISTOL);
        a.drawAt = worldTime();
    }

    /** 맡아 둔 상태를 되살림: 인벤토리를 비우고 권총, 주무기 칸을 다시 만들어 주무기(없으면 권총)를 듦 */
    private void restore(Player p, Arms a) {
        quiet(a);
        aside.remove(p.getUniqueId());
        arms.put(p.getUniqueId(), a);
        refreshMods(p, a);
        p.getInventory().clear();
        p.getInventory().setItem(SLOT_PISTOL, item(SPECS.get(Kind.PISTOL)));
        if (a.primary != null) p.getInventory().setItem(SLOT_PRIMARY, item(a.spec(a.primary)));
        Kind k = a.primary != null ? a.primary : Kind.PISTOL;
        p.getInventory().setHeldItemSlot(a.primary != null ? SLOT_PRIMARY : SLOT_PISTOL);
        a.drawUntil = host.now() + a.spec(k).draw();
        a.drawAt = worldTime();
    }

    /** 주무기(칸 0)를 k 로(굴리지 않은 기본 총): 탄창, 예비탄 가득, 바로 꺼내 듦. 권총은 칸 1 에 늘 있어 무시 */
    public void givePrimary(Player p, Kind k) {
        if (k == null || k == Kind.PISTOL) return;
        givePrimary(p, Gun.basic(k));
    }

    /** 주무기(칸 0)를 굴린 총 g 로: 탄창, 예비탄 가득, 바로 꺼내 듦(전에 든 주무기는 버림). 권총 종류면 무시 */
    public void givePrimary(Player p, Gun g) {
        Arms a = arms(p);
        if (a == null || g == null || g.kind == Kind.PISTOL) return;
        Kind k = g.kind;
        a.gun = g;
        a.primary = k;
        refreshMods(p, a);
        Spec s = a.spec(k);
        a.mag[k.ordinal()] = s.mag();
        a.res[k.ordinal()] = s.reserve();
        cancelReload(a);
        a.cycleKind = null;
        p.getInventory().setItem(at(p, SLOT_PRIMARY), item(s));
        a.sentStamp[0] = -1;
        a.sentMag[0] = false;
        hold(p, SLOT_PRIMARY);
        a.drawUntil = host.now() + s.draw();
        a.drawAt = worldTime();
        a.next[k.ordinal()] = host.now();
        a.cycleOwed[k.ordinal()] = false;
        Props.soundAround(p.getLocation(), partEvent(s, "gun." + s.key() + ".draw"), Sound.ITEM_ARMOR_EQUIP_IRON, 0.8f, 1f, 1f, 12, -1);
    }

    /** 탄약: 가진 주무기 예비탄 가득. 채운 게 있으면 true */
    public boolean refill(Player p) {
        Arms a = arms(p);
        if (a == null || a.primary == null) return false;
        Spec s = a.spec(a.primary);
        if (a.res[a.primary.ordinal()] >= s.reserve()) return false;
        a.res[a.primary.ordinal()] = s.reserve();
        return true;
    }

    /** 이미 든 주무기 k 를 다시 삼: 탄창과 예비탄 가득. 채운 게 있으면 true */
    public boolean refillFull(Player p, Kind k) {
        Arms a = arms(p);
        if (a == null || a.primary != k) return false;
        Spec s = a.spec(k);
        int i = k.ordinal();
        if (a.mag[i] >= s.mag() && a.res[i] >= s.reserve()) return false;
        a.mag[i] = s.mag();
        a.res[i] = s.reserve();
        if (a.reloading == k) cancelReload(a);
        return true;
    }

    /**
     * 탄약 채우기(상점, 진짜 보급 상자): 가진 모든 총(주무기와 권총)의 장전된 탄창과 예비탄을 가득(2026-11-12 사용자 '총알 사면 장전된 것도 꽉').
     * 그 총을 재장전하던 중이면 재장전을 끝냄(이미 가득). 권총 예비탄은 무한이라 탄창만. 채운 게 있으면 true
     */
    public boolean refillAll(Player p) {
        Arms a = arms(p);
        if (a == null) return false;
        refreshMods(p, a);
        boolean any = false;
        for (Kind k : Kind.values()) {
            if (k != Kind.PISTOL && k != a.primary) continue;
            Spec s = a.spec(k);
            int i = k.ordinal();
            if (a.mag[i] < s.mag()) {
                a.mag[i] = s.mag();
                if (a.reloading == k) cancelReload(a);
                any = true;
            }
            if (s.reserve() >= 0 && a.res[i] < s.reserve()) {
                a.res[i] = s.reserve();
                any = true;
            }
        }
        return any;
    }

    /** 손에 든 굴린 총(칸 0 주무기, 칸 1 권총, 칸 2 를 들었으면 주무기, 주무기가 없으면 권총). 판에 없으면 null */
    public Gun gun(Player p) {
        Arms a = arms(p);
        if (a == null) return null;
        int slot = slot(p);
        if (slot == SLOT_PISTOL || a.gun == null) return a.pistol;
        return a.gun;
    }

    /** 주무기 굴린 총(없으면 null) */
    public Gun primaryGun(Player p) {
        Arms a = arms(p);
        return a == null ? null : a.gun;
    }

    /** 권총(굴리지 않음, 강화 단계만) */
    public Gun pistolGun(Player p) {
        Arms a = arms(p);
        return a == null ? null : a.pistol;
    }

    /**
     * 강화 한 단계(+8% 피해): pistol 이면 권총, 아니면 주무기. 새 단계, 못 하면 -1(총 없음, 최대). 탄창 수는 그대로(피해만 오름)
     */
    public int upgrade(Player p, boolean pistol) {
        Arms a = arms(p);
        if (a == null) return -1;
        Gun g = pistol ? a.pistol : a.gun;
        if (g == null || !g.upgrade()) return -1;
        refreshMods(p, a);
        return g.level();
    }

    /** 주무기를 빼앗음(총 주운 게이브): 칸 0 을 비우고 그 총을 돌려줌(없으면 null). 돌려줄 때는 givePrimary(p, gun) */
    public Gun takePrimary(Player p) {
        Arms a = arms(p);
        if (a == null || a.gun == null) return null;
        Gun g = a.gun;
        if (a.reloading == a.primary) cancelReload(a);
        a.gun = null;
        a.primary = null;
        p.getInventory().setItem(at(p, SLOT_PRIMARY), null);
        if (slot(p) == SLOT_PRIMARY) hold(p, SLOT_PISTOL);
        refreshMods(p, a);
        return g;
    }

    /** 총 아이템. 이름은 비워 둠: 총을 바꿀 때 바닐라가 핫바 위에 띄우는 이름 글자가 없게(무엇을 들었는지는 HUD 총 그림이 보임) */
    public static ItemStack item(Spec s) {
        ItemStack it = Props.vanillaLook(Material.STICK, s.vanillaModel(), s.model(), Component.empty());
        // 소모품 컴포넌트는 주지 않는다(머리 주석 '누르고 있기'): 사용 중은 onInteract 가 서버에서 켠다. use_effects 는 사용 중 걷기 빠르기
        it.setData(DataComponentTypes.USE_EFFECTS, io.papermc.paper.datacomponent.item.UseEffects.useEffects().canSprint(true).speedMultiplier(useSpeed(s.kind()))
            .interactVibrations(false).build());
        it.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes()
            .addModifier(Attribute.ENTITY_INTERACTION_RANGE, new AttributeModifier(RANGE_ENTITY, -3, AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND)).build());
        it.setData(DataComponentTypes.ATTACK_RANGE, io.papermc.paper.datacomponent.item.AttackRange.attackRange()
            .minReach(64).maxReach(64).minCreativeReach(64).maxCreativeReach(64).hitboxMargin(0).build());
        it.setData(DataComponentTypes.INTERACT_ANIMATION, SwingAnimation.swingAnimation().type(SwingAnimation.Animation.NONE).build());
        return it;
    }

    /** 손에 든 총(배율을 건 값). 칸 2(소모품)나 빈 주무기 칸이면 null */
    public Spec held(Player p) {
        Arms a = arms(p);
        if (a == null) return null;
        int slot = slot(p);
        if (slot == SLOT_PRIMARY && a.primary != null) return a.spec(a.primary);
        if (slot == SLOT_PISTOL) return a.spec(Kind.PISTOL);
        return null;
    }

    // ------------------------------------------------------------------ 칸 옮김(원정 갈림길 숫자키 투표)

    /** 옮겨 둔 칸: Guns 가 보는 원래 칸(0, 1), 아이템이 실제로 있는 칸(3..8), 옮길 때 그 칸에 다른 아이템이 있어 맞바꿨나 */
    private static final class Aside {
        int logical;
        final int phys;
        final boolean swapped;

        Aside(int logical, int phys, boolean swapped) {
            this.logical = logical;
            this.phys = phys;
            this.swapped = swapped;
        }
    }

    private final Map<UUID, Aside> aside = new HashMap<>();
    /** 시험: 되돌릴 때 원래 칸에 뜻밖의 아이템이 있던 수(맞바꿔 잃지 않음) */
    public int asideClash;

    /** Guns 가 보는 든 칸: 옮겨 둔 칸을 들었으면 원래 칸 */
    public int slot(Player p) {
        int h = p.getInventory().getHeldItemSlot();
        Aside s = aside.get(p.getUniqueId());
        return s != null && h == s.phys ? s.logical : h;
    }

    /** 칸 logical(0..2)의 아이템이 실제로 놓인 인벤토리 칸 */
    private int at(Player p, int logical) {
        Aside s = aside.get(p.getUniqueId());
        return s != null && s.logical == logical ? s.phys : logical;
    }

    /** 옮겨 둔 실제 칸(없으면 -1) */
    public int aside(Player p) {
        Aside s = aside.get(p.getUniqueId());
        return s == null ? -1 : s.phys;
    }

    /**
     * 칸 logical 을 든다. 칸 옮김 중이면 옮겨 둔 아이템을 원래 칸으로 돌리고 새 칸(총)의 아이템을 옮겨 둔 칸에 넣어 그 칸을 그대로 든다
     * (손에는 새 총, 숫자키 1 2 3 은 계속 칸 바꿈을 보냄). 소모품 칸은 옮기지 않는다(Supplies 가 칸 2 를 맞추므로): 되돌리고 칸 2 를 듦
     */
    private void hold(Player p, int logical) {
        Aside s = aside.get(p.getUniqueId());
        if (s == null || logical > SLOT_PISTOL) {
            unstow(p);
            p.getInventory().setHeldItemSlot(logical);
            return;
        }
        if (s.logical != logical) {
            swap(p, s.logical, s.phys);
            s.logical = logical;
            swap(p, s.logical, s.phys);
        }
        p.getInventory().setHeldItemSlot(s.phys);
    }

    /** 두 칸의 아이템을 맞바꾼다(만들거나 지우지 않음) */
    private static void swap(Player p, int i, int j) {
        PlayerInventory inv = p.getInventory();
        ItemStack x = inv.getItem(i), y = inv.getItem(j);
        x = x == null ? null : x.clone();
        y = y == null ? null : y.clone();
        inv.setItem(i, y);
        inv.setItem(j, x);
    }

    /**
     * 칸 옮김(원정 갈림길 숫자키 투표, RouteVote): 지금 든 칸의 번호 키는 클라가 칸 바꿈을 안 보내서 그 갈래를 못 고르므로, 든 총(칸 0, 1)의 아이템을
     * 빈 칸(인덱스 3..8 가운데 앞쪽, 없으면 8 과 맞바꿈)으로 옮기고 그 칸을 든다. 손에는 같은 총 아이템이 그대로이고 Guns 는 그 칸을 원래 칸으로 본다
     * (쏘기, 재장전, 탄, 강화, 등급, 손 총 도장이 그대로). 소모품 칸(2)이나 다른 칸을 들었으면 주무기(없거나 잠겼으면 권총)를 꺼내 옮긴다.
     * 이미 옮겨 두었고 그 칸을 들고 있으면 그대로. 옮겨 둔 칸, 못 하면 -1. 되돌리기는 {@link #unstow}
     */
    public int stow(Player p) {
        Arms a = arms(p);
        if (a == null) return -1;
        PlayerInventory inv = p.getInventory();
        Aside cur = aside.get(p.getUniqueId());
        if (cur != null) {
            if (inv.getHeldItemSlot() == cur.phys) return cur.phys;
            unstow(p);
        }
        int h = inv.getHeldItemSlot(), logical = h;
        if (logical > SLOT_PISTOL) logical = a.primary != null && a.primaryLockUntil <= host.now() ? SLOT_PRIMARY : SLOT_PISTOL;
        int phys = 8;
        for (int i = SLOT_ITEM + 1; i <= 8; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.isEmpty()) {
                phys = i;
                break;
            }
        }
        ItemStack there = inv.getItem(phys);
        aside.put(p.getUniqueId(), new Aside(logical, phys, there != null && !there.isEmpty()));
        swap(p, logical, phys);
        inv.setHeldItemSlot(phys);
        if (logical != h) switched(p, a, logical);
        return phys;
    }

    /**
     * 칸 옮김을 되돌린다: 옮겨 둔 아이템을 원래 칸으로, 옮겨 둔 칸을 들고 있었으면 원래 칸을 든다(다른 칸을 들었으면 그대로).
     * 원래 칸에 뜻밖의 아이템이 있으면 맞바꿔서 아무것도 잃거나 늘지 않게 하고 asideClash 를 센다. 옮긴 적이 없으면 아무것도 안 함
     */
    public void unstow(Player p) {
        Aside s = aside.remove(p.getUniqueId());
        if (s == null) return;
        PlayerInventory inv = p.getInventory();
        ItemStack back = inv.getItem(s.logical);
        if (!s.swapped && back != null && !back.isEmpty()) asideClash++;
        swap(p, s.logical, s.phys);
        if (inv.getHeldItemSlot() == s.phys) inv.setHeldItemSlot(s.logical);
    }

    // ------------------------------------------------------------------ 입력

    private static boolean usingHand(Player p) {
        return p.isHandRaised() && p.getActiveItemHand() == EquipmentSlot.HAND;
    }

    private static int holdGap(Player p) {
        return Math.max(6, (int) Math.ceil(p.getPing() / 50.0) + 3);
    }

    /** 확인 신호를 보낸 뒤 다시 누름이 올 만한 틱(왕복 + 클라 한 틱 + 여유 한 틱) */
    private static int graceTicks(Player p) {
        return (int) Math.ceil(p.getPing() / 50.0) + 2;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Arms a = arms(p);
        if (a == null) return;
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        if (e.getHand() != EquipmentSlot.HAND) return;
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!right) return;
        long now = host.now();
        // 서버가 아직 '사용 중'이면 누른 채 다시 온 패킷: 상점 열기로 치지 않는다. 사용 중인 클라는 누름을 안 보내므로 이때 클라는 사용 중이
        // 아니다(확인 신호의 답, 사용 중이 클라에 닿기 전의 되풀이, 서버가 막은 칸 바꿈 뒤 클라만 사용을 끊은 것): 사용 중을 다시 보내 줘야
        // 클라가 뗄 때 뗌을 보낸다
        boolean fresh = !usingHand(p);
        if (fresh) {
            if (a.holdNoFire) return;
            // 총을 들었으면 서버가 '사용 중'을 켠다: 이 값이 클라에 닿으면 클라가 뗄 때 뗌을 보낸다(이미 뗀 뒤에 닿아도 바로 보냄).
            // 상점을 열었을 때도 켠다: 뗄 때까지 holdNoFire 가 남고 클라가 누른 채 다시 누름을 보내 상점을 또 열지 않게
            boolean used = host.tryUse(p);
            if (held(p) != null) p.startUsingItem(EquipmentSlot.HAND);
            if (used) {
                a.holdNoFire = true;
                return;
            }
        } else usingFlag(p, true);
        // 어떤 누름 패킷이든 지금 버튼이 눌려 있다는 뜻: 4 틱 동안 한 발(연사 박자가 발 수를 막음). 빠른 연타가 앞 누름의 뗌보다 먼저 와도 살아남
        a.tapUntil = now + 4;
        a.confirmedAt = now;
        a.probeAt = -1;
    }

    /**
     * 클라에만 '사용 중' 값을 보낸다. on = false 면 꺼서(서버 값은 그대로) 클라가 누른 채면 다시 누름을 보내게 하고(확인 신호),
     * on = true 면 서버 값 그대로 다시 보내 클라를 사용 중으로 되돌린다
     */
    private static void usingFlag(Player p, boolean on) {
        var h = ((CraftPlayer) p).getHandle();
        byte flags = h.getEntityData().get(LivingFlags.LIVING_FLAGS);
        h.connection.send(new ClientboundSetEntityDataPacket(h.getId(),
            List.of(SynchedEntityData.DataValue.create(LivingFlags.LIVING_FLAGS, on ? flags : (byte) (flags & ~1)))));
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        e.setCancelled(true);
        if (arms(p) != null) reload(p);
    }

    /** 칸 바꿈. 다른 갈고리가 이미 막은 바꿈(원정 갈림길 숫자키 투표)에는 재장전 끊기, 꺼내기 시간을 걸지 않는다(ignoreCancelled). */
    @EventHandler(ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        Arms a = arms(p);
        if (a == null) return;
        int ns = e.getNewSlot();
        if (ns > SLOT_ITEM || ns == SLOT_PRIMARY && a.primary == null || ns == SLOT_PRIMARY && a.primaryLockUntil > host.now()) {
            e.setCancelled(true);
            return;
        }
        int was = slot(p);
        // 칸 옮김 중에 막히지 않은 바꿈(투표 창 밖): 옮긴 아이템을 먼저 원래 칸으로 돌려 새 칸이 제 아이템을 가리키게
        unstow(p);
        if (ns != was) switched(p, a, ns);
    }

    /**
     * 총 바꿈: 재장전 취소, 꺼내는 시간 동안 못 쏨. 연사 박자는 총마다 따로라 그대로 이어진다(바꿔 쏘기로 빨라지지 않음).
     * 쏜 뒤 펌프, 노리쇠를 다 못 하고 바꾼 총은 다음에 꺼낼 때 꺼내는 동안 한다. 칸 2(소모품)로 바꾸면 총 일은 쉰다
     */
    private void switched(Player p, Arms a, int slot) {
        cancelReload(a);
        a.spin = 0;
        a.burstLeft = 0;
        a.chargeAt = -1;
        if (a.cycleKind != null) a.cycleOwed[a.cycleKind.ordinal()] = true;
        a.cycleKind = null;
        Spec s = slot == SLOT_PRIMARY ? a.spec(a.primary) : slot == SLOT_PISTOL ? a.spec(Kind.PISTOL) : null;
        if (s == null) return;
        long now = host.now();
        a.drawUntil = now + s.draw();
        a.drawAt = worldTime();
        int k = s.kind().ordinal();
        if (a.cycleOwed[k]) {
            // 펌프(시작 뒤 4 틱), 노리쇠(1, 4 틱)가 다 꺼내기 전에 끝나게
            a.cycleOwed[k] = false;
            a.cycleKind = s.kind();
            a.cycleAt = now + Math.max(0, s.draw() - 6);
            a.cycleStep = 0;
        }
        Props.soundAround(p.getLocation(), partEvent(s, "gun." + s.key() + ".draw"), Sound.ITEM_ARMOR_EQUIP_IRON, 0.8f, 1f, 1f, 12, -1);
    }

    /**
     * 좌클릭: 돕기(일으키기, 떼기, 달래기, 상점, 붙잡혔을 때 탈출 연타, 죽은 사람의 '살려 달라' 신호, 표지판 투표). 무엇을 할지는 판(host)이 정한다.
     * 무기 상태가 없는 사람(웨이브 중에 들어온 사람, 죽은 채 다시 들어온 관전자)의 좌클릭도 넘긴다: 판에 든 사람인지, 무기가 필요한 일인지는
     * Players.help 와 그 뒤(상점은 무기 상태가 없으면 안 열림)가 거른다
     */
    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING || !host.running()) return;
        host.onHelp(e.getPlayer());
    }

    /** '쥠' 판정(scpsl Firearms.triggerHeld + 답 기다리는 동안은 핑 만큼만 쏨) */
    private boolean triggerHeld(Player p, Arms a, long tk) {
        if (tk < a.simUntil) return true;
        if (!usingHand(p) || p.getGameMode() == GameMode.SPECTATOR) {
            a.probeAt = -1;
            a.holdNoFire = false;
            return false;
        }
        if (a.holdNoFire || p.getOpenInventory().getType() != InventoryType.CRAFTING) return false;
        if (a.probeAt >= 0) {
            long waited = tk - a.probeAt;
            if (waited > holdGap(p) + 2) {
                a.probeAt = -1;
                p.clearActiveItem();
                return false;
            }
            return waited <= graceTicks(p);
        } else if (tk - a.confirmedAt >= 10) {
            usingFlag(p, false);
            a.probeAt = tk;
        }
        return true;
    }

    // ------------------------------------------------------------------ 틱

    public void tick() {
        long now = host.now();
        if (ownFx && fx != null) fx.tick();
        if (now < pauseUntil) return;
        tickedAt = Bukkit.getCurrentTick();
        settled = false;
        shotRate = shotRate * 0.9 + shotsThisTick * 0.1;
        shotsThisTick = 0;
        long real = System.nanoTime(), err = real - (grid + 50_000_000L);
        grid = Math.abs(err) > 20_000_000L ? real : grid + 50_000_000L + err / 10;
        othersHear = Math.min(1, HEARD / Math.max(HEARD, shotRate));
        mates = List.copyOf(host.shooters());
        downed = hooks.downedMates();
        burnTick(now);
        arsenal.tick(now);
        Set<UUID> done = new HashSet<>();
        for (Player p : mates) {
            Arms a = arms(p);
            if (a == null) continue;
            done.add(p.getUniqueId());
            refreshMods(p, a);
            Location l = p.getLocation();
            a.speed = a.speed * 0.6 + Math.hypot(l.getX() - a.lx, l.getZ() - a.lz) * 0.4;
            a.airborne = !p.isOnGround() && Math.abs(l.getY() - a.ly) > 1e-4;
            a.lx = l.getX();
            a.ly = l.getY();
            a.lz = l.getZ();
            if (now - a.lastShot > 2) a.bloom *= 0.82;
            recoilTick(p, a, now);
            // 주무기 잠금(WEAPONLOCK): 주무기를 들고 있으면 권총으로
            if (a.primaryLockUntil > now && slot(p) == SLOT_PRIMARY) {
                hold(p, SLOT_PISTOL);
                switched(p, a, SLOT_PISTOL);
            }
            Spec s = held(p);
            if (s != null) {
                cycleTick(p, a, now);
                if (a.reloading != null && a.reloading != s.kind()) cancelReload(a);
                if (a.reloading != null) reloadTick(p, a, s, now);
                boolean trigger = triggerHeld(p, a, now) || now <= a.tapUntil;
                // 점사: 한 번 당기면 남은 발을 다 쏜다(짧게 눌러도 세 발)
                if (!host.running() || host.blocked(p)) {
                    // 막힘(붙잡힘, 돕는 중, 아군 탄): 쏘기와 재장전 시작을 못 한다(하던 재장전은 이어짐). 점사 남은 발은 버려 풀린 뒤 저절로 나가지 않게
                    a.burstLeft = 0;
                    trigger = false;
                } else if (a.burstLeft > 0) trigger = true;
                if (now < a.drawUntil) trigger = false;
                if (!trigger) {
                    a.dryClicked = false;
                    arsenal.idle(p, a, s, now);
                } else pull(p, a, s, now);
            } else {
                a.spin = 0;
                a.burstLeft = 0;
                a.chargeAt = -1;
            }
            flushKick(p, a, now);
            visual(p, a, now);
        }
        // 쏠 수 없는 사람(쓰러짐, 죽음, 관전): 재장전, 반동, 시야 킥을 풀고 손 총을 쉬는 모습으로
        for (Map.Entry<UUID, Arms> e : arms.entrySet()) {
            if (done.contains(e.getKey())) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            rest(p, e.getValue(), now);
        }
    }

    private void rest(Player p, Arms a, long now) {
        if (a.reloading != null) cancelReload(a);
        a.spin = 0;
        a.burstLeft = 0;
        a.chargeAt = -1;
        a.simUntil = a.tapUntil = -1;
        calm(a);
        a.fovUntil = -1;
        if (a.sentWalk >= 0) fov(p, a, 1f);
        visual(p, a, now);
    }

    /** 방아쇠가 당겨진 틱: 연사 박자대로 쏨(한 틱 최대 3 발) */
    private void pull(Player p, Arms a, Spec s, long now) {
        int k = s.kind().ordinal();
        if (a.reloading != null) {
            // 산탄총은 한 발이라도 들었으면 넣다가 쏠 수 있다. 빈 총에서 시작했으면 먼저 펌프하고(이미 끝 펌프 중이면 그대로),
            // 펌프가 끝나는 틱에 이 당김을 쏜다(탭은 4 틱만 살아 펌프 8 틱보다 짧아서 따로 기억)
            if (!s.shells() || a.mag[k] <= 0) return;
            if (!a.reloadEmpty) cancelReload(a);
            else {
                if (a.shellPhase != 2) startPump(p, a, s, now);
                a.fireAfterPump = true;
            }
            if (a.reloading != null) return;
        }
        Mode md = mode(s.kind());
        // 미니건: 누르는 동안 총열이 돌아 빨라지고, md.effect 만큼 돌기 전에는 안 나감
        if (md.fire() == Fire.SPIN) {
            a.spin = Math.min(1, a.spin + 1.0 / Math.max(1, md.ticks()));
            arsenal.spinSound(p, a, now);
            if (a.spin < md.effect()) {
                a.next[k] = Math.max(a.next[k], now);
                return;
            }
        }
        // 레일건: 누르면 바로 모으기 시작(쏜 뒤 쉼과 겹침), md.ticks 를 다 모았고 쏠 박자도 됐을 때 나감(떼면 Arsenal.idle 이 풀어 다시 모음)
        if (md.fire() == Fire.CHARGE && a.mag[k] <= 0 && a.res[k] <= 0 && s.reserve() >= 0 && a.next[k] <= now + 1e-9 && hooks.dryFire(p, s))
            a.mag[k] = 1;
        if (md.fire() == Fire.CHARGE && a.mag[k] > 0) {
            if (a.chargeAt < 0 || a.chargeKind != s.kind()) {
                a.chargeAt = now;
                a.chargeKind = s.kind();
                arsenal.chargeStart(p, s);
            }
            double end = chargeEnd(p, a, s);
            if (end >= now + 1 - 1e-9) return;
            a.next[k] = Math.max(now, end);
        }
        // 쉬었으면 지금부터, 아니면 소수 박자를 이어 감(초당 13 발 = 1.54 틱마다). 이 틱 안(지금부터 한 틱)에 박자가 오는 발을 지금 쏘고
        // 총소리만 그 시각(틱의 몫)에 맞춰 울린다: 판정은 틱마다지만 소리 박자는 틱에 묶이지 않고 고르다
        if (a.next[k] < now) a.next[k] = now;
        int fired = 0;
        while (a.next[k] < now + 1 - 1e-9 && fired < 3) {
            // trip_aug: 탄창도 예비탄도 없으면 증강이 한 발을 대신 낼 수 있음(피로 사는 탄)
            if (a.mag[k] <= 0 && a.res[k] <= 0 && s.reserve() >= 0 && hooks.dryFire(p, s)) a.mag[k] = 1;
            if (a.mag[k] <= 0) {
                a.burstLeft = 0;
                a.chargeAt = -1;
                boolean first = !a.dryClicked;
                if (first) Props.soundAround(p.getLocation(), partEvent(s, "gun." + s.key() + ".dry"), Sound.BLOCK_DISPENSER_FAIL, 0.8f, 1f, 1f, 12, -1);
                a.dryClicked = true;
                a.next[k] = now + 6;
                a.tapUntil = -1;
                // 주무기 탄창도 예비탄도 비었으면 권총(무한)으로 저절로 바꿈(처음 하는 사람이 2 를 몰라 그대로 쓰러지지 않게)
                if (first && s.kind() != Kind.PISTOL && a.res[k] <= 0 && slot(p) == SLOT_PRIMARY) {
                    hold(p, SLOT_PISTOL);
                    switched(p, a, SLOT_PISTOL);
                    break;
                }
                reload(p);
                break;
            }
            // 엉킴 헛방(JAM): 철컥 소리만, 이 박자는 날아감(탄은 안 씀)
            if (a.jamUntil > now && a.jamMisfire > 0 && rnd.nextDouble() < a.jamMisfire) {
                Props.soundAround(p.getLocation(), partEvent(s, "gun." + s.key() + ".dry"), Sound.BLOCK_DISPENSER_FAIL, 0.8f, 1f, 1.3f, 12, -1);
                a.next[k] += 20.0 / Math.max(0.1, s.rps()) + 4;
                a.tapUntil = -1;
                a.burstLeft = 0;
                jamMisfires++;
                break;
            }
            s = fire(p, a, s, a.next[k] - now);
            fired++;
            a.tapUntil = -1;
            // 미니건은 돈 만큼(20% .. 100%) 빠르게
            a.next[k] += 20.0 / (md.fire() == Fire.SPIN ? s.rps() * (0.2 + 0.8 * a.spin) : s.rps());
            if (md.fire() == Fire.BURST) {
                // 점사 첫 발이면 남은 발을 세고, 마지막 발 뒤에는 md.ticks 쉼
                a.burstLeft = a.burstLeft <= 0 ? hooks.burstCount(p, s.kind(), md.count()) - 1 : a.burstLeft - 1;
                if (a.burstLeft <= 0) {
                    a.burstLeft = 0;
                    a.next[k] += md.ticks();
                }
            }
            if (md.fire() == Fire.CHARGE) a.chargeAt = -1;
            host.onShot(p, s.key());
            // 마지막 발이 자동 재장전을 시작했으면 같은 틱에 빈 소리를 내지 않게 멈춤(연사 배율이 크면 한 틱 여러 발)
            if (a.reloading != null) {
                a.burstLeft = 0;
                break;
            }
            if (md.fire() == Fire.CHARGE) break;
        }
    }

    /**
     * 한 발. 쏠 때 배율을 다시 읽어 그 값으로 쏜다(돌려준 총이 이번 발에 쓴 값). late = 이 틱 안에서 이 발의 박자가 오는 때(0..1 틱):
     * 총소리를 그만큼 늦춰 울린다
     */
    private Spec fire(Player p, Arms a, Spec s0, double late) {
        refreshMods(p, a);
        shotsThisTick++;
        Spec s = a.spec(s0.kind());
        Shot shot = new Shot();
        int k = s.kind().ordinal();
        long now = host.now();
        a.mag[k]--;
        a.shots++;
        a.lastShot = now;
        a.shotLog[a.shotLogAt++ & 3] = now;
        a.lastInterval = 20.0 / s.rps();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        double spread = s.spread() + a.bloom
            + (s.move() * Math.min(1, a.speed / 0.22) + (a.airborne ? s.move() * 1.5 : 0)) * hooks.moveSpread(p, s.kind());
        if (!a.airborne && !p.isOnGround()) a.groundFlicker++;
        if (p.isSneaking()) spread *= 0.6;
        if (a.jamUntil > now) spread *= a.jamSpread;
        Location firstEnd = null;
        Mode md = mode(s.kind());
        Gun gun = a.gun(s.kind());
        switch (md.fire()) {
            // 총 말고 다른 쏘는 방식(2026-11-12): 불길, 냉기 원뿔, 번개, 레일 광선, 날아가는 탄
            case FLAME, FREEZE -> arsenal.stream(p, a, s, md, eye, cone(dir, spread), gun, a.crit[k]);
            case CHAIN -> firstEnd = arsenal.chain(p, a, s, md, eye, cone(dir, spread), gun, a.crit[k]);
            case CHARGE -> firstEnd = arsenal.rail(p, a, s, md, eye, cone(dir, spread), gun, a.crit[k]);
            case LOB, ROCKET -> arsenal.launch(p, a, s, md, eye, cone(dir, spread), gun);
            default -> {
                int sounds = 0;
                for (int i = 0; i < s.pellets(); i++) {
                    Vector d = cone(dir, spread);
                    Location end = trace(p, a, eye, d, s, shot, i, sounds < 3);
                    sounds++;
                    // 광선총: 곡선 탄 줄 대신 빛줄기(연사가 빨라 한 발 걸러 그림, 사람 몫 예산은 ShotFx 가 지킴)
                    if (md.fire() == Fire.BEAM) {
                        if (i == 0 && ((a.shots & 1) == 0 || s.rps() < 6)) fx().beam(Arsenal.muzzle(eye, dir), end);
                    } else if (i < 3) fx().tracer(p, eye, end, i == 0, rnd.nextDouble() * shotRate < 2);
                    if (firstEnd == null) firstEnd = end;
                }
            }
        }
        a.bloom = Math.min(s.bloomMax(), a.bloom + s.bloom());
        recoilShot(p, a, s, now);
        // 총소리: 흔들림 0.96~1.04, s.pitch() 는 바닐라 대체 소리에만(바닐라 음량은 soundAround 가 들리는 거리만큼 키움).
        // 불길, 냉기는 줄기 소리를 Arsenal 이 박자에 맞춰 이어 붙이고, 미니건은 두 발에 한 번
        if (md.fire() == Fire.FLAME || md.fire() == Fire.FREEZE) arsenal.streamSound(p, a, s, eye, now);
        else if (md.fire() != Fire.SPIN || (a.shots & 1) == 0) fireSound(p, eye, s, late);
        // 총구 불꽃: 냉동총, 전기총, 레일건, 활은 없음(그 무기 효과가 따로 그림)
        if (flashes(s.kind())) {
            a.flashAt = worldTime();
            a.flashSeed = kickLevel(s.kind()) << 6 | KICK_BIT | rnd.nextInt(32);
            if (!Props.has(s.model())) fx().muzzle(p);
        }
        if (md.fire() == Fire.HITSCAN || md.fire() == Fire.BURST || md.fire() == Fire.SPIN || md.fire() == Fire.BOLT || md.fire() == Fire.BEAM)
            whiz(p, eye, firstEnd);
        if (a.mag[k] == 0 && (s.reserve() < 0 || a.res[k] > 0)) {
            a.cycleKind = null;
            reload(p);
        } else if (a.mag[k] > 0 && (s.kind() == Kind.SHOTGUN || s.kind() == Kind.SNIPER || s.kind() == Kind.BOLTACTION || s.kind() == Kind.ANTIMATERIEL)) {
            a.cycleKind = s.kind();
            a.cycleAt = now;
            a.cycleStep = 0;
        }
        return s;
    }

    /**
     * 한 줄기: 블록 광선을 먼저 쏘고 그 거리 안에서 가장 가까운 게이브를 맞힌다. 맞히면 피해를 주고 관통 수만큼 이어 감(블록은 못 뚫음).
     * 끝 자리를 돌려줌. sound = 탄착 소리를 낼지(산탄 9 알이 다 내지 않게)
     */
    private Location trace(Player p, Arms a, Location eye, Vector d, Spec s, Shot shot, int pellet, boolean sound) {
        World w = p.getWorld();
        HitTargets targets = host.targets();
        Set<Object> hit = new HashSet<>();
        Vector from = eye.toVector();
        double left = s.range(), travelled = 0;
        int pierce = s.pierce();
        DamageType type = damageType(s.kind());
        Gun gun = a.gun(s.kind());
        double critChance = a.crit[s.kind().ordinal()];
        while (true) {
            Location fromLoc = from.toLocation(w);
            RayTraceResult rb = w.rayTraceBlocks(fromLoc, d, left, FluidCollisionMode.NEVER, true);
            double blockDist = rb == null ? left : rb.getHitPosition().distance(from);
            Hit h = targets == null ? null : targets.cast(p, from.clone(), d.clone(), blockDist, hit);
            if (h != null && (h.target() == null || h.dist() > blockDist)) h = null;
            // 아군 사격: 서 있는 동료 몸(또는 쓰러진 동료 좌석 자리 낮은 상자)이 몹, 블록보다 가까우면 피해 없이 탄이 거기서 멈춤(한 발에 한 사람
            // 한 번 알림). 맞힐 게이브가 그 동료에게 붙은 것이면 그 동료 몸은 건너뜀(붙잡은 것, 머리에 붙은 것을 동료 쪽에서 쏘기)
            double near = h == null ? blockDist : h.dist();
            Player ally = null;
            boolean allyDown = false;
            double allyDist = near;
            Object aim = h == null ? null : h.target();
            for (Player o : mates) {
                if (o == p || o.getWorld() != w || aim != null && hooks.attached(o, aim)) continue;
                double dist = boxHit(o.getBoundingBox(), from, d, allyDist);
                if (dist < allyDist) {
                    allyDist = dist;
                    ally = o;
                }
            }
            for (Player o : downed) {
                if (o == p || o.getWorld() != w) continue;
                double dist = boxHit(downBox(o), from, d, allyDist);
                if (dist < allyDist) {
                    allyDist = dist;
                    ally = o;
                    allyDown = true;
                }
            }
            if (ally != null) {
                boolean taken = false;
                for (AllyShot f : allyShots) {
                    try {
                        if (f.take(p, ally, from.clone(), d.clone(), allyDist, s)) {
                            taken = true;
                            break;
                        }
                    } catch (RuntimeException ex) {
                        host.plugin().getLogger().warning("[총] 아군 탄 갈고리 오류: " + ex);
                    }
                }
                if (!taken && shot.allies.add(ally)) {
                    // 서 있는 동료의 사격 끊김은 ALLY_GAP 틱에 한 번(쓰러진 동료의 출혈 줄이기는 Players 가 0.5초에 한 번으로 막음)
                    long nowT = host.now();
                    Long last = allyDown ? null : allyHitAt.get(ally.getUniqueId());
                    if (last == null || nowT - last >= ALLY_GAP) {
                        if (!allyDown) allyHitAt.put(ally.getUniqueId(), nowT);
                        hooks.friendly(p, ally);
                    }
                    // 쏜 사람에게: 탄이 동료 몸에 막혔다는 낮은 소리(0.5초에 한 번, 본인만)
                    if (nowT - a.blockSoundAt >= 10) {
                        a.blockSoundAt = nowT;
                        Props.sound(p, null, Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 1f, 0.6f);
                    }
                }
                return from.clone().add(d.clone().multiply(allyDist)).toLocation(w);
            }
            if (h != null) {
                hit.add(h.target());
                Vector pos = h.pos() == null ? from.clone().add(d.clone().multiply(h.dist())) : h.pos().clone();
                Location at = pos.toLocation(w);
                hitIndex = hit.size() - 1;
                double amount = s.damage() * falloff(travelled + h.dist(), s.range()) * pierced(hitIndex) * hitMul(p, h.target(), s);
                critNow = critChance > 0 && rnd.nextDouble() < critChance;
                if (critNow) amount *= 2;
                boolean died = targets.damage(h.target(), new Damage(amount, type, p, pos.clone(), d.clone(), s.key(), 0, true));
                credit(p, h.target(), amount, died, gun, true);
                critNow = false;
                hitIndex = 0;
                // 볼트 피스톨의 탄착 폭발이 먼저(폭발탄 특성이 붙어도 한 번만 터짐: shot.boom)
                if (BOLT_MODEL.equals(s.model())) boltBoom(p, gun, s, shot, at, h.target(), d);
                if (gun != null && !gun.traits.isEmpty()) traits(p, a, gun, s, shot, h.target(), pos, d, amount, died, hit);
                // 복합궁 화살: 꿰뚫은 개체마다 뒤로 밀침
                if (!died && h.target() instanceof Gabe hg && mode(s.kind()).fire() == Fire.BOLT) push(w, hg, d, mode(s.kind()).effect());
                // 피 안개: 산탄은 앞 세 알만, 판 전체 사격이 많으면 틱마다 하나쯤으로 솎음(뒤로 뿜는 것과 자국은 첫 알의 첫 대상만)
                if (pellet < 3 && rnd.nextDouble() * shotRate < 1) fx().gunBlood(at, d, pellet == 0 && hit.size() == 1);
                if (sound) soundNear(p, at, "impact.flesh", Sound.ENTITY_PLAYER_HURT, 0.9f, 0.9f + rnd.nextFloat() * 0.2f, 1f, 20);
                if (pierce-- > 0) {
                    left -= h.dist();
                    travelled += h.dist();
                    from = pos.add(d.clone().multiply(0.05));
                    if (left > 0.5) continue;
                }
                return at;
            }
            if (rb == null) return fromLoc.add(d.clone().multiply(left));
            Location at = rb.getHitPosition().toLocation(w);
            Block b = rb.getHitBlock();
            Material mat = b == null ? Material.STONE : b.getType();
            fx().gunHit(at, rb.getHitBlockFace(), mat, d, pellet < 3 && rnd.nextDouble() * shotRate < 1);
            if (sound) impactSound(p, at, mat);
            if (BOLT_MODEL.equals(s.model())) boltBoom(p, gun, s, shot, at, null, d);
            return at;
        }
    }

    /** 볼트 피스톨(옮긴 총, 2026-11-16 사용자 "탄착지점에 기본 폭발피해 ... 피해량의 50%"): 탄이 멈춘 자리에서 터진다(직접 맞은 것은 빼고) */
    static final String BOLT_MODEL = "gcb_boltpistol";

    private void boltBoom(Player p, Gun gun, Spec s, Shot shot, Location at, Object direct, Vector d) {
        if (shot.boom) return;
        shot.boom = true;
        HitTargets t = host.targets();
        long now = host.now();
        Long last = popAt.get(p.getUniqueId());
        if (last == null || now - last >= 3) {
            popAt.put(p.getUniqueId(), now);
            fx().pop(at);
            // GCB 볼트 피스톨 탄착음 그대로(맞힌 곳마다 boom, 블록이면 boom_block 을 더함, 그쪽 음량 0.75, 32블록)
            Props.soundAround(at, "gun.gcb_boltpistol.boom", Sound.ENTITY_GENERIC_EXPLODE, 0.75f, 1f, 1.5f, 32, -1);
            if (direct == null) Props.soundAround(at, "gun.gcb_boltpistol.boom_block", null, 0.75f, 1f, 1f, 32, -1);
        }
        if (t == null) return;
        double dmg = s.damage() * 0.5;
        for (Object o : t.inRadius(at, 2.0)) {
            if (o == direct) continue;
            boolean k = t.damage(o, new Damage(dmg, DamageType.EXPLOSION, p, t.center(o), d.clone(), "explosive", 0));
            credit(p, o, dmg, k, gun, false);
        }
    }

    /** 상자 bb 에 from 에서 d(단위)로 max 안에 닿는 거리, 안 닿으면 +무한(가운데가 광선에서 먼 상자는 바로 버림) */
    private static double boxHit(BoundingBox bb, Vector from, Vector d, double max) {
        Vector c = bb.getCenter();
        double t = c.clone().subtract(from).dot(d);
        if (t < 0 || t > max + 1.5 || c.clone().subtract(from).subtract(d.clone().multiply(t)).lengthSquared() > 2.25) return Double.POSITIVE_INFINITY;
        RayTraceResult rp = bb.rayTrace(from, d, max);
        return rp == null ? Double.POSITIVE_INFINITY : rp.getHitPosition().distance(from);
    }

    /**
     * 쓰러진 동료 몸 상자: 좌석 자리(바닥)부터 앉은 머리 끝(+1.45)까지, 가로 1.1. 사람 발은 좌석보다 0.6 아래라 서 있는 상자의 아랫부분은
     * 바닥에 묻혀 있으므로 바닥 위로 보이는 앉은 몸만 친다
     */
    private static BoundingBox downBox(Player o) {
        Location l = o.getVehicle() != null ? o.getVehicle().getLocation() : o.getLocation();
        return new BoundingBox(l.getX() - 0.55, l.getY() - 0.05, l.getZ() - 0.55, l.getX() + 0.55, l.getY() + 1.45, l.getZ() + 0.55);
    }

    // ------------------------------------------------------------------ 특성

    /** 맞힌 뒤 굴린 총 특성(흡혈, 관통, 치명은 hooks, 배율에서 이미 처리됨) */
    private void traits(Player p, Arms a, Gun gun, Spec s, Shot shot, Object target, Vector pos, Vector d, double amount, boolean died,
        Set<Object> hit) {
        HitTargets t = host.targets();
        World w = p.getWorld();
        if (gun.has(Gun.Trait.EXPLOSIVE) && !shot.boom) {
            shot.boom = true;
            Location at = pos.toLocation(w);
            long now = host.now();
            Long last = popAt.get(p.getUniqueId());
            if (last == null || now - last >= 3) {
                popAt.put(p.getUniqueId(), now);
                fx().pop(at);
                Props.soundAround(at, null, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1f, 1.7f, 24, -1);
            }
            double dmg = s.damage() * 0.45;
            for (Object o : t.inRadius(at, 1.8)) {
                if (o == target) continue;
                boolean k = t.damage(o, new Damage(dmg, DamageType.EXPLOSION, p, t.center(o), d.clone(), "explosive", 0));
                credit(p, o, dmg, k, gun, false);
            }
        }
        if (gun.has(Gun.Trait.INCENDIARY) && !died) ignite(target, p, gun, Math.max(4, s.damage() * 0.5), 60);
        if (gun.has(Gun.Trait.CHAIN) && !shot.chain) {
            shot.chain = true;
            Set<Object> done = new HashSet<>(hit);
            done.add(target);
            Vector from = t.center(target);
            if (from == null) from = pos.clone();
            double dmg = amount * 0.5;
            for (int jump = 0; jump < 2 && from != null; jump++) {
                Object next = null;
                double best = 4.5 * 4.5;
                for (Object o : t.inRadius(from.toLocation(w), 4.5)) {
                    if (done.contains(o)) continue;
                    Vector c = t.center(o);
                    if (c == null) continue;
                    double dd = c.distanceSquared(from);
                    if (dd < best) {
                        best = dd;
                        next = o;
                    }
                }
                if (next == null) break;
                Vector c = t.center(next);
                done.add(next);
                fx().streak(from.toLocation(w), c.toLocation(w));
                // 같은 자리(분열 직후, 같은 자리 소환)면 방향은 탄 방향(0 벡터 normalize 로 NaN 이 되지 않게)
                Vector jd = c.clone().subtract(from);
                jd = jd.lengthSquared() < 1e-6 ? d.clone() : jd.normalize();
                boolean k = t.damage(next, new Damage(dmg, DamageType.BULLET, p, c.clone(), jd, "chain", 0, false));
                credit(p, next, dmg, k, gun, false);
                from = c;
                dmg *= 0.5;
            }
        }
        if (gun.has(Gun.Trait.KNOCKBACK) && !died && target instanceof Gabe g) push(w, g, d, Math.max(0.1, Math.min(0.8, s.damage() * 0.04)));
    }

    /** 게이브를 d 의 수평 쪽으로 dist 블록 밀침(발 자리가 막히면 그대로). 넉백 특성, 복합궁 화살, 폭발 */
    static void push(World w, Gabe g, Vector d, double dist) {
        // 보스와 거대한 몸은 밀리지 않는다(제자리 보스가 로켓, 대물 저격에 밀려난 채 남지 않게. 증강의 밀침도 보스를 거른다)
        if (g.big || g.sp.is(kr.gave.mob.Role.BOSS)) return;
        Vector hd = d.clone().setY(0);
        if (hd.lengthSquared() < 1e-6 || dist <= 0) return;
        Vector to = g.pos.clone().add(hd.normalize().multiply(dist));
        Block feet = w.getBlockAt(to.getBlockX(), (int) Math.floor(to.getY()), to.getBlockZ());
        if (feet.isPassable()) g.pos.copy(to);
    }

    /**
     * 맞힘 기록 한곳: 맞힌 알 수(shot = 총에서 바로 나간 것만), 처치 수, 증강과 처치 알림(hooks.onHit: 처치 회복, 현상금, 처치로 터지는 증강),
     * 쏜 사람만 듣는 맞힘 소리. 총, 특성 번짐, 불붙음, 폭발, 수류탄, 화염병이 모두 여기로 온다. by 가 없으면 아무 일도 안 함
     */
    void credit(Player by, Object target, double amount, boolean died, Gun gun, boolean shot) {
        if (by == null) return;
        Arms a = arms(by);
        if (a != null) {
            long now = host.now();
            if (shot) a.hits++;
            a.hitAt = now;
            if (died) {
                a.kills++;
                a.killAt = now;
            }
        }
        hooks.onHit(by, target, amount, died, gun);
        if (a != null && (shot || died)) hitSound(by, a, died, shot && critNow);
        if (shot && critNow) host.crit(by);
    }

    /** 이 사람이 이 대상에 주는 피해 배율: 증강의 조건 피해(hooks) x 얼어붙은 대상 덤 */
    double hitMul(Player p, Object target, Spec s) {
        double m = Math.max(0, hooks.hitMul(p, target, s));
        return arsenal.frozenOn(target) ? m * FROZEN_BONUS : m;
    }

    /** 게이브 체력 배율(감독: 웨이브, 인원, 안개). 판 밖이면 1. 수류탄, 화염병 피해가 판 크기와 웨이브를 조금 따라가는 데 쓴다 */
    double crowdHp() {
        return host instanceof kr.gave.game.Game g ? Math.max(1, g.director.hpMul()) : 1;
    }

    /** 화염탄 불붙음: 10 틱마다 반 초 몫 피해(종류 FIRE), 작은 불꽃 연출(덤). 죽거나 시간이 다하면 끝 */
    private void burnTick(long now) {
        if (burns.isEmpty()) return;
        HitTargets t = host.targets();
        // 도는 동안 피해, 처치 알림이 새 불을 붙일 수 있다(ignite 가 burnsNew 로 돌림): burns 는 여기서만 고친다
        burnLoop = true;
        try {
            for (var it = burns.entrySet().iterator(); it.hasNext();) {
                var e = it.next();
                Object o = e.getKey();
                Burn b = e.getValue();
                if (t == null || now > b.until || o instanceof Gabe g && g.dead) {
                    it.remove();
                    continue;
                }
                if (now < b.next) continue;
                b.next = now + 10;
                Vector c = t.center(o);
                if (c == null) {
                    it.remove();
                    continue;
                }
                fx().flame(c.toLocation(host.world()), 0.6f, 14, ShotFx.EXTRA);
                double dmg = b.dps * 0.5;
                boolean k = t.damage(o, new Damage(dmg, DamageType.FIRE, b.by, c.clone(), new Vector(0, 1, 0), "incendiary", 0));
                credit(b.by, o, dmg, k, b.gun, false);
                if (k) it.remove();
            }
        } finally {
            burnLoop = false;
            if (!burnsNew.isEmpty()) {
                burns.putAll(burnsNew);
                burnsNew.clear();
            }
        }
    }

    /**
     * 불붙임(화염탄 특성, 화염방사기, 불 폭발): 대상마다 하나, 다시 걸리면 초당 피해는 큰 쪽, 시간은 새로. Arsenal 이 부른다
     */
    void ignite(Object target, Player by, Gun gun, double dps, int ticks) {
        if (target == null || target instanceof Gabe g && g.dead) return;
        Burn b = burns.get(target);
        if (b == null) b = burnsNew.get(target);
        if (b == null) {
            b = new Burn();
            b.next = host.now() + 10;
            (burnLoop ? burnsNew : burns).put(target, b);
        }
        b.by = by;
        b.gun = gun;
        b.dps = Math.max(b.dps, dps);
        b.until = Math.max(b.until, host.now() + (long) (ticks * (by == null ? 1 : Math.max(0.1, hooks.burnMul(by)))));
    }

    // ------------------------------------------------------------------ trip_aug: 증강이 부르는 공개 창구

    /** 불붙임(총 특성 없이). 길이에 hooks.burnMul 이 곱해진다 */
    public void burn(Object target, Player by, double dps, int ticks) {
        ignite(target, by, null, dps, ticks);
    }

    /** 지금 불붙어 있나 */
    public boolean burningOn(Object target) {
        return burns.containsKey(target) || burnsNew.containsKey(target);
    }

    /** 지금 냉기에 느려지거나 얼어 있나 */
    public boolean chilledOn(Object target) {
        return arsenal.chilledOn(target);
    }

    /** 게이브를 d 의 수평 쪽으로 dist 블록 밀침 */
    public static void shove(World w, Gabe g, Vector d, double dist) {
        push(w, g, d, dist);
    }

    /** 지금 불붙은 대상 수(시험) */
    public int burning() {
        return burns.size();
    }

    /** 탄착 소리: 쇠, 나무, 유리, 그 밖은 돌 */
    private void impactSound(Player shooter, Location at, Material m) {
        float jitter = 0.9f + rnd.nextFloat() * 0.2f;
        switch (ShotFx.material(m)) {
            case ShotFx.MAT_METAL -> soundNear(shooter, at, "impact.metal", Sound.BLOCK_ANVIL_LAND, 0.8f, jitter, 1f, 20);
            case ShotFx.MAT_WOOD -> soundNear(shooter, at, "impact.wood", Sound.BLOCK_WOOD_HIT, 0.9f, jitter, 1f, 20);
            // 유리는 바닐라 소리만: 1.4 x 0.93~1.07 = 1.3~1.5
            case ShotFx.MAT_GLASS -> soundNear(shooter, at, null, Sound.BLOCK_GLASS_HIT, 1.0f, 0.93f + rnd.nextFloat() * 0.14f, 1.4f, 20);
            default -> soundNear(shooter, at, "impact.stone", Sound.BLOCK_STONE_HIT, 0.9f, jitter, 1f, 20);
        }
    }

    // ------------------------------------------------------------------ 소리(솎기, 틱 안 시각)

    /**
     * 총소리: 들리는 거리 안 사람에게 따로 보낸다. 쏜 사람에게는 늘, 남에게는 {@link #othersHear} 몫만(판 전체 사격이 많을수록 솎음).
     * late(0..1 틱)만큼 늦춰 울려 연사 박자가 틱(0.05초)에 묶이지 않게 한다(초당 13 발이면 1, 2, 1, 2 틱이 아니라 1.54 틱마다).
     * 흔들림 0.96~1.04, s.pitch() 는 바닐라 대체 소리에만(바닐라 음량은 들리는 거리만큼 키움)
     */
    private void fireSound(Player shooter, Location eye, Spec s, double late) {
        String ev = s.fireEvent();
        boolean mod = Props.hasSound(ev);
        if (!mod && s.fire() == null) return;
        float jitter = 0.96f + rnd.nextFloat() * 0.08f;
        long seed = Props.seedFor(mod ? ev : null, -1);
        double r2 = s.soundRange() * s.soundRange();
        Location at = eye.clone();
        boolean bolt = BOLT_MODEL.equals(s.model());
        // 쏜 사람은 1인칭 사격음(그쪽 GCB 처럼 fire_1p, 팩에 있을 때만. 같은 씨앗이라 같은 변형 차례)
        String selfEv = mod && Props.hasSound(ev + "_1p") ? ev + "_1p" : ev;
        List<Player> to = new ArrayList<>();
        for (Player o : at.getWorld().getPlayers())
            if (o == shooter || o.getLocation().distanceSquared(at) <= r2 && rnd.nextDouble() < othersHear) to.add(o);
        Runnable send = () -> {
            soundLog[soundLogAt++ & 15] = System.nanoTime();
            for (Player o : to) {
                // 클라는 한 소리의 음량을 1 에서 자르므로 같은 소리(같은 씨앗, 높이, 자리)를 같은 틱에 두 번 보내 겹친다: 쏜 사람 2배,
                // 볼트 피스톨은 남에게도 0.75 두 번(1.5배). 나머지 남은 예전 그대로 한 번
                boolean self = o == shooter;
                int copies = self || bolt ? 2 : 1;
                float vol = self ? 1f : bolt ? 0.75f : 1f;
                for (int c = 0; c < copies; c++) {
                    if (mod) o.playSound(at, Props.NS + ":" + (self ? selfEv : ev), SoundCategory.PLAYERS, vol, jitter, seed);
                    else o.playSound(at, s.fire(), SoundCategory.PLAYERS, Math.max(vol, (float) (s.soundRange() / 16.0)), s.pitch() * jitter, seed);
                }
            }
        };
        // 소리 패킷은 다른 스레드에서 보내도 된다(CraftPlayer.playSound 는 패킷을 만들어 connection.send 만 함). 듣는 사람은 지금 틱에 골라 둠
        long when = grid + Math.round(late * 50_000_000L);
        if (when - System.nanoTime() < 300_000) send.run();
        else clock.at(when, send);
    }

    /**
     * 맞은 자리에서 나는 소리(탄착, 살): 쏜 사람에게는 늘(거리 안이면), 남에게는 {@link #othersHear} 몫만. 바닐라 대체 소리는 soundAround 처럼
     * 들리는 거리만큼 음량을 키운다(솎을 때도 같은 거리에서 들리게)
     */
    private void soundNear(Player shooter, Location at, String event, Sound fallback, float volume, float jitter, float fallbackPitch, double range) {
        if (othersHear >= 1) {
            Props.soundAround(at, event, fallback, volume, jitter, fallbackPitch, range, -1);
            return;
        }
        float vol = Props.hasSound(event) ? volume : Math.max(volume, (float) (range / 16.0));
        double r2 = range * range;
        for (Player o : at.getWorld().getPlayers())
            if (o.getLocation().distanceSquared(at) <= r2 && (o == shooter || rnd.nextDouble() < othersHear))
                Props.soundTo(o, at, event, fallback, vol, jitter, fallbackPitch, -1);
    }

    /**
     * 쏜 사람만 듣는 맞힘 소리(거리와 상관없이 본인 자리에서): 맞힘은 화살 맞는 딩 소리, 치명은 더 높고 크게(둘 다 2 틱에 하나), 처치는 다른
     * 소리(한 틱에 하나). 처치 소리는 따로 세어 같은 틱의 맞힘 소리에 묻히지 않고, 처치한 틱에는 맞힘 소리를 더 내지 않는다.
     * 산탄 아홉 알, 줄 선 떼를 꿰뚫는 레일건도 한 틱에 많아야 둘. 멀리서 맞혀도(저격) HUD 표시 말고 귀로 안다
     */
    void hitSound(Player p, Arms a, boolean died, boolean crit) {
        long now = host.now();
        if (died) {
            if (now == a.killSoundAt) return;
            a.killSoundAt = now;
            Props.sound(p, null, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 0.97f + rnd.nextFloat() * 0.06f, 0.75f);
        } else if (now != a.killSoundAt && now - a.hitSoundAt >= 2) {
            a.hitSoundAt = now;
            Props.sound(p, null, Sound.ENTITY_ARROW_HIT_PLAYER, crit ? 0.6f : 0.4f, 0.97f + rnd.nextFloat() * 0.06f, crit ? 1.8f : 1.25f);
        }
    }

    /** 탄이 곁(2.5 블록 안)을 스쳐 간 사람(쏜 사람 빼고)에게 그 사람만 듣는 스침 소리 */
    private void whiz(Player shooter, Location from, Location to) {
        if (to == null) return;
        Vector a = from.toVector(), ab = to.toVector().subtract(a);
        double len2 = ab.lengthSquared();
        if (len2 < 4) return;
        long now = host.now();
        for (Player o : shooter.getWorld().getPlayers()) {
            if (o == shooter || o.getGameMode() == GameMode.SPECTATOR) continue;
            Vector e = o.getEyeLocation().toVector();
            double t = e.clone().subtract(a).dot(ab) / len2;
            if (t <= 0.02 || t >= 0.97) continue;
            Vector c = a.clone().add(ab.clone().multiply(t));
            if (c.distanceSquared(e) > 2.5 * 2.5) continue;
            Long last = whizAt.get(o.getUniqueId());
            if (last != null && now - last < 3) continue;
            whizAt.put(o.getUniqueId(), now);
            Props.soundTo(o, c.toLocation(o.getWorld()), "bullet.whiz", null, 0.9f, 0.9f + rnd.nextFloat() * 0.2f, 1f, -1);
        }
    }

    private Vector cone(Vector dir, double deg) {
        if (deg <= 0) return dir.clone();
        double a = Math.toRadians(deg) * Math.sqrt(rnd.nextDouble()), t = rnd.nextDouble() * Math.PI * 2;
        Vector u = Math.abs(dir.getY()) > 0.99 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector x = dir.clone().crossProduct(u).normalize(), y = dir.clone().crossProduct(x).normalize();
        return dir.clone().multiply(Math.cos(a)).add(x.multiply(Math.sin(a) * Math.cos(t))).add(y.multiply(Math.sin(a) * Math.sin(t))).normalize();
    }

    /** 클라 시점에 더하는 상대 회전(마우스를 빼앗지 않음). 수류탄 흔들림, 맞음 흔들림에도 쓴다 */
    public static void kick(Player p, float yaw, float pitch) {
        ((CraftPlayer) p).getHandle().connection.send(new ClientboundPlayerRotationPacket(yaw, true, pitch, true));
        kickedAt.put(p.getUniqueId(), Bukkit.getCurrentTick());
    }

    // ------------------------------------------------------------------ 반동, 시야

    /**
     * 한 발의 반동. 위 킥 = up x (1 + 이어 쏜 몫) x (한도까지 남은 몫): 처음 몇 발은 발마다 또렷이 오르고 한도(cap)에 가까울수록 줄어든다.
     * 한도 둘레에서는 위아래 잔떨림(더했다 뺐다, 쌓이지 않음)과 옆 무늬만 남는다. 옆 쏠림은 가운데로 살짝 당겨 끝없이 한쪽으로 가지 않게
     */
    private void recoilShot(Player p, Arms a, Spec s, long now) {
        Recoil rc = s.recoil();
        int n = ++a.burst;
        // 쉬다가 쏘는 첫 발: 지금 시점이 놓은 뒤 돌아올 자리
        if (a.climb <= 0) a.anchorPitch = viewPitch(p, a, now);
        double crouch = p.isSneaking() ? 0.75 : 1.0;
        // 한도도 반동 배율(증강, 웅크림)만큼 줄어든다
        double cap = rc.cap() * crouch * s.up() / Math.max(1e-6, SPECS.get(s.kind()).up());
        double full = cap <= 0 ? 1 : Math.min(1, a.climb / cap);
        double up = s.up() * crouch * ((1 + rc.climb() * Math.min(n - 1, rc.climbMax())) * (0.9 + 0.2 * rnd.nextDouble()) * (1 - full)
            + 0.35 * full * (rnd.nextDouble() * 2 - 1));
        up = Math.max(-a.climb, Math.min(Math.max(0, cap - a.climb), up));
        double ramp = Math.max(0, Math.min(1, (n - 2) / 4.0));
        double side = s.side() * (rc.pattern() * Math.sin(n * 0.55 + rc.phase()) * ramp + (rnd.nextDouble() * 2 - 1) * (1 - rc.pattern() * 0.5)) * crouch
            - a.drift * 0.06;
        a.climb += up;
        a.drift += side;
        a.outYaw += (float) (side * 0.65);
        a.outPitch -= (float) (up * 0.65);
        a.restYaw += (float) (side * 0.35);
        a.restPitch -= (float) (up * 0.35);
        a.restAt = now + 1;
        a.settle = rc.settle();
        // 되돌림 시작: 연사 총(박자 3 틱 미만)은 박자보다 길게(누르고 있는 동안 발 사이에 되돌리지 않음), 한 발씩 쏘는 총은 킥(2 틱)이
        // 끝나면 바로(누르고 있어도 발 사이에 조금씩 돌아옴: 톡, 쾅). 마지막 킥의 클라 답이 서버에 닿은 뒤여야 하므로 핑만큼 더 기다린다
        a.settleDelay = Math.max(a.lastInterval >= 3 ? 2 : (int) Math.ceil(a.lastInterval) + 1, lagTicks(p) + 2);
        a.fovUntil = now + 2;
        a.fovMul = (float) rc.fov();
    }

    private void recoilTick(Player p, Arms a, long now) {
        if (a.restAt >= 0 && now >= a.restAt) {
            a.outYaw += a.restYaw;
            a.outPitch += a.restPitch;
            a.restYaw = a.restPitch = 0;
            a.restAt = -1;
        }
        // 이어 쏘기가 끊김(다음 발 박자(최대 5 틱) + 3 틱 동안 안 쏨): 옆 무늬를 처음부터(증강의 '이어 쏜 발 수'도 0)
        if (a.burst > 0 && now - a.lastShot > Math.min(a.lastInterval, 5) + 3) a.burst = 0;
        // 놓은 뒤 되돌림: 연사를 시작한 시점보다 아직 위에 있는 만큼만(사람이 스스로 내린 몫은 이미 빠져 있다), 많아야 반동으로 오른 만큼.
        // 틱마다 남은 몫의 settle 을 보내 부드럽게 멎고(첫 두 틱은 살살), 그사이 사람이 내리면 그만큼 일찍 끝난다.
        // 옆은 좌우로 표적을 따라가는 일이 흔해 사람 몫을 가릴 수 없으니 반동으로 쏠린 만큼(작다)만 그대로 되돌린다
        if ((a.climb > 0 || a.drift != 0) && now - a.lastShot >= a.settleDelay) {
            double rate = a.settle * Math.min(1, (now - a.lastShot - a.settleDelay + 1) / 3.0);
            double left = Math.min(a.climb, a.anchorPitch - viewPitch(p, a, now));
            if (left < 0.03) a.climb = 0;
            else {
                double step = Math.min(left, Math.max(0.03, left * rate));
                a.outPitch += (float) step;
                a.climb -= step;
            }
            if (Math.abs(a.drift) < 0.03) a.drift = 0;
            else {
                double step = a.drift * rate;
                a.outYaw -= (float) step;
                a.drift -= step;
            }
        }
        // 웅크리면 조준경: 쏠 때 킥과 곱하되 fov() 가 맞출 수 있는 아래끝(0.54) 위로(조준경 0.55 에서도 킥이 좁아지는 쪽으로)
        float base = scopeK(p, a);
        fov(p, a, now < a.fovUntil ? Math.max(0.54f, a.fovMul * base) : base);
    }

    /** 이번 틱에 모인 반동 회전을 한 번에 보낸다(한 사람에게 틱마다 많아야 한 패킷) */
    private void flushKick(Player p, Arms a, long now) {
        if (a.outYaw == 0 && a.outPitch == 0) return;
        kick(p, a.outYaw, a.outPitch);
        int i = (int) (now & 15);
        a.sentAt[i] = now;
        a.sentPitch[i] = a.outPitch;
        a.outYaw = a.outPitch = 0;
    }

    /** 반동을 쉬는 상태로(쌓인 오름, 보낼 회전, 되돌림을 모두 버림) */
    private static void calm(Arms a) {
        a.burst = 0;
        a.climb = a.drift = 0;
        a.outYaw = a.outPitch = a.restYaw = a.restPitch = 0;
        a.restAt = -1;
    }

    /**
     * 보낸 회전의 클라 답이 아직 서버에 안 닿았다고 칠 틱 수(왕복 핑을 틱으로 올림). 실제보다 길게 잡으면 되돌림이 조금 느려질 뿐이고,
     * 짧게 잡으면 사람이 내린 몫을 한두 틱 늦게 알아챌 뿐이다(되돌리는 양은 반동으로 오른 만큼을 넘지 않음)
     */
    private static int lagTicks(Player p) {
        return Math.min(14, (int) Math.ceil(p.getPing() / 50.0));
    }

    /**
     * 지금 클라 시점(pitch) 어림: 서버가 아는 값 + 보냈지만 아직 답이 안 온 반동 회전(최근 lagTicks 틱에 보낸 것). 사람이 그사이 움직인
     * 마우스는 모른다(다음 틱에 저절로 반영됨)
     */
    private static float viewPitch(Player p, Arms a, long now) {
        float v = p.getPitch();
        for (int i = lagTicks(p); i >= 1; i--) {
            int j = (int) ((now - i) & 15);
            if (a.sentAt[j] == now - i) v += a.sentPitch[j];
        }
        return v;
    }

    /** 조준경 배율: 웅크리고 조준경 총을 들었고 재장전 중이 아니면 그 총 배율, 아니면 1 */
    private float scopeK(Player p, Arms a) {
        if (!p.isSneaking() || a.reloading != null) return 1f;
        Spec s = held(p);
        return s == null ? 1f : scope(s.kind());
    }

    /** 쏠 때 시야 킥: 클라에만 걷기 속도를 바꿔 보낸다(마크 FOV 배율 = (이동 속도 속성 / 걷기 속도 + 1) / 2) */
    private void fov(Player p, Arms a, float k) {
        float base = p.getWalkSpeed() / 2f;
        float w = base;
        if (Math.abs(k - 1) > 1e-3) {
            AttributeInstance attr = p.getAttribute(Attribute.MOVEMENT_SPEED);
            double av = attr == null ? base : attr.getValue();
            double denom = k * (av / base + 1) - 1;
            if (av > 1e-4 && denom > 0.05) w = (float) (av / denom);
        }
        if (a.sentWalk < 0 && Math.abs(w - base) < 1e-6) return;
        if (Math.abs(w - a.sentWalk) < 1e-6) return;
        a.sentWalk = w;
        sendWalk(p, w);
    }

    private static void sendWalk(Player p, float walk) {
        var h = ((CraftPlayer) p).getHandle();
        net.minecraft.world.entity.player.Abilities ab = new net.minecraft.world.entity.player.Abilities();
        ab.apply(h.getAbilities().pack());
        ab.setWalkingSpeed(walk);
        h.connection.send(new ClientboundPlayerAbilitiesPacket(ab));
    }

    // ------------------------------------------------------------------ 손 총 모습(탄창, 총구 불꽃)

    /** 1인칭 반동 세기(셰이더 zr_kick): 0 기관단총, 1 소총, 2 권총, 3 산탄총, 저격총 */
    static int kickLevel(Kind k) {
        return switch (k) {
            case SMG, MINIGUN, FLAME, FREEZE, MACHINEPISTOL, SILENCED -> 0;
            case RIFLE, LMG, BURST, TESLA, LASER -> 1;
            case PISTOL, REVOLVER, DMR, DUAL -> 2;
            default -> 3;
        };
    }

    /**
     * 손에 든 총 아이템의 custom_model_data: 깃발 0 = 탄창 빠짐, 색 0..2 = 남이 보는(3인칭) 총구 불꽃 도장(종류 11..13,
     * 씨앗 [반동 세기 2][0][무작위 5]), 색 3 = 1인칭 몸통 몸짓 도장([F][도장 상위][몸짓][도장 중간][15 - 세기][도장 하위], 없으면 흰색),
     * 색 4..6 = 1인칭 총구 불꽃 도장(씨앗 [세기 2][반동 켬 1][무작위 5]). 몸짓 E 반동, D 꺼내기, C 재장전 자세, B 재장전 자세 풀기
     * (셰이더 zr_kick). 아이템은 모두가 받지만 색 3..6 은 총 아이템 정의가 1인칭 자리(firstperson_*)에서만 읽는다
     * (build_pack.py): 곁에 선 동료의 총이 내 눈 공간에서 움직이거나 찢어지지 않는다. 바뀔 때만 보낸다.
     * 연사 중에는 진짜 아이템을 {@link #THIRD_GAP} 틱에 한 번만 바꾸고 사이 발은 쏜 사람에게만 칸 패킷으로 보낸다(남에게 가는 장비 패킷을 아낌)
     */
    private void visual(Player p, Arms a, long now) {
        int held = slot(p);
        long wt = worldTime();
        for (int slot = 0; slot <= 1; slot++) {
            Kind kind = slot == 0 ? a.primary : Kind.PISTOL;
            if (kind == null) continue;
            boolean isHeld = slot == held;
            boolean mag = isHeld && (a.magOut && a.reloading == kind || now < a.debugMagUntil);
            long flashAt = -1, bodyAt = -1;
            int seed = a.flashSeed;
            int level = kickLevel(kind);
            int sig = 0xE;
            int drawLen = SPECS.get(kind).draw();
            if (isHeld && now < a.debugFlashUntil) {
                flashAt = wt;
                seed = level << 6 | (int) (wt * 7 & 31);
            } else if (isHeld && now < a.debugKickUntil) {
                bodyAt = wt;
            } else if (isHeld && wt - a.flashAt <= FLASH_KEEP && a.flashAt >= a.drawAt) {
                flashAt = bodyAt = a.flashAt;
            } else if (isHeld && wt - a.drawAt <= drawLen + 1) {
                bodyAt = a.drawAt;
                sig = 0xD;
                level = Math.max(0, Math.min(3, (drawLen - 5) / 2));
            } else if (isHeld && a.reloading == kind && a.reloadWt >= 0) {
                bodyAt = a.reloadWt;
                sig = 0xC;
                level = 0;
            } else if (isHeld && a.poseEndKind == kind && wt - a.poseEndWt <= 6) {
                bodyAt = a.poseEndWt;
                sig = 0xB;
                level = 0;
            }
            long key = (flashAt + 1) * 31 + (bodyAt + 1) * 1000003L + seed * 7L + sig;
            if (mag == a.sentMag[slot] && key == a.sentStamp[slot]) continue;
            int inv = at(p, slot);
            ItemStack real = p.getInventory().getItem(inv);
            if (real == null || real.getType() != Material.STICK) continue;
            // 연사 중 발마다 바뀌는 도장: 진짜 아이템은 THIRD_GAP 틱에 한 번만 바꾸고 그 사이는 쏜 사람에게만 보낸다(탄창 깃발, 도장 지우기는 늘 진짜로)
            boolean mine = flashAt >= 0 && mag == a.sentMag[slot] && now - a.realAt[slot] < THIRD_GAP && inv <= 8
                && p.getOpenInventory().getType() == InventoryType.CRAFTING;
            ItemStack it = mine ? real.clone() : real;
            if (!mag && flashAt < 0 && bodyAt < 0) it.resetData(DataComponentTypes.CUSTOM_MODEL_DATA);
            else {
                CustomModelData.Builder b = CustomModelData.customModelData().addFlag(mag);
                if (flashAt >= 0 || bodyAt >= 0) {
                    // 도장은 클라 시계에 맞춘다(서버가 느려 클라 시계가 앞서 있으면 그만큼 더함, ClientClock). 논리(언제 지울지)는 서버 시각 그대로
                    long ahead = ClientClock.ahead(host.world());
                    int stamp = (int) Math.floorMod(flashAt + ahead, 4000L);
                    int third = (seed & 255 & ~KICK_BIT) << 12 | stamp, first = (seed & 255) << 12 | stamp;
                    for (int kindFx : MUZZLE_KINDS) b.addColor(Color.fromRGB(flashAt < 0 ? 0 : kindFx << 20 | third));
                    int st = (int) Math.floorMod(bodyAt + ahead, 4000L);
                    b.addColor(Color.fromRGB(bodyAt < 0 ? 0xFFFFFF
                        : (0xF0 | st >> 8 & 15) << 16 | (sig << 4 | st >> 4 & 15) << 8 | (15 - level) << 4 | st & 15));
                    for (int kindFx : MUZZLE_KINDS) b.addColor(Color.fromRGB(flashAt < 0 ? 0 : kindFx << 20 | first));
                }
                it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, b.build());
            }
            if (mine) {
                // 핫바 칸 inv 는 사람 인벤토리 화면의 칸 36 + inv
                var h = ((CraftPlayer) p).getHandle();
                h.connection.send(new ClientboundContainerSetSlotPacket(h.inventoryMenu.containerId, h.inventoryMenu.getStateId(), 36 + inv,
                    CraftItemStack.asNMSCopy(it)));
            } else {
                p.getInventory().setItem(inv, it);
                a.realAt[slot] = now;
            }
            a.sentMag[slot] = mag;
            a.sentStamp[slot] = key;
        }
    }

    // ------------------------------------------------------------------ 재장전

    /** 든 총 재장전(Q). 붙잡힘, 쓰러짐, 탄창 가득, 예비탄 없음, tick() 이 멈춘 동안이면 안 함 */
    public void reload(Player p) {
        Arms a = arms(p);
        Spec s = held(p);
        // tick() 이 멈춘 동안(대기실)은 재장전이 나아가지 못하니 시작하지 않는다
        if (a == null || s == null || a.reloading != null || host.blocked(p) || stale()) return;
        int k = s.kind().ordinal();
        if (a.mag[k] >= s.mag() || s.reserve() >= 0 && a.res[k] <= 0) return;
        long now = host.now();
        a.reloading = s.kind();
        a.reloadStart = a.phaseStart = now;
        a.reloadWt = worldTime();
        a.reloadEmpty = a.mag[k] == 0;
        // 엉킴(JAM): 재장전 시간 배율(박자 시각도 같이 늘어남)
        double jr = a.jamUntil > now ? Math.max(0.2, a.jamReload) : 1;
        a.reloadMul = cueMul(s) * jr;
        a.cueIdx = 0;
        a.magOut = false;
        a.cycleKind = null;
        if (s.shells()) {
            a.shellPhase = 0;
            a.reloadUntil = now + scaled(SHELL_START, a.reloadMul);
            sound(p, s, "reload_start", Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1f, -1);
        } else {
            a.reloadUntil = now + Math.max(1, Math.round(s.reload() * jr));
        }
    }

    // ------------------------------------------------------------------ 새 종 갈고리(2026-11-12 sp_engine, docs/모듈/API.md '새 종 갈고리')

    /** 엉킴 헛방 수(시험). */
    public int jamMisfires;

    /** RELOAD: 든 총을 재장전하는 중인가. */
    public boolean reloading(Player p) {
        Arms a = arms(p);
        return a != null && a.reloading != null;
    }

    /** RELOAD: 재장전 진행 0..1(재장전 중이 아니면 -1). */
    public double reloadProgress(Player p) {
        Arms a = arms(p);
        if (a == null || a.reloading == null) return -1;
        double len = Math.max(1, a.reloadUntil - a.phaseStart);
        return Math.max(0, Math.min(1, 1 - Math.max(0, a.reloadUntil - host.now()) / len));
    }

    /** RELOAD: 재장전을 끊는다(넣던 탄은 안 들어감, 다시 Q 로). 끊었으면 true. */
    public boolean cancelReload(Player p) {
        Arms a = arms(p);
        if (a == null || a.reloading == null) return false;
        cancelReload(a);
        return true;
    }

    /**
     * AMMO: 예비탄을 몫만큼 뺀다. 주무기와 권총마다 그 총 예비탄 최대(Spec.reserve)의 frac(0..1, 한 발 이상)을 빼고 0 아래로는 안 간다. 무한 예비탄(권총 기본)은
     * 그대로. magToo 면 예비탄이 모자란 만큼 탄창에서도 뺀다. 뺀 발 수 합.
     */
    public int takeAmmo(Player p, double frac, boolean magToo) {
        Arms a = arms(p);
        if (a == null || frac <= 0) return 0;
        int took = 0;
        for (Kind k : new Kind[] {a.primary, Kind.PISTOL}) {
            if (k == null) continue;
            Spec s = a.spec(k);
            if (s == null || s.reserve() < 0) continue;
            int i = k.ordinal();
            int want = Math.max(1, (int) Math.round(s.reserve() * Math.min(1, frac)));
            int r = Math.max(0, Math.min(a.res[i], want));
            a.res[i] -= r;
            took += r;
            if (magToo && r < want) {
                int m = Math.max(0, Math.min(a.mag[i], want - r));
                a.mag[i] -= m;
                took += m;
            }
        }
        return took;
    }

    /**
     * JAM: 이 사람 총을 ticks 동안 엉키게 한다. reloadMul = 재장전 시간 배율(1.5 = 1.5배 느림, 그동안 시작한 재장전에 걸림), misfire = 한 발마다 헛방 확률(0..1, 철컥
     * 소리만 나고 탄은 안 씀), spreadMul = 퍼짐 배율. 이미 엉켜 있으면 끝은 늦은 쪽, 값은 큰 쪽. 다 1, 0, 1 이거나 ticks 0 이하면 풂.
     */
    public void jam(Player p, int ticks, double reloadMul, double misfire, double spreadMul) {
        Arms a = arms(p);
        if (a == null) return;
        long now = host.now();
        if (ticks <= 0) {
            a.jamUntil = -1;
            a.jamReload = 1;
            a.jamMisfire = 0;
            a.jamSpread = 1;
            return;
        }
        boolean on = a.jamUntil > now;
        a.jamUntil = Math.max(a.jamUntil, now + ticks);
        a.jamReload = on ? Math.max(a.jamReload, reloadMul) : reloadMul;
        a.jamMisfire = Math.max(0, Math.min(1, on ? Math.max(a.jamMisfire, misfire) : misfire));
        a.jamSpread = on ? Math.max(a.jamSpread, spreadMul) : spreadMul;
    }

    /** JAM: 지금 엉켜 있나. */
    public boolean jammed(Player p) {
        Arms a = arms(p);
        return a != null && a.jamUntil > host.now();
    }

    /** WEAPONLOCK: ticks 동안 주무기를 못 듦(들고 있으면 권총으로 바뀌고, 숫자키 1 이 막힘). 0 이하면 풂. */
    public void lockPrimary(Player p, int ticks) {
        Arms a = arms(p);
        if (a == null) return;
        a.primaryLockUntil = ticks <= 0 ? -1 : Math.max(a.primaryLockUntil, host.now() + ticks);
    }

    /** WEAPONLOCK: 지금 주무기가 잠겼나. */
    public boolean primaryLocked(Player p) {
        Arms a = arms(p);
        return a != null && a.primaryLockUntil > host.now();
    }

    /**
     * 재장전 박자 시각 배율 = 이 총의 실제 재장전 틱 / 바탕 재장전 틱(굴린 총 x 사람 배율이 반올림까지 들어감): 끝나는 틱과 탄창 꽂힘,
     * 장전 소리가 같은 비율로 당겨지고 늘어남(산탄총은 한 발 넣는 틱의 비율을 시작 자세, 끝 펌프에 씀)
     */
    static double cueMul(Spec s) {
        return Math.max(0.2, Math.min(5, (double) s.reload() / SPECS.get(s.kind()).reload()));
    }

    private static int scaled(int ticks, double mul) {
        return Math.max(ticks == 0 ? 0 : 1, (int) Math.round(ticks * mul));
    }

    private void cancelReload(Arms a) {
        if (a.reloading != null) {
            a.poseEndWt = worldTime();
            a.poseEndKind = a.reloading;
        }
        a.reloading = null;
        a.reloadUntil = -1;
        a.magOut = false;
        a.fireAfterPump = false;
    }

    private void reloadTick(Player p, Arms a, Spec s, long now) {
        int k = s.kind().ordinal();
        if (s.shells()) {
            shellTick(p, a, s, now);
            return;
        }
        long el = now - a.reloadStart;
        List<Cue> cues = CUES.getOrDefault(s.kind(), List.of());
        while (a.cueIdx < cues.size() && scaled(cues.get(a.cueIdx).at(), a.reloadMul) <= el) {
            Cue c = cues.get(a.cueIdx++);
            if (c.emptyOnly() && !a.reloadEmpty) continue;
            if (c.act() == MAG_OUT) a.magOut = true;
            else if (c.act() == MAG_IN) a.magOut = false;
            if (c.events().length > 0 || c.fallback() != null)
                Props.soundAround(p.getLocation().add(0, 1.2, 0), cueEvent(s, c), c.fallback(), 0.9f, 0.97f + rnd.nextFloat() * 0.06f, c.pitch(), 16, -1);
        }
        if (now < a.reloadUntil) return;
        int need = s.mag() - a.mag[k];
        int take = s.reserve() < 0 ? need : Math.min(need, a.res[k]);
        a.mag[k] += Math.max(0, take);
        if (s.reserve() >= 0) a.res[k] -= Math.max(0, take);
        cancelReload(a);
    }

    /** 산탄총: 시작 자세(7 틱) → 한 발씩 9 틱(넣는 소리가 넣는 틱에 오게) → 빈 총에서 시작했으면 끝에 펌프(8 틱), 아니면 끝 소리만. 틱은 재장전 배율을 곱함 */
    private void shellTick(Player p, Arms a, Spec s, long now) {
        int k = s.kind().ordinal();
        if (a.shellPhase == 2) {
            if (now >= a.reloadUntil) {
                // 넣다가 당겨서 시작한 펌프: 끝난 이 틱에 그 한 발(tick() 은 재장전 다음에 방아쇠를 봄)
                boolean shot = a.fireAfterPump;
                cancelReload(a);
                if (shot) a.tapUntil = now + 4;
            }
            return;
        }
        if (a.shellPhase == 1 && now == a.reloadUntil - 1) sound(p, s, "shell", Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.95f + rnd.nextFloat() * 0.1f, 1);
        if (now < a.reloadUntil) return;
        if (a.shellPhase == 0) {
            a.shellPhase = 1;
        } else {
            a.mag[k]++;
            if (s.reserve() >= 0) a.res[k]--;
        }
        if (a.mag[k] < s.mag() && (s.reserve() < 0 || a.res[k] > 0)) {
            a.phaseStart = now;
            // 한 발 넣는 틱 = 이 총의 재장전 값(굴린 총, 증강이 이미 들어 있음) x 엉킴 배율(reloadMul 에서 그 몫만)
            a.reloadUntil = now + scaled(s.reload(), a.reloadMul / cueMul(s));
            if (a.shellPhase == 1 && now + 1 >= a.reloadUntil - 1) sound(p, s, "shell", Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1f, 1);
            return;
        }
        if (a.reloadEmpty) startPump(p, a, s, now);
        else {
            sound(p, s, "reload_end", Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1f, -1);
            cancelReload(a);
        }
    }

    private void startPump(Player p, Arms a, Spec s, long now) {
        a.shellPhase = 2;
        a.phaseStart = now;
        a.reloadUntil = now + scaled(SHELL_PUMP, a.reloadMul);
        sound(p, s, "pump", Sound.BLOCK_PISTON_CONTRACT, 1f, 0);
    }

    /** 쏜 뒤 동작 소리: 산탄총 펌프(4 틱 뒤), 저격총 노리쇠 당김(1 틱), 밂(4 틱) */
    private void cycleTick(Player p, Arms a, long now) {
        if (a.cycleKind == null) return;
        Spec s = a.spec(a.cycleKind);
        long el = now - a.cycleAt;
        if (a.cycleKind == Kind.SHOTGUN) {
            if (el >= 4) {
                sound(p, s, "pump", Sound.BLOCK_PISTON_CONTRACT, 1f, 0);
                a.cycleKind = null;
            }
        } else if (a.cycleKind == Kind.SNIPER || a.cycleKind == Kind.BOLTACTION || a.cycleKind == Kind.ANTIMATERIEL) {
            if (a.cycleStep == 0 && el >= 1) {
                sound(p, s, "bolt_pull", Sound.BLOCK_PISTON_EXTEND, 1f, -1);
                a.cycleStep = 1;
            }
            if (a.cycleStep == 1 && el >= 4) {
                sound(p, s, "bolt_release", Sound.BLOCK_PISTON_CONTRACT, 1f, -1);
                a.cycleKind = null;
            }
        } else {
            a.cycleKind = null;
        }
    }

    /**
     * 총 손질 소리(16 블록 안 사람들, 소리 듣는 게이브는 onShot 으로 따로 앎). jitter 는 흔들림(바닐라 대체 소리도 1 x jitter).
     * 그 모델 전용 부품 사건이 팩에 있으면 그것을 울린다({@link #partEvent}). variant 는 그 사건의 변형 수 안일 때만 고른다(Props.seedFor)
     */
    private void sound(Player p, Spec s, String part, Sound fallback, float jitter, int variant) {
        Props.soundAround(p.getLocation().add(0, 1.2, 0), partEvent(s, "gun." + s.key() + "." + part), fallback, 0.9f, jitter, 1f, 16, variant);
    }

    /** 레일건이 나가는 틱: 모으기를 다 채운 때와 쏜 뒤 쉼이 끝나는 때 가운데 늦은 쪽(쉬다가 쏘면 모으는 시간, 이어 쏘면 연사 박자) */
    private double chargeEnd(Player p, Arms a, Spec s) {
        return Math.max(a.chargeAt + mode(s.kind()).ticks() * hooks.chargeMul(p, s.kind()), a.next[s.kind().ordinal()]);
    }

    /** HUD 용: 든 레일건을 모은 몫(0..1, 다 차면 나감), 모으지 않으면 -1 */
    public double charge(Player p) {
        Arms a = arms(p);
        Spec s = held(p);
        if (a == null || s == null || a.chargeAt < 0 || a.chargeKind != s.kind()) return -1;
        return Math.min(1, (host.now() - a.chargeAt) / Math.max(1, chargeEnd(p, a, s) - a.chargeAt));
    }

    /** HUD 용: 든 미니건 총열 회전(0..1), 미니건이 아니면 -1 */
    public double spin(Player p) {
        Arms a = arms(p);
        Spec s = held(p);
        return a == null || s == null || mode(s.kind()).fire() != Fire.SPIN ? -1 : a.spin;
    }

    /** HUD 용: {탄창, 예비(-1 무한), 재장전 중 0..1(-1 아님, 산탄총은 지금 한 발 또는 펌프의 진행)}. 총을 안 들었으면 null */
    public double[] ammo(Player p) {
        Arms a = arms(p);
        Spec s = held(p);
        if (a == null || s == null) return null;
        int k = s.kind().ordinal();
        double rl = -1;
        if (a.reloading != null) {
            double len = Math.max(1, a.reloadUntil - a.phaseStart);
            rl = Math.max(0, Math.min(1, 1 - Math.max(0, a.reloadUntil - host.now()) / len));
        }
        return new double[] {a.mag[k], s.reserve() < 0 ? -1 : a.res[k], rl};
    }

    /**
     * HUD 용 재장전 단계: {남은 틱, 이 단계 전체 틱}. 재장전 중이 아니면 null. 탄창 총은 재장전 전체 한 단계,
     * 산탄총은 시작 자세(7) / 한 발(9) / 끝 펌프(8) 마다 새 단계(탄창 수는 한 발 넣을 때마다 오름)
     */
    public int[] reloadPhase(Player p) {
        Arms a = arms(p);
        Spec s = held(p);
        if (a == null || s == null || a.reloading != s.kind()) return null;
        long now = host.now();
        return new int[] {(int) Math.max(0, a.reloadUntil - now), (int) Math.max(1, a.reloadUntil - a.phaseStart)};
    }

    // ------------------------------------------------------------------ 시험

    /**
     * 시험(이름 x 로도 됨): 모델목록 x = 종류별 모델 수, 모델상자 x [웨이브] [수] = 무기 상자를 수 번 굴려 모델마다 나온 수(사지 않음),
     * 안 나온 모델과 종류별 수
     */
    private String modelTest(String act, String[] a) {
        if (act.equals("모델목록")) {
            StringBuilder sb = new StringBuilder("models=" + Models.all().size());
            for (Kind k : Kind.values()) sb.append(' ').append(k.name()).append('=').append(Models.list(k).size());
            return sb.toString();
        }
        int wave = a.length > 3 ? (int) parse(a[3]) : 10;
        int cnt = a.length > 4 ? (int) parse(a[4]) : 2000;
        Map<String, Integer> seen = new java.util.TreeMap<>();
        Map<Kind, Integer> byKind = new EnumMap<>(Kind.class);
        for (int i = 0; i < cnt; i++) {
            Gun g = Gun.rollBox(Math.max(1, wave), null, rnd);
            seen.merge(g.model, 1, Integer::sum);
            byKind.merge(g.kind, 1, Integer::sum);
        }
        List<String> missing = new ArrayList<>();
        for (Models.Model m : Models.all()) if (m.kind() != Kind.PISTOL && !seen.containsKey(m.key())) missing.add(m.key());
        int lo = Integer.MAX_VALUE, hi = 0;
        for (int v : seen.values()) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        StringBuilder sb = new StringBuilder("wave=" + wave + " n=" + cnt + " models=" + Models.all().size() + " seen=" + seen.size() + " missing="
            + missing + " min=" + (seen.isEmpty() ? 0 : lo) + " max=" + hi + " |");
        for (Map.Entry<Kind, Integer> e : byKind.entrySet()) sb.append(' ').append(e.getKey().name()).append('=').append(e.getValue());
        return sb.toString();
    }

    /** 시험: ticks 틱 동안 방아쇠를 당긴 것으로 친다 */
    public void testFire(Player p, int ticks) {
        Arms a = arms(p);
        if (a != null) a.simUntil = host.now() + ticks;
    }

    /**
     * 시험 훅: /gave 시험 &lt;행동&gt; &lt;이름&gt; ... 중 이 클래스(와 수류탄, 연출)가 맡는 것. 처리하면 결과 글, 아니면 null(p 는 null 일 수 있음).
     * 쏘기 틱 / 재장전 / 총 KIND / 수류탄 / 수류탄주기 n / 총상태 / 돌기 도 [위아래] / 탄창 n / 예비 n / 바꾸기 0|1|2 / 섬광 틱 / 탄창빼기 틱 /
     * 반동보기 틱 / 웅크리기 on|off / 탭 / 멈춤 틱 / 쏜틱 / 총색 / 누름 / 배율 / 총굴리기 KIND [등급] [특성...] / 총정보 / 강화 [권총] / 불붙음,
     * 그 밖은 Grenades.test(수류탄기록), ShotFx.test(폭발, 터짐, 피, 연출수, 자국시험, 예산시험)
     */
    public String test(Player p, String[] a) {
        if (a.length < 2) return null;
        String act = a[1];
        double n = a.length > 3 ? parse(a[3]) : Double.NaN;
        if (act.equals("모델목록") || act.equals("모델상자")) return modelTest(act, a);
        if (p == null) return null;
        Arms ar = arms(p);
        switch (act) {
            // 새 종 갈고리 시험(2026-11-12 sp_engine)
            case "탄약빼기" -> {
                // 탄약빼기 <이름> [몫 0.25] [탄창도 0|1]
                if (ar == null) return "총 없음";
                int took = takeAmmo(p, Double.isNaN(n) ? 0.25 : n, a.length > 4 && a[4].equals("1"));
                double[] am = ammo(p);
                return "took=" + took + " ammo=" + (am == null ? "-" : (int) am[0] + "/" + (int) am[1]);
            }
            case "엉킴" -> {
                // 엉킴 <이름> [틱 200] [재장전 배 2] [헛방 0.5] [퍼짐 배 2]
                if (ar == null) return "총 없음";
                jam(p, Double.isNaN(n) ? 200 : (int) n, a.length > 4 ? parse(a[4]) : 2, a.length > 5 ? parse(a[5]) : 0.5, a.length > 6 ? parse(a[6]) : 2);
                return "jammed=" + jammed(p) + " misfires=" + jamMisfires;
            }
            case "주무기잠금" -> {
                if (ar == null) return "총 없음";
                lockPrimary(p, Double.isNaN(n) ? 200 : (int) n);
                return "locked=" + primaryLocked(p) + " slot=" + slot(p);
            }
            case "재장전끊기" -> {
                boolean was = reloading(p);
                return "was=" + was + " cancelled=" + cancelReload(p) + " now=" + reloading(p);
            }
            case "쏘기" -> {
                if (ar == null) return "총 없음";
                testFire(p, (int) (Double.isNaN(n) ? 20 : n));
                return "ok t=" + host.now();
            }
            case "재장전" -> {
                if (ar == null) return "총 없음";
                reload(p);
                return "reloading=" + ar.reloading;
            }
            case "총" -> {
                if (ar == null) return "총 없음";
                if (a.length < 4) return "총 " + kinds();
                Kind k;
                try {
                    k = Kind.valueOf(a[3].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return "모르는 총: " + a[3];
                }
                if (k == Kind.PISTOL) return "권총은 칸 1 에 늘 있음";
                givePrimary(p, k);
                return "ok " + k.name().toLowerCase(Locale.ROOT);
            }
            case "총굴리기" -> {
                // 총굴리기 <이름> <종류> [등급] [특성...]: 굴린 총을 주무기로(등급을 안 주면 지금 웨이브 확률표 대신 일반)
                if (ar == null) return "총 없음";
                if (a.length < 4) return "총굴리기 <이름> " + kinds() + " [COMMON|RARE|EPIC|LEGENDARY] [특성...]";
                Kind k;
                try {
                    k = Kind.valueOf(a[3].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return "모르는 총: " + a[3];
                }
                if (k == Kind.PISTOL) return "권총은 굴리지 않음";
                Gun.Rarity r = a.length > 4 ? Gun.Rarity.of(a[4]) : Gun.Rarity.COMMON;
                if (r == null) return "모르는 등급: " + a[4];
                Gun g = Gun.roll(k, r, rnd);
                if (a.length > 5) {
                    java.util.EnumSet<Gun.Trait> ts = java.util.EnumSet.noneOf(Gun.Trait.class);
                    for (int i = 5; i < a.length; i++) {
                        Gun.Trait t = Gun.Trait.of(a[i]);
                        if (t == null) return "모르는 특성: " + a[i];
                        ts.add(t);
                    }
                    g = new Gun(k, g.model, r, g.damage, g.rate, g.mag, g.reload, g.spread, g.range, g.pierce, ts, 0);
                }
                givePrimary(p, g);
                return "ok " + g.info();
            }
            case "총모델" -> {
                // 총모델 <이름> <모델 키> [등급] [특성...]: 그 모델의 굴린 총을 주무기로
                if (ar == null) return "총 없음";
                if (a.length < 4) return "총모델 <이름> <모델 키> [COMMON|RARE|EPIC|LEGENDARY] [특성...] (모델 " + Models.all().size() + ")";
                Models.Model md = Models.of(a[3]);
                if (md == null || md.kind() == Kind.PISTOL) return "모르는 모델: " + a[3];
                Gun.Rarity r = a.length > 4 ? Gun.Rarity.of(a[4]) : Gun.Rarity.COMMON;
                if (r == null) return "모르는 등급: " + a[4];
                Gun g = Gun.roll(md.kind(), md.key(), r, rnd);
                if (a.length > 5) {
                    java.util.EnumSet<Gun.Trait> ts = java.util.EnumSet.noneOf(Gun.Trait.class);
                    for (int i = 5; i < a.length; i++) {
                        Gun.Trait t = Gun.Trait.of(a[i]);
                        if (t == null) return "모르는 특성: " + a[i];
                        ts.add(t);
                    }
                    g = new Gun(md.kind(), md.key(), r, g.damage, g.rate, g.mag, g.reload, g.spread, g.range, g.pierce, ts, 0);
                }
                givePrimary(p, g);
                return "ok " + g.info();
            }
            case "총정보" -> {
                if (ar == null) return "총 없음";
                refreshMods(p, ar);
                Gun g = gun(p);
                Spec s = g == null ? null : ar.spec(g.kind);
                return (g == null ? "총없음" : g.info()) + (s == null ? "" : String.format(Locale.ROOT,
                    " | eff dmg=%.2f rps=%.2f mag=%d res=%d reload=%d spread=%.2f range=%.1f pierce=%d pellets=%d crit=%.2f", s.damage(), s.rps(), s.mag(),
                    s.reserve(), s.reload(), s.spread(), s.range(), s.pierce(), s.pellets(), ar.crit[g.kind.ordinal()])) + " | ammo=" + ar.mag[g == null ? 0
                        : g.kind.ordinal()] + "/" + ar.res[g == null ? 0 : g.kind.ordinal()] + " item=" + ar.item + "x" + ar.items() + " | mods=" + fmtMods(ar.mods)
                    + " " + hooks.info(p);
            }
            case "강화" -> {
                // 강화 <이름> [권총]: 상점 값 없이 한 단계
                if (ar == null) return "총 없음";
                boolean pistol = a.length > 3 && (a[3].equals("권총") || a[3].equalsIgnoreCase("pistol"));
                int lv = upgrade(p, pistol);
                return lv < 0 ? "강화 못 함" : "level=" + lv;
            }
            case "불붙음" -> {
                return "burning=" + burns.size();
            }
            case "장전박자" -> {
                // 든 총 재장전 박자(시작 뒤 틱): 끝, 탄창 빠짐(out), 꽂힘(in), 소리. 빈 총 재장전 기준(emptyOnly 포함)
                if (ar == null) return "총 없음";
                refreshMods(p, ar);
                Spec s = held(p);
                if (s == null) return "총 안 듦";
                double mul = cueMul(s);
                StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%s mul=%.3f end=%d", s.key(), mul, s.shells() ? -1 : s.reload()));
                if (s.shells()) sb.append(" start=").append(scaled(SHELL_START, mul)).append(" shell=").append(s.reload()).append(" pump=")
                    .append(scaled(SHELL_PUMP, mul));
                else for (Cue c : CUES.getOrDefault(s.kind(), List.of())) {
                    int at = scaled(c.at(), mul);
                    sb.append(' ').append(c.act() == MAG_OUT ? "out" : c.act() == MAG_IN ? "in" : "snd").append('@').append(at)
                        .append(at > s.reload() ? "!" : "");
                }
                return sb.toString();
            }
            case "맡기기" -> {
                // 시험: 무기 상태만 나간 것처럼 맡김(forget) 뒤 바로 equip 으로 되살림
                if (ar == null) return "총 없음";
                String before = ar.gun + " pistol=" + ar.pistol + " ammo=" + (ar.primary == null ? "-" : ar.mag[ar.primary.ordinal()] + "/"
                    + ar.res[ar.primary.ordinal()]);
                forget(p.getUniqueId());
                boolean was = parked(p.getUniqueId());
                equip(p);
                Arms nw = arms(p);
                return "parked=" + was + " before=" + before + " after=" + nw.gun + " pistol=" + nw.pistol + " ammo=" + (nw.primary == null ? "-"
                    : nw.mag[nw.primary.ordinal()] + "/" + nw.res[nw.primary.ordinal()]) + " slot=" + slot(p);
            }
            case "수류탄" -> {
                if (ar == null || grenades == null) return "수류탄 없음";
                if (ar.grenades <= 0) ar.grenades = 1;
                grenades.throwFrom(p);
                return "ok 남음=" + ar.grenades;
            }
            case "수류탄주기" -> {
                if (ar == null) return "총 없음";
                ar.grenades = (int) Math.max(0, Double.isNaN(n) ? Grenades.MAX : n);
                return "grenades=" + ar.grenades;
            }
            case "총상태" -> {
                if (ar == null) return "총 없음";
                Spec s = held(p);
                int k = s == null ? 0 : s.kind().ordinal();
                // 시험 클라 기록은 200 자에서 잘려서 자주 보는 값을 앞에
                return String.format("yaw=%.2f pitch=%.2f", p.getLocation().getYaw(), p.getLocation().getPitch()) + " using=" + usingHand(p)
                    + " shots=" + ar.shots + " held=" + (s == null ? "-" : s.key()) + " mag=" + (s == null ? 0 : ar.mag[k]) + " res="
                    + (s == null ? 0 : ar.res[k]) + " grenades=" + ar.grenades + " reloading=" + ar.reloading + " magOut=" + ar.magOut + " burst=" + ar.burst
                    + String.format(" climb=%.2f drift=%.2f bloom=%.2f", ar.climb, ar.drift, ar.bloom) + " flick=" + ar.groundFlicker + "/" + groundFixes + " walk=" + ar.sentWalk + " phase=" + ar.shellPhase
                    + " slot=" + slot(p) + " draw=" + Math.max(0, ar.drawUntil - host.now()) + " sentMag=" + ar.sentMag[0] + ","
                    + ar.sentMag[1] + " hits=" + ar.hits + " kills=" + ar.kills + " probe=" + (ar.probeAt < 0 ? -1 : host.now() - ar.probeAt)
                    + " t=" + host.now() + " aside=" + aside(p);
            }
            case "배율" -> {
                if (ar == null) return "총 없음";
                Spec s = held(p);
                return "mods=" + ar.mods + (s == null ? "" : String.format(" eff mag=%d res=%d rps=%.2f reload=%d pellets=%d pierce=%d range=%.1f dmg=%.1f",
                    s.mag(), s.reserve(), s.rps(), s.reload(), s.pellets(), s.pierce(), s.range(), s.damage()));
            }
            case "돌기" -> {
                Location l = p.getLocation();
                l.setYaw(l.getYaw() + (float) (Double.isNaN(n) ? 90 : n));
                if (a.length > 4) l.setPitch((float) parse(a[4]));
                p.teleport(l);
                return "ok";
            }
            case "탄창", "예비" -> {
                Spec s = held(p);
                if (ar == null || s == null || Double.isNaN(n)) return "총 없음";
                if (act.equals("탄창")) ar.mag[s.kind().ordinal()] = (int) n;
                else ar.res[s.kind().ordinal()] = (int) n;
                return "ok";
            }
            case "바꾸기" -> {
                if (ar == null || Double.isNaN(n)) return "총 없음";
                int slot = (int) n;
                if (slot == SLOT_PRIMARY && ar.primary == null || slot < 0 || slot > SLOT_ITEM) return "그 칸 없음";
                if (slot != slot(p)) switched(p, ar, slot);
                hold(p, slot);
                return "ok t=" + host.now();
            }
            case "섬광", "탄창빼기", "반동보기" -> {
                if (ar == null) return "총 없음";
                long until = host.now() + (long) (Double.isNaN(n) ? 100 : n);
                if (act.equals("섬광")) ar.debugFlashUntil = until;
                else if (act.equals("반동보기")) ar.debugKickUntil = until;
                else ar.debugMagUntil = until;
                return "ok";
            }
            case "웅크리기" -> {
                p.setSneaking(a.length > 3 && a[3].equals("on"));
                return "ok";
            }
            case "탭" -> {
                // 짧게 한 번 누름(onInteract 의 새 누름과 같음): 4 틱 동안 한 발
                if (ar == null) return "총 없음";
                ar.tapUntil = host.now() + 4;
                ar.confirmedAt = host.now();
                ar.probeAt = -1;
                return "ok t=" + host.now();
            }
            case "멈춤" -> {
                // n 틱(기본 40) 동안 tick() 을 건너뜀: Game 이 tick() 을 안 부를 때 저절로 정리되는지 봄
                pauseUntil = host.now() + (long) (Double.isNaN(n) ? 40 : n);
                return "ok t=" + host.now();
            }
            case "쏜틱" -> {
                if (ar == null) return "총 없음";
                StringBuilder sb = new StringBuilder("shots=" + ar.shots + " ticks=");
                for (int i = 0; i < 4; i++) sb.append(i == 0 ? "" : ",").append(ar.shotLog[(ar.shotLogAt + i) & 3]);
                Spec s = held(p);
                ItemStack it = p.getInventory().getItemInMainHand();
                CustomModelData cmd = it.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
                return sb + " held=" + (s == null ? "-" : s.key()) + " mag=" + (s == null ? 0 : ar.mag[s.kind().ordinal()]) + " reloading="
                    + ar.reloading + " phase=" + ar.shellPhase + " walk=" + ar.sentWalk + " cmd=" + (cmd == null ? "없음"
                        : cmd.flags() + "/" + cmd.colors().size()) + " stale=" + stale() + " t=" + host.now();
            }
            case "틱재우기" -> {
                // 틱재우기 <이름> [ms 62] [틱 200]: 서버 틱마다 그만큼 자서 TPS 를 떨어뜨림(62ms 면 15 TPS 쯤). 클라 시계는 스스로 가므로 도장 연출이 어긋나는지 봄
                lagMs = (int) Math.max(0, Math.min(400, Double.isNaN(n) ? 62 : n));
                lagUntil = Bukkit.getCurrentTick() + (a.length > 4 ? (int) parse(a[4]) : 200);
                return "lag=" + lagMs + "ms until=" + lagUntil;
            }
            case "도장보정" -> {
                // 도장보정 <이름> [on|off]: 셰이더 도장을 클라 시계 어림에 맞추기(ClientClock) 켬, 끔. 지금 앞섬 틱도 보임
                if (a.length > 3) ClientClock.enabled = !a[3].equals("off");
                return "clock=" + ClientClock.enabled + " ahead=" + ClientClock.ahead(host.world()) + " tps=" + String.format(Locale.ROOT, "%.1f", Bukkit.getTPS()[0])
                    + " mspt=" + String.format(Locale.ROOT, "%.1f", Bukkit.getAverageTickTime());
            }
            case "부품소리" -> {
                // 부품소리 <이름>: 손에 든 총의 재장전 박자와 부품 소리가 어느 사건으로 울리는지(모델 전용 사건이 팩에 있으면 그것, 없으면 종류 사건).
                // 끝의 * 는 팩에 없는 사건(바닐라 대체 소리로 울림). fire 줄은 같은 규칙이 팩의 모델 총소리를 찾아내는지 보는 견줌용
                Spec s = held(p);
                if (s == null) return "총 없음";
                StringBuilder sb = new StringBuilder(s.kind().name()).append(" model=").append(s.model()).append(" | 박자:");
                for (Cue c : CUES.getOrDefault(s.kind(), List.of())) {
                    if (c.events().length == 0) continue;
                    String ev = cueEvent(s, c);
                    sb.append(' ').append(c.at()).append('=').append(ev == null ? c.events()[0] + "*" : ev);
                }
                sb.append(" | 부품:");
                for (String part : new String[] {"draw", "dry", "reload_start", "shell", "reload_end", "pump", "bolt_pull", "bolt_release", "fire"}) {
                    String ev = partEvent(s, "gun." + s.key() + "." + part);
                    sb.append(' ').append(ev).append(Props.hasSound(ev) ? "" : "*");
                }
                return sb.toString();
            }
            case "총줄" -> {
                // 총줄 <이름>: 손에 든 총의 상점 스탯 줄을 글로(다른 쪽 총과 견줌: 값 뒤 괄호는 글자 색)
                if (ar == null) return "총 없음";
                Gun g = gun(p), other = g == ar.pistol ? ar.gun : ar.pistol;
                StringBuilder sb = new StringBuilder(g.name()).append(" 대 ").append(other == null ? "-" : other.name()).append(": ");
                for (Component c : g.lore(other)) {
                    sb.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c));
                    if (!c.children().isEmpty() && c.children().get(0).color() != null) sb.append('(').append(c.children().get(0).color().asHexString()).append(')');
                    sb.append(" / ");
                }
                return sb.toString();
            }
            case "화력표" -> {
                // 화력표 <이름> [종류]: 종류마다 한 대상 지속 화력(일반, 전설)과 초당 발 수. 종류를 주면 그 종류 모델마다(지속 화력, 피해, 연사, 탄창, 재장전 틱, 전설 특성)
                StringBuilder sb = new StringBuilder();
                if (a.length > 3) {
                    Kind k;
                    try {
                        k = Kind.valueOf(a[3].toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        return "모르는 총: " + a[3];
                    }
                    for (Models.Model md : Models.list(k)) {
                        Gun g = Gun.of(k, md.key(), Gun.Rarity.COMMON);
                        Spec s = g.spec();
                        sb.append(String.format(Locale.ROOT, "%s=%.1f(%.1fx%d %.2f/s %d %dt %s) ", md.key(), sustained(s), s.damage(), s.pellets(), shotsPerSecond(s),
                            s.mag(), reloadTicks(s), Gun.traitsOf(k, md.key(), Gun.Rarity.LEGENDARY)));
                    }
                    return sb.toString().trim();
                }
                for (Kind k : Kind.values()) {
                    Spec b = SPECS.get(k);
                    Gun lg = Gun.of(k, Models.defaultKey(k), Gun.Rarity.LEGENDARY);
                    sb.append(String.format(Locale.ROOT, "%s=%.1f/%.1f(%.2f/s) ", k.name(), sustained(b), sustained(lg.spec()), shotsPerSecond(b)));
                }
                return sb.toString().trim();
            }
            case "소리시각" -> {
                // 마지막 총소리 16 개를 보낸 시각 사이 간격(ms): 연사 박자가 틱(50ms)에 묶이지 않고 고른지
                StringBuilder sb = new StringBuilder("gaps_ms=");
                long prev = -1;
                for (int i = 0; i < 16; i++) {
                    long t = soundLog[(soundLogAt + i) & 15];
                    if (t == 0) continue;
                    if (prev >= 0) sb.append(String.format(Locale.ROOT, "%.0f ", (t - prev) / 1e6));
                    prev = t;
                }
                return sb + String.format(Locale.ROOT, "| rate=%.2f hear=%.2f", shotRate, othersHear);
            }
            case "총색" -> {
                // 손에 든 총 아이템의 custom_model_data(깃발, 색 16진수)
                CustomModelData cmd = p.getInventory().getItemInMainHand().getData(DataComponentTypes.CUSTOM_MODEL_DATA);
                if (cmd == null) return "없음 t=" + host.now() + " wt=" + worldTime();
                StringBuilder sb = new StringBuilder(cmd.flags().toString());
                for (Color c : cmd.colors()) sb.append(' ').append(String.format("%06X", c.asRGB()));
                return sb + " t=" + host.now() + " wt=" + worldTime();
            }
            case "누름" -> {
                // 서버 쪽 '사용 중'을 켜서 실제 쥠 판정(확인 신호, 핑만큼 더 쏘기, 시간 초과)을 거치게 함. 시험 클라는 다시 누르지 않으니
                // 10 틱 + 핑 여유 뒤 멈추고 holdGap 뒤 사용이 풀려야 함
                p.startUsingItem(EquipmentSlot.HAND);
                if (ar != null) {
                    ar.confirmedAt = host.now();
                    ar.probeAt = -1;
                    ar.tapUntil = host.now() + 4;
                }
                return "using=" + usingHand(p) + " t=" + host.now();
            }
            default -> {
            }
        }
        String r = arsenal.test(p, a);
        if (r == null) r = grenades == null ? null : grenades.test(p, a);
        if (r == null) r = fx().test(p, a);
        return r;
    }

    /** 종류 이름 목록(시험 훅 안내) */
    static String kinds() {
        StringBuilder sb = new StringBuilder();
        for (Kind k : Kind.values()) sb.append(sb.length() == 0 ? "" : "|").append(k.name());
        return sb.toString();
    }

    private static String fmtMods(GunMods m) {
        return String.format(Locale.ROOT, "dmg=%.2f rate=%.2f mag=%.2f reload=%.2f spread=%.2f recoil=%.2f range=%.2f pierce=%d pellets=%d crit=%.2f res=%.2f",
            m.damage(), m.rate(), m.mag(), m.reload(), m.spread(), m.recoil(), m.range(), m.pierce(), m.pellets(), m.crit(), m.reserve());
    }

    static double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
