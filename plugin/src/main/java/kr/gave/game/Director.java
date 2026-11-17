package kr.gave.game;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import kr.gave.Props;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.mob.SpeciesBan;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.world.Fog;
import kr.gave.world.Plaza;

/**
 * 웨이브 감독. 판마다 종 묶음을 뽑고, 웨이브마다 계획(종과 수, 쏠림, 사건)을 짜서 대기열로 내보낸다. 공식과 흐름은 docs/게이브도감.md
 * '웨이브 흐름', 모듈 계약은 docs/모듈/감독맵.md, 전장은 docs/모듈/맵변경_황야.md, 내보내기 모양(사방 고리)은 docs/모듈/떼와절망.md.
 *
 * <p>내보내기(사방 고리): 고리 종은 늘 360도에서 나온다. 웨이브 첫 무리는 한꺼번에 고르게 두르고, 그 뒤는 무리(띠)마다 같은 반지름에서 나와 줄지어
 * 조여 온다. 무리의 절반은 고르게, 나머지는 후보 각도 셋 가운데 사람들이 가장 안 보는 쪽(등 뒤)으로 간다. 웨이브 안에서 몰아치기와 잦아듦이
 * 되풀이되고 동시 수 목표만큼 채운다. 7웨이브부터 가끔 쏠림(한쪽 호에 덤, HUD 화살촉으로 미리 알림, 나머지 방향은 비지 않음). 밸런스 숫자(총량,
 * 동시 수, 체력, 보상)는 모두 {@link Tune} 한곳에 있다.
 *
 * <p>종 담당이 읽는 것: {@link #hpMul()}, {@link #hpMul(Species)}, {@link #speedMul()}, {@link #dmgMul()}, {@link #mutTimeMul()},
 * {@link #fog()}, {@link #silent()}, {@link #inBundle(Species)}, {@link #isBanned(Species)}, {@link #phase()}, {@link #waveSpecies()},
 * {@link #dirs()}, {@link #placeFor(Species)}, {@link #ringPoint(Species)}, {@link #forehead(Species)}, {@link #lettered(Gabe)},
 * {@link #mutation(String)}, {@link #plan()}.
 *
 * <p>감독이 놓는 개체: Mobs.spawn(sp, at, false) 바로 뒤(종의 spawned() 다음) maxHp 와 hp 에 hpMul(sp) 를 곱한다. 종이 직접 만드는 새끼(child)는
 * 곱하지 않으니 필요하면 종이 director.hpMul() 을 곱한다.
 *
 * <p>대기실 우리(설정 cage, 기본 꺼짐: 대기실은 중앙 원이고 우리 관련 코드는 조용히 아무 일도 하지 않는다). 켰을 때 우리 개체는 생성자 뒤,
 * spawned() 앞에 untargetable, noRules, noReward 가 이미 걸려 있고 {@link #cageSpawning()} 이 true 다. 그때 spawned() 는 자리를 옮기거나 사람에게
 * 영향을 주지 않는다.
 *
 * <p>Game.tick 이 부를 곳: 매 틱 맨 앞 {@link #sideTick()}, LOBBY 에서 {@link #lobbyTick()}, WAVE 에서 {@link #tick()}(부르기 전까지는 감독 예비
 * 스케줄러가 앞 둘을 대신 돈다).
 */
public final class Director {

    // ------------------------------------------------------------------ 상수

    /** 웨이브 단계: 1 배우기(1~5), 2 조합(6~12), 3 먹힘과 감염(13~20), 4 믿을 수 없음(21~30), 5 종이 배운다(31~35), 6 끝없는 증폭(36~: Tune.ENDLESS_FROM). */
    public static final int PHASE_LEARN = 1, PHASE_COMBO = 2, PHASE_EAT = 3, PHASE_TRUST = 4, PHASE_LEARNED = 5, PHASE_ENDLESS = 6;
    /** 웨이브 시작 뒤 첫 게이브까지 틱(쏠림 웨이브는 방향 알림 동안 더 김). */
    public static final int ANNOUNCE = 20, ANNOUNCE_DIR = 70;
    /** 새 종 혼자 보여 주는 최대 틱, 혼자 나오기가 실패할 때 다시 해 보는 틱 수(넘으면 건너뜀). */
    public static final int SOLO_HOLD = 200, SOLO_TRIES = 20;
    /** 새 종을 보여 주는 동안 떼가 비켜 나오는 호(그 종 각도 좌우, 도). */
    public static final double SOLO_ARC = 35;
    /** 미리 깔리는 종이 예산을 기다리는 최대 틱(넘으면 그 몫은 버림). */
    public static final int PLACED_WAIT = 600;
    /** 보상 종(G08 금빛 홍조)이 한 웨이브에 하나 나올 확률(도감: 웨이브당 1~2%). */
    public static final double BONUS_CHANCE = 0.015;
    /** 미리 깔린 종만 남았을 때 웨이브를 끝내기까지 틱, 아무도 안 죽고 멈춘 웨이브를 정리하기까지 틱. */
    public static final int LINGER = 400, STALL = 2400;
    /**
     * 사람을 직접 몰아붙이지 않는 종(상점 갉기, 박수, 실핏줄, 보고 배우기, 올라타기, 얼굴 꿰기): 한 웨이브에서 몰려오는 수의 Tune.PASSIVE_MAX 씩,
     * 모두 합쳐 Tune.PASSIVE_TOTAL 까지만(2026-11-11 실서버 13웨이브에 상점 갉는 G14 가 63마리로 웨이브를 채움).
     */
    public static final Set<Species> PASSIVE = Collections.unmodifiableSet(EnumSet.of(Species.G14, Species.G15, Species.G36, Species.G51, Species.G54, Species.G12));

    /**
     * 밸런스 숫자 한곳(총량, 동시 수, 내보내기 박자, 쏠림, 체력, 빠르기, 피해, 보상). 밸런스 담당은 이 값들만 맞추면 된다. 켠 채로 바꿔 보려면 시험 훅
     * {@code /gave 시험 균형 x <이름> <값>}(이름만 주면 그 값, 아무것도 안 주면 모두 보임). 종별 기본 체력과 보상은 tools/gen_species.py 의 TUNE.
     */
    public static final class Tune {
        /**
         * 난이도 손잡이 하나(2026-11-12 파워커브: docs/모듈/파워커브.md 2.1절). 1.0 = 아래 곡선 상수 그대로, 기준 1.15(무기 26종일 때). 설정 difficulty 로 읽고
         * 켠 채 '/gave 시험 균형 x DIFFICULTY <값>'. 총량 x D^DIFF_UNITS, 게이브 체력 x D^DIFF_HP, 사람이 받는 피해 x D^DIFF_DMG, 보스 체력 x D^DIFF_BOSS_HP.
         * 트럭이 받는 피해(Director.dmgWave)와 보스 공격 피해(Director.bossDmgMul)도 x D^DIFF_DMG. 원정 스테이지 갈래의 난이도 배수(Trip.mul 'diff')도 같은 자리에 곱한다.
         */
        public static double DIFFICULTY = 1.15, DIFF_UNITS = 0.5, DIFF_HP = 0.5, DIFF_DMG = 1.0, DIFF_BOSS_HP = 0.5;

        // 총량(단위) = max(UNITS_K x 접속자^UNITS_POW x (UNITS_BASE + UNITS_PER_WAVE x 웨이브), UNITS_MIN + UNITS_MIN_PER_WAVE x 웨이브) x 세기 x 숨 고르기 x 변이
        // x 톱니(PRE_BOSS, POST_BOSS) x 끝없는 구간(ENDLESS_UNITS) x 난이도^DIFF_UNITS.
        // 2026-11-12 파워커브: 2 + 0.6w → 2.85 + 0.91w(30명 1웨이브 174, 20웨이브 973, 35웨이브 1603 단위: 넘침은 MAX_ENTRIES 에서 체력으로)
        public static double UNITS_K = 6, UNITS_BASE = 2.85, UNITS_PER_WAVE = 0.91;
        /** 총량 하한(혼자여도 떼로 보이게): UNITS_MIN + UNITS_MIN_PER_WAVE x 웨이브. 2026-11-12 20 + 5w → 22 + 6w → 16 + 5w(파워커브: 혼자 공식이 17 + 5.5w 라 하한에 안 걸리게만) */
        public static double UNITS_MIN = 16, UNITS_MIN_PER_WAVE = 5;
        /** 톱니: 보스 바로 앞 웨이브 총량 x PRE_BOSS(조여 옴: 9, 19, 29, 34), 보스 바로 뒤 웨이브 x POST_BOSS(한숨: 11, 21, 31, 36). 숨 고르기와 보스 웨이브는 뺌. */
        public static double PRE_BOSS = 1.1, POST_BOSS = 0.85;
        /** 감독 세기 한 단계, 숨 고르기 웨이브(2026-11-12 1.4 → 1.1: 30명 숨 고르기가 4분 넘게 걸림), 변이 horde 한 겹의 총량 배율. */
        public static double STRENGTH_STEP = 0.12, BREATHER_UNITS = 1.1, HORDE_UNITS = 1.2;
        /** 웨이브 한 번에 내보낼 최대 개체 수(넘치는 몫은 체력으로). 2026-11-12 160 → 800(30명 판에서 대기열이 늘 160 에 막혀 체력만 불었음). */
        public static int MAX_ENTRIES = 800;

        // 동시 수 목표 = (ALIVE_BASE + ALIVE_PER_WAVE x 웨이브) x (1 + ALIVE_PEOPLE x (제곱근(접속자) - 1)), ALIVE_MAX 까지(넘친 몫은 체력으로).
        // 2026-11-12: 혼자 1웨이브 11, 10웨이브 40, 20웨이브 72 / 5명 x2.1 / 10명 x2.9 / 30명 x5.0(1웨이브 56, 10웨이브 200, 15웨이브부터 300).
        // 옛 값 7 + 2.8w, 사람 0.3, 상한 150(30명이 와도 x2.3 이라 화면이 비었음). 300 은 게임 안 측정(몹 400~500 마리 서버 틱, 패킷)으로 정함
        public static double ALIVE_BASE = 8, ALIVE_PER_WAVE = 3.2, ALIVE_PEOPLE = 0.9, ALIVE_MAX = 300;
        /** 떼 한 무리(디스플레이 8 넘는 떼 종)를 동시 수에서 몇으로 셀까, 목표를 채웠어도 조금씩 더 내보내는 한계(목표의 몇 배까지). */
        public static double SWARM_COUNTS = 4, OVERFILL = 1.5;
        /** 혼자일 때 OVERFILL 대신(2026-11-11 1.5 → 1.25: 혼자에게 빽빽함). */
        public static double OVERFILL_SOLO = 1.25;
        /** 감독이 내보낼 때 종의 새끼(분열, 알, 조각, 떼 터짐) 몫으로 비워 두는 몹 디스플레이 수(Mobs.BUDGET 안). */
        public static int CHILD_ROOM = 40;
        /** 한 웨이브 몰려오는 수에서 한 종이 넘지 못하는 몫(숨 고르기, 하늘 종만 웨이브는 빼고). 넘친 몫은 사람을 치는 종(앞잡이)에게. */
        public static double SHARE_MAX = 0.4;
        /** 사람을 직접 몰아붙이지 않는 종(Director.PASSIVE) 한 종의 몫, 모두 합친 몫. */
        public static double PASSIVE_MAX = 0.1, PASSIVE_TOTAL = 0.2;
        /**
         * 보스 웨이브 차례(2026-11-12 사용자 '35라운드를 엔딩으로', 같은 날 'B3 를 33 에'): BOSS_WAVES 의 웨이브(10 B1, 20 B2, 30 B4, 33 B3, 35 B5 엔딩),
         * 그 뒤로는 마지막 값에서 BOSS_EVERY 마다(45, 55 ...: 끝없는 구간, 회차). {@link Director#bossIndex(int)}, Plan.boss, bossWave(). 어느 보스인지는 보스 감독
         * (kr.gave.mob.boss.BossDirector.ORDER). 숨 고르기는 보스와 겹치지 않는 5의 배수: 15, 25, 40, 50, 60 ...
         */
        public static int BOSS_EVERY = 10;
        public static int[] BOSS_WAVES = {10, 20, 30, 33, 35};

        // 공격 박자(종의 쿨다운과 준비 틱에 곱함, Gabe.cd): TEMPO_BASE 에서 웨이브마다 -TEMPO_PER_WAVE, TEMPO_MIN 까지
        public static double TEMPO_BASE = 0.75, TEMPO_PER_WAVE = 0.007, TEMPO_MIN = 0.45;

        // 내보내기 박자: 무리(띠)는 BAND_EVERY 틱마다 하나(걸음 0.16 블록/틱이면 띠 사이 8블록), 띠 폭은 가운데 ± BAND_HALF
        public static int BAND_EVERY = 50;
        /** 한 틱에 내보내는 최대 수(2026-11-12: 무리 하나가 150~300 이 될 수 있어 한 틱에 몰아 만들면 틱이 튐). 넘으면 2틱 뒤에 이어서. */
        public static int BATCH_MAX = 40;
        public static double BAND_HALF = 2;
        /** 웨이브 첫 무리: max(FIRST_MIN, min(동시 수 목표, FIRST_SHARE x 대기열)). 360도에 고르게 한꺼번에. */
        public static int FIRST_MIN = 8;
        public static double FIRST_SHARE = 0.5;
        /** 그 뒤 무리 하나의 상한(동시 수 목표의 몇 배), 무리 가운데 등 뒤 편향으로 보내는 몫(나머지는 고르게), 등 뒤 편향의 후보 각도 수. */
        public static double BATCH_SHARE = 0.5, BACK_SHARE = 0.5;
        public static int CANDIDATES = 3;
        /** 새 개체가 사람에게서 떨어져 나와야 하는 거리의 상한(안개가 가리는 거리와 이 값 가운데 작은 쪽). */
        public static double HIDE_MAX = 64;
        /** 몰아치기(틱) → 잦아듦(틱)을 되풀이. 잦아들 때 동시 수 목표 배율, 대기열 마지막 몫(FINAL_SHARE)은 마지막 몰아치기(목표 x FINAL_GOAL). */
        public static int SURGE_MIN = 240, SURGE_MAX = 400, LULL_MIN = 120, LULL_MAX = 160;
        // 2026-11-12 LULL_GOAL 0.35 → 0.65: 잦아들어도 화면의 떼가 비지 않게
        public static double LULL_GOAL = 0.65, FINAL_SHARE = 0.35, FINAL_GOAL = 1.3;

        // 쏠림: LEAN_FROM 웨이브부터 LEAN_CHANCE 로, 몰려오는 종마다 LEAN_EXTRA 만큼 덤이 쏠림 쪽 좌우 LEAN_ARC 도 안에서 나옴
        public static int LEAN_FROM = 7;
        public static double LEAN_CHANCE = 0.35, LEAN_EXTRA = 0.3, LEAN_ARC = 40;

        // 체력: HP_BASE x 웨이브마다 HP_GROWTH 배(HP_LATE_FROM 웨이브 뒤로는 HP_GROWTH_LATE: 엔딩 뒤 끝없는 구간) x 난이도^DIFF_HP, 안개 짙음(4, 5단계), 맑음(1단계),
        // 변이 thick 한 겹, 숨 고르기, 넘침 보정 상한. 2026-11-12 파워커브: HP_BASE 0.49(새로, 인원 지수 HP_POW 0.75 와 한 묶음), 40 뒤 x1.05 → 35 뒤 x1.08
        public static double HP_BASE = 0.49, HP_GROWTH = 1.022, HP_GROWTH_LATE = 1.08;
        public static int HP_LATE_FROM = 35;
        /** 총량을 나눌 때 한 개체 몫의 하한 = 종 체력 / UNIT_HP(튼튼한 종일수록 적게 나옴: 웨이브 체력 총량이 종 섞임에 덜 흔들림). 0 이면 위협 등급표만. */
        public static double UNIT_HP = 25;
        public static double HP_FOG_DENSE = 0.8, HP_FOG_CLEAR = 1.25, HP_THICK = 1.15, HP_BREATHER = 0.6, HP_OVERFLOW_MAX = 4.0;

        // 빠르기(웨이브마다 +SPEED_PER_WAVE, 상한 +SPEED_MAX), 사람이 받는 피해(DMG_BASE 에서 웨이브마다 +DMG_PER_WAVE, 상한 DMG_MAX 배, x 난이도^DIFF_DMG,
        // 끝없는 구간은 그 위에 웨이브마다 x ENDLESS_DMG). 2026-11-12 0.6 + 0.015w(상한 1.8) → 0.8 + 0.025w(상한 5) → 파워커브 1.9 + 0.13w(상한 4.4: 20웨이브에서 멈춤.
        // 그 뒤는 수, 체력, 박자, 트럭 노림이 조임). 종의 한 번 피해와 공격 박자(TEMPO), 덮치기와 함께 근거는 떼와절망.md, 파워커브.md 2.2절
        public static double SPEED_PER_WAVE = 0.005, SPEED_MAX = 0.3, DMG_BASE = 1.9, DMG_PER_WAVE = 0.13, DMG_MAX = 4.4;
        /** 빠르기 밑: speedMul = SPEED_BASE + min(SPEED_MAX, SPEED_PER_WAVE x (웨이브 - 1)). 2026-11-12 사용자 '게이브 이동속도 조금만 처음부터 더 빠르게': 1 → 1.12(1웨이브부터 12%). 켠 채 '균형 x SPEED_BASE <값>'. */
        public static double SPEED_BASE = 1.12;
        /**
         * 적은 인원이 받는 피해: x min(1, (접속자 / DMG_FULL_PEOPLE)^DMG_PEOPLE_POW). 2026-11-12 사용자 '피해감소 제거': DMG_FULL_PEOPLE 30 → 1 이라 몇 명이든 1
         * (혼자도 30명 판과 같은 피해, 보스 bossDmgMul 도). 옛 감소를 되살리려면 '균형 x DMG_FULL_PEOPLE 30'(혼자 0.15, 5명 0.37, 10명 0.55, 30명부터 1).
         */
        public static double DMG_PEOPLE_POW = 0.55, DMG_FULL_PEOPLE = 1;

        // 보상: 감독이 낸 개체의 보상(종 기본값) x REWARD_MUL x (1 + REWARD_PER_WAVE x (웨이브 - 1)) x 무리 체력 배율^REWARD_CROWD(사람 수, 동시 수 넘침) x 넘침 보정.
        // 2026-11-12 파워커브: 1.4 → 0.95(수가 1.45배라 1인 벌이는 비슷), REWARD_CROWD 0.53(체력 지수 0.75 에서도 한 사람 벌이가 인원과 무관하게: 0.6 + 0.75 x 0.53 = 1)
        // 2026-11-14 돈 구조(build/audit/돈.md 안 B): 0.95 → 2.3. 설정 moneyMul 1.7 과 30명 판의 도움 배율(약 1.4)을 여기에 녹였다(설정 배율은 1 이 기본).
        // 나누기는 kr.gave.player.Wallet(팀 몫 70% 는 모두 똑같이, 30% 는 때린 만큼, 함께 잡으면 +10%)
        // 2026-11-16 사용자 "라운드 넘어갈수록 증강이나 업그레이드 뽑기 비싸지는거에 비해 돈 수입률 증가가 못따라가": 처치 돈도 웨이브마다
        // 오르게. 다만 상점 값(웨이브마다 +5%, Shop.Ware 의 growth 0.05)은 못 따라가는 3%(난이도로 남김, 같은 날 사용자 "완화만", "3%만"). 1웨이브는 그대로
        public static double REWARD_MUL = 2.3, REWARD_PER_WAVE = 0.03, REWARD_CROWD = 0.53;

        // 인원 비례: 총량은 접속자^UNITS_POW, 체력은 x 접속자^HP_POW(동시 수 상한에 걸린 몫도 체력으로). 사람 수와 무관하게 비슷하게(근거 tools/balance_sim.py)
        // 2026-11-11 0.45, 0.58 → 0.4, 0.45. 2026-11-12 → 0.6, 0.35: 30명 판이 웨이브마다 160 마리에 막혀 체력만 불었음(수는 많게, 한 마리는 덜 단단하게)
        // 2026-11-12 파워커브 → 0.6, 0.75(x HP_BASE 0.49): 큰 판은 화력이 한곳에 겹치고 범위 무기가 빽빽한 떼를 쓸어 1인 몫보다 훨씬 세다(30명 x12.8, 5명 x3.3, 혼자 x1)
        public static double UNITS_POW = 0.6, HP_POW = 0.75;
        // 거대 종 체력의 사람 수 지수(HPEXP, 2026-11-12 sp_engine): 종이 생성자에서 Gabe.hpPow = HP_POW_BIG 을 넣으면 감독이 그 개체 체력의 사람 수 몫을 접속자^HP_POW_BIG 으로 셈.
        // 지금은 HP_POW 와 같게 둠(species_critic 권장 0.6 은 옛 HP_POW 0.35 를 바탕으로 한 값). 값은 파워커브 담당이 정한다
        public static double HP_POW_BIG = 0.75;

        // 끝없는 구간(엔딩 35 뒤. 파워커브.md 10절): ENDLESS_FROM 웨이브부터 웨이브마다 피해 x ENDLESS_DMG(DMG_MAX 로 자른 값 위에), 총량 x ENDLESS_UNITS(체력은
        // HP_LATE_FROM, HP_GROWTH_LATE). 변이는 MUT_FROM 부터 MUT_EVERY 웨이브마다 하나(옛 41, 5), 쉬는 시간은 SHORT_BREAK_FROM 부터 15초(옛 50),
        // 안개는 CLEAR_FROM 부터 늘 맑음(옛 60). ENDLESS_FROM 0 이면 끔
        public static int ENDLESS_FROM = 36, MUT_FROM = 36, MUT_EVERY = 3, SHORT_BREAK_FROM = 36, CLEAR_FROM = 46;
        public static double ENDLESS_DMG = 1.06, ENDLESS_UNITS = 1.04;
        /** 쉬는 시간 시작에 서 있는 사람 체력 회복(최대 체력의 비율). */
        public static double BREAK_HEAL = 0.25;

        // 떼 소리 층: 살아 있는 몰려오는 수가 HORDE_FULL 이면 가장 촘촘하고 크게(HORDE_EVERY_MIN 틱마다), 적으면 HORDE_EVERY_MAX 틱마다.
        // 합창(킥킥)은 CHORUS_FROM 마리부터. 가장 가까운 몰려오는 게이브가 중앙 원 테두리에서 HEART_R 블록 안이면 심장 박동
        public static int HORDE_EVERY_MIN = 4, HORDE_EVERY_MAX = 36, CHORUS_FROM = 14;
        public static double HORDE_FULL = 60, HORDE_NEAR = 22, HEART_R = 16;

        // 판 끝(전멸): 남은 게이브가 END_SPEED 배로 중앙 원에 모여 원을 메운다(모자라면 END_FILL 까지 흙먼지에서 더 나옴), END_TICKS 뒤 기록
        public static int END_TICKS = 200, END_FILL = 48;
        public static double END_SPEED = 2.6;

        private Tune() {
        }
    }

    /** Tune.MUT_FROM(36) 웨이브부터 MUT_EVERY(3) 웨이브마다 하나씩 영구로 쌓이는 변이(압박형만). 뒤 셋은 겹쳐 쌓임. */
    public static final List<String> MUTATIONS = List.of("burst", "riders", "split", "mutfast", "elite", "swift", "thick", "horde");
    private static final Set<String> STACKING = Set.of("swift", "thick", "horde");
    /** 5웨이브 한정 정보 지우기 변이(HUD 가 종 이름표를 지움). */
    public static final String TEMP_NAMELESS = "nameless";

    /** 시선 규칙 종(한 웨이브에 하나), 잠복 계열(한 웨이브 둘까지), 먹는 종, 흉내와 미끼. */
    public static final Set<Species> GAZE = Collections.unmodifiableSet(EnumSet.of(Species.G23, Species.G07, Species.G44, Species.G43, Species.G20,
            Species.G81, Species.G122, Species.G139, Species.G97));
    public static final Set<Species> LATENT = Collections.unmodifiableSet(EnumSet.of(Species.G17, Species.G30, Species.G41, Species.G49));
    public static final Set<Species> EATERS = Collections.unmodifiableSet(EnumSet.of(Species.G27, Species.G34, Species.G39, Species.G143));
    public static final Set<Species> MIMIC = Collections.unmodifiableSet(EnumSet.of(Species.G35, Species.G44, Species.G17, Species.G07, Species.G47));
    /** 한 웨이브에 하나만 내는 거대 종(2026-11-12 통합: 묶음 8, 10, 12 와 거인 요청). 계획을 다 짠 뒤(라운드 종류가 고친 뒤에도) 1 로 줄인다. */
    public static final Set<Species> ONE_PER_WAVE = Collections.unmodifiableSet(EnumSet.of(Species.G106, Species.G149, Species.G126, Species.G169, Species.G184,
            Species.G196, Species.G200));

    // ------------------------------------------------------------------ 웨이브 계획

    /** 한 웨이브의 계획(감독이 웨이브 시작 전에 짬). 종 담당과 HUD 는 읽기만. */
    public static final class Plan {
        public final int wave;
        /** 종 → 내보낼 개체 수(미리 깔리는 것, 혼자 나오는 것 포함). */
        public final LinkedHashMap<Species, Integer> counts = new LinkedHashMap<>();
        /** 조합 패(6웨이브부터): 앞잡이, 받침, 가시. 없으면 null. */
        public Species lead, base, spike;
        /** 이 웨이브에 처음 나오는 종(혼자 먼저 나와 규칙을 보여 줌). 없으면 null. */
        public Species fresh;
        /** 몰려오는 방향(0 북, 1 동, 2 남, 3 서). 황야에서는 늘 네 방향 모두(길이 4): 고리 종은 360도에서 나온다. */
        public int[] dirs = {0, 1, 2, 3};
        /** 쏠림 쪽(0 북, 1 동, 2 남, 3 서): 그 쪽 호에 덤이 더 나온다(나머지 방향 몫은 그대로). 없으면 -1. */
        public int lean = -1;
        /** 쏠림 덤 개체 수와 종별 몫(counts 에 이미 들어 있음). */
        public int leanExtra;
        public final LinkedHashMap<Species, Integer> leanCounts = new LinkedHashMap<>();
        /** 안개 짙기 1..5(사건 없으면 0). */
        public int fog;
        public boolean breather, silent, night, flood, puddleEye;
        /** 보스 웨이브(Tune.BOSS_WAVES, 그 뒤 BOSS_EVERY 마다: bossIndex). 보스는 Director.boss 고리(보스 감독)가 낸다. 숨 고르기와 겹치지 않음. */
        public boolean boss;
        /** 맑은 웨이브의 탐조등 게이브 수. */
        public int searchlights;
        /** 이 웨이브 탐조등이 설 쪽(0..3, 맑은 웨이브). 없으면 -1. */
        public int lightDir = -1;
        /** 밤 웨이브의 노란 점 쪽(0..3) = 다음 맑은 웨이브의 lightDir. 없으면 -1. */
        public int dotDir = -1;
        /** 보상 종(G08)이 이 웨이브에 하나 섞였나. */
        public boolean bonus;
        /** 총량(단위), 체력 보정(넘침, 숨 고르기), 정예 수. */
        public double units, hpBonus = 1.0;
        /** 넘침 보정(개체 수 상한에 걸려 체력으로 돌린 배율)만큼 보상도 곱함(줄어든 마릿수만큼). */
        public double rewardBonus = 1.0;
        /** 계획을 짤 때 총량에 곱한 난이도 배율(난이도^DIFF_UNITS x 원정 'units'). 웨이브를 열 때 이 값이 달라졌으면(쉬는 시간의 길 투표, 시험 훅) 다시 짠다. */
        public double scale = 1.0;
        public int elites;
        /** 원정 막 덱 뽑기 표지(kr.gave.trip.Deck.Draw.stamp, 덱을 안 쓰면 0). 웨이브를 열 때 덱 뽑기가 달라졌으면(라운드 종류의 갈래가 정해짐) 다시 짠다. */
        public int deck;

        Plan(int wave) {
            this.wave = wave;
        }

        public int total() {
            int n = 0;
            for (int c : counts.values()) n += c;
            return n;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder("w" + wave);
            for (Map.Entry<Species, Integer> e : counts.entrySet()) sb.append(' ').append(e.getKey().name()).append('x').append(e.getValue());
            sb.append(" dirs=").append(dirNames(dirs)).append(" fog=").append(fog);
            if (lean >= 0) sb.append(" 쏠림=").append(Plaza.DIR_KO[lean & 3]).append('+').append(leanExtra);
            if (boss) sb.append(" 보스");
            if (breather) sb.append(" 숨고르기");
            if (silent) sb.append(" 조용");
            if (night) sb.append(" 밤");
            if (flood) sb.append(" 땅갈라짐");
            if (puddleEye) sb.append(" 눈");
            if (searchlights > 0) sb.append(" 탐조등").append(searchlights);
            if (lightDir >= 0) sb.append(" 빛쪽=").append(Plaza.DIR_KO[lightDir & 3]);
            if (dotDir >= 0) sb.append(" 점쪽=").append(Plaza.DIR_KO[dotDir & 3]);
            if (bonus) sb.append(" 금빛");
            if (fresh != null) sb.append(" 새종=").append(fresh.name());
            sb.append(String.format(Locale.ROOT, " units=%.1f hp=%.2f elite=%d", units, hpBonus, elites));
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ 상태

    public final Game game;
    /** 이 판 종 묶음(판 시작에 대기실 묶음을 옮겨 담음). */
    public final Set<Species> bundle = EnumSet.noneOf(Species.class);
    /** 대기실 우리에 도는 다음 판 묶음. */
    private final Set<Species> lobbyBundle = EnumSet.noneOf(Species.class);
    private final Random rnd = new Random();

    private int total, spawned;
    private Plan plan, nextPlan;
    /** 대기열 한 칸: 종과 쏠림 덤 여부(덤은 쏠림 쪽 호에서 나온다), 정한 각도(라디안, NaN 이면 없음: 보스 부름 bossLean). */
    private record Queued(Species sp, boolean lean, double ang) {
        Queued(Species sp, boolean lean) {
            this(sp, lean, Double.NaN);
        }
    }

    private final List<Queued> queue = new ArrayList<>();
    /** 원정 막 덱의 새 종 → 대기열에서 풀리는 틱(그 전에는 그 종 칸을 건너뛰고 뒤 칸을 냄). 원정.md 2.3절 '적어도 15초 간격으로 한 종씩'. */
    private final EnumMap<Species, Long> gateAt = new EnumMap<>(Species.class);
    private final List<Species> placedQueue = new ArrayList<>();
    private int qAt, pAt;
    private long waveStart, announceUntil, nextSpawnAt, lastDeathAt, lingerSince = -1;
    /** 내보내기 박자: 웨이브 첫 무리를 아직 안 냈나, 지금 몰아치기인가(아니면 잦아듦), 마지막 몰아치기인가, 그 구간이 끝나는 틱. */
    private boolean firstBatch, surge, finalOn;
    private long phaseUntil;
    private Species soloSp;
    private Gabe solo;
    private long soloUntil;
    private double soloAng;
    private int soloTries;
    // 소리 층(떼, 심장 박동)과 판 끝 연출
    private long nextHordeAt, nextChorusAt, nextHeartAt;
    /** 시험용: 이번 판에 울린 떼, 합창, 심장 박동 수. */
    private int hordeN, chorusN, heartN;
    private boolean ending;
    private long endAt;
    private int endFilled, endInside;
    private boolean waveRunning;
    private int startedRound;

    /** 감독 세기 -2..3(직전 웨이브 쓰러짐과 평균 체력으로 오르내림). */
    private int strength;
    private final Map<Integer, Integer> fogPlan = new HashMap<>();
    /** 웨이브별 미리 굴린 쏠림 쪽(없으면 -1)과 탐조등 쪽(밤 웨이브의 노란 점과 다음 맑은 웨이브 탐조등이 같은 쪽이 되게). */
    private final Map<Integer, Integer> leanPlan = new HashMap<>();
    private final Map<Integer, Integer> lightPlan = new HashMap<>();
    private int silentWave = -1;
    private final EnumMap<Species, Integer> introducedAt = new EnumMap<>(Species.class), lastSeen = new EnumMap<>(Species.class);
    private final EnumSet<Species> seenRound = EnumSet.noneOf(Species.class);
    private final EnumMap<Species, Integer> foreheadLv = new EnumMap<>(Species.class), foreheadKind = new EnumMap<>(Species.class);
    private Species foreheadLast;
    private final EnumMap<Species, Integer> downsBy = new EnumMap<>(Species.class);
    private int downsWave, trappedRound;
    private final LinkedHashMap<String, Integer> mutations = new LinkedHashMap<>();
    private final Set<Integer> mutWaves = new java.util.HashSet<>();
    private int namelessUntil = -1;
    private final Map<UUID, Life> lastLife = new HashMap<>();

    // 웨이브 중 개체 추적(죽음 감지: 변이, 이마 글자 보상)
    private final Set<Gabe> known = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Gabe> lettered = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Gabe> splitDone = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Gabe> fromDirector = Collections.newSetFromMap(new IdentityHashMap<>());

    // 사건 상태
    private int fogLevel;
    /** fogGoal: 목표 단계(0 기본 .. 5), fogPos: 지금 타임라인 자리(Fog.pos 차례, 옅은 것부터. 기본 단계 0 = 자리 1). */
    private double fogGoal, fogPos = 1;
    private boolean fogClock;
    private boolean silentOn, nightOn, floodOn, eyeOn, eyeTriggered;
    private int contamination;
    private Display yellowDot;
    private final List<Display> decor = new ArrayList<>(), eyes = new ArrayList<>(), onlookers = new ArrayList<>();
    private int murmurVoices, forcedMurmur;
    private long nextMurmurAt;
    private int[] forcedDirs;
    /** 시험 훅 '새종': 다음 계획의 새 종을 이것으로(그 웨이브에 쓸 수 있을 때만, 한 번 쓰면 풂). */
    private Species forcedFresh;
    private double pace = 1.0;
    private String lastEvent = "";

    // 대기실 우리
    private final List<Gabe> cage = new ArrayList<>();
    private final Map<Gabe, Long> cageLure = new IdentityHashMap<>();
    private boolean cageDirty = true;
    private long cageAt, nextCageSound;
    private Map<Species, List<String>> packSounds;
    private Set<String> packItems;
    /** 우리 개체를 놓는 동안 true(종의 spawned() 가 읽을 수 있음). */
    private boolean cageSpawning;

    // 곁 틱: Game.tick 이 sideTick(), lobbyTick() 을 부르기 전까지만 감독이 따로 도는 예비 작업(Game 이 부르면 그 몫은 멈춤)
    private org.bukkit.scheduler.BukkitTask sideTask;
    private boolean gameSide, gameLobby;

    public Director(Game game) {
        this.game = game;
        // 보스 웨이브(docs/모듈/보스.md, 2026-11-12 boss_infra): 보스 감독을 고리에 단다
        this.bosses = new kr.gave.mob.boss.BossDirector(game, this);
        this.boss = bosses;
        // 난이도 손잡이(파워커브.md 2.1절): 설정 difficulty(0 보다 클 때만). 보스 체력에도 난이도^DIFF_BOSS_HP
        try {
            double d = game.plugin.getConfig().getDouble("difficulty", Tune.DIFFICULTY);
            if (d > 0 && !Double.isNaN(d) && !Double.isInfinite(d)) Tune.DIFFICULTY = d;
            bossHpCfg = Math.max(0.01, game.plugin.getConfig().getDouble("bossHp", BOSS_HP_DEFAULT));
            // 운영자 피해 배율(/gave 데미지): 설정 damageMul, 범위 밖이면 가장자리로
            double dm = game.plugin.getConfig().getDouble("damageMul", 1.0);
            dmgCmd = Double.isNaN(dm) || Double.isInfinite(dm) ? 1.0 : Math.max(DMG_CMD_MIN, Math.min(DMG_CMD_MAX, dm));
        } catch (RuntimeException e) {
            bossHpCfg = BOSS_HP_DEFAULT;
        }
        syncBossHp();
        lobbyBundle.addAll(draw(rnd));
        try {
            Fog.install(game.plugin, game.world);
            fogClock = Fog.ready(game.world);
            if (fogClock) Fog.setTicks(game.world, Fog.ticksFor(0));
        } catch (RuntimeException e) {
            fogClock = false;
        }
        sideTask = Bukkit.getScheduler().runTaskTimer(game.plugin, this::fallbackSide, 1L, 1L);
    }

    // ------------------------------------------------------------------ 판 흐름(Game 이 부름)

    /** 판 시작(대기실에서 부르면 대기실 묶음을 이 판 묶음으로) 또는 판 끝(새 대기실 묶음을 뽑음). */
    public void reset() {
        // Game.start 는 round 를 올린 뒤 부른다(대기실에서 다시 부르는 중지, 끄기와 가름)
        boolean starting = game.state == Game.State.LOBBY && game.round != startedRound;
        if (starting) startedRound = game.round;
        clearCage();
        ending = false;
        endLured.clear();
        hordeN = chorusN = heartN = 0;
        endWaveEvents(true);
        clearDisplays(onlookers);
        clearDisplays(decor);
        clearDisplays(eyes);
        removeDot();
        setFog(0, true);
        long baseT = baseTime();
        if (game.world.getTime() != baseT) game.world.setTime(baseT);
        if (game.shop != null && contamination != 0) game.shop.contaminate(0);
        contamination = 0;
        total = spawned = 0;
        queue.clear();
        placedQueue.clear();
        qAt = pAt = 0;
        soloSp = null;
        solo = null;
        plan = nextPlan = null;
        waveRunning = false;
        strength = 0;
        fogPlan.clear();
        leanPlan.clear();
        lightPlan.clear();
        silentWave = rnd.nextDouble() < 0.4 ? 21 + rnd.nextInt(10) : -1;
        introducedAt.clear();
        lastSeen.clear();
        seenRound.clear();
        foreheadLv.clear();
        foreheadKind.clear();
        foreheadLast = null;
        downsBy.clear();
        dmgBoosts.clear();
        downsWave = trappedRound = 0;
        mutations.clear();
        mutWaves.clear();
        namelessUntil = -1;
        lastLife.clear();
        known.clear();
        lettered.clear();
        splitDone.clear();
        fromDirector.clear();
        murmurVoices = forcedMurmur = 0;
        forcedDirs = null;
        bundle.clear();
        gateAt.clear();
        if (starting && deck() != null) {
            // 원정 막 덱(원정.md 2.3절): 묶음을 뽑지 않고 라운드마다 덱이 종을 낸다. 묶음은 이 판에 나온 종이 쌓이는 자리(inBundle = 나왔나)
            log("묶음 없음: 원정 막 덱");
        } else if (starting) {
            if (lobbyBundle.isEmpty()) lobbyBundle.addAll(draw(rnd));
            bundle.addAll(lobbyBundle);
            log("묶음 " + names(bundle) + " (" + bundle.size() + "종)");
        } else {
            lobbyBundle.clear();
            lobbyBundle.addAll(draw(rnd));
            cageDirty = true;
        }
        if (boss != null) {
            try {
                boss.reset();
            } catch (RuntimeException e) {
                game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[감독] 보스 고리 reset 예외", e);
            }
        }
    }

    public void beginWave(int n) {
        // G150 모래시계 지킴이: 판 단위 갈고리라 판이 바뀌면 떼인다. 웨이브마다 다시 건다(이미 있으면 그대로, 34웨이브 전에는 아무것도 안 함)
        if (Species.G150.implemented()) kr.gave.mob.sp.G150.arm(game);
        kr.gave.trip.Deck dk = deck();
        if (bundle.isEmpty() && dk == null) {
            if (lobbyBundle.isEmpty()) lobbyBundle.addAll(draw(rnd));
            bundle.addAll(lobbyBundle);
        }
        clearDisplays(onlookers);
        clearDisplays(eyes);
        endWaveEvents(false);
        if (Tune.MUT_FROM > 0) for (int w = Tune.MUT_FROM; w <= n; w += Math.max(1, Tune.MUT_EVERY)) if (!mutWaves.contains(w)) addMutationFor(w);
        // 쉬는 시간에 짠 계획의 난이도 배율이 지금과 다르면(원정 길 투표로 갈래가 정해짐, 시험 훅 '균형 x DIFFICULTY') 다시 짠다
        tripAt = Long.MIN_VALUE;
        if (nextPlan != null && nextPlan.wave == n && Math.abs(nextPlan.scale - unitsScale()) > 1e-6) nextPlan = null;
        // 원정 막 덱: 쉬는 시간에 짠 계획의 덱 뽑기가 지금과 다르면(라운드 종류의 갈래가 정해짐, 덱을 켜고 끔) 다시 짠다
        if (nextPlan != null && nextPlan.wave == n && nextPlan.deck != deckStamp(dk, n)) nextPlan = null;
        plan = nextPlan != null && nextPlan.wave == n ? nextPlan : plan(n, rnd);
        nextPlan = null;
        // 원정: 이번 웨이브 계획이 정해짐(라운드 종류가 축 값을 입힘: trip_rounds 의 Rounds.EV_PLAN). 덱 확정(Deck.planned)은 그 뒤라 고친 종이 '나온 종'이 된다
        if (game.trip != null) game.trip.fire("roundPlan", plan);
        capOnes(plan);
        // 설정 bannedSpecies 로 막은 종(SpeciesBan): 고르는 길에서 이미 빠지지만 다른 부품이 넣은 것까지 마지막으로 뺀다
        stripBanned(plan);
        if (forcedFresh != null) {
            // 시험 훅 '새종'(막은 종도 연다: SpeciesBan.free)
            if (spawnable(forcedFresh) || (dk != null || SpeciesBan.listed(forcedFresh)) && forcedFresh.implemented() && !forcedFresh.is(Role.BOSS)) {
                plan.fresh = forcedFresh;
                plan.counts.merge(forcedFresh, 2, Math::max);
            }
            forcedFresh = null;
        }
        commit(plan);
        // 원정 막 덱: 이 계획의 종을 '나온 종'으로(덱에서 뺌), 묶음에도 쌓음
        kr.gave.trip.Deck.Draw deckDraw = null;
        if (dk != null && plan.deck != 0) {
            try {
                deckDraw = dk.draw(n);
                dk.planned(n, plan.counts.keySet());
            } catch (RuntimeException e) {
                game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[감독] 원정 막 덱 확정 예외", e);
            }
            bundle.addAll(plan.counts.keySet());
        }
        long now = game.now();
        waveStart = now;
        announceUntil = now + (plan.lean >= 0 ? ANNOUNCE_DIR : ANNOUNCE);
        nextSpawnAt = announceUntil;
        lastDeathAt = now;
        lingerSince = -1;
        solo = null;
        soloTries = 0;
        downsWave = 0;
        downsBy.clear();
        eyeTriggered = false;
        waveRunning = true;
        game.mobs.atkReset();
        known.clear();
        lettered.clear();
        splitDone.clear();
        fromDirector.clear();
        // 대기열: 혼자 나올 새 종 하나와 미리 깔리는 종은 따로
        queue.clear();
        placedQueue.clear();
        qAt = pAt = 0;
        soloSp = plan.fresh != null && plan.fresh.spawn != Species.Spawn.PLACED && plan.counts.getOrDefault(plan.fresh, 0) > 0 ? plan.fresh : null;
        List<Queued> lead = new ArrayList<>(), rest = new ArrayList<>();
        for (Map.Entry<Species, Integer> e : plan.counts.entrySet()) {
            Species sp = e.getKey();
            int c = e.getValue();
            if (sp == soloSp) c--;
            // 쏠림 덤은 그 종 몫의 뒤쪽 칸(섞여서 웨이브 내내 나옴)
            int leanFrom = c - Math.min(c, plan.lean >= 0 ? plan.leanCounts.getOrDefault(sp, 0) : 0);
            for (int i = 0; i < c; i++) {
                if (sp.spawn == Species.Spawn.PLACED) placedQueue.add(sp);
                else if (sp == plan.lead && i < c / 3 && i < leanFrom) lead.add(new Queued(sp, false));
                else rest.add(new Queued(sp, i >= leanFrom));
            }
        }
        Collections.shuffle(lead, rnd);
        Collections.shuffle(rest, rnd);
        queue.addAll(lead);
        queue.addAll(rest);
        gateAt.clear();
        if (deckDraw != null) deckQueue(deckDraw);
        firstBatch = true;
        surge = true;
        // 새 웨이브 첫 내보내기 전까지 finalSurge() 가 앞 웨이브 값을 돌려주지 않게(2026-11-12)
        finalOn = false;
        phaseUntil = announceUntil + between(Tune.SURGE_MIN, Tune.SURGE_MAX);
        total = plan.total();
        spawned = 0;
        startEvents(plan);
        // 보스 고리: 보통 웨이브에도 불러 지난 보스 상태를 지우게 함(웨이브 건너뛰기). 보스 웨이브면 보스를 놓는다
        if (boss != null) {
            try {
                boss.start(n, plan);
            } catch (RuntimeException e) {
                game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[감독] 보스 고리 예외(이 웨이브 보스 건너뜀)", e);
            }
        }
        game.records.noteWave(n);
        log("웨이브 " + n + " 시작 " + plan.describe() + " 접속 " + people() + " 세기 " + strength + " 변이 " + mutationNames()
                + String.format(Locale.ROOT, " 난이도 %.2f 체력x%.2f 피해x%.2f 보상x%.2f 동시 %d", difficulty(), hpMul(), dmgMul(), rewardMul(), Math.round(aliveGoal())));
    }

    public void beginBreak(int n) {
        long now = game.now();
        waveRunning = false;
        // 시험 훅으로 웨이브를 일찍 끝냈으면 남은 대기열은 버림(HUD 남은 수가 0 이 되게)
        queue.clear();
        placedQueue.clear();
        qAt = pAt = 0;
        soloSp = null;
        solo = null;
        total = spawned;
        int left = removeLeftovers("wave_end");
        gateAt.clear();
        // 감독 규칙: 쓰러진 수와 평균 체력으로 다음 세기
        double hp = 0;
        int standing = 0;
        for (Player p : game.players.standing()) {
            hp += game.players.get(p).hpFrac();
            standing++;
        }
        double avg = standing == 0 ? 0 : hp / standing;
        int ppl = people();
        int before = strength;
        if (downsWave == 0 && avg >= 0.75) strength = Math.min(3, strength + 1);
        else if (downsWave >= Math.max(1, (int) Math.ceil(ppl * 0.5)) || avg < 0.35) strength = Math.max(-2, strength - 1);
        // 이마 글자(31~): 이 웨이브에 가장 많이 쓰러뜨린 종
        if (n >= 31) {
            Species top = null;
            int best = 0;
            for (Map.Entry<Species, Integer> e : downsBy.entrySet()) {
                if (e.getValue() > best) {
                    best = e.getValue();
                    top = e.getKey();
                }
            }
            if (top != null) foreheadUp(top);
        }
        log(String.format(Locale.ROOT, "웨이브 %d 끝 %d초 쓰러짐 %d 평균체력 %.2f 세기 %d→%d 남은정리 %d", n, (now - waveStart) / 20, downsWave, avg, before, strength,
                left));
        // 종별 공격 기록(판뒤분석: 사람 곁에 든 수, 처음 곁까지 초, 곁에서 처음 때리기까지 초, 곁 1분에 때린 수, 한 번 피해)
        log("공격 w" + n + " " + game.mobs.atkSummary(8));
        endWaveEvents(false);
        setFog(fogBase(game.wave >= Tune.CLEAR_FROM ? 1 : 0), false);
        int next = n + 1;
        if (Tune.MUT_FROM > 0 && next >= Tune.MUT_FROM && (next - Tune.MUT_FROM) % Math.max(1, Tune.MUT_EVERY) == 0 && !mutWaves.contains(next)) addMutationFor(next);
        nextPlan = plan(next, rnd);
        if (n >= 20) {
            placeOnlookers(nextPlan);
            murmurVoices = Math.min(6, trappedRound) + forcedMurmur;
            nextMurmurAt = now + 60 + rnd.nextInt(60);
        }
        // 보스 고리: 남은 보스 정리, 다음이 보스면 예고(7.3절)
        if (boss != null) {
            try {
                boss.beginBreak(n);
            } catch (RuntimeException e) {
                game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[감독] 보스 고리 beginBreak 예외", e);
            }
        }
    }

    /** 웨이브 중 매 틱(대기열에서 내보내기, 사건). */
    public void tick() {
        long now = game.now();
        if (boss != null) {
            try {
                boss.tick();
            } catch (RuntimeException e) {
                if (now - bossErrAt >= 200) {
                    bossErrAt = now;
                    game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[감독] 보스 고리 틱 예외", e);
                }
            }
        }
        watchPeople();
        watchMobs(now);
        eventTick(now);
        soundTick(now);
        if (now < announceUntil) return;
        // 미리 깔리는 종: 처음에 한꺼번에, 디스플레이 예산이 모자라면 날 때까지 기다림(PLACED_WAIT 넘으면 그 몫은 버림)
        while (pAt < placedQueue.size()) {
            Species sp = placedQueue.get(pAt);
            if (!game.mobs.budgetFor(game.mobs.costOf(sp) + reserve())) {
                if (now - announceUntil >= PLACED_WAIT) {
                    log("웨이브 " + game.wave + " 미리 깔기 예산 모자람, " + (placedQueue.size() - pAt) + "개 버림");
                    spawned += placedQueue.size() - pAt;
                    pAt = placedQueue.size();
                }
                break;
            }
            spawnEntry(sp, false);
            pAt++;
            spawned++;
        }
        if (soloSp != null) {
            // 새 종 보여 주기: 사람들이 보는 쪽 흙먼지에서 하나가 먼저 걸어 나오고 금빛 화살촉으로 가리킨다. 떼는 멈추지 않는다(보여 주는 동안
            // 그 쪽 호만 비워 두어 눈에 띄게). 만들기가 계속 실패하면(종 코드 예외, 예산) SOLO_TRIES 틱 뒤 건너뜀
            Gabe g = game.mobs.budgetFor(soloSp) ? spawnEntry(soloSp, true) : null;
            if (g != null || !soloSp.implemented() || ++soloTries >= SOLO_TRIES) {
                if (g == null) log("웨이브 " + game.wave + " 새 종 " + soloSp.name() + " 혼자 나오기 실패, 건너뜀");
                spawned++;
                solo = g;
                soloUntil = now + SOLO_HOLD;
                soloSp = null;
                if (g != null) {
                    soloAng = Math.atan2(g.pos.getZ() - Plaza.CZ - 0.5, g.pos.getX() - Plaza.CX - 0.5);
                    game.hud.point(null, g.pos.clone(), 80);
                }
            }
        }
        if (solo != null && (solo.dead || now >= soloUntil)) solo = null;
        if (qAt < queue.size() && now >= nextSpawnAt) {
            boolean more = releaseBatch(now);
            nextSpawnAt = now + (more ? 2 : Math.max(5, Math.round(Tune.BAND_EVERY / pace)));
        }
        // 멈춘 웨이브 정리(대기열이 비었는데 오래 아무도 안 죽음). 보스가 살아 있으면 하지 않는다
        if (qAt >= queue.size() && pAt >= placedQueue.size() && now - lastDeathAt > STALL && !bossBlocking()) {
            int k = removeLeftovers("stall");
            lastDeathAt = now;
            log("웨이브 " + game.wave + " 막힘 정리 " + k);
        }
    }

    /**
     * 무리 하나를 내보낸다(띠 하나: 같은 반지름 띠에서 한꺼번에 나와 줄지어 걸어온다). 웨이브 첫 무리는 360도에 고르게 두르고, 그 뒤 무리는 절반을
     * 고르게, 나머지를 사람들이 가장 안 보는 쪽(후보 각도 셋 가운데)으로 보낸다. 쏠림 덤은 쏠림 쪽 호로. 몰아치기에는 동시 수 목표까지 채우고
     * 잦아듦에는 조금씩만(0 은 아님), 대기열의 마지막 FINAL_SHARE 는 가장 큰 마지막 몰아치기다. 숫자는 {@link Tune}.
     */
    private boolean releaseBatch(long now) {
        int left = queue.size() - qAt;
        boolean fin = left <= Tune.FINAL_SHARE * queue.size();
        finalOn = fin;
        if (fin) surge = true;
        else if (now >= phaseUntil) {
            surge = !surge;
            phaseUntil = now + Math.round((surge ? between(Tune.SURGE_MIN, Tune.SURGE_MAX) : between(Tune.LULL_MIN, Tune.LULL_MAX)) / pace);
        }
        double full = aliveGoal() * bossScale(), goal = full * (fin ? Tune.FINAL_GOAL : surge ? 1.0 : Tune.LULL_GOAL);
        int alive = aliveNow(), n;
        if (firstBatch) n = (int) Math.round(Math.max(Tune.FIRST_MIN, Math.min(goal, Tune.FIRST_SHARE * queue.size())));
        else {
            n = (int) Math.ceil(goal - alive);
            // 목표를 채웠어도 조금씩은 나온다(잦아들 때도 0 이 아님, 웨이브가 멈추지 않게). 목표의 OVERFILL 배를 넘으면 쉰다
            if (n < 1 && alive < full * (people() <= 1 ? Tune.OVERFILL_SOLO : Tune.OVERFILL)) n = 1;
            n = Math.min(n, Math.max(3, (int) Math.ceil(goal * Tune.BATCH_SHARE)));
        }
        n = Math.min(n, left);
        if (n <= 0) return false;
        // 한 틱에 BATCH_MAX 까지만(나머지는 2틱 뒤 이어서: 같은 띠, 첫 무리면 다음 조각도 고르게)
        boolean more = n > Tune.BATCH_MAX;
        n = Math.min(n, Math.max(1, Tune.BATCH_MAX));
        double band = bandRadius();
        int even = firstBatch ? n : (int) Math.round(n * (1 - Tune.BACK_SHARE));
        double a0 = rnd.nextDouble() * Math.PI * 2, step = Math.PI * 2 / Math.max(1, even);
        int made = 0;
        for (int i = 0; i < n && qAt < queue.size(); i++) {
            if (!gateAt.isEmpty() && !ungate(now)) break;
            Queued e = queue.get(qAt);
            // G101 나만 보이는 게이브: 동시에 서 있는 사람 셋에 하나, 한 사람에 하나까지. 넘치면 뒤의 다른 종과 자리를 바꿔 미룸(다른 종이 없으면 기다림)
            if (e.sp() == Species.G101 && g101Full()) {
                if (!deferAt(qAt, Species.G101)) break;
                e = queue.get(qAt);
            }
            if (!game.mobs.budgetFor(game.mobs.costOf(e.sp()) + reserve())) break;
            double ang;
            if (!Double.isNaN(e.ang())) ang = e.ang() + (rnd.nextDouble() - 0.5) * Math.toRadians(30);
            else if (e.lean() && plan != null && plan.lean >= 0) ang = leanAngle(plan.lean);
            else if (i < even) {
                ang = a0 + step * i + (rnd.nextDouble() - 0.5) * step * 0.4;
                // 고르게 나눈 자리가 누군가의 코앞(안 보이는 거리 안쪽)이면 안 보는 쪽 후보로 바꾼다: 끝 가까이 나가 있는 사람 눈앞 허공에서 솟지 않게
                if (exposed(ang, band)) ang = unwatchedAngle(band);
            } else ang = unwatchedAngle(band);
            if (solo != null) ang = besideSolo(ang);
            spawnEntry(e.sp(), false, ang, band);
            qAt++;
            spawned++;
            made++;
        }
        if (made > 0) firstBatch = false;
        // 덜 냈으면(예산이 막음) 서두르지 않음
        return more && made >= n;
    }

    /** G101 이 동시 상한(서 있는 사람의 1/3, 적어도 하나, 사람 수 넘지 않게)에 찼나. */
    private boolean g101Full() {
        int alive = 0;
        for (Gabe g : game.mobs.all()) if (g.sp == Species.G101 && !g.dead && !g.removed) alive++;
        int ppl = game.players.standing().size();
        return alive >= Math.max(1, Math.min(ppl, ppl / 3));
    }

    /** 대기열 at 칸(종 sp)을 그 뒤 첫 다른 종 칸과 바꾼다. 다른 종이 없으면 false. */
    private boolean deferAt(int at, Species sp) {
        for (int j = at + 1; j < queue.size(); j++) {
            if (queue.get(j).sp() == sp) continue;
            Collections.swap(queue, at, j);
            return true;
        }
        return false;
    }

    /** 대기열 qAt 칸을 지금 낼 수 있는 것으로(덱 새 종이 아직 풀릴 때가 아니면 뒤에서 풀린 칸을 찾아 바꿈). 낼 칸이 없으면 false. */
    private boolean ungate(long now) {
        gateAt.entrySet().removeIf(en -> en.getValue() <= now);
        if (gateAt.isEmpty()) return true;
        for (int j = qAt; j < queue.size(); j++) {
            if (gateAt.containsKey(queue.get(j).sp())) continue;
            if (j != qAt) Collections.swap(queue, qAt, j);
            return true;
        }
        return false;
    }

    /**
     * 원정 막 덱의 새 종 차례(원정.md 2.3절). 보스 라운드: 덱에 남은 종을 한 마리씩 대기열 맨 앞에(보스 떼 = 아직 못 본 얼굴들). 보통 라운드: 혼자 나오는
     * 종과 밑떼는 바로, 나머지 새 종은 Deck.gap(막) 틱 간격으로 한 종씩 풀어 무리에 섞는다(미리 깔리는 종은 처음에 깔림).
     */
    private void deckQueue(kr.gave.trip.Deck.Draw d) {
        if (d.boss) {
            List<Queued> front = new ArrayList<>();
            for (Species sp : d.fresh) {
                for (int i = qAt; i < queue.size(); i++) {
                    if (queue.get(i).sp() != sp) continue;
                    front.add(queue.remove(i));
                    break;
                }
            }
            queue.addAll(qAt, front);
            return;
        }
        long gap = kr.gave.trip.Deck.gap(d.act);
        if (gap <= 0) return;
        int k = soloSp != null ? 1 : 0;
        for (Species sp : d.fresh) {
            if (sp == soloSp || sp == plan.fresh || d.base.contains(sp) || sp.spawn == Species.Spawn.PLACED) continue;
            if (k > 0) gateAt.put(sp, announceUntil + gap * k);
            k++;
        }
    }

    /** 대기열에서 다음에 나올 종 k 개(시험: 덱 보스 라운드의 맨 앞, 새 종 풀기). */
    public List<Species> queueHead(int k) {
        List<Species> out = new ArrayList<>();
        for (int i = qAt; i < queue.size() && out.size() < k; i++) out.add(queue.get(i).sp());
        return out;
    }

    /** 대기열에서 풀리기를 기다리는 덱 새 종 수(풀릴 틱이 아직 안 온 것). */
    public int gated() {
        long now = game.now();
        int n = 0;
        for (long at : gateAt.values()) if (at > now) n++;
        return n;
    }

    // ------------------------------------------------------------------ 소리 층(떼, 심장 박동)

    /**
     * 웨이브 중 매 틱: 살아 있는 몰려오는 수에 따라 사방에서 깔리는 떼 소리(퍼덕 무리, 많으면 킥킥 합창. 많을수록 촘촘하고 크게)와, 가장 가까운
     * 몰려오는 게이브가 중앙 원 테두리에서 HEART_R 블록 안이면 낮은 심장 박동(가까울수록 빠르고 큼). 소리 없는 웨이브에서는 아무것도 울리지 않는다
     * (그 웨이브가 시작되는 순간 뚝 끊김). 떼 소리는 사람마다 그 사람에게서 HORDE_NEAR 블록 떨어진, 살아 있는 게이브 하나 쪽 자리에서 난다.
     */
    private void soundTick(long now) {
        if (silentOn) return;
        int n = 0, k = 0;
        double nearest = Double.MAX_VALUE;
        Gabe pick = null;
        for (Gabe g : fromDirector) {
            if (g.dead || g.sp.spawn == Species.Spawn.PLACED || g.disguised) continue;
            n += (int) Math.round(weight(g.sp));
            nearest = Math.min(nearest, Plaza.radius(g.pos.getX(), g.pos.getZ()));
            if (rnd.nextInt(++k) == 0) pick = g;
        }
        if (n == 0) return;
        double frac = Math.min(1, n / Tune.HORDE_FULL);
        List<Player> ps = game.players.inPlay();
        if (now >= nextHordeAt) {
            int every = (int) Math.round(Tune.HORDE_EVERY_MAX - (Tune.HORDE_EVERY_MAX - Tune.HORDE_EVERY_MIN) * frac);
            nextHordeAt = now + every + rnd.nextInt(Math.max(1, every / 2 + 1));
            for (Player p : ps) hordeSound(p, pick, "director.horde", Sound.ENTITY_PHANTOM_FLAP, (float) (0.3 + 0.7 * frac), 0.55f);
            hordeN++;
        }
        if (n >= Tune.CHORUS_FROM && now >= nextChorusAt) {
            nextChorusAt = now + Math.round(150 - 90 * frac) + rnd.nextInt(40);
            for (Player p : ps) hordeSound(p, pick, "director.chorus", Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, (float) (0.35 + 0.65 * frac), 0.5f);
            chorusN++;
        }
        double heartR = Plaza.R_SAFE + Tune.HEART_R;
        if (nearest <= heartR && now >= nextHeartAt) heartbeat(now, Math.max(0, Math.min(1, (heartR - nearest) / Tune.HEART_R)), ps);
    }

    /** 떼 소리 하나를 p 에게: 살아 있는 게이브 g 쪽(없으면 아무 쪽)으로 HORDE_NEAR 블록 떨어진 자리에서. */
    private void hordeSound(Player p, Gabe g, String event, Sound fallback, float volume, float fallbackPitch) {
        Location l = p.getLocation();
        double a = g == null ? rnd.nextDouble() * Math.PI * 2 : Math.atan2(g.pos.getZ() - l.getZ(), g.pos.getX() - l.getX()) + (rnd.nextDouble() - 0.5) * 0.6;
        Location at = l.clone().add(Math.cos(a) * Tune.HORDE_NEAR, 1.5, Math.sin(a) * Tune.HORDE_NEAR);
        Props.soundTo(p, at, event, fallback, volume, 0.85f + rnd.nextFloat() * 0.3f, fallbackPitch, -1);
    }

    /** 낮은 심장 박동(close 0 이면 느리고 작게, 1 이면 빠르고 크게). 다음 박동 틱을 정한다. */
    private void heartbeat(long now, double close, List<Player> ps) {
        nextHeartAt = now + Math.round(32 - 16 * close);
        heartN++;
        for (Player p : ps) Props.sound(p, "director.heart", Sound.ENTITY_WARDEN_HEARTBEAT, (float) (0.5 + 0.5 * close), 1f, 0.75f);
    }

    // ------------------------------------------------------------------ 판 끝 연출(전멸)

    /**
     * 판 끝(Game.gameOver 가 부름): 마지막 사람이 쓰러지면 남은 게이브가 모두 END_SPEED 배로 중앙 원 안 흩은 자리로 모여들어 원을 메운다. 모자라면
     * 대기열 남은 것과 G01 이 흙먼지 속(R_ROAM 밖)에서 END_FILL 마리까지 더 나와 함께 모인다(보상 없음). 모인 게이브는 사람을 치지 않는다(끌림 중에는
     * 종 행동이 돌지 않음). {@link #endTicks()} 뒤 Game 이 기록을 보여 준다.
     */
    public void gameOver() {
        if (boss != null) {
            try {
                boss.gameOver();
            } catch (RuntimeException e) {
                game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[감독] 보스 고리 gameOver 예외", e);
            }
        }
        waveRunning = false;
        ending = true;
        endAt = game.now();
        endFilled = 0;
        endLured.clear();
        int n = 0;
        for (Gabe g : game.mobs.all()) {
            if (g.dead || cage.contains(g)) continue;
            endLure(g);
            n++;
        }
        log("판 끝 연출: 남은 " + n + "마리 중앙 원으로");
    }

    /** 다시 하기(Game.retry): 판 끝 연출을 거두고 보스 고리를 되돌린다. 모여든 게이브는 Game 이 치우고, 판 단위 기록(덱, 나온 종, 변이)과 묶음은 그대로. */
    public void retry() {
        ending = false;
        endLured.clear();
        waveRunning = false;
        if (boss != null) {
            try {
                boss.retry();
            } catch (RuntimeException e) {
                game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[감독] 보스 고리 retry 예외", e);
            }
        }
    }

    /** 판 끝에서 기록을 보여 주기까지 틱(모여드는 시간). */
    public int endTicks() {
        return Tune.END_TICKS;
    }

    /** 판 끝 연출 중인가. */
    public boolean ending() {
        return ending;
    }

    private final Set<Gabe> endLured = Collections.newSetFromMap(new IdentityHashMap<>());

    private void endLure(Gabe g) {
        double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * (Plaza.R_SAFE - 1.5);
        double x = Plaza.CX + 0.5 + Math.cos(a) * r, z = Plaza.CZ + 0.5 + Math.sin(a) * r;
        double hover = g.sp.band.hover + (g.sp.spawn == Species.Spawn.SKY ? 1.5 + rnd.nextDouble() * 2 : 0);
        g.lure(new Vector(x, game.mobs.flow.floor(x, z) + hover, z), Tune.END_TICKS + 600);
        endLured.add(g);
    }

    private void endTick() {
        long t = game.now() - endAt;
        if (t % 5 != 0) return;
        int alive = 0, inside = 0;
        for (Gabe g : game.mobs.all()) {
            if (g.dead || cage.contains(g)) continue;
            alive++;
            if (Plaza.radius(g.pos.getX(), g.pos.getZ()) <= Plaza.R_SAFE) inside++;
            if (!endLured.contains(g)) endLure(g);
        }
        endInside = inside;
        // 원을 메울 만큼 더(흙먼지 속에서 걸어 나옴). 기록 1초 전까지만
        for (int k = 0; k < 4 && alive < Tune.END_FILL && t < Tune.END_TICKS - 20; k++) {
            Species sp = null;
            while (qAt < queue.size() && sp == null) {
                Species q = queue.get(qAt++).sp();
                if (q.spawn != Species.Spawn.PLACED && q.implemented()) sp = q;
            }
            if (sp == null) sp = Species.G01.implemented() ? Species.G01 : null;
            if (sp == null || !game.mobs.budgetFor(game.mobs.costOf(sp))) break;
            Gabe g = trySpawn(sp, ringAt(sp, rnd.nextDouble() * Math.PI * 2, Plaza.R_ROAM + 14), true);
            if (g == null) break;
            endLure(g);
            alive++;
            endFilled++;
        }
        if (!silentOn && t < Tune.END_TICKS - 40 && game.now() >= nextHeartAt) heartbeat(game.now(), 1.0 - (double) t / Tune.END_TICKS, game.players.inPlay());
    }

    /** 새 종을 보여 주는 동안 그 쪽 호(좌우 SOLO_ARC)는 비워 둔다: 들어오는 각도를 호 바깥 가까운 쪽으로 민다. */
    private double besideSolo(double ang) {
        double d = Math.atan2(Math.sin(ang - soloAng), Math.cos(ang - soloAng)), arc = Math.toRadians(SOLO_ARC);
        if (Math.abs(d) >= arc) return ang;
        return soloAng + Math.signum(d == 0 ? rnd.nextDouble() - 0.5 : d) * (arc + rnd.nextDouble() * 0.2);
    }

    private int between(int lo, int hi) {
        return lo + rnd.nextInt(Math.max(1, hi - lo + 1));
    }

    /** 이번 웨이브 동시 수 목표(살아 있는 몰려오는 개체 수. 웨이브가 깊을수록, 사람이 많을수록 큼. 숫자는 {@link Tune}). */
    public double aliveGoal() {
        return Math.min(Tune.ALIVE_MAX, aliveRaw());
    }

    private double aliveRaw() {
        return (Tune.ALIVE_BASE + Tune.ALIVE_PER_WAVE * Math.max(1, game.wave)) * (1 + Tune.ALIVE_PEOPLE * (Math.sqrt(people()) - 1));
    }

    /** 무리 체력 배율: 접속자^HP_POW x 동시 수 상한에 걸려 못 낸 몫(ALIVE_MAX 를 넘친 비율). 보상도 같이 곱한다(한 사람 벌이가 인원과 무관하게). */
    public double crowdMul() {
        return Math.pow(people(), Tune.HP_POW) * Math.max(1.0, aliveRaw() / Math.max(1.0, Tune.ALIVE_MAX));
    }

    /** 지금 살아 있는 몰려오는 개체 수(감독이 낸 것. 미리 깔린 종은 빼고 떼 한 무리는 SWARM_COUNTS 로 셈). */
    public int aliveNow() {
        double n = 0;
        for (Gabe g : fromDirector) {
            if (g.dead || g.sp.spawn == Species.Spawn.PLACED) continue;
            n += weight(g.sp);
        }
        return (int) Math.round(n);
    }

    /** 다 내보냈고 산 게이브가 없으면 true. 미리 깔린 종만 남으면 LINGER 틱 뒤 끝(쉬는 시간에 치움). */
    public boolean waveDone() {
        if (!waveRunning) return spawned >= total && game.mobs.alive() == 0;
        if (game.now() < announceUntil || pAt < placedQueue.size() || soloSp != null || qAt < queue.size()) return false;
        // 보스가 살아 있거나(시험 소환 포함) 무너짐 물결 중이면 안 끝남
        if (bossBlocking()) return false;
        int active = 0, placed = 0;
        for (Gabe g : game.mobs.all()) {
            if (g.dead) continue;
            // 웨이브를 붙들지 않는 개체(Gabe.noHold, persist: 2026-11-12 sp_engine)
            if (g.noHold || g.persist) continue;
            if (g.sp.spawn == Species.Spawn.PLACED && g.target == null) placed++;
            else active++;
        }
        if (active > 0) {
            lingerSince = -1;
            return false;
        }
        if (placed == 0) return true;
        if (lingerSince < 0) lingerSince = game.now();
        return game.now() - lingerSince >= LINGER;
    }

    /** 이번 웨이브에 아직 안 잡힌 수(남은 대기열 + 산 것, 대기실 우리 개체는 뺌). */
    public int remaining() {
        int q = waveRunning ? Math.max(0, queue.size() - qAt) + Math.max(0, placedQueue.size() - pAt) + (soloSp != null ? 1 : 0) : Math.max(0, total - spawned);
        int caged = 0;
        for (Gabe g : cage) if (!g.dead) caged++;
        // 웨이브를 붙들지 않는 개체(noHold, persist)는 남은 수에서 뺌
        for (Gabe g : game.mobs.all()) if (!g.dead && (g.noHold || g.persist) && !cage.contains(g)) caged++;
        return q + Math.max(0, game.mobs.alive() - caged);
    }

    /** 이번 웨이브 총 개체 수(HUD 막대 분모). */
    public int total() {
        return total;
    }

    /** 지금까지 내보낸 수. */
    public int spawned() {
        return spawned;
    }

    /** 쉬는 시간 틱(Tune.SHORT_BREAK_FROM(36) 웨이브부터 15초, 보스 엔딩 뒤 쉬는 시간은 보스 고리가 정함: 60초). */
    public int breakTicks() {
        int t = (game.wave >= Tune.SHORT_BREAK_FROM ? 15 : game.breakSeconds) * 20;
        if (boss == null) return t;
        try {
            return boss.breakTicks(t);
        } catch (RuntimeException e) {
            return t;
        }
    }

    // ------------------------------------------------------------------ 배율(종 담당이 읽음)

    /**
     * 뼈대 공식 체력 배율: HP_BASE x HP_GROWTH^(웨이브 - 1)(HP_LATE_FROM 뒤로는 웨이브마다 HP_GROWTH_LATE) x 난이도^DIFF_HP. 숫자는 {@link Tune}.
     * 사람 수 몫은 {@link #crowdMul()} 이 따로 곱한다.
     */
    public double baseHpMul() {
        int w = Math.max(1, game.wave), late = Tune.HP_LATE_FROM;
        double m = w <= late ? Math.pow(Tune.HP_GROWTH, w - 1) : Math.pow(Tune.HP_GROWTH, late - 1) * Math.pow(Tune.HP_GROWTH_LATE, w - late);
        return m * Tune.HP_BASE * Math.pow(difficulty(), Tune.DIFF_HP);
    }

    /** 이번 웨이브 체력 배율: 뼈대 공식 x 안개(짙으면 0.8, 맑으면 1.25) x 넘침, 숨 고르기 보정 x 변이(thick). */
    public double hpMul() {
        double m = baseHpMul();
        if (fogLevel >= 4) m *= Tune.HP_FOG_DENSE;
        else if (fogLevel == 1) m *= Tune.HP_FOG_CLEAR;
        if (plan != null) m *= plan.hpBonus;
        m *= Math.pow(Tune.HP_THICK, mutations.getOrDefault("thick", 0));
        m *= crowdMul() * tripHp;
        return m;
    }

    /** 이 종 체력 배율(hpMul x 이마 글자 체력 강화 단계마다 1.3). */
    public double hpMul(Species sp) {
        double m = hpMul();
        if (sp != null && foreheadKind.getOrDefault(sp, 0) == 1) m *= Math.pow(1.3, foreheadLv.getOrDefault(sp, 0));
        return m;
    }

    /** 이번 웨이브 빠르기 배율(밑 SPEED_BASE 1.12 + 웨이브마다 0.5%, 오름 상한 +30%) x 물웅덩이 속 눈(1.15) x 변이(swift). 이마 글자 빠르기는 감독이 그 종 baseSpeed 에 곱함. */
    public double speedMul() {
        double m = Tune.SPEED_BASE + Math.min(Tune.SPEED_MAX, Tune.SPEED_PER_WAVE * Math.max(0, game.wave - 1));
        if (eyeTriggered) m *= 1.15;
        m *= Math.pow(1.1, mutations.getOrDefault("swift", 0));
        if (ending) m *= Tune.END_SPEED;
        tripMuls();
        m *= tripSpeed;
        return m;
    }

    /**
     * 이번 웨이브 게이브가 사람에게 주는 피해 배율 = 웨이브 피해 배율({@link #dmgWave()}) x 적은 인원 배율({@link #dmgPeople()}). Players.hurt 가 곱한다.
     */
    public double dmgMul() {
        return dmgWave() * dmgPeople() * dmgBoost() * dmgCmd;
    }

    /** 운영자 피해 배율 받는 범위(/gave 데미지). */
    public static final double DMG_CMD_MIN = 0.1, DMG_CMD_MAX = 10;

    /** 운영자 피해 배율(/gave 데미지, 설정 damageMul, 기본 1): dmgMul() 과 bossDmgMul() 에 곱한다(트럭 피해 dmgWave 에는 안 듦). */
    private double dmgCmd = 1.0;

    /** 지금 운영자 피해 배율. */
    public double dmgCmd() {
        return dmgCmd;
    }

    /** 운영자 피해 배율을 바꾼다(바로 먹음). 범위(DMG_CMD_MIN..DMG_CMD_MAX) 밖이거나 숫자가 아니면 false 로 그대로 둔다. */
    public boolean dmgCmd(double v) {
        if (Double.isNaN(v) || v < DMG_CMD_MIN - 1e-9 || v > DMG_CMD_MAX + 1e-9) return false;
        dmgCmd = v;
        return true;
    }

    // ------------------------------------------------------------------ 새 종 갈고리(2026-11-12 sp_engine, docs/모듈/API.md '새 종 갈고리')

    private final Map<Object, Double> dmgBoosts = new LinkedHashMap<>();

    /**
     * DMGMUL: 판 전체 게이브 피해 배율 칸(키마다 하나, 여럿이면 곱함). dmgMul() 에 곱해져 게이브가 사람에게 주는 모든 피해에 든다(트럭 피해 dmgWave 에는 안 듦).
     * mul 1 이면 뗌. 판이 바뀌면 지운다. 웨이브마다 되돌리는 것은 건 쪽이 한다(Mobs.Field.waveStart 가 좋음).
     */
    public void dmgBoost(Object key, double mul) {
        if (key == null) return;
        if (Math.abs(mul - 1) < 1e-9) dmgBoosts.remove(key);
        else dmgBoosts.put(key, Math.max(0, mul));
    }

    /** DMGMUL: 지금 판 전체 피해 배율 칸의 곱. */
    public double dmgBoost() {
        if (dmgBoosts.isEmpty()) return 1;
        double k = 1;
        for (double v : dmgBoosts.values()) k *= v;
        return k;
    }

    /** DIRECTOR: 이번 웨이브가 시작한 뒤 지난 틱(웨이브 중이 아니면 -1). */
    public long waveTicks() {
        return waveRunning ? game.now() - waveStart : -1;
    }

    /** HPEXP: 이 개체 체력에 곱할 배율(hpMul(sp) 에 Gabe.hpPow 가 있으면 사람 수 몫을 그 지수로 바꿈). */
    private double hpMulFor(Gabe g, Species sp) {
        double k = hpMul(sp);
        if (!Double.isNaN(g.hpPow)) k *= Math.pow(people(), g.hpPow - Tune.HP_POW);
        return k;
    }

    /**
     * DIRECTOR: 감독이 낸 것처럼 놓기(줄로 서는 종이 나머지를 옆에 놓을 때 같은 데): Mobs.spawn 뒤 체력 배율(hpMul, Gabe.hpPow), 보상 배율, 감독 추적
     * (fromDirector, 처음 보는 종 알림)을 감독이 내는 것과 똑같이 건다. 웨이브 총량 셈(total, spawned)에는 안 든다. 예산이 차거나 실패하면 null.
     */
    public Gabe spawnLike(Species sp, Vector at) {
        if (sp == null || at == null || !sp.implemented()) return null;
        Gabe g = trySpawn(sp, at, false);
        if (g == null) return null;
        g.maxHp *= hpMulFor(g, sp);
        g.hp = g.maxHp;
        double rm = rewardMul();
        if (rm != 1.0 && g.reward > 0) g.reward = (int) Math.max(1, Math.round(g.reward * rm));
        fromDirector.add(g);
        known.add(g);
        onNew(g);
        return g;
    }

    /**
     * 웨이브 피해 배율(사람 수 보정 빼고): min(DMG_MAX, DMG_BASE + DMG_PER_WAVE x (웨이브 - 1)) x 끝없는 구간(ENDLESS_DMG^k) x 난이도^DIFF_DMG x 원정 'dmg'.
     * 사람이 아닌 것(트럭)이 받는 피해는 이것을 쓴다(파워커브.md 6절: 트럭 피해 = 무는 값 x dmgWave() x TRUCK_DMG / 접속자^TRUCK_POW).
     */
    public double dmgWave() {
        int w = Math.max(1, game.wave);
        tripMuls();
        return Math.min(Tune.DMG_MAX, Tune.DMG_BASE + Tune.DMG_PER_WAVE * (w - 1)) * Math.pow(Tune.ENDLESS_DMG, endlessK(w)) * Math.pow(difficulty(), Tune.DIFF_DMG)
                * tripDmg;
    }

    /** 적은 인원이 받는 피해 배율: min(1, (접속자 / DMG_FULL_PEOPLE)^DMG_PEOPLE_POW). 2026-11-12 DMG_FULL_PEOPLE 1 이라 늘 1(인원 피해 감소 없앰). */
    public double dmgPeople() {
        return Math.min(1.0, Math.pow(people() / Math.max(1.0, Tune.DMG_FULL_PEOPLE), Tune.DMG_PEOPLE_POW));
    }

    /**
     * 보스 공격 피해에 곱할 배율(보스 표 값은 웨이브 피해 배율과 떨어진 절대값이다): 난이도^DIFF_DMG x 적은 인원 배율. Boss.hitBoss 가 곱하면 된다
     * (파워커브.md 8절: 지금 보스 한 방은 사람 수와 난이도에 무관).
     */
    public double bossDmgMul() {
        return Math.pow(difficulty(), Tune.DIFF_DMG) * dmgPeople() * dmgCmd;
    }

    /**
     * 난이도 손잡이의 지금 값: Tune.DIFFICULTY x 원정 스테이지 갈래의 난이도 배수(Trip.mul 'diff': 부품이 답하지 않으면 1). 총량과 체력에는 제곱근(DIFF_UNITS,
     * DIFF_HP), 피해에는 그대로(DIFF_DMG) 곱한다.
     */
    public double difficulty() {
        tripMuls();
        return Math.max(0.01, Tune.DIFFICULTY) * tripDiff;
    }

    /** 총량에 곱하는 난이도 배율: 난이도^DIFF_UNITS x 원정 'units'. */
    public double unitsScale() {
        return Math.pow(difficulty(), Tune.DIFF_UNITS) * tripUnits;
    }

    /** 끝없는 구간에 들어선 웨이브 수(ENDLESS_FROM 웨이브 = 1, 그 앞은 0). */
    public static int endlessK(int w) {
        return Tune.ENDLESS_FROM > 0 && w >= Tune.ENDLESS_FROM ? w - Tune.ENDLESS_FROM + 1 : 0;
    }

    /** 톱니(총량 배율): 보스 바로 앞 웨이브 x PRE_BOSS, 바로 뒤 웨이브 x POST_BOSS. 보스 자리와 숨 고르기 웨이브는 1(보스가 꺼져 있어도 자리는 같음). */
    public static double saw(int n) {
        if (bossIndex(n) >= 0 || (n >= 10 && n % 5 == 0)) return 1;
        double m = 1;
        if (bossIndex(n + 1) >= 0) m *= Tune.PRE_BOSS;
        if (n > 1 && bossIndex(n - 1) >= 0) m *= Tune.POST_BOSS;
        return m;
    }

    // 원정 배율(Trip.mul: 스테이지 갈래와 라운드 종류가 답한다. 원정이 꺼져 있거나 답하는 부품이 없으면 1). 한 틱에 한 번만 묻는다.
    // 키: diff(난이도 배수: 총량, 체력에 제곱근, 피해에 그대로), units(총량), hp(체력), speed(빠르기), dmg(피해), reward(처치 보상)
    private long tripAt = Long.MIN_VALUE;
    private double tripDiff = 1, tripUnits = 1, tripHp = 1, tripSpeed = 1, tripDmg = 1, tripReward = 1;

    private void tripMuls() {
        long now = game.now();
        if (now == tripAt) return;
        tripAt = now;
        tripDiff = tripMul("diff");
        tripUnits = tripMul("units");
        tripHp = tripMul("hp");
        tripSpeed = tripMul("speed");
        tripDmg = tripMul("dmg");
        tripReward = tripMul("reward");
    }

    private double tripMul(String key) {
        kr.gave.trip.Trip t = game.trip;
        if (t == null) return 1;
        try {
            double v = t.mul(key);
            return v > 0 && !Double.isNaN(v) && !Double.isInfinite(v) ? Math.max(0.05, Math.min(20, v)) : 1;
        } catch (RuntimeException | LinkageError e) {
            return 1;
        }
    }

    /** 설정 bossHp 기본값(2026-11-12 사용자 "보스 피통을 한 40% 가량 감소": 0.6). */
    public static final double BOSS_HP_DEFAULT = 0.6;
    /** 설정 bossHp(보스 체력 배율). 보스 감독의 hpScale 에는 여기에 난이도^DIFF_BOSS_HP 를 곱해 넣는다({@link #syncBossHp()}). */
    private double bossHpCfg = BOSS_HP_DEFAULT;

    /**
     * 보스 체력에 난이도 손잡이를 곱함: bosses.hpScale = 설정 bossHp x DIFFICULTY^DIFF_BOSS_HP(보스 체력 공식 BossDirector.hpFormula 가 hpScale 을 곱한다).
     * 켤 때와 시험 훅 '균형' 으로 DIFFICULTY, DIFF_BOSS_HP 를 바꿀 때 부른다. 시험 훅 '보스 x 배율 <값>' 은 hpScale 을 바로 덮어쓴다(다음 '균형 x DIFFICULTY' 까지).
     */
    private void syncBossHp() {
        if (bosses == null) return;
        bosses.hpScale = Math.max(0.001, bossHpCfg * Math.pow(Math.max(0.01, Tune.DIFFICULTY), Tune.DIFF_BOSS_HP));
    }

    /** 공격 박자 배율(종 쿨다운, 준비 틱에 곱함: Gabe.cd). TEMPO_BASE 에서 웨이브마다 -TEMPO_PER_WAVE, TEMPO_MIN 까지. */
    public double tempo() {
        return Math.max(Tune.TEMPO_MIN, Tune.TEMPO_BASE - Tune.TEMPO_PER_WAVE * Math.max(0, game.wave - 1));
    }

    /** 동시 수에서 이 종 한 개체를 몇으로 셀까: 디스플레이 8 넘는 떼 종(떼 역할)은 SWARM_COUNTS, 그 밖은 1(쪼개지는 종, 웅덩이처럼 몸 하나인 떼 역할도 1). */
    public static double weight(Species sp) {
        return sp.is(Role.SWARM) && sp.cost >= 8 ? Tune.SWARM_COUNTS : 1;
    }

    // ------------------------------------------------------------------ 감독 상태 읽기(연출 담당, HUD, 음악이 읽음. 2026-11-12)

    /** 지금 몰아치기인가(웨이브 중이 아니면 false). */
    public boolean surging() {
        return waveRunning && surge;
    }

    /** 대기열 마지막 몫(가장 큰 마지막 몰아치기)을 내보내는 중인가. */
    public boolean finalSurge() {
        return waveRunning && finalOn && qAt < queue.size();
    }

    /** 몰아치기나 잦아듦이 끝나기까지 남은 틱(웨이브 중이 아니거나 마지막 몰아치기면 0). */
    public long phaseLeft() {
        return waveRunning && !finalOn ? Math.max(0, phaseUntil - game.now()) : 0;
    }

    /** 아직 안 나온 수(몰려올 대기열 + 미리 깔릴 것 + 혼자 나올 새 종). */
    public int queued() {
        if (!waveRunning) return 0;
        return Math.max(0, queue.size() - qAt) + Math.max(0, placedQueue.size() - pAt) + (soloSp != null ? 1 : 0);
    }

    /** 이번 웨이브가 보스 웨이브인가(bossIndex, 그 보스가 구현돼 있을 때. 보스 자체는 {@link #boss} 고리의 보스 감독이 낸다). */
    public boolean bossWave() {
        return waveRunning && plan != null && plan.boss;
    }

    /**
     * n 웨이브가 몇 번째 보스 웨이브인가(0부터: 10 → 0, 20 → 1, 30 → 2, 33 → 3, 35 → 4, 45 → 5, 55 → 6 ...), 보스 웨이브가 아니면 -1.
     * 차례는 {@link Tune#BOSS_WAVES}, 그 뒤로는 마지막 값에서 {@link Tune#BOSS_EVERY} 마다. 어느 보스, 몇 회차인지는 보스 감독이 이 번호로 정한다.
     */
    public static int bossIndex(int n) {
        int[] w = Tune.BOSS_WAVES;
        int every = Tune.BOSS_EVERY;
        if (w == null || w.length == 0) return every > 0 && n >= every && n % every == 0 ? n / every - 1 : -1;
        for (int i = 0; i < w.length; i++) if (w[i] == n) return i;
        int last = w[w.length - 1];
        if (every <= 0 || n <= last || (n - last) % every != 0) return -1;
        return w.length - 1 + (n - last) / every;
    }

    /** n 보다 뒤의 첫 보스 웨이브(없으면 -1: 보스를 껐을 때). */
    public static int nextBossWave(int n) {
        for (int w = n + 1; w <= n + 200; w++) if (bossIndex(w) >= 0) return w;
        return -1;
    }

    /**
     * 보스 고리(생성자에서 보스 감독 {@link #bosses} 를 단다). 보스 웨이브 시작에 {@link BossHook#start} 를 부르고, {@link BossHook#blocking} 이 true 인
     * 동안은 대기열이 비어도 웨이브가 끝나지 않는다(막힘 정리도 안 함). 고리가 던지면 기록 한 줄 뒤 그 일은 건너뜀.
     */
    public BossHook boss;
    /** 보스 감독(kr.gave.mob.boss.BossDirector, docs/모듈/보스.md 11.4). HUD, 음악이 보스 틀과 상태를 읽는다. */
    public final kr.gave.mob.boss.BossDirector bosses;
    private long bossErrAt = Long.MIN_VALUE / 2;

    /**
     * 보스 고리. 감독이 부르는 자리: plan 끝 {@link #adjust}, beginWave 끝 {@link #start}, tick 첫머리 {@link #tick}, 곁 틱(모든 상태) {@link #sideTick},
     * releaseBatch 동시 수 목표 x {@link #aliveScale}, reserve + {@link #reserve}, waveDone 과 막힘 정리 {@link #blocking}, beginBreak 끝
     * {@link #beginBreak}, breakTicks {@link #breakTicks}, gameOver 첫머리 {@link #gameOver}, reset 끝 {@link #reset}, status 끝 {@link #status},
     * test '보스' {@link #test}.
     */
    public interface BossHook {
        /** 보스 웨이브 시작(계획 p 는 읽기만). */
        void start(int wave, Plan p);

        /** 보스가 아직 살아 있나(웨이브 끝을 막음). */
        boolean alive();

        /** 보스 웨이브 계획 고치기(plan 끝, 보스가 없으면 p.boss = false 로 보통 웨이브). */
        default void adjust(Plan p) {
        }

        /** 웨이브 중 매 틱(감독 tick 첫머리). */
        default void tick() {
        }

        /** 모든 상태 매 틱(감독 곁 틱). */
        default void sideTick() {
        }

        /** 동시 수 목표 배율(보스 웨이브 0.6). */
        default double aliveScale() {
            return 1;
        }

        /** 감독 예비 디스플레이 몫에 더할 것. */
        default int reserve() {
            return 0;
        }

        /** 웨이브 끝과 막힘 정리를 막나. */
        default boolean blocking() {
            return alive();
        }

        /** 보스 싸움 중인가(음악, 압도감, HUD). */
        default boolean active() {
            return false;
        }

        /** 쉬는 시간 시작(beginBreak 끝, 다음 계획이 정해진 뒤). */
        default void beginBreak(int n) {
        }

        /** 쉬는 시간 틱(기본 base). */
        default int breakTicks(int base) {
            return base;
        }

        /** 전멸(판 끝 연출 시작). */
        default void gameOver() {
        }

        /** 다시 하기(Game.retry, 판 끝 연출 중): 보스와 그 땅, 하늘을 거둠. 판 단위 기록(엔딩을 봤나)은 그대로. */
        default void retry() {
        }

        /** 판 시작, 판 끝. */
        default void reset() {
        }

        /** /gave 상태 에 붙일 글(boss=...). */
        default String status() {
            return "";
        }

        /** 시험 훅 '보스'. */
        default String test(Player p, String[] a) {
            return null;
        }
    }

    /** 보스가 살아 있거나 무너짐 물결 중인가(웨이브 끝, 막힘 정리를 막음). */
    public boolean bossBlocking() {
        if (boss == null) return false;
        try {
            return boss.blocking();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 보스 싸움 중인가(보스 웨이브이고 보스가 나와 있음): 압도감 연출, 음악이 읽음. */
    public boolean bossActive() {
        if (boss == null) return false;
        try {
            return boss.active();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private double bossScale() {
        if (boss == null) return 1;
        try {
            return boss.aliveScale();
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** 안개 단계를 바꿈(보스가 정함: B4 맑음, 엔딩 맑음). 0 기본, 1 맑음, 2..5 짙음. */
    public void bossFog(int level) {
        setFog(level, false);
        fogLevel = Math.max(0, Math.min(5, level));
    }

    /** 첫 게이브를 지금부터 ticks 뒤로 미룸(보스 등장 동안 떼를 안 냄). 이미 더 늦으면 그대로. */
    public void delayAnnounce(int ticks) {
        long until = game.now() + Math.max(0, ticks);
        if (until <= announceUntil) return;
        long d = until - announceUntil;
        announceUntil = until;
        nextSpawnAt = Math.max(nextSpawnAt, announceUntil);
        phaseUntil += d;
    }

    /** 대기열 끝에 그 종 n 개를 더함(보스 웨이브 보충). 감독 총량(HUD 막대 분모)도 늘림. */
    public void addQueue(Species sp, int n) {
        if (sp == null || n <= 0 || !sp.implemented()) return;
        for (int i = 0; i < n; i++) queue.add(new Queued(sp, false));
        total += n;
    }

    /** 쏠림 덤처럼 그 각도(가운데에서 본 라디안, 0 = +x 동, PI/2 = +z 남) 호에서 n 개를 더 냄(B4 부름). 종은 그 웨이브 앞잡이, 받침, 많은 종. */
    public void bossLean(double ang, int n) {
        if (plan == null || n <= 0) return;
        List<Species> pool = new ArrayList<>();
        if (plan.lead != null && plan.lead.implemented()) pool.add(plan.lead);
        if (plan.base != null && plan.base.implemented()) pool.add(plan.base);
        for (Species sp : plan.counts.keySet()) if (pool.isEmpty() && sp.spawn != Species.Spawn.PLACED && sp.implemented()) pool.add(sp);
        if (pool.isEmpty()) return;
        // 지금 내보낼 차례 바로 뒤에 끼워 넣어 곧 나오게
        int at = Math.min(qAt, queue.size());
        for (int i = 0; i < n; i++) queue.add(at, new Queued(pool.get(i % pool.size()), true, ang));
        total += n;
    }

    /** 남은 대기열을 버림(보스가 쓰러졌을 때: 무너짐 물결 뒤 웨이브가 끝나게). */
    public void dropQueue() {
        int left = Math.max(0, queue.size() - qAt) + Math.max(0, placedQueue.size() - pAt) + (soloSp != null ? 1 : 0);
        spawned += left;
        qAt = queue.size();
        pAt = placedQueue.size();
        soloSp = null;
    }

    /**
     * 감독이 낸 개체의 보상 배율(종 기본 보상에 곱함): REWARD_MUL x 무리 체력 배율^REWARD_CROWD x 넘침 보정 x 원정 'reward'(스테이지 갈래의 처치 보상 배수).
     * 숫자는 {@link Tune}.
     */
    public double rewardMul() {
        tripMuls();
        return Tune.REWARD_MUL * (1 + Tune.REWARD_PER_WAVE * Math.max(0, game.wave - 1)) * Math.pow(crowdMul(), Tune.REWARD_CROWD)
                * (plan != null ? plan.rewardBonus : 1.0) * tripReward;
    }

    /** 이 판 종 묶음에 들었나(원정 막 덱 판이면 이 판에 나온 종인가: 덱이 계획을 확정할 때 묶음에 쌓는다). */
    public boolean inBundle(Species sp) {
        return bundle.contains(sp);
    }

    /** 원정 막 덱(원정이 켜져 있고 Deck 부품이 덱을 쓰는 판이면 그것, 아니면 null: 옛 묶음). docs/모듈/원정.md 2.3절. */
    public kr.gave.trip.Deck deck() {
        kr.gave.trip.Trip t = game.trip;
        if (t == null || !t.enabled) return null;
        kr.gave.trip.Deck d = t.part(kr.gave.trip.Deck.class);
        return d != null && d.active() ? d : null;
    }

    /** 덱 뽑기 표지(덱이 없거나 뽑기가 비면 0). */
    private int deckStamp(kr.gave.trip.Deck dk, int n) {
        if (dk == null) return 0;
        try {
            kr.gave.trip.Deck.Draw d = dk.draw(n);
            return d.all().isEmpty() ? 0 : d.stamp;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 세계 시각 바탕(원정 테마가 답하면 그 막의 시각, 원정이 꺼져 있으면 13000). trip_theme 요청. */
    private long baseTime() {
        return game.trip == null ? 13000L : game.trip.baseTime();
    }

    /** 안개 기본 단계(원정 테마의 막 기본 'fogBase', 없으면 def). trip_theme 요청. */
    private int fogBase(int def) {
        return game.trip == null ? def : game.trip.askInt("fogBase", def);
    }

    /** 소리 없는 웨이브(웃음, 펄럭임 끊김). 종 소리는 Gabe.sound 가 이것을 보고 막고, Mobs 가 몸 펄럭임을 얼린다. */
    public boolean silent() {
        return silentOn;
    }

    /** 오래 살면 변함 시간 배율(맑은 웨이브 0.5, 변이 mutfast 0.5 더). */
    public double mutTimeMul() {
        double m = fogLevel == 1 ? 0.5 : 1.0;
        if (mutations.containsKey("mutfast")) m *= 0.5;
        return m;
    }

    /** 안개 짙기 1(맑음)..5, 사건 없으면 0. */
    public int fog() {
        return fogLevel;
    }

    /** 웨이브 단계(PHASE_*). 웨이브 0(준비)은 1. */
    public int phase() {
        return phaseOf(Math.max(1, game.wave));
    }

    public static int phaseOf(int w) {
        if (w <= 5) return PHASE_LEARN;
        if (w <= 12) return PHASE_COMBO;
        if (w <= 20) return PHASE_EAT;
        if (w <= 30) return PHASE_TRUST;
        if (w < Math.max(31, Tune.ENDLESS_FROM)) return PHASE_LEARNED;
        return PHASE_ENDLESS;
    }

    /** 이번 웨이브 계획(없으면 null). */
    public Plan plan() {
        return plan;
    }

    /** 다음 웨이브 계획(쉬는 시간에만, 없으면 null). */
    public Plan nextPlan() {
        return nextPlan;
    }

    /** 이번 웨이브에 나오는 종(감독이 내보내는 것). */
    public Set<Species> waveSpecies() {
        return plan == null ? Set.of() : Collections.unmodifiableSet(plan.counts.keySet());
    }

    /** 이번 웨이브 방향(0 북, 1 동, 2 남, 3 서). 황야에서는 늘 네 방향 모두(길이 4). 한쪽이 더 두꺼운 웨이브는 {@link #lean()}. */
    public int[] dirs() {
        return plan == null ? new int[] {0, 1, 2, 3} : plan.dirs.clone();
    }

    /** 이번 웨이브 쏠림 쪽(0 북, 1 동, 2 남, 3 서: 그 쪽 호에 덤이 더 나옴). 없으면 -1. */
    public int lean() {
        return plan == null ? -1 : plan.lean;
    }

    /** 이번 웨이브가 방향 d 에서도 오나. */
    public boolean fromDir(int d) {
        for (int x : dirs()) if (x == (d & 3)) return true;
        return false;
    }

    /** 금지표: 이 종을 이번 웨이브에 (더) 내면 안 되나. 시선 규칙 종 하나, 내리꽂는(G16)과 문틀(G47) 따로, 잠복 계열 둘까지. */
    public boolean isBanned(Species sp) {
        if (plan == null) return false;
        Set<Species> others = EnumSet.noneOf(Species.class);
        for (Species s : plan.counts.keySet()) if (s != sp) others.add(s);
        return !allowed(sp, others);
    }

    /** 한 웨이브 하나 상한(ONE_PER_WAVE): 계획의 수를 1 로(쏠림 덤도 같이 지움). */
    public static void capOnes(Plan p) {
        if (p == null) return;
        for (Species sp : ONE_PER_WAVE) {
            Integer c = p.counts.get(sp);
            if (c == null || c <= 1) continue;
            p.counts.put(sp, 1);
            Integer x = p.leanCounts.remove(sp);
            if (x != null) p.leanExtra = Math.max(0, p.leanExtra - x);
        }
    }

    /**
     * 설정 bannedSpecies 로 막은 종(SpeciesBan.listed)을 계획에서 뺀다(몫, 쏠림 덤, 조합 칸, 새 종 칸). 묶음, 덱, 라운드 고르기가 이미 빼므로 보통은 뺄 것이
     * 없다(다른 부품이 이름으로 넣은 것을 막는 마지막 자리).
     */
    private void stripBanned(Plan p) {
        if (p == null) return;
        EnumSet<Species> out = EnumSet.noneOf(Species.class);
        for (Species sp : p.counts.keySet()) if (SpeciesBan.listed(sp)) out.add(sp);
        if (out.isEmpty()) return;
        for (Species sp : out) {
            p.counts.remove(sp);
            Integer x = p.leanCounts.remove(sp);
            if (x != null) p.leanExtra = Math.max(0, p.leanExtra - x);
        }
        if (out.contains(p.fresh)) p.fresh = null;
        if (out.contains(p.lead)) p.lead = null;
        if (out.contains(p.base)) p.base = null;
        if (out.contains(p.spike)) p.spike = null;
        int el = 0;
        for (Map.Entry<Species, Integer> e : p.counts.entrySet()) if (e.getKey().is(Role.ELITE)) el += e.getValue();
        p.elites = el;
        log("막은 종 뺌 w" + p.wave + " " + names(out));
    }

    /** 두 종을 같은 웨이브에 넣으면 안 되나. */
    public static boolean bannedTogether(Species a, Species b) {
        if (a == b) return false;
        if (GAZE.contains(a) && GAZE.contains(b)) return true;
        return (a == Species.G16 && b == Species.G47) || (a == Species.G47 && b == Species.G16);
    }

    public boolean breather() {
        return plan != null && plan.breather && waveRunning;
    }

    public boolean night() {
        return nightOn;
    }

    public boolean flood() {
        return floodOn;
    }

    public boolean puddleEye() {
        return eyeOn;
    }

    /** 감독 세기 -2..3. */
    public int strength() {
        return strength;
    }

    /** 겹친 변이(키 → 쌓인 수, 넣은 차례). */
    public Map<String, Integer> mutations() {
        return Collections.unmodifiableMap(mutations);
    }

    public boolean mutation(String key) {
        return mutations.containsKey(key);
    }

    /** 정보 지우기 변이 중(HUD 종 이름표 지움). */
    public boolean hideNames() {
        return game.wave <= namelessUntil;
    }

    /** 이마 글자 단계(0 없음). */
    public int forehead(Species sp) {
        return sp == null ? 0 : foreheadLv.getOrDefault(sp, 0);
    }

    /** 가장 최근에 이마 글자가 돋은 종(HUD 실루엣). 없으면 null. */
    public Species foreheadSpecies() {
        return foreheadLast;
    }

    /** 이 개체 이마에 글자가 돋았나(잡으면 그 사람에게 증강 뽑기). */
    public boolean lettered(Gabe g) {
        return lettered.contains(g);
    }

    /** 이 판에 먹힌 사람 수(쉬는 시간 웅얼거림). */
    public int eaten() {
        return trappedRound;
    }

    /** 이번 웨이브에 쓰러진 사람 수. */
    public int downsThisWave() {
        return downsWave;
    }

    /** 다음 판 묶음(대기실 우리). */
    public Set<Species> lobbyBundle() {
        return Collections.unmodifiableSet(lobbyBundle);
    }

    // ------------------------------------------------------------------ 묶음

    /** 이 판에 쓸 수 있나(묶음, 클래스, 다른 일 결과로만 생기는 종은 뺌). */
    private boolean spawnable(Species sp) {
        return bundle.contains(sp) && sp.implemented() && sp.spawn != Species.Spawn.DERIVED && !sp.is(Role.BOSS) && !SpeciesBan.listed(sp);
    }

    /** 묶음 뽑기: 등급 1~8 은 늘, 그 위는 역할 목표(돌격 5, 떼 4, 원거리 3, 방패와 튼튼이 3, 지원 3, 함정 4, 방해 4, 정예 3)까지. 구현된 종만. */
    static Set<Species> draw(Random r) {
        EnumSet<Species> b = EnumSet.noneOf(Species.class);
        for (Species sp : Species.values()) if (sp.implemented() && sp.tier <= 8 && !SpeciesBan.listed(sp)) b.add(sp);
        Object[][] goals = {{EnumSet.of(Role.ELITE), 3}, {EnumSet.of(Role.SHIELD, Role.TANK), 3}, {EnumSet.of(Role.SUPPORT), 3},
            {EnumSet.of(Role.RANGED), 3}, {EnumSet.of(Role.TRAP), 4}, {EnumSet.of(Role.DISRUPT), 4}, {EnumSet.of(Role.SWARM), 4},
            {EnumSet.of(Role.CHARGE), 5}};
        for (Object[] g : goals) {
            @SuppressWarnings("unchecked")
            Set<Role> roles = (Set<Role>) g[0];
            int want = (Integer) g[1] + r.nextInt(3) - 1;
            int have = 0;
            for (Species sp : b) if (hasAny(sp, roles)) have++;
            List<Species> pool = new ArrayList<>();
            for (Species sp : Species.values()) if (sp.implemented() && !b.contains(sp) && hasAny(sp, roles) && !SpeciesBan.listed(sp)) pool.add(sp);
            Collections.shuffle(pool, r);
            for (int i = 0; i < pool.size() && have < want; i++) {
                b.add(pool.get(i));
                have++;
            }
        }
        return b;
    }

    private static boolean hasAny(Species sp, Set<Role> roles) {
        for (Role ro : roles) if (sp.is(ro)) return true;
        return false;
    }

    // ------------------------------------------------------------------ 계획 짜기

    private int fogFor(int n) {
        if (n < 13) return 0;
        if (n >= Tune.CLEAR_FROM) return 1;
        return fogPlan.computeIfAbsent(n, k -> {
            int x = rnd.nextInt(100);
            return x < 15 ? 1 : x < 40 ? 2 : x < 65 ? 3 : x < 85 ? 4 : 5;
        });
    }

    private int people() {
        return Math.max(1, game.players.inPlay().size());
    }

    /** 단위 값(총량을 나눌 때 한 개체가 차지하는 몫). */
    static double unit(Species sp) {
        double u = sp.is(Role.SWARM) ? 4 : switch (sp.threat) {
            case 1 -> 1.0;
            case 2 -> 1.5;
            case 3 -> 2.0;
            case 4 -> 3.0;
            default -> 4.0;
        };
        if (sp.is(Role.ELITE)) u *= 1.5;
        if (Tune.UNIT_HP > 0 && !sp.is(Role.SWARM)) u = Math.max(u, sp.hp / Tune.UNIT_HP);
        return u;
    }

    /**
     * 조합, 덧붙임, 다시 나오기에 쓰는 종. 보상 종(G08, 웨이브마다 드물게 하나만)과 탐조등(G43, 밤 다음 맑은 웨이브에만)은 따로 넣으므로 뺀다.
     */
    private List<Species> eligible(int n) {
        List<Species> out = new ArrayList<>();
        for (Species sp : bundle) if (spawnable(sp) && sp.tier <= n && !special(sp)) out.add(sp);
        return out;
    }

    /** 감독이 자기 길로만 넣는 종(보상 종, 탐조등). */
    static boolean special(Species sp) {
        return sp.is(Role.BONUS) || sp == Species.G43;
    }

    /**
     * 미리 깔리는 종의 한 웨이브 상한(자리 수와 디스플레이 예산을 지킴). 도감: 노크하는 게이브 처마 밑 서너 마리, 열매 나무와 문틀은 하나씩.
     * 그 밖은 1 + 접속자/10, 최대 4.
     */
    public static int placedCap(Species sp, int ppl) {
        int[] c = switch (sp) {
            case G21 -> new int[] {3, 6, 8};
            case G07 -> new int[] {2, 5, 6};
            case G17 -> new int[] {1, 6, 4};
            case G35, G44, G55 -> new int[] {1, 12, 3};
            case G47, G49 -> new int[] {1, 15, 2};
            case G48, G98 -> new int[] {1, 20, 2};
            default -> new int[] {1, 10, 4};
        };
        return Math.max(1, Math.min(c[2], c[0] + Math.max(0, ppl - 1) / c[1]));
    }

    private boolean allowed(Species sp, Set<Species> chosen) {
        int latent = 0;
        for (Species c : chosen) {
            if (bannedTogether(sp, c)) return false;
            if (LATENT.contains(c)) latent++;
        }
        return !(LATENT.contains(sp) && latent >= 2);
    }

    private Species pickFrom(List<Species> pool, Set<Species> chosen, Random r, int n, Set<Species> prefer) {
        List<Species> cand = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        double sum = 0;
        for (Species sp : pool) {
            if (chosen.contains(sp) || !allowed(sp, chosen)) continue;
            double x = 1.0;
            if (!introducedAt.containsKey(sp)) x *= n >= 31 ? 3.0 : 1.6;
            Integer ls = lastSeen.get(sp);
            if (ls != null && ls == n - 1) x *= 0.6;
            x *= Math.pow(Math.max(1, sp.threat), 0.25 * strength);
            if (prefer != null && prefer.contains(sp)) x *= 4;
            cand.add(sp);
            w.add(x);
            sum += x;
        }
        if (cand.isEmpty()) return null;
        double t = r.nextDouble() * sum;
        for (int i = 0; i < cand.size(); i++) {
            t -= w.get(i);
            if (t <= 0) return cand.get(i);
        }
        return cand.get(cand.size() - 1);
    }

    private static List<Species> withRoles(List<Species> pool, Role... roles) {
        List<Species> out = new ArrayList<>();
        for (Species sp : pool) for (Role ro : roles) if (sp.is(ro)) {
            out.add(sp);
            break;
        }
        return out;
    }

    /**
     * 웨이브 n 쏠림 쪽(미리 굴려 기억: 밤 웨이브가 다음 맑은 웨이브 탐조등 쪽을 알아야 함). LEAN_FROM 웨이브부터 LEAN_CHANCE 로 한쪽, 아니면 -1.
     * 시험 훅 '방향' 으로 고정했으면 그것.
     */
    private int leanFor(int n) {
        if (forcedDirs != null) return forcedDirs.length >= 4 ? -1 : forcedDirs[0];
        if (n < Tune.LEAN_FROM) return -1;
        return leanPlan.computeIfAbsent(n, k -> rnd.nextDouble() < Tune.LEAN_CHANCE ? rnd.nextInt(4) : -1);
    }

    /** 웨이브 n 탐조등이 설 쪽(쏠림이 있으면 그 쪽, 없으면 아무 쪽. 기억). 그 전 밤 웨이브의 노란 점도 이 쪽 하늘에 뜬다. */
    private int lightDirFor(int n) {
        Integer d = lightPlan.get(n);
        if (d == null) {
            int l = leanFor(n);
            d = l >= 0 ? l : rnd.nextInt(4);
            lightPlan.put(n, d);
        }
        return d;
    }

    /** 웨이브 n 계획. 상태를 바꾸지 않는다(안개, 쏠림, 탐조등 쪽 미리 굴림만 기억). */
    Plan plan(int n, Random r) {
        Plan p = new Plan(n);
        p.fog = fogFor(n);
        p.boss = bossIndex(n) >= 0;
        // 숨 고르기(약한 대량 떼, 돈 벌기): 10 부터 5웨이브마다, 보스 웨이브(10, 20, 30, 33, 35, 45, 55 ...)와 겹치면 빼서 15, 25, 40, 50, 60 ...
        p.breather = n >= 10 && n % 5 == 0 && !p.boss;
        p.lean = leanFor(n);
        p.silent = n == silentWave;
        p.night = n >= 21 && n < Tune.CLEAR_FROM && fogFor(n + 1) == 1;
        if (p.night) p.dotDir = lightDirFor(n + 1);
        // 원정 막 덱(원정.md 2.3절): 묶음 대신 덱이 이 라운드의 새 종, 돌아온 종, 밑떼와 몫을 정한다(배우기, 조합 패, 맑은 웨이브의 하늘 종만 규칙 대신)
        kr.gave.trip.Deck dk = deck();
        kr.gave.trip.Deck.Draw dd = null;
        if (dk != null) {
            try {
                dd = dk.draw(n);
            } catch (RuntimeException e) {
                game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[감독] 원정 막 덱 뽑기 예외(옛 묶음으로)", e);
            }
            if (dd != null && dd.all().isEmpty()) dd = null;
        }
        List<Species> elig = eligible(n);
        if (dd != null) {
            elig = new ArrayList<>(dd.all());
            elig.removeAll(dd.single);
        }
        Map<Species, Double> share = new LinkedHashMap<>();
        boolean skyOnly = false;
        Species g01 = elig.contains(Species.G01) ? Species.G01 : null;
        for (Species sp : elig) if (g01 == null && sp.spawn != Species.Spawn.PLACED) g01 = sp;
        if (dd != null) {
            p.deck = dd.stamp;
            // 숨 고르기는 원정에서 보급, 정비 스테이지가 대신한다(원정.md 11.2)
            p.breather = false;
            share.putAll(dd.share);
            p.fresh = dd.solo;
            p.lead = dd.lead;
            p.base = dd.baseRole;
            p.spike = dd.spike;
            skyOnly = "sky".equals(dd.comp);
        } else if (n <= 5) {
            Species fresh = null;
            if (!introducedAt.containsKey(g01) && g01 != null) fresh = g01;
            else {
                int bestTier = Integer.MAX_VALUE;
                List<Species> c = new ArrayList<>();
                for (Species sp : elig) {
                    if (introducedAt.containsKey(sp) || sp.tier > Math.min(5, n)) continue;
                    if (sp.tier < bestTier) {
                        bestTier = sp.tier;
                        c.clear();
                    }
                    if (sp.tier == bestTier) c.add(sp);
                }
                if (!c.isEmpty()) fresh = c.get(r.nextInt(c.size()));
            }
            p.fresh = fresh;
            for (Map.Entry<Species, Integer> e : introducedAt.entrySet()) {
                Species sp = e.getKey();
                if (sp == g01 || !elig.contains(sp)) continue;
                if (e.getValue() >= n - 2 || r.nextDouble() < 0.4) share.put(sp, 0.14);
            }
            if (g01 != null) share.put(g01, Math.max(0.3, 1 - share.size() * 0.14));
        } else {
            List<Species> sky = new ArrayList<>();
            for (Species sp : elig) if (sp.spawn == Species.Spawn.SKY) sky.add(sp);
            if (p.fog == 1 && sky.size() == 1 && !p.breather) {
                // 맑은 웨이브인데 묶음에 하늘 종이 하나뿐: 그 종만 나오면 웨이브 전체가 한 종이라 땅 조합에 하늘 종을 40% 섞는다
                combo(p, elig, share, r, g01);
                for (Map.Entry<Species, Double> e : share.entrySet()) e.setValue(e.getValue() * 0.6);
                share.merge(sky.get(0), 0.4, Double::sum);
            } else if (p.fog == 1 && !sky.isEmpty()) {
                // 맑은 웨이브는 하늘 종만(숨 고르기와 겹치면 하늘 떼를 앞세우고 체력 보정만 숨 고르기 몫)
                skyOnly = true;
                if (p.breather) sky.sort((x, y) -> Boolean.compare(y.is(Role.SWARM), x.is(Role.SWARM)));
                else Collections.shuffle(sky, r);
                int k = Math.min(3, sky.size());
                for (int i = 0; i < k; i++) share.put(sky.get(i), 1.0 / k);
                if (p.breather) p.hpBonus *= Tune.HP_BREATHER;
            } else if (p.breather) {
                List<Species> weak = new ArrayList<>();
                for (Species sp : elig) if (sp.is(Role.SWARM) && sp.threat <= 2 && sp.spawn != Species.Spawn.PLACED) weak.add(sp);
                if (g01 != null) share.put(g01, weak.isEmpty() ? 1.0 : 0.4);
                for (Species sp : weak) share.put(sp, 0.6 / weak.size());
                p.hpBonus *= Tune.HP_BREATHER;
            } else combo(p, elig, share, r, g01);
        }
        // 다음 두 웨이브 안에 다시(처음 나온 종). 맑은 웨이브(하늘 종만)는 하늘 종만 다시
        for (Map.Entry<Species, Integer> e : introducedAt.entrySet()) {
            Species sp = e.getKey();
            int at = e.getValue();
            if (share.containsKey(sp) || !elig.contains(sp) || at < n - 2 || at >= n) continue;
            if (skyOnly && sp.spawn != Species.Spawn.SKY) continue;
            if (p.breather && sp.spawn == Species.Spawn.PLACED) continue;
            Integer ls = lastSeen.get(sp);
            if (ls != null && ls > at) continue;
            if (allowed(sp, share.keySet())) share.put(sp, 0.08);
        }
        if (mutations.containsKey("elite")) for (Map.Entry<Species, Double> e : share.entrySet()) if (e.getKey().is(Role.ELITE)) e.setValue(e.getValue() * 1.5);
        // 총량(단위) = max(6 x 접속자^0.6 x (2.85 + 웨이브 x 0.91), 16 + 5 x 웨이브) x 세기 x 숨 고르기 x 변이 x 톱니 x 끝없는 구간 x 난이도^0.5. 하한은 혼자여도 떼로 보이게(숫자는 Tune)
        int ppl = people();
        double units = Math.max(Tune.UNITS_K * Math.pow(ppl, Tune.UNITS_POW) * (Tune.UNITS_BASE + n * Tune.UNITS_PER_WAVE), Tune.UNITS_MIN + Tune.UNITS_MIN_PER_WAVE * n)
                * (1 + Tune.STRENGTH_STEP * strength);
        if (p.breather) units *= Tune.BREATHER_UNITS;
        units *= Math.pow(Tune.HORDE_UNITS, mutations.getOrDefault("horde", 0));
        // 2026-11-12 파워커브: 톱니(보스 앞 조임, 보스 뒤 한숨), 끝없는 구간, 난이도 손잡이(x 원정 갈래 배수)
        units *= saw(n) * Math.pow(Tune.ENDLESS_UNITS, endlessK(n));
        p.scale = unitsScale();
        units *= p.scale;
        p.units = units;
        // 몰려오는 종이 총량을 나누고, 미리 깔리는 종은 자기 몫을 상한(placedCap) 안에서만(처음 나오는 것은 하나)
        double sum = 0, sumMove = 0;
        for (Map.Entry<Species, Double> e : share.entrySet()) {
            sum += e.getValue();
            if (e.getKey().spawn != Species.Spawn.PLACED) sumMove += e.getValue();
        }
        if (sum <= 0) sum = 1;
        if (sumMove <= 0) sumMove = 1;
        for (Map.Entry<Species, Double> e : share.entrySet()) {
            Species sp = e.getKey();
            int c;
            if (sp.spawn == Species.Spawn.PLACED) {
                c = (int) Math.max(1, Math.round(units * e.getValue() / sum / unit(sp)));
                c = sp == p.fresh ? 1 : Math.min(c, placedCap(sp, ppl));
            } else {
                c = (int) Math.max(1, Math.round(units * e.getValue() / sumMove / unit(sp)));
                if (sp == p.fresh && sp != g01 && n <= 5) c = Math.min(c, 2);
            }
            p.counts.put(sp, c);
        }
        // 한 종 쏠림과 사람을 무시하는 종 막기(숨 고르기, 하늘 종만 웨이브, 배우기 1~5 는 뺌): 넘친 몫은 사람을 치는 앞잡이에게
        if (!p.breather && !skyOnly && n > 5) capShares(p, elig, g01);
        if (p.fresh != null && !p.counts.containsKey(p.fresh) && elig.contains(p.fresh))
            p.counts.put(p.fresh, p.fresh.spawn == Species.Spawn.PLACED ? 1 : n <= 5 ? 2 : 1);
        // 땅 갈라짐(예전 배관 범람. 13~, 웅덩이 게이브가 있을 때만): 총량의 4분의 1 을 땅 갈라진 틈에서
        p.flood = n >= 13 && !p.breather && p.fog != 1 && spawnable(Species.G31) && r.nextDouble() < 0.3;
        if (p.flood) p.counts.merge(Species.G31, Math.max(3, p.total() / 4), Integer::sum);
        p.puddleEye = n >= 31 && !p.breather && r.nextDouble() < 0.25;
        // 탐조등: 노란 점 뜬 밤(21~59) 다음 맑은 웨이브에만 2~3, 그 밤에 점이 뜬 쪽 가장자리. 시선 규칙 종(금지표)은 이 웨이브에서 뺀다
        if (p.fog == 1 && n >= 22 && n <= Tune.CLEAR_FROM && spawnable(Species.G43)) {
            p.counts.keySet().removeIf(sp -> bannedTogether(sp, Species.G43));
            p.searchlights = 2 + r.nextInt(2);
            p.lightDir = lightDirFor(n);
            p.counts.put(Species.G43, p.searchlights);
        }
        // 쏠림(LEAN_FROM 웨이브부터 가끔): 몰려오는 종마다 LEAN_EXTRA 만큼 덤. 덤은 쏠림 쪽 호에서 나오고 나머지 방향 몫은 그대로다
        if (p.lean >= 0) {
            Species most = null;
            for (Map.Entry<Species, Integer> e : p.counts.entrySet()) {
                Species sp = e.getKey();
                if (sp.spawn == Species.Spawn.PLACED || special(sp) || (sp == Species.G31 && p.flood)) continue;
                if (most == null || e.getValue() > p.counts.get(most)) most = sp;
                int extra = (int) Math.round(e.getValue() * Tune.LEAN_EXTRA);
                if (extra <= 0) continue;
                p.leanCounts.put(sp, extra);
                e.setValue(e.getValue() + extra);
                p.leanExtra += extra;
            }
            if (p.leanExtra == 0 && most != null) {
                p.leanCounts.put(most, 1);
                p.counts.merge(most, 1, Integer::sum);
                p.leanExtra = 1;
            }
            if (p.leanExtra == 0) p.lean = -1;
        }
        // 넘치는 몫은 체력으로(몰려오는 종만 줄임, 미리 깔리는 종은 이미 상한 안)
        int placedTot = 0, moveTot = 0;
        for (Map.Entry<Species, Integer> e : p.counts.entrySet()) {
            if (e.getKey().spawn == Species.Spawn.PLACED) placedTot += e.getValue();
            else moveTot += e.getValue();
        }
        int room = Math.max(Tune.MAX_ENTRIES / 2, Tune.MAX_ENTRIES - placedTot);
        if (moveTot > room) {
            double k = (double) moveTot / room;
            for (Map.Entry<Species, Integer> e : p.counts.entrySet()) {
                if (e.getKey().spawn != Species.Spawn.PLACED) e.setValue(Math.max(1, (int) Math.ceil(e.getValue() / k)));
            }
            p.leanExtra = 0;
            for (Map.Entry<Species, Integer> e : p.leanCounts.entrySet()) {
                int v = Math.min(p.counts.getOrDefault(e.getKey(), 0), (int) Math.round(e.getValue() / k));
                e.setValue(v);
                p.leanExtra += v;
            }
            p.hpBonus *= Math.min(Tune.HP_OVERFLOW_MAX, k);
            p.rewardBonus = Math.min(Tune.HP_OVERFLOW_MAX, k);
        }
        // 보상 종(G08 금빛 홍조): 웨이브마다 드물게 하나만(넘침 보정 뒤에 넣어 늘 1, 다시 나오기에도 안 듦)
        for (Species sp : bundle) {
            if (sp.is(Role.BONUS) && spawnable(sp) && sp.tier <= n && r.nextDouble() < BONUS_CHANCE) {
                p.counts.put(sp, 1);
                p.bonus = true;
            }
        }
        // 보스 웨이브(3.3절): 떼 50%, 미리 깔리는 종, 사건 뺌. 그 보스가 없으면 보통 웨이브로(p.boss = false)
        if (p.boss && boss != null) {
            try {
                boss.adjust(p);
            } catch (RuntimeException e) {
                game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[감독] 보스 고리 adjust 예외", e);
            }
        }
        // 원정 막 덱: 한 마리만 낼 종(보상 종, 죽음 결과 같은 파생 종, '돌아온 것들'의 새 종), 보스 라운드면 덱에 남은 종 모두(보스 고리가 뺀 미리 깔리는 종,
        // 보상 종도 한 마리씩 되살림: 보스 떼 = 그 막의 아직 못 본 얼굴들)
        if (dd != null) {
            for (Species sp : dd.single) {
                p.counts.put(sp, 1);
                if (sp.is(Role.BONUS)) p.bonus = true;
            }
            if (dd.boss) for (Species sp : dd.fresh) p.counts.putIfAbsent(sp, 1);
        }
        capOnes(p);
        int el = 0;
        for (Map.Entry<Species, Integer> e : p.counts.entrySet()) if (e.getKey().is(Role.ELITE)) el += e.getValue();
        p.elites = el;
        return p;
    }

    /**
     * 한 웨이브 몰려오는 수(미리 깔리는 종, 보상 종, 탐조등 빼고)에서 한 종이 SHARE_MAX 를, 사람을 직접 몰아붙이지 않는 종(PASSIVE)이 한 종 PASSIVE_MAX,
     * 모두 PASSIVE_TOTAL 을 넘지 않게 줄인다. 줄인 몫(단위 unit 으로 바꿔)은 계획 안 사람을 치는 종에게 SHARE_MAX 까지 나눠 주고, 그래도 남으면
     * G01(묶음에 있으면) 아니면 금지표에 걸리지 않는 돌격, 떼 종 하나를 더해 준다(총량은 그대로). 몰려오는 종이 둘 아래면 그대로.
     */
    private void capShares(Plan p, List<Species> elig, Species g01) {
        List<Species> moving = new ArrayList<>();
        int total = 0, passiveTot = 0;
        for (Map.Entry<Species, Integer> e : p.counts.entrySet()) {
            Species sp = e.getKey();
            if (sp.spawn == Species.Spawn.PLACED || special(sp)) continue;
            moving.add(sp);
            total += e.getValue();
            if (PASSIVE.contains(sp)) passiveTot += e.getValue();
        }
        if (moving.size() < 2 || total <= 0) return;
        double passiveScale = passiveTot > Tune.PASSIVE_TOTAL * total ? Tune.PASSIVE_TOTAL * total / passiveTot : 1.0;
        double freed = 0;
        for (Species sp : moving) {
            int c = p.counts.get(sp);
            double cap = PASSIVE.contains(sp) ? Math.min(Tune.PASSIVE_MAX * total, c * passiveScale) : Tune.SHARE_MAX * total;
            int keep = Math.max(1, (int) Math.floor(cap));
            if (c > keep) {
                freed += (c - keep) * unit(sp);
                p.counts.put(sp, keep);
            }
        }
        if (freed <= 0) return;
        List<Species> takers = new ArrayList<>();
        if (p.lead != null && moving.contains(p.lead) && !PASSIVE.contains(p.lead)) takers.add(p.lead);
        for (Species sp : moving) if (!PASSIVE.contains(sp) && !takers.contains(sp)) takers.add(sp);
        for (Species sp : takers) {
            if (freed <= 1e-6) break;
            double room = Math.max(0, Tune.SHARE_MAX * total - p.counts.get(sp)) * unit(sp);
            double give = Math.min(room, freed);
            int add = (int) Math.floor(give / unit(sp));
            if (add <= 0) continue;
            p.counts.merge(sp, add, Integer::sum);
            freed -= add * unit(sp);
        }
        if (freed < 1) return;
        // 아직 남음: 사람을 치는 종을 하나 더(G01 먼저)
        Species extra = null;
        if (g01 != null && g01.spawn != Species.Spawn.PLACED && !PASSIVE.contains(g01) && (p.counts.containsKey(g01) || allowed(g01, p.counts.keySet())))
            extra = g01;
        if (extra == null) {
            for (Species sp : elig) {
                if (sp.spawn == Species.Spawn.PLACED || PASSIVE.contains(sp) || special(sp) || !(sp.is(Role.CHARGE) || sp.is(Role.SWARM))) continue;
                if (p.counts.containsKey(sp) || !allowed(sp, p.counts.keySet())) continue;
                extra = sp;
                break;
            }
        }
        if (extra == null && !takers.isEmpty()) extra = takers.get(0);
        if (extra != null) p.counts.merge(extra, (int) Math.max(1, Math.round(freed / unit(extra))), Integer::sum);
    }

    /** 6웨이브부터: 앞잡이(돌격, 떼) + 받침(방패, 튼튼이, 지원) + 가시(함정, 방해, 원거리), 단계에 따라 종을 더함. 금지표 적용. */
    private void combo(Plan p, List<Species> elig, Map<Species, Double> share, Random r, Species g01) {
        int n = p.wave;
        int want = n <= 12 ? 3 : n <= 20 ? 4 : n <= 30 ? 4 + r.nextInt(2) : 5 + r.nextInt(2);
        Set<Species> chosen = EnumSet.noneOf(Species.class);
        List<Species> leadPool = withRoles(elig, Role.CHARGE, Role.SWARM);
        leadPool.removeIf(sp -> sp.spawn == Species.Spawn.PLACED);
        p.lead = pickFrom(leadPool, chosen, r, n, null);
        if (p.lead != null) chosen.add(p.lead);
        p.base = pickFrom(withRoles(elig, Role.SHIELD, Role.TANK, Role.SUPPORT), chosen, r, n, null);
        if (p.base != null) chosen.add(p.base);
        p.spike = pickFrom(withRoles(elig, Role.TRAP, Role.DISRUPT, Role.RANGED), chosen, r, n, null);
        if (p.spike != null) chosen.add(p.spike);
        Set<Species> prefer = n <= 12 ? null : n <= 20 ? union(EATERS, LATENT) : n <= 30 ? MIMIC : notSeen(elig);
        List<Species> extra = new ArrayList<>();
        while (chosen.size() < want) {
            Species sp = pickFrom(elig, chosen, r, n, prefer);
            if (sp == null) break;
            chosen.add(sp);
            extra.add(sp);
        }
        if (p.lead != null) share.put(p.lead, 0.42);
        if (p.base != null) share.put(p.base, 0.2);
        if (p.spike != null) share.put(p.spike, 0.2);
        for (Species sp : extra) share.put(sp, 0.18 / Math.max(1, extra.size()) + 0.06);
        if (g01 != null && n <= 12 && !share.containsKey(g01)) share.put(g01, 0.12);
        if (share.isEmpty() && g01 != null) share.put(g01, 1.0);
        for (Species sp : chosen) {
            if (!introducedAt.containsKey(sp)) {
                p.fresh = sp;
                break;
            }
        }
    }

    private static Set<Species> union(Set<Species> a, Set<Species> b) {
        EnumSet<Species> s = EnumSet.noneOf(Species.class);
        s.addAll(a);
        s.addAll(b);
        return s;
    }

    private Set<Species> notSeen(List<Species> elig) {
        EnumSet<Species> s = EnumSet.noneOf(Species.class);
        for (Species sp : elig) if (!introducedAt.containsKey(sp)) s.add(sp);
        return s;
    }

    /** 계획을 판 기록에 반영(처음 나온 종, 마지막 본 웨이브). */
    private void commit(Plan p) {
        for (Species sp : p.counts.keySet()) {
            introducedAt.putIfAbsent(sp, p.wave);
            lastSeen.put(sp, p.wave);
        }
    }

    // ------------------------------------------------------------------ 내보내기

    private int reserve() {
        int r = Tune.CHILD_ROOM;
        if (mutations.containsKey("split")) r += 30;
        if (mutations.containsKey("riders")) r += 10;
        if (boss != null) {
            try {
                r += boss.reserve();
            } catch (RuntimeException e) {
                // 보스 고리가 던지면 예비 몫만 빠짐
            }
        }
        return r;
    }

    /** 한 개체를 계획 자리에 놓는다(체력 배율, 이마 글자, 처음 본 종, 변이 올라타기). 예산이나 자리가 없으면 null. 고리 종의 자리는 등 뒤 편향으로 고른다. */
    private Gabe spawnEntry(Species sp, boolean soloShow) {
        return spawnEntry(sp, soloShow, Double.NaN, 0);
    }

    /** 고리 종이면 가운데에서 본 각도 ang(라디안, NaN 이면 등 뒤 편향으로 고름)과 띠 반지름 band 에 놓는다. 그 밖의 종은 자기 자리 규칙대로. */
    private Gabe spawnEntry(Species sp, boolean soloShow, double ang, double band) {
        if (!sp.implemented()) return null;
        Vector at;
        if (sp == Species.G31 && floodOn) at = floodPoint();
        else if (sp == Species.G43 && plan != null && plan.searchlights > 0) at = edgePoint(sp);
        else if (sp.spawn == Species.Spawn.PLACED) at = placeFor(sp);
        else if (sp.is(Role.BONUS)) at = bonusPoint(sp);
        else if (soloShow) at = soloPoint(sp);
        else at = Double.isNaN(ang) ? ringPoint(sp) : ringAt(sp, ang, band);
        if (at == null) return null;
        Gabe g = trySpawn(sp, at, false);
        if (g == null) return null;
        double k = hpMulFor(g, sp);
        g.maxHp *= k;
        g.hp = g.maxHp;
        double rm = rewardMul();
        if (rm != 1.0 && g.reward > 0) g.reward = (int) Math.max(1, Math.round(g.reward * rm));
        if (foreheadKind.getOrDefault(sp, 0) == 0 && foreheadLv.containsKey(sp)) g.baseSpeed *= Math.pow(1.15, foreheadLv.get(sp));
        fromDirector.add(g);
        known.add(g);
        onNew(g);
        if (mutations.containsKey("riders") && sp.is(Role.SWARM) && Species.G54.implemented() && game.mobs.budgetFor(Species.G54)) {
            Gabe r = trySpawn(Species.G54, at.clone().add(new Vector(0.8, 0, 0.8)), false);
            if (r != null) {
                r.maxHp *= hpMul(Species.G54);
                r.hp = r.maxHp;
                known.add(r);
                onNew(r);
            }
        }
        return g;
    }

    private final Set<Species> spawnFailed = EnumSet.noneOf(Species.class);

    /** Mobs.spawn 을 감싼 것: 종 생성자 예외는 그 종마다 한 번 적고 null. */
    private Gabe trySpawn(Species sp, Vector at, boolean child) {
        try {
            return game.mobs.spawn(sp, at, child);
        } catch (RuntimeException e) {
            if (spawnFailed.add(sp)) game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[감독] " + sp.name() + " 만들기 실패, 그 종은 건너뜀", e);
            return null;
        }
    }

    /**
     * 몰려오는 자리 하나(종 담당도 씀): 나오는 고리(R_SPAWN_IN..R_SPAWN_OUT) 위, 사람들이 가장 안 보는 쪽(후보 각도 셋 가운데), 안개에 가려 안 보이는
     * 띠. 방향 넷의 출입구는 없다(늘 360도). 하늘 종은 바닥 + 띠 높이 + 0..3.
     */
    public Vector ringPoint(Species sp) {
        double band = bandRadius();
        return ringAt(sp, unwatchedAngle(band), band);
    }

    /** 고리 위 한 점: 가운데에서 본 각도 ang(라디안, 0 = +x 동, 90도 = +z 남), 반지름 band ± BAND_HALF. 땅 종은 걸을 수 있는 칸. */
    private Vector ringAt(Species sp, double ang, double band) {
        boolean sky = sp != null && sp.spawn == Species.Spawn.SKY;
        double x = 0, z = 0;
        for (int tries = 0; tries < 6; tries++) {
            double rr = band + (rnd.nextDouble() * 2 - 1) * Tune.BAND_HALF;
            x = Plaza.CX + 0.5 + Math.cos(ang) * rr;
            z = Plaza.CZ + 0.5 + Math.sin(ang) * rr;
            if (sky || Plaza.walkable((int) Math.floor(x), (int) Math.floor(z))) break;
            ang += 0.04;
        }
        double y = game.mobs.flow.floor(x, z) + (sp == null ? 1 : sp.band.hover) + (sky ? rnd.nextDouble() * 3 : 0);
        return new Vector(x, y, z);
    }

    /**
     * 이번 무리가 출발할 반지름(띠 가운데). 흙먼지에 가려 안 보이는 데서 나오게, 가운데에서 가장 멀리 나가 있는 서 있는 사람에게서 '안 보이는 거리'만큼
     * 떨어진 가장 안쪽 띠를 고른다(나오는 고리 안에서). 사람들이 중앙 원 근처에 있으면 늘 같은 띠라서, 무리 사이 틈은 걸음 빠르기 x BAND_EVERY 로 고르다.
     */
    private double bandRadius() {
        double far = 0;
        for (Player p : game.players.standing()) far = Math.max(far, Plaza.radius(p.getLocation().getX(), p.getLocation().getZ()));
        double lo = Plaza.R_SPAWN_IN + Tune.BAND_HALF, hi = Plaza.R_SPAWN_OUT - Tune.BAND_HALF;
        return Math.max(lo, Math.min(hi, far + hideDist() + Tune.BAND_HALF));
    }

    /** 새 개체가 눈에 띄지 않으려면 사람에게서 떨어져야 하는 거리: 지금 안개가 가리는 거리(조금 안쪽), 맑아도 HIDE_MAX 까지만. */
    private double hideDist() {
        int lv = (int) Math.round(Math.max(0, Math.min(5, fogGoal)));
        return Math.min(Tune.HIDE_MAX, Fog.END[lv] - 4);
    }

    /**
     * 등 뒤 편향: 무작위 후보 각도 CANDIDATES 개 가운데 서 있는 사람들이 가장 안 보고 있는 쪽. 돌아볼 때마다 뒤에 더 와 있게 된다. 사람에게서 '안 보이는
     * 거리' 안쪽인 후보는 크게 밀린다. 서 있는 사람이 없으면 아무 각도.
     */
    private double unwatchedAngle(double band) {
        List<Player> ps = game.players.standing();
        double best = rnd.nextDouble() * Math.PI * 2;
        if (ps.isEmpty()) return best;
        double bestScore = Double.MAX_VALUE, hide = hideDist();
        for (int k = 0; k < Math.max(1, Tune.CANDIDATES); k++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double s = watched(ps, Plaza.CX + 0.5 + Math.cos(a) * band, Plaza.CZ + 0.5 + Math.sin(a) * band, hide);
            if (s < bestScore) {
                bestScore = s;
                best = a;
            }
        }
        return best;
    }

    /**
     * 고리 위 각 ang, 반지름 band 의 자리가 서 있는 누군가에게서 '안 보이는 거리'({@link #hideDist}) 안쪽인가(그 사람 눈에 나오는 순간이 보임). 안개가 맑아 숨길
     * 수 없으면(안개 끝 - 4 가 HIDE_MAX 를 넘음: 맑음 단계) 늘 false: 어디서 나와도 보이니 바꿔 봐야 사람들 등 뒤로 쏠리기만 한다.
     */
    private boolean exposed(double ang, double band) {
        int lv = (int) Math.round(Math.max(0, Math.min(5, fogGoal)));
        if (Fog.END[lv] - 4 > Tune.HIDE_MAX) return false;
        double x = Plaza.CX + 0.5 + Math.cos(ang) * band, z = Plaza.CZ + 0.5 + Math.sin(ang) * band, hide = hideDist();
        for (Player p : game.players.standing()) {
            Location l = p.getLocation();
            double dx = x - l.getX(), dz = z - l.getZ();
            if (dx * dx + dz * dz < hide * hide) return true;
        }
        return false;
    }

    /**
     * (x, z) 가 얼마나 눈에 띄는 자리인가: 그 자리를 가장 정면으로 보는 사람의 시선 맞음(-1 등 뒤 .. 1 정면). 누군가에게서 hide 블록 안쪽이면 2 넘게 더한다.
     */
    private static double watched(List<Player> ps, double x, double z, double hide) {
        double s = -1, near = Double.MAX_VALUE;
        for (Player p : ps) {
            Location l = p.getLocation();
            double dx = x - l.getX(), dz = z - l.getZ(), d = Math.sqrt(dx * dx + dz * dz);
            if (d < 1e-6) continue;
            // 마크 yaw: 0 = +Z, 90 = -X
            double yaw = Math.toRadians(l.getYaw());
            s = Math.max(s, (-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / d);
            near = Math.min(near, d);
        }
        return near < hide ? s + 2 + (hide - near) / hide : s;
    }

    /** 쏠림 쪽 호 안의 각도(그 방향 좌우 LEAN_ARC 도). */
    private double leanAngle(int d) {
        Vector v = Plaza.dirVec(d);
        return Math.atan2(v.getZ(), v.getX()) + Math.toRadians((rnd.nextDouble() * 2 - 1) * Tune.LEAN_ARC);
    }

    /** 새 종 혼자 보여 주기: 사람들이 보고 있는 쪽 고리에서(등 뒤 편향의 반대. 규칙을 보여 주려는 것이라 눈앞 흙먼지에서 걸어 나오게). */
    private Vector soloPoint(Species sp) {
        double sx = 0, sz = 0;
        for (Player p : game.players.standing()) {
            double yaw = Math.toRadians(p.getLocation().getYaw());
            sx -= Math.sin(yaw);
            sz += Math.cos(yaw);
        }
        double ang = sx * sx + sz * sz > 1e-6 ? Math.atan2(sz, sx) + (rnd.nextDouble() - 0.5) * 0.5 : rnd.nextDouble() * Math.PI * 2;
        return ringAt(sp, ang, bandRadius());
    }

    /**
     * 미리 깔리는 종의 기본 자리(종이 spawned() 에서 옮겨도 됨). 구조물이 없으니 모두 황야 위다: G21 은 중앙 원 테두리 근처(R 10~14) 허공(바닥 위
     * 3.5블록, 예전 처마 밑 높이), G43 은 edgePoint, G47 은 R 16~40, 그 밖은 R 16~44(우는 아기 G17 은 46 까지)의 걷는 칸. 사람에게서 12블록, 같은
     * 종끼리 5블록(G21 은 2.5블록) 넘게 띄운다. y 는 바닥 + 띠 높이.
     */
    public Vector placeFor(Species sp) {
        if (sp == Species.G43) return edgePoint(sp);
        boolean hang = sp == Species.G21;
        // 꾸러미(G44): 진짜 보급 상자(Crates.drop)와 같은 띠, 칸 가운데로 맞추지 않음
        if (sp == Species.G44) {
            for (int tries = 0; tries < 40; tries++) {
                double a = rnd.nextDouble() * Math.PI * 2, rr = Crates.R_MIN + rnd.nextDouble() * (Crates.R_MAX - Crates.R_MIN);
                double x = Plaza.CX + 0.5 + Math.cos(a) * rr, z = Plaza.CZ + 0.5 + Math.sin(a) * rr;
                if (!Plaza.walkable((int) Math.floor(x), (int) Math.floor(z))) continue;
                if (nearSame(sp, x, z, 5) && tries < 30) continue;
                return new Vector(x, game.mobs.flow.floor(x, z) + sp.band.hover, z);
            }
        }
        double r0 = hang ? Plaza.R_SAFE - 2 : Plaza.R_SAFE + 4;
        double r1 = hang ? Plaza.R_SAFE + 2 : sp == Species.G47 ? 40 : sp == Species.G17 ? Plaza.R_ROAM - 2 : Plaza.R_ROAM - 4;
        double apart = hang ? 2.5 : 5;
        for (int tries = 0; tries < 40; tries++) {
            double a = rnd.nextDouble() * Math.PI * 2, rr = r0 + rnd.nextDouble() * (r1 - r0);
            double x = Plaza.CX + 0.5 + Math.cos(a) * rr, z = Plaza.CZ + 0.5 + Math.sin(a) * rr;
            if (!Plaza.walkable((int) Math.floor(x), (int) Math.floor(z))) continue;
            if (!hang && nearPlayer(x, z, 12) && tries < 30) continue;
            if (nearSame(sp, x, z, apart) && tries < 30) continue;
            if (hang) return new Vector(x, Plaza.EAVE_Y + 0.5, z);
            return new Vector(Math.floor(x) + 0.5, game.mobs.flow.floor(x, z) + sp.band.hover, Math.floor(z) + 0.5);
        }
        return ringPoint(sp);
    }

    /** 살아 있는 같은 종이 (x, z) 에서 r 블록 안(수평)에 있나. */
    private boolean nearSame(Species sp, double x, double z, double r) {
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.sp != sp || cage.contains(g)) continue;
            double dx = g.pos.getX() - x, dz = g.pos.getZ() - z;
            if (dx * dx + dz * dz < r * r) return true;
        }
        return false;
    }

    /**
     * 탐조등 자리: 사람이 갈 수 있는 끝 바로 밖(R_ROAM + 4..8) 하늘, 바닥 + 10..12(도감). 탐조등 쪽(plan.lightDir, 없으면 쏠림 쪽, 그것도 없으면 아무 쪽)
     * 좌우로 퍼진다. 가릴 구조물은 없다.
     */
    private Vector edgePoint(Species sp) {
        int side = plan != null && plan.lightDir >= 0 ? plan.lightDir : plan != null ? plan.lean : -1;
        double x = 0, z = 0;
        for (int tries = 0; tries < 32; tries++) {
            double a;
            if (side >= 0) {
                Vector dv = Plaza.dirVec(side);
                a = Math.atan2(dv.getZ(), dv.getX()) + (rnd.nextBoolean() ? 1 : -1) * (0.2 + rnd.nextDouble() * 0.55);
            } else a = rnd.nextDouble() * Math.PI * 2;
            double rr = Plaza.R_ROAM + 4 + rnd.nextDouble() * 4;
            x = Plaza.CX + 0.5 + Math.cos(a) * rr;
            z = Plaza.CZ + 0.5 + Math.sin(a) * rr;
            if (!nearSame(sp, x, z, 7) || tries >= 24) break;
        }
        return new Vector(x, edgeY(x, z), z);
    }

    private double edgeY(double x, double z) {
        double y = game.mobs.flow.floor(x, z) + 10 + rnd.nextDouble() * 2;
        try {
            y = Math.max(y, game.world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 3);
        } catch (RuntimeException ignored) {
            // 청크를 못 읽으면 바닥 기준 그대로
        }
        return y;
    }

    /** 땅 갈라짐(예전 배관 범람): 땅 갈라진 틈(Plaza.grates(), 모두 중앙 원 밖)에서 스며 나온다. 중앙 원 안은 안전. */
    private Vector floodPoint() {
        List<Vector> src = Plaza.grates();
        src.removeIf(v -> Plaza.radius(v.getX(), v.getZ()) <= Plaza.R_SAFE + 2);
        if (src.isEmpty()) return ringPoint(Species.G31);
        Vector v = src.get(rnd.nextInt(src.size()));
        double x = v.getX() + (rnd.nextDouble() - 0.5) * 2, z = v.getZ() + (rnd.nextDouble() - 0.5) * 2;
        return new Vector(x, game.mobs.flow.floor(x, z) + Species.G31.band.hover, z);
    }

    /** 보상 종(금빛 홍조): 눈에 띄게, 사람이 갈 수 있는 끝 바로 밖(R_ROAM + 4..10)에서 나온다(나오는 고리는 흙먼지에 가려 안 보임). */
    private Vector bonusPoint(Species sp) {
        double a = rnd.nextDouble() * Math.PI * 2, rr = Plaza.R_ROAM + 4 + rnd.nextDouble() * 6;
        double x = Plaza.CX + 0.5 + Math.cos(a) * rr, z = Plaza.CZ + 0.5 + Math.sin(a) * rr;
        return new Vector(x, game.mobs.flow.floor(x, z) + sp.band.hover, z);
    }

    private boolean nearPlayer(double x, double z, double r) {
        for (Player p : game.players.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - x, dz = l.getZ() - z;
            if (dx * dx + dz * dz < r * r) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 지켜보기(사람, 개체)

    private void watchPeople() {
        for (PState s : game.players.all()) {
            Life prev = lastLife.put(s.id, s.life);
            if (prev == null || prev == s.life) continue;
            // 이마 글자 셈(downsBy)에서 보스는 뺀다(보스.md 11.8)
            if (s.life == Life.DOWNED && prev == Life.ALIVE) {
                downsWave++;
                if (s.lastBy != null && !s.lastBy.is(Role.BOSS)) downsBy.merge(s.lastBy, 1, Integer::sum);
            } else if (s.life == Life.TRAPPED) {
                trappedRound++;
                if (prev == Life.ALIVE) downsWave++;
                if (s.eater != null && !s.eater.sp.is(Role.BOSS)) downsBy.merge(s.eater.sp, 1, Integer::sum);
            }
        }
    }

    private void watchMobs(long now) {
        for (Gabe g : game.mobs.all()) {
            if (g.dead || known.contains(g)) continue;
            known.add(g);
            onNew(g);
        }
        boolean split = mutations.containsKey("split"), burst = mutations.containsKey("burst");
        List<Gabe> born = null;
        for (Iterator<Gabe> it = known.iterator(); it.hasNext();) {
            Gabe g = it.next();
            if (g.dead) {
                it.remove();
                fromDirector.remove(g);
                splitDone.remove(g);
                if (!g.removed) onDeath(g, burst);
                continue;
            }
            if (split && !splitDone.contains(g) && !g.noReward && !g.sp.is(Role.SWARM) && g.hp < g.maxHp - 0.01 && g.hp > 2) {
                splitDone.add(g);
                double yr = Math.toRadians(-g.yaw);
                Vector side = new Vector(Math.cos(yr), 0, -Math.sin(yr)).multiply(0.6);
                Gabe c = game.mobs.budgetFor(g.sp) ? trySpawn(g.sp, g.pos.clone().add(side), true) : null;
                if (c != null) {
                    g.hp /= 2;
                    c.maxHp = g.maxHp;
                    c.hp = g.hp;
                    c.yaw = g.yaw;
                    splitDone.add(c);
                    if (born == null) born = new ArrayList<>();
                    born.add(c);
                }
            }
        }
        if (born != null) known.addAll(born);
    }

    /** 감독이 웨이브 대기열에서 낸 개체인가(시험 소환, 죽음 결과, 조각이면 false). */
    public boolean fromDirector(Gabe g) {
        return fromDirector.contains(g);
    }

    private void onNew(Gabe g) {
        // 처음 본 종 안내: 이름 지우기 중이거나 흉내 중, 맞지 않는 개체는 알리지 않고 '본 종'으로도 치지 않음(HUD 가 드러날 때, 풀린 뒤 스스로 올림)
        if (!hideNames() && !g.disguised && !g.untargetable && seenRound.add(g.sp)) game.hud.firstSeen(g.sp);
        int lv = foreheadLv.getOrDefault(g.sp, 0);
        if (lv > 0 && fromDirector.contains(g) && rnd.nextInt(3) == 0) {
            lettered.add(g);
            if (g.body != null) g.body.look.on.add("forehead");
        }
    }

    private void onDeath(Gabe g, boolean burst) {
        lastDeathAt = game.now();
        if (lettered.remove(g)) {
            Player best = null;
            double bd = 0;
            for (Map.Entry<UUID, Double> e : game.mobs.contributions(g).entrySet()) {
                if (e.getValue() > bd) {
                    Player p = Bukkit.getPlayer(e.getKey());
                    if (p != null) {
                        best = p;
                        bd = e.getValue();
                    }
                }
            }
            if (best != null) {
                game.augments.offer(best, 3);
                log("이마 글자 처치 " + best.getName() + " " + g.sp.name());
            }
        }
        if (burst && !g.sp.is(Role.BOSS)) {
            Location at = g.loc();
            game.shotFx.burst(at);
            for (Player p : game.players.standing()) {
                if (p.getLocation().add(0, 0.9, 0).distanceSquared(at) <= 2.5 * 2.5) game.players.hurt(p, 6, g, "cause_mut_burst");
            }
        }
    }

    /**
     * 남은 게이브 치우기(보상 없이 죽음 처리해 붙잡힌 사람, 갇힌 사람을 풀어 줌). 치운 수. 감독 추적에서 먼저 빼므로 죽음 효과(변이 burst 터짐,
     * 이마 글자 보상)는 없다. 종이 die() 안에서 새로 만든 개체도 몇 번 더 돌아 치우고, 그래도 남으면 그냥 지운다.
     */
    private int removeLeftovers(String how) {
        int k = 0;
        for (int pass = 0; pass < 4; pass++) {
            List<Gabe> left = new ArrayList<>();
            // 웨이브 끝 정리에서 빠지는 개체(Gabe.persist, 2026-11-12 sp_engine)
            for (Gabe g : game.mobs.all()) if (!g.dead && !cage.contains(g) && !g.persist) left.add(g);
            if (left.isEmpty()) break;
            for (Gabe g : left) {
                forget(g);
                g.noReward = true;
                g.reward = 0;
                if (pass < 3) {
                    try {
                        g.die(new Damage(Math.max(1, g.hp), DamageType.OTHER, null, g.pos.clone(), null, how, 0));
                    } catch (RuntimeException e) {
                        g.remove();
                    }
                }
                if (!g.dead) g.remove();
                k++;
            }
        }
        return k;
    }

    /** 감독 추적에서 뺌(죽음 감지, 이마 글자, 쪼개짐). */
    private void forget(Gabe g) {
        known.remove(g);
        lettered.remove(g);
        splitDone.remove(g);
        fromDirector.remove(g);
    }

    // ------------------------------------------------------------------ 사건

    private void startEvents(Plan p) {
        // 쏠림 알림(7웨이브부터 가끔): 그 쪽 주황 화살촉과 '몰려온다', 소리는 그 쪽 나오는 고리에서
        if (p.lean >= 0) {
            announceDirs(new int[] {p.lean}, ANNOUNCE_DIR + 40);
            Location at = Plaza.gate(p.lean).toLocation(game.world).add(0, 2, 0);
            if (!p.silent) Props.soundAround(at, "director.gate", Sound.BLOCK_BELL_RESONATE, 1.2f, 1f, 0.5f, 140, -1);
        }
        if (p.breather) game.hud.notice("ev_breather", 80);
        // 안개 짙기(재난문자 꼴 HUD, 비튼 경보음)
        if (p.fog > 0) {
            setFog(p.fog, false);
            game.hud.alert("ev_fog", p.fog, 120);
            if (!p.silent) for (Player pl : game.world.getPlayers()) Props.sound(pl, "director.alert", Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1f, 0.55f);
        } else setFog(fogBase(game.wave >= Tune.CLEAR_FROM ? 1 : 0), false);
        fogLevel = p.fog;
        silentOn = p.silent;
        // 노란 점 뜨는 밤(맑은 웨이브 직전 웨이브)
        if (p.night) {
            nightOn = true;
            game.world.setTime(18000);
            placeDot(p);
        } else {
            nightOn = false;
            removeDot();
            long baseT = baseTime();
            if (game.world.getTime() != baseT) game.world.setTime(baseT);
        }
        floodOn = p.flood;
        if (p.flood) {
            game.hud.notice(floodKey(), 100);
            if (!p.silent) for (Vector v : Plaza.grates()) Props.soundAround(v.toLocation(game.world), "director.flood", Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 1f, 1f, 0.6f, 40, -1);
        }
        // 상점 오염(21~): 메뉴 실눈 칸(문 위 웃는 얼굴 장식은 문이 없어 놓이지 않는다)
        if (p.wave >= 21) {
            contamination = Math.min(24, p.wave - 20);
            game.shop.contaminate(contamination);
            placeDecor(Math.min(12, (p.wave - 19) / 2 + 1));
        }
        eyeOn = p.puddleEye;
        if (eyeOn) placeEyes();
        lastEvent = (p.fog > 0 ? "안개" + p.fog + " " : "") + (p.night ? "밤 " : "") + (p.silent ? "조용 " : "") + (p.flood ? "땅갈라짐 " : "") + (p.puddleEye ? "눈 " : "")
                + (p.breather ? "숨고르기 " : "");
    }

    /** 웨이브 사건 끝(쉬는 시간, 판 끝). */
    private void endWaveEvents(boolean all) {
        silentOn = false;
        floodOn = false;
        eyeOn = false;
        eyeTriggered = false;
        fogLevel = 0;
        clearDisplays(eyes);
        if (all) {
            nightOn = false;
            removeDot();
        }
    }

    private void eventTick(long now) {
        if (eyeOn && !eyeTriggered && now % 5 == 0) {
            List<Vector> pts = Plaza.puddles();
            for (Player p : game.players.standing()) {
                Location l = p.getLocation();
                for (int i = 0; i < pts.size(); i++) {
                    Vector v = pts.get(i);
                    double r = Plaza.puddleRadius(i) + 1.3;
                    if ((l.getX() - v.getX()) * (l.getX() - v.getX()) + (l.getZ() - v.getZ()) * (l.getZ() - v.getZ()) <= r * r) {
                        triggerEye(v);
                        return;
                    }
                }
            }
        }
    }

    private void triggerEye(Vector at) {
        eyeTriggered = true;
        for (Display d : eyes) {
            if (d instanceof ItemDisplay id && id.isValid()) {
                LookState ls = new LookState();
                ls.open = true;
                ls.freeze = true;
                id.setItemStack(Body.item("gabe/g01", "", ls, game.worldTime()));
            }
        }
        // 곁에 선 사람의 시점을 튕기던 것은 뺌(2026-11-12 사용자 "시야 각도를 바꾸는 게이브는 등장하지 않게 해"와 같은 취지: 눈 뜸과 소리, 알림은 그대로)
        game.hud.notice("ev_eye", 80);
        if (!silentOn) Props.soundAround(at.toLocation(game.world), "director.eye", Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 1f, 1f, 0.5f, 30, -1);
        log("물웅덩이 속 눈 뜸 빠르기 x1.15");
    }

    // ------------------------------------------------------------------ 안개

    /** 안개 짙기를 바꿈(시계가 있으면 2초 동안 부드럽게, 없으면 보기 거리 대안). now 면 바로. */
    private void setFog(int level, boolean now) {
        fogGoal = Math.max(0, Math.min(5, level));
        if (now) {
            fogPos = Fog.pos(level);
            applyFog();
        }
    }

    /** 타임라인 자리로 옮겨 간다(기본 → 짙음이 맑음을 거치지 않게. 한 자리 30틱). */
    private void fogStep() {
        double goal = Fog.pos((int) fogGoal);
        if (fogPos != goal) {
            double step = 1 / 30.0;
            fogPos = Math.abs(goal - fogPos) <= step ? goal : fogPos + Math.signum(goal - fogPos) * step;
            applyFog();
        } else if (!fogClock && game.now() % 40 == 0) applyFog();
    }

    private void applyFog() {
        if (fogClock) {
            if (!Fog.setTicks(game.world, Fog.ticksAt(fogPos))) fogClock = false;
            if (fogClock) return;
        }
        int lv = Fog.levelAt(fogPos);
        for (Player p : game.world.getPlayers()) Fog.view(p, lv);
    }

    // ------------------------------------------------------------------ 표시물(구경꾼, 문 위 얼굴, 물웅덩이 눈, 노란 점)

    private ItemDisplay faceDisplay(Location at, String model, LookState ls, float yaw, float pitch, float scale, int light) {
        return faceDisplay(at, model, ls, yaw, pitch, scale, 1f, light);
    }

    /** depth 는 얼굴 두께 배율(물웅덩이 눈은 납작하게 눕혀 물속에 잠기게). */
    private ItemDisplay faceDisplay(Location at, String model, LookState ls, float yaw, float pitch, float scale, float depth, int light) {
        ItemStack it = Body.item(model, "", ls, game.worldTime());
        Matrix4f m = new Matrix4f().rotateY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch)).scale(scale, scale, scale * depth)
                .rotateY((float) Math.PI);
        at.setYaw(0);
        at.setPitch(0);
        return game.world.spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(light, light));
            d.setViewRange(1.5f);
            d.setDisplayWidth(scale * 1.5f);
            d.setDisplayHeight(scale * 1.5f);
            d.setTransformationMatrix(m);
        });
    }

    private static void clearDisplays(List<Display> l) {
        for (Display d : l) if (d != null && d.isValid()) d.remove();
        l.clear();
    }

    /** 쉬는 시간 구경꾼(다음 웨이브 정예 수만큼, 사람이 갈 수 있는 끝 바로 밖 흙먼지 속에 서서 가운데를 봄). */
    private void placeOnlookers(Plan next) {
        clearDisplays(onlookers);
        int n = Math.min(8, next.elites + (forcedMurmur > 0 ? 0 : 0));
        Species model = null;
        for (Species sp : next.counts.keySet()) if (sp.is(Role.ELITE)) {
            model = sp;
            break;
        }
        if (model == null) model = Species.G01;
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = Plaza.R_ROAM + 4 + rnd.nextDouble() * 6;
            double x = Plaza.CX + Math.cos(a) * r, z = Plaza.CZ + Math.sin(a) * r;
            float yaw = (float) Math.toDegrees(Math.atan2(x - Plaza.CX, -(z - Plaza.CZ)));
            LookState ls = new LookState();
            ls.freeze = true;
            ls.open = true;
            Location at = new Location(game.world, x, game.mobs.flow.floor(x, z) + model.band.hover, z);
            onlookers.add(faceDisplay(at, "gabe/" + model.key(), ls, yaw, 0, 1f, 6));
        }
        if (n > 0) log("구경꾼 " + n + " " + model.name());
    }

    /** 문 위 웃는 얼굴 장식 n 개(상점 오염). 황야에는 문이 없어(자리 목록이 빔) 아무것도 놓지 않는다. */
    private void placeDecor(int n) {
        List<Vector> slots = Plaza.doorDecor();
        if (slots.isEmpty()) {
            clearDisplays(decor);
            return;
        }
        while (decor.size() > n) {
            Display d = decor.remove(decor.size() - 1);
            if (d.isValid()) d.remove();
        }
        // 고르게 퍼지게: 문 넷을 돌아가며 가운데, 왼쪽, 오른쪽
        int[] order = {1, 4, 7, 10, 0, 3, 6, 9, 2, 5, 8, 11};
        for (int i = decor.size(); i < n && i < order.length; i++) {
            int k = order[i];
            Vector v = slots.get(k);
            int f = Plaza.doorDecorFacing(k);
            float yaw = new float[] {180, 270, 0, 90}[f];
            LookState ls = new LookState();
            ls.freeze = true;
            decor.add(faceDisplay(v.toLocation(game.world), "gabe/g01", ls, yaw, 0, 0.32f, 9));
        }
    }

    /** 물웅덩이 속 잠든 실눈(물 바로 밑에 누워 하늘을 봄). */
    private void placeEyes() {
        clearDisplays(eyes);
        for (Vector v : Plaza.puddles()) {
            LookState ls = new LookState();
            ls.freeze = true;
            float s = (float) Math.min(2.2, Plaza.puddleRadius(eyes.size()) * 1.3);
            eyes.add(faceDisplay(v.toLocation(game.world).add(0, 0.02, 0), "gabe/g01", ls, rnd.nextFloat() * 360, -90, s, 0.12f, 5));
        }
    }

    /**
     * 노란 점: 다음 맑은 웨이브 탐조등이 설 쪽(p.dotDir = 다음 웨이브 lightDir) 하늘 낮게. 팩에 gave:env/yellow_dot 가 있으면 그 모델, 없으면
     * 글자 점.
     */
    private void placeDot(Plan p) {
        removeDot();
        int side = p.dotDir >= 0 ? p.dotDir : p.dirs[rnd.nextInt(p.dirs.length)];
        Vector dv = Plaza.dirVec(side);
        Location at = new Location(game.world, Plaza.CX + dv.getX() * 80, Plaza.FLOOR_Y + 52, Plaza.CZ + dv.getZ() * 80);
        if (packItem("env/yellow_dot")) {
            ItemStack it = ItemStack.of(org.bukkit.Material.PAPER);
            it.setData(DataComponentTypes.ITEM_MODEL, Key.key(Props.NS, "env/yellow_dot"));
            yellowDot = game.world.spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setItemStack(it);
                d.setBillboard(Display.Billboard.CENTER);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(4f);
                d.setTransformationMatrix(new Matrix4f().scale(5f));
            });
        } else {
            yellowDot = game.world.spawn(at, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.text(Component.text("●", TextColor.color(0xF2D34B)));
                d.setBillboard(Display.Billboard.CENTER);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setShadowed(false);
                // 흙먼지 안개가 늘 껴 있어 멀리 뜬 점이 가려진다: 안개를 타지 않는 글자 층으로 그린다
                d.setSeeThrough(true);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(4f);
                d.setTransformationMatrix(new Matrix4f().scale(22f));
            });
        }
    }

    private void removeDot() {
        if (yellowDot != null && yellowDot.isValid()) yellowDot.remove();
        yellowDot = null;
    }

    // ------------------------------------------------------------------ 이마 글자, 변이

    private void foreheadUp(Species sp) {
        int lv = foreheadLv.merge(sp, 1, Integer::sum);
        foreheadKind.putIfAbsent(sp, rnd.nextInt(2));
        foreheadLast = sp;
        game.hud.notice("ev_forehead", sp, 120);
        log("이마 글자 " + sp.name() + " 단계 " + lv + " " + (foreheadKind.get(sp) == 0 ? "빠르기+15%" : "체력+30%"));
    }

    private void addMutationFor(int w) {
        mutWaves.add(w);
        List<String> pool = new ArrayList<>();
        for (String m : MUTATIONS) {
            if (m.equals("riders") && !Species.G54.implemented()) continue;
            if (!mutations.containsKey(m)) pool.add(m);
        }
        if (pool.isEmpty()) pool.addAll(STACKING);
        addMutation(pool.get(rnd.nextInt(pool.size())));
        if (rnd.nextDouble() < 0.3) {
            namelessUntil = w + 4;
            log("정보 지우기 변이 " + TEMP_NAMELESS + " ~" + namelessUntil);
        }
    }

    /** 변이 하나 영구로(시험 훅도 부름). 모르는 키면 false. */
    public boolean addMutation(String key) {
        if (!MUTATIONS.contains(key)) return false;
        if (mutations.containsKey(key) && !STACKING.contains(key)) return true;
        mutations.merge(key, 1, Integer::sum);
        game.hud.notice("notice_mut_" + key, 120);
        log("변이 " + key + " x" + mutations.get(key) + " 모두 " + mutationNames());
        return true;
    }

    private String mutationNames() {
        if (mutations.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : mutations.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey());
            if (e.getValue() > 1) sb.append('x').append(e.getValue());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 곁 틱(모든 상태: 안개, 대기실 우리, 쉬는 시간 웅얼거림)

    /**
     * 모든 상태에서 매 틱(Game.tick 이 맨 앞, director 차례에 부름): 안개 부드럽게 넘기기, 대기실 밖이면 우리 치우기, 쉬는 시간 웅얼거림. Game 이 한 번이라도
     * 부르면 감독의 예비 스케줄러 몫은 멈춘다.
     */
    public void sideTick() {
        gameSide = true;
        stopFallbackIfDone();
        side();
    }

    /**
     * 대기실(LOBBY)에서만 매 틱(Game.lobbyTick 이 부름): 게이브 우리 채우기, 끌기, 소리, 그리고 우리 개체가 있을 때 엔진 Mobs.tick() 한 번. Game 은 LOBBY 에서
     * 따로 mobs.tick() 을 돌리지 않아야 한다(두 번 돌지 않게). Game 이 한 번이라도 부르면 예비 스케줄러의 우리 몫은 멈춘다.
     */
    public void lobbyTick() {
        gameLobby = true;
        stopFallbackIfDone();
        if (game.state != Game.State.LOBBY) return;
        try {
            cageTick();
        } catch (RuntimeException e) {
            game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[감독] 우리 틱 오류", e);
        }
    }

    /** 예비 스케줄러(Game 이 sideTick, lobbyTick 을 아직 안 부를 때만 그 몫을 대신 돌림). */
    private void fallbackSide() {
        if (!gameSide) side();
        if (!gameLobby && game.state == Game.State.LOBBY) {
            try {
                cageTick();
            } catch (RuntimeException e) {
                game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[감독] 우리 틱 오류", e);
            }
        }
    }

    private void stopFallbackIfDone() {
        if (gameSide && gameLobby && sideTask != null) {
            sideTask.cancel();
            sideTask = null;
            game.plugin.getLogger().info("[감독] Game.tick 이 곁 틱을 부름, 예비 스케줄러 멈춤");
        }
    }

    private void side() {
        if (boss != null) {
            try {
                boss.sideTick();
            } catch (RuntimeException e) {
                if (game.now() - bossErrAt >= 200) {
                    bossErrAt = game.now();
                    game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[감독] 보스 고리 곁 틱 예외", e);
                }
            }
        }
        try {
            fogStep();
            if (game.state != Game.State.LOBBY && !cage.isEmpty()) clearCage();
            if (game.state == Game.State.BREAK) breakTick();
            if (game.state == Game.State.OVER && ending) endTick();
        } catch (RuntimeException e) {
            game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[감독] 곁 틱 오류", e);
        }
    }

    private void breakTick() {
        long now = game.now();
        if (murmurVoices <= 0 || now < nextMurmurAt) return;
        nextMurmurAt = now + Math.max(40, 160 / murmurVoices) + rnd.nextInt(60);
        double a = rnd.nextDouble() * Math.PI * 2;
        Location at = new Location(game.world, Plaza.CX + Math.cos(a) * 74, Plaza.FLOOR_Y + 1.5, Plaza.CZ + Math.sin(a) * 74);
        Props.soundAround(at, "director.murmur", Sound.ENTITY_VILLAGER_AMBIENT, 1f, 0.9f + rnd.nextFloat() * 0.2f, 0.5f, 130, -1);
        String key = "murmur_" + (1 + rnd.nextInt(6));
        for (Player p : game.players.inPlay()) game.hud.notice(p, key, 50);
    }

    private void cageTick() {
        // 설정 cage 가 꺼져 있으면(기본) 우리는 없다: 조용히 아무 일도 하지 않는다
        if (!Plaza.cageRoom) {
            if (!cage.isEmpty()) clearCage();
            return;
        }
        long now = game.now();
        boolean someone = false;
        for (Player p : game.world.getPlayers()) {
            if (Plaza.inLobby(p.getLocation().getX(), p.getLocation().getZ())) {
                someone = true;
                break;
            }
        }
        if (!someone) {
            if (!cage.isEmpty()) clearCage();
            return;
        }
        cage.removeIf(g -> g.dead);
        int want = 0;
        for (Species sp : lobbyBundle) if (sp.implemented()) want++;
        if ((cageDirty || cage.size() < Math.min(want, 24)) && now - cageAt >= 60) fillCage();
        for (Gabe g : cage) {
            Long until = cageLure.get(g);
            if (until == null || until - now < 40 || rnd.nextInt(120) == 0) {
                double y = g.flying ? Plaza.LOBBY_Y + 1.5 + rnd.nextDouble() * 3 : Plaza.LOBBY_Y + g.hover;
                game.mobs.lure(g, Plaza.cagePoint(rnd, y), 160);
                cageLure.put(g, now + 160);
            }
        }
        if (now >= nextCageSound && !cage.isEmpty()) {
            nextCageSound = now + 60 + rnd.nextInt(100);
            Gabe g = cage.get(rnd.nextInt(cage.size()));
            List<String> ev = soundsOf(g.sp);
            if (!ev.isEmpty()) Props.soundAround(g.loc(), ev.get(rnd.nextInt(ev.size())), null, 0.8f, 0.95f + rnd.nextFloat() * 0.1f, 1f, 28, -1);
        }
        // Game 은 대기실에서 엔진을 돌리지 않으므로 우리가 있을 때만 여기서 돌림
        if (!cage.isEmpty()) game.mobs.tick();
    }

    private void fillCage() {
        cageAt = game.now();
        if (cageDirty) clearCage();
        cageDirty = false;
        Set<Species> have = EnumSet.noneOf(Species.class);
        for (Gabe g : cage) have.add(g.sp);
        List<Species> order = new ArrayList<>(lobbyBundle);
        order.sort((x, y) -> Integer.compare(x.tier, y.tier));
        for (Species sp : order) {
            if (cage.size() >= 24) break;
            if (have.contains(sp) || !sp.implemented()) continue;
            double y = sp.band == kr.gave.mob.Band.SKY ? Plaza.LOBBY_Y + 3 : Plaza.LOBBY_Y + sp.band.hover;
            Gabe g = cageSpawn(sp, Plaza.cagePoint(rnd, y));
            if (g == null) continue;
            if (g.flying) g.hover = 2;
            game.mobs.lure(g, Plaza.cagePoint(rnd, y), 160);
            cageLure.put(g, game.now() + 160);
            cage.add(g);
        }
    }

    /**
     * 우리 개체 하나: 종 생성자 뒤, 몸 만들기와 spawned() 앞에 우리 표시(untargetable, noRules, noMutate, noBite, noReward)를 먼저 건다. 그래서 종의
     * spawned() 는 {@link #cageSpawning()} 이나 g.noRules 로 우리 개체임을 알 수 있다. spawned() 가 자리를 옮기거나 표시를 풀었으면 되돌린다.
     */
    private Gabe cageSpawn(Species sp, Vector at) {
        if (!sp.implemented() || !game.mobs.budgetFor(sp)) return null;
        Gabe g;
        try {
            g = sp.create(game);
        } catch (RuntimeException e) {
            if (spawnFailed.add(sp)) game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[감독] 우리 " + sp.name() + " 만들기 실패", e);
            return null;
        }
        if (g == null) return null;
        markCage(g);
        cageSpawning = true;
        Gabe out;
        try {
            out = game.mobs.place(g, at);
        } finally {
            cageSpawning = false;
        }
        if (out == null) return null;
        markCage(out);
        if (!Plaza.inCage(out.pos.getX(), out.pos.getZ())) out.pos.copy(at);
        if (game.mobs.budgetUsed() > kr.gave.mob.Mobs.BUDGET) {
            out.remove();
            return null;
        }
        return out;
    }

    private static void markCage(Gabe g) {
        g.untargetable = true;
        g.noRules = true;
        g.noMutate = true;
        g.noBite = true;
        g.noReward = true;
        g.reward = 0;
    }

    /** 감독이 지금 대기실 우리 개체를 놓는 중(종의 생성 뒤 spawned() 안에서 true). 그때 spawned() 는 자리를 옮기거나 사람에게 영향을 주지 말 것. */
    public boolean cageSpawning() {
        return cageSpawning;
    }

    /** 이 개체가 대기실 우리 개체인가(맞지 않고 판 규칙, 보상 없음, 엔진이 끌기로만 움직임). */
    public boolean inCage(Gabe g) {
        return g != null && cage.contains(g);
    }

    private void clearCage() {
        for (Gabe g : cage) if (!g.dead) g.remove();
        cage.clear();
        cageLure.clear();
    }

    // ------------------------------------------------------------------ 팩 읽기(우리 소리, 노란 점 모델)

    private void readPack() {
        packSounds = new EnumMap<>(Species.class);
        packItems = new java.util.HashSet<>();
        try {
            String d = System.getProperty("gave.packDir");
            Path data = game.plugin.getDataFolder().toPath().toAbsolutePath();
            Path dir = d != null ? Path.of(d) : data.getParent().getParent().getParent().resolve("build");
            Path zip = dir.resolve("Gave-pack.zip");
            if (!Files.isRegularFile(zip)) return;
            try (ZipFile z = new ZipFile(zip.toFile())) {
                // 소리 사건 목록: 팩이 모습, 소리 둘로 나뉘어 sounds.json 은 소리 팩에 있다 → 팩 서버가 합쳐 준 목록(없으면 이 zip 의 sounds.json)
                Set<String> events = new LinkedHashSet<>();
                try {
                    if (game.plugin.pack != null) events.addAll(game.plugin.pack.soundEvents());
                } catch (RuntimeException ex) {
                    game.plugin.getLogger().warning("[감독] 팩 소리 목록 읽기 실패: " + ex);
                }
                ZipEntry e = events.isEmpty() ? z.getEntry("assets/gave/sounds.json") : null;
                if (e != null) {
                    JsonObject o = JsonParser.parseString(new String(z.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    events.addAll(o.keySet());
                }
                for (String k : events) {
                    String key = k.startsWith("gave:") ? k.substring(5) : k;
                    // gabe.<종>.<이름>: 종 키는 g01 .. g155(두세 자리)
                    int dot = key.indexOf('.', 5);
                    if (!key.startsWith("gabe.g") || dot < 0 || dot + 1 >= key.length()) continue;
                    Species sp = Species.of(key.substring(5, dot));
                    String name = key.substring(dot + 1);
                    if (sp == null || name.equals("hurt") || name.equals("die")) continue;
                    packSounds.computeIfAbsent(sp, x -> new ArrayList<>()).add(key);
                }
                var en = z.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    if (n.startsWith("assets/gave/items/") && n.endsWith(".json")) packItems.add(n.substring(18, n.length() - 5));
                }
            }
        } catch (Exception ex) {
            game.plugin.getLogger().warning("[감독] 팩 읽기 실패: " + ex);
        }
    }

    private List<String> soundsOf(Species sp) {
        if (packSounds == null) readPack();
        return packSounds.getOrDefault(sp, List.of());
    }

    private boolean packItem(String key) {
        if (packItems == null) readPack();
        return packItems.contains(key);
    }

    // ------------------------------------------------------------------ 글

    private void log(String s) {
        game.plugin.getLogger().info("[판] " + s);
    }

    private static String names(Set<Species> s) {
        StringBuilder sb = new StringBuilder();
        for (Species sp : s) {
            if (sb.length() > 0) sb.append(',');
            sb.append(sp.name());
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    static String dirNames(int[] d) {
        if (d.length >= 4) return "모두";
        StringBuilder sb = new StringBuilder();
        for (int x : d) {
            if (sb.length() > 0) sb.append(',');
            sb.append(Plaza.DIR_KO[x & 3]);
        }
        return sb.toString();
    }

    /** 방향 묶음 글귀 키 조각(북동 → ne, 차례는 n e s w). */
    /** 쏠림 알림: 그 쪽 주황 화살촉과 '몰려온다'(HUD waveFrom, 마크 yaw 0 남 90 서 180 북 270 동). */
    private void announceDirs(int[] dirs, int ticks) {
        double[] yaws = new double[dirs.length];
        for (int i = 0; i < dirs.length; i++) {
            Vector v = Plaza.dirVec(dirs[i]);
            yaws[i] = (Math.toDegrees(Math.atan2(-v.getX(), v.getZ())) + 360) % 360;
        }
        game.hud.waveFrom("ev_dir", ticks, yaws);
    }

    public static String dirKey(int[] d) {
        int[] s = d.clone();
        java.util.Arrays.sort(s);
        StringBuilder sb = new StringBuilder();
        for (int x : s) sb.append(Plaza.DIR_KEY[x & 3]);
        return sb.toString();
    }

    /** 땅 갈라짐(예전 배관 범람) 알림 글귀: 새 글귀 ev_crack 이 팩에 있으면 그것, 없으면 옛 ev_flood. */
    private static String floodKey() {
        return Hud.hasPhrase("ev_crack") ? "ev_crack" : "ev_flood";
    }

    /** out= 은 지금 살아 있는 몰려오는 수 / 동시 수 목표, 뒤 부호는 몰아치기(+)와 잦아듦(-). lean= 은 쏠림 쪽. */
    public String status() {
        return "total=" + total + " spawned=" + spawned + " queue=" + Math.max(0, queue.size() - qAt) + " fog=" + fogLevel + " dirs=" + dirNames(dirs())
                + (plan != null && plan.lean >= 0 ? " lean=" + Plaza.DIR_KO[plan.lean & 3] : "") + " str=" + strength
                + (waveRunning ? " out=" + aliveNow() + "/" + Math.round(aliveGoal()) + (surge ? "+" : "-") : "")
                + (lastEvent.isBlank() || !waveRunning ? "" : " ev=" + lastEvent.trim().replace(' ', ','))
                + " snd=" + hordeN + "/" + chorusN + "/" + heartN + (ending ? " end=" + (game.now() - endAt) + "+" + endFilled + " in=" + endInside : "")
                + bossStatus();
    }

    private String bossStatus() {
        if (boss == null) return "";
        try {
            String s = boss.status();
            return s == null || s.isEmpty() ? "" : " " + s;
        } catch (RuntimeException e) {
            return " boss=?";
        }
    }

    /**
     * 시험 훅 '균형 x [이름 [값]]': Tune 숫자를 보거나 켠 채 바꾼다(밸런스 맞출 때). 난이도 손잡이는 '균형 x DIFFICULTY 1.3', 보스 차례는
     * '균형 x BOSS_WAVES 10,20,30,33,35'(쉼표로).
     */
    private static String tune(String[] a) {
        try {
            if (a.length > 4) {
                java.lang.reflect.Field f = Tune.class.getField(a[3]);
                if (f.getType() == int.class) f.setInt(null, (int) Double.parseDouble(a[4]));
                else if (f.getType() == int[].class) {
                    String[] t = a[4].split(",");
                    int[] v = new int[t.length];
                    for (int k = 0; k < t.length; k++) v[k] = (int) Double.parseDouble(t[k].trim());
                    f.set(null, v);
                } else f.setDouble(null, Double.parseDouble(a[4]));
            }
            StringBuilder sb = new StringBuilder("[감독] 균형");
            for (java.lang.reflect.Field f : Tune.class.getFields()) {
                if (a.length > 3 && !a[3].equals("x") && !f.getName().equals(a[3])) continue;
                Object v = f.get(null);
                sb.append(' ').append(f.getName()).append('=').append(v instanceof int[] arr ? java.util.Arrays.toString(arr).replace(" ", "") : String.valueOf(v));
            }
            return sb.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "[감독] 균형 [이름 [값]]: " + e;
        }
    }

    // ------------------------------------------------------------------ 시험 훅

    private static int dirArg(String s) {
        return switch (s) {
            case "북", "n", "0" -> 0;
            case "동", "e", "1" -> 1;
            case "남", "s", "2" -> 2;
            case "서", "w", "3" -> 3;
            default -> -1;
        };
    }

    private static double num(String[] a, int i, double def) {
        if (a.length <= i) return def;
        try {
            return Double.parseDouble(a[i]);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * /gave 시험 &lt;행동&gt; &lt;이름&gt; ...: 감독, 묶음 [다시], 웨이브내용 &lt;n&gt;, 사건 &lt;안개 n|범람|밤|조용|오염 n|웅얼 [n]|구경 [n]|이마 [종]|눈|탐조등&gt;,
     * 변이 &lt;키|목록&gt;, 방향 &lt;북|동|남|서|0..3&gt; [둘째] (끝 이면 풂), 감독빨리 [배], 카메라 &lt;이름&gt; x y z yaw pitch [낮|밤], 카메라끝 &lt;이름&gt;,
     * 우리, 새종 &lt;종&gt;(다음 웨이브 새 종 고정), 안개계획 &lt;웨이브&gt; &lt;0..5&gt;, 막힘정리(멈춘 웨이브 정리와 같은 길), 맵검사, 맵점 &lt;이름&gt;,
     * 맵목록 &lt;종류&gt;. 사건에 금빛(G08 하나) 더함. 방향은 쏠림 쪽을 고정한다(모두 = 쏠림 없음, 끝 = 풂). 사건 범람 = 땅갈라짐.
     * 안개틱 &lt;틱&gt;(안개 시계를 바로 그 틱으로), 균형 [이름 [값]](Tune 숫자 보기, 바꾸기), 감독세기 [-2..3](감독 세기를 바로 정함).
     */
    public String test(Player p, String[] a) {
        if (a.length < 2) return null;
        String act = a[1];
        String map = Plaza.test(p, a);
        if (map != null) return map;
        String rec = game.records.test(p, a);
        if (rec != null) return rec;
        switch (act) {
            case "감독" -> {
                return "[감독] state=" + game.state + " wave=" + game.wave + " phase=" + phase() + " " + status() + " remaining=" + remaining()
                        + String.format(Locale.ROOT, " hpMul=%.2f speedMul=%.2f dmgMul=%.2f mutT=%.2f diff=%.3f people=%d crowd=%.2f dmgWave=%.2f dmgPeople=%.2f rewardMul=%.2f saw=%.2f", hpMul(), speedMul(), dmgMul(), mutTimeMul(), difficulty(), people(), crowdMul(), dmgWave(), dmgPeople(), rewardMul(), saw(Math.max(1, game.wave)))
                        + " silent=" + silentOn + " night=" + nightOn + " flood=" + floodOn + " eye=" + eyeOn + "/" + eyeTriggered + " contam=" + contamination
                        + " fogClock=" + fogClock + "(" + Fog.ticks(game.world) + ") pack=" + Fog.note() + " muts=" + mutationNames() + " eaten=" + trappedRound
                        + " downs=" + downsWave + " forehead=" + foreheadLv
                        + (yellowDot != null && yellowDot.isValid() ? String.format(Locale.ROOT, " dot=%.0f,%.0f,%.0f(%s)", yellowDot.getLocation().getX(),
                                yellowDot.getLocation().getY(), yellowDot.getLocation().getZ(), Plaza.DIR_KO[Plaza.dirOf(yellowDot.getLocation().getX(), yellowDot.getLocation().getZ())]) : "")
                        + " side=" + (gameSide ? "game" : "task") + "/" + (gameLobby ? "game" : "task")
                        + " plan=" + (plan == null ? "-" : plan.describe())
                        + (nextPlan == null ? "" : " next=" + nextPlan.describe());
            }
            case "묶음" -> {
                if (a.length > 3 && a[3].equals("다시")) {
                    lobbyBundle.clear();
                    lobbyBundle.addAll(draw(rnd));
                    cageDirty = true;
                    if (game.state == Game.State.LOBBY) {
                        bundle.clear();
                    } else if (bundle.isEmpty()) bundle.addAll(lobbyBundle);
                }
                return "[감독] 묶음 " + names(bundle) + " | 대기실 " + names(lobbyBundle) + " (" + lobbyBundle.size() + "종, 구현 "
                        + Species.values().length + "중 " + java.util.Arrays.stream(Species.values()).filter(Species::implemented).count() + ")";
            }
            case "웨이브내용" -> {
                int n = (int) num(a, 3, game.wave + 1);
                boolean temp = bundle.isEmpty();
                if (temp) bundle.addAll(lobbyBundle);
                Plan pl = plan(n, new Random(n * 7919L));
                if (temp) bundle.clear();
                return "[감독] " + pl.describe() + " total=" + pl.total() + " lead=" + pl.lead + " base=" + pl.base + " spike=" + pl.spike;
            }
            case "사건" -> {
                return event(p, a);
            }
            case "변이" -> {
                String k = a.length > 3 ? a[3] : "목록";
                if (k.equals("목록")) return "[감독] 변이 " + mutationNames() + " 가능 " + MUTATIONS;
                return addMutation(k) ? "[감독] 변이 " + mutationNames() : "[감독] 모르는 변이 " + k + " " + MUTATIONS;
            }
            case "방향" -> {
                if (a.length > 3 && a[3].equals("끝")) {
                    forcedDirs = null;
                    return "[감독] 방향 풂";
                }
                List<Integer> ds = new ArrayList<>();
                for (int i = 3; i < a.length; i++) {
                    int d = dirArg(a[i]);
                    if (d == 4 || a[i].equals("모두")) {
                        ds.clear();
                        ds.addAll(List.of(0, 1, 2, 3));
                        break;
                    }
                    if (d >= 0 && !ds.contains(d)) ds.add(d);
                }
                if (ds.isEmpty()) return "[감독] 방향 <북|동|남|서> | 모두 | 끝 (쏠림 쪽을 고정)";
                forcedDirs = ds.stream().mapToInt(Integer::intValue).toArray();
                int lean = forcedDirs.length < 4 ? forcedDirs[0] : -1;
                if (plan != null) {
                    plan.lean = lean;
                    if (plan.lightDir >= 0 && lean >= 0) plan.lightDir = lean;
                    if (waveRunning && lean >= 0) {
                        // 도는 웨이브: 남은 대기열의 3분의 1 을 쏠림 덤으로 돌리고 알린다
                        for (int i = qAt; i < queue.size(); i += 3) queue.set(i, new Queued(queue.get(i).sp(), true));
                        announceDirs(new int[] {lean}, 100);
                    }
                }
                return "[감독] 쏠림 " + (lean < 0 ? "없음" : Plaza.DIR_KO[lean]);
            }
            case "새종" -> {
                Species sp = a.length > 3 ? Species.of(a[3]) : null;
                if (sp == null) return "[감독] 새종 <종>";
                forcedFresh = sp;
                // 막은 종(설정 bannedSpecies)도 시험으로는 나오게: 계획에 넣고 나중 틱에 놓을 때 Mobs.spawn 이 막지 않게 연다
                if (SpeciesBan.listed(sp)) SpeciesBan.free(sp);
                return "[감독] 다음 웨이브 새 종 " + sp.name() + (spawnable(sp) || bundle.isEmpty() || SpeciesBan.listed(sp) ? "" : " (이 판 묶음에 없음)")
                    + (SpeciesBan.listed(sp) ? " (막은 종, 시험으로 엶)" : "");
            }
            case "막은종" -> {
                return "[감독] " + SpeciesBan.describe();
            }
            case "안개계획" -> {
                int n = (int) num(a, 3, -1), lv = (int) num(a, 4, -1);
                if (n < 1 || lv < 0 || lv > 5) return "[감독] 안개계획 <웨이브> <0..5>";
                fogPlan.put(n, lv);
                return "[감독] 안개계획 w" + n + "=" + lv;
            }
            case "깔린" -> {
                // 살아 있는 미리 깔린 종 전부(자리, 같은 자리 겹침, 막힌 블록 안이면 !)
                StringBuilder sb = new StringBuilder();
                int n = 0, dup = 0;
                List<Gabe> seen = new ArrayList<>();
                for (Gabe g : game.mobs.all()) {
                    if (g.dead || g.sp.spawn != Species.Spawn.PLACED || cage.contains(g)) continue;
                    n++;
                    for (Gabe o : seen) if (o.sp == g.sp && o.pos.distanceSquared(g.pos) < 0.25) dup++;
                    seen.add(g);
                    boolean in = game.world.getBlockAt(g.pos.getBlockX(), g.pos.getBlockY(), g.pos.getBlockZ()).getType().isSolid();
                    sb.append(' ').append(g.sp.name()).append(String.format(Locale.ROOT, "@%.1f,%.1f,%.1f", g.pos.getX(), g.pos.getY(), g.pos.getZ())).append(in ? "!" : "");
                }
                return "[감독] 깔린 " + n + " 겹침 " + dup + sb;
            }
            case "막힘정리" -> {
                // 멈춘 웨이브 정리와 같은 길(죽음 효과 없이 남은 개체 치우기)
                int k = removeLeftovers("stall");
                lastDeathAt = game.now();
                return "[감독] 막힘정리 " + k + " known=" + known.size() + " lettered=" + lettered.size();
            }
            case "감독세기" -> {
                // 감독세기 x [-2..3]: 감독 세기를 바로 정함(이어 가는 시험 판에서 앞 판의 세기를 되살림). 다음에 짜는 웨이브 계획부터 먹는다
                if (a.length > 3) strength = (int) Math.max(-2, Math.min(3, num(a, 3, 0)));
                return "[감독] 세기 " + strength;
            }
            case "감독빨리" -> {
                pace = Math.max(0.1, num(a, 3, 4));
                return "[감독] 내보내기 빠르기 x" + pace;
            }
            case "카메라" -> {
                if (p == null) return "[감독] 사람 없음";
                double x = num(a, 3, 0), y = num(a, 4, 120), z = num(a, 5, 0);
                float yaw = (float) num(a, 6, 0), pitch = (float) num(a, 7, 90);
                p.setGameMode(GameMode.SPECTATOR);
                if (a.length > 8) {
                    if (a[8].equals("낮")) p.setPlayerTime(6000, false);
                    else if (a[8].equals("밤")) p.setPlayerTime(18000, false);
                }
                p.teleport(new Location(game.world, x, y, z, yaw, pitch));
                return "[감독] 카메라 " + p.getName() + String.format(Locale.ROOT, " %.1f %.1f %.1f %.0f %.0f", x, y, z, yaw, pitch);
            }
            case "카메라끝" -> {
                if (p == null) return "[감독] 사람 없음";
                p.resetPlayerTime();
                p.setGameMode(GameMode.ADVENTURE);
                if (game.state == Game.State.LOBBY) game.toLobby(p);
                else p.teleport(Plaza.shopFront(game.world));
                return "[감독] 카메라끝 " + p.getName();
            }
            case "안개틱" -> {
                // 안개 시계를 바로 그 틱으로(타임라인 값을 눈으로 맞출 때). 다음 안개 바뀜까지 그대로
                long t = (long) num(a, 3, Fog.ticksFor(0));
                return "[감독] 안개틱 " + t + " ok=" + Fog.setTicks(game.world, t) + " clock=" + fogClock;
            }
            case "균형" -> {
                String out = tune(a);
                // 난이도 손잡이를 바꿨으면 보스 체력 배율도 맞춘다
                if (a.length > 4 && (a[3].equals("DIFFICULTY") || a[3].equals("DIFF_BOSS_HP"))) syncBossHp();
                tripAt = Long.MIN_VALUE;
                return out;
            }
            case "보스" -> {
                // /gave 시험 보스 <이름|x> <행동> ...(docs/모듈/보스.md 18절)
                return boss == null ? "[보스] 고리 없음" : boss.test(p, a);
            }
            case "우리" -> {
                StringBuilder sb = new StringBuilder("[감독] 우리(설정 cage=" + Plaza.cageRoom + ") " + cage.size());
                for (Gabe g : cage) sb.append(' ').append(g.sp.name()).append(String.format(Locale.ROOT, "@%.1f,%.1f,%.1f", g.pos.getX(), g.pos.getY(), g.pos.getZ()));
                return sb.toString();
            }
            default -> {
                return null;
            }
        }
    }

    private String event(Player p, String[] a) {
        String k = a.length > 3 ? a[3] : "";
        int lv = (int) num(a, 4, -1);
        switch (k) {
            case "안개" -> {
                int l = lv < 0 ? 4 : lv;
                fogLevel = l;
                setFog(l, false);
                if (l > 0) game.hud.alert("ev_fog", l, 120);
                return "[감독] 안개 " + l + " clock=" + fogClock + " ticks=" + Fog.ticksFor(l);
            }
            case "범람", "땅갈라짐" -> {
                floodOn = true;
                game.hud.notice(floodKey(), 100);
                int made = 0;
                if (spawnable(Species.G31) || Species.G31.implemented()) {
                    for (int i = 0; i < Math.max(1, lv); i++) if (trySpawn(Species.G31, floodPoint(), false) != null) made++;
                }
                return "[감독] 땅갈라짐 G31=" + made + (Species.G31.implemented() ? "" : " (G31 없음: 건너뜀)") + " 틈 " + Plaza.grates().size();
            }
            case "밤" -> {
                if (a.length > 4 && a[4].equals("끝")) {
                    nightOn = false;
                    removeDot();
                    game.world.setTime(13000);
                    return "[감독] 밤 끝";
                }
                nightOn = true;
                game.world.setTime(18000);
                Plan pl = new Plan(game.wave);
                int d = a.length > 4 ? dirArg(a[4]) : -1;
                pl.dirs = d >= 0 ? new int[] {d} : plan != null ? plan.dirs.clone() : pl.dirs;
                pl.dotDir = d >= 0 ? d : plan != null ? plan.dotDir : -1;
                placeDot(pl);
                return "[감독] 밤 dot=" + (yellowDot == null ? "-" : yellowDot.getType().name());
            }
            case "조용" -> {
                silentOn = !(a.length > 4 && a[4].equals("끝"));
                return "[감독] 조용 " + silentOn;
            }
            case "오염" -> {
                contamination = Math.max(0, lv < 0 ? 4 : lv);
                game.shop.contaminate(contamination);
                placeDecor(Math.min(12, contamination));
                return "[감독] 오염 " + contamination + " 장식 " + decor.size();
            }
            case "웅얼" -> {
                forcedMurmur = Math.max(1, lv < 0 ? 2 : lv);
                murmurVoices = forcedMurmur;
                nextMurmurAt = game.now();
                return "[감독] 웅얼 " + murmurVoices + " (쉬는 시간에 들림)";
            }
            case "구경" -> {
                Plan pl = new Plan(game.wave + 1);
                pl.elites = lv < 0 ? 3 : lv;
                placeOnlookers(pl);
                return "[감독] 구경꾼 " + onlookers.size();
            }
            case "이마" -> {
                Species sp = a.length > 4 ? Species.of(a[4]) : Species.G01;
                if (sp == null) return "[감독] 모르는 종";
                foreheadUp(sp);
                return "[감독] 이마 " + sp.name() + " " + foreheadLv.get(sp);
            }
            case "눈" -> {
                eyeOn = true;
                eyeTriggered = false;
                placeEyes();
                return "[감독] 물웅덩이 눈 " + eyes.size();
            }
            case "눈뜸" -> {
                if (eyes.isEmpty()) placeEyes();
                eyeOn = true;
                triggerEye(Plaza.puddles().get(0));
                return "[감독] 눈 뜸 speedMul=" + String.format(Locale.ROOT, "%.2f", speedMul());
            }
            case "탐조등" -> {
                // 자리만 n 개 고르고(만들 수 있으면 만듦), 막힌 블록 안(몸 높이 -1..+1)에 든 자리 수를 셈. 자리 n 이 12 넘으면 만들지 않고 셈만
                StringBuilder pts = new StringBuilder();
                int made = 0, solid = 0, want = Math.max(2, lv);
                for (int i = 0; i < want; i++) {
                    Vector at = edgePoint(Species.G43);
                    boolean in = false;
                    for (int dy = -1; dy <= 1; dy++) {
                        if (game.world.getBlockAt(at.getBlockX(), at.getBlockY() + dy, at.getBlockZ()).getType().isSolid()) in = true;
                    }
                    if (in) solid++;
                    if (i < 6) pts.append(String.format(Locale.ROOT, " (%.1f,%.1f,%.1f)", at.getX(), at.getY(), at.getZ()));
                    if (want <= 12 && Species.G43.implemented() && trySpawn(Species.G43, at, false) != null) made++;
                }
                return "[감독] 탐조등 " + made + " 자리 " + want + " 막힘 " + solid + (Species.G43.implemented() ? "" : " (G43 없음: 자리만)") + pts;
            }
            case "금빛" -> {
                // 보상 종 하나(웨이브 계획의 드문 한 마리와 같은 길)
                Species sp = Species.G08;
                if (!sp.implemented()) return "[감독] G08 없음: 건너뜀";
                Gabe g = spawnEntry(sp, false);
                return "[감독] 금빛 " + (g == null ? "실패" : String.format(Locale.ROOT, "@%.1f,%.1f,%.1f", g.pos.getX(), g.pos.getY(), g.pos.getZ()));
            }
            case "끝" -> {
                endWaveEvents(true);
                setFog(0, false);
                game.world.setTime(13000);
                clearDisplays(onlookers);
                murmurVoices = forcedMurmur = 0;
                return "[감독] 사건 끝";
            }
            default -> {
                return "[감독] 사건 안개 n|땅갈라짐(범람) [n]|밤 [끝]|조용 [끝]|오염 n|웅얼 [n]|구경 [n]|이마 [종]|눈|눈뜸|탐조등 [n]|금빛|끝";
            }
        }
    }
}
