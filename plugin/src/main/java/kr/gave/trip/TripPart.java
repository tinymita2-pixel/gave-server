package kr.gave.trip;

import java.util.Map;

import org.bukkit.entity.Player;

/**
 * 원정(docs/모듈/원정.md)의 부품 하나. 허브 {@link Trip} 이 이름으로 찾아 만든다(리플렉션, 클래스가 없으면 건너뜀).
 *
 * <p>약속: 클래스는 {@code kr.gave.trip.<이름>}(이름 목록 {@link Trip#PARTS}), 공개 생성자 {@code (Game, Trip)}. 생성자는 Game 생성자 안에서
 * 불린다(game.trip 은 아직 null: 넘겨받은 trip 을 쓴다. game 의 다른 모듈은 다 만들어진 뒤다). 모든 메서드는 기본 구현이 '아무 일 없음'이라
 * 필요한 것만 덮어쓴다. 부품이 던지면 허브가 그 부품의 그 부름만 건너뛰고(10초에 한 번 기록) 판은 계속 돈다.
 * 설정 trip.enabled 가 꺼져 있으면 아래 메서드는 하나도 안 불린다({@link #enabled(boolean)} 만 빼고).
 *
 * <p>부르는 차례(Game 이 Trip 을, Trip 이 부품을 {@link Trip#PARTS} 차례로):
 * <pre>
 * 판 시작        startGame()                      Game.start(): 사람들이 판에 든 직후, PREP 앞
 * 라운드 n 앞    beforeRound(n)                   Game.beginWave(n) 맨 앞(감독이 계획을 짜기 전: 라운드 종류, 덱)
 * 라운드 n 시작  beginRound(n)                    Game.beginWave(n) 맨 끝(감독이 웨이브를 연 뒤)
 * 웨이브 중      roundDone(done) / lost()         매 틱: 라운드 끝 판정, 판 끝 조건
 * 쉬는 시간      beginBreak(n)                    Game.beginBreak() 맨 끝(n = 끝난 라운드)
 *               breakTicks(ticks)                매 틱: 쉬는 시간 길이
 * 매 틱          tick()                           모든 상태(LOBBY 포함), 모듈 차례 맨 끝. 상태는 game.state 로 본다
 * 판 끝          gameOver()                       Game.gameOver()(OVER 가 된 직후)
 * 판 정리        reset()                          Game.reset()(대기실로: 땅, 테마, 개체를 되돌림)
 * </pre>
 * /gave 웨이브 n 은 라운드를 건너뛴다(beforeRound, beginRound 가 1, 2, 3 차례가 아닐 수 있다. LOBBY 에서 치면 startGame 다음 바로 n).
 */
public interface TripPart {

    /** 판 시작(지도, 덱, 트럭 체력, 테마). */
    default void startGame() {
    }

    /** 라운드 n 을 열기 전(감독이 계획을 짜기 전). */
    default void beforeRound(int n) {
    }

    /** 라운드 n 이 열린 뒤. */
    default void beginRound(int n) {
    }

    /**
     * 라운드 끝 판정에 낌. done = 앞 부품까지의 판정(맨 처음은 감독 Director.waveDone()). 목표가 있는 라운드는 감독이 끝났다 해도 false 로 잡아 두거나,
     * 감독이 안 끝났어도 true 로 끝낼 수 있다. 기본은 그대로 넘김.
     */
    default boolean roundDone(boolean done) {
        return done;
    }

    /** 판 끝 조건(웨이브 중 매 틱). 판을 끝내야 하면 까닭 키(예: "truck"), 아니면 null. */
    default String lost() {
        return null;
    }

    /** 쉬는 시간이 열린 뒤(n = 방금 끝난 라운드): 보상, 갈림길, 이동 예약. */
    default void beginBreak(int n) {
    }

    /** 쉬는 시간 길이에 낌. ticks = 앞 부품까지의 길이(맨 처음은 감독 Director.breakTicks()). */
    default int breakTicks(int ticks) {
        return ticks;
    }

    /** 매 틱(모든 상태). */
    default void tick() {
    }

    /** 판 끝(OVER 가 된 직후). 까닭은 {@link Trip#endCause()}. */
    default void gameOver() {
    }

    /** 다시 하기(Game.retry: 진 판의 판 끝 동안 채팅을 누름, 그 라운드 앞 준비 시간으로 되감음). 판 끝에 한 것을 되돌린다. 판 끝 까닭은 이 뒤에 지워진다. */
    default void retry() {
    }

    /** 판 정리(대기실로). 놓은 개체, 바꾼 땅과 하늘을 되돌린다. */
    default void reset() {
    }

    /** 원정이 켜지거나 꺼짐(시험 훅 '원정 x 켬|끔', 설정). 꺼질 때는 reset() 다음에 불린다. 켜진 채 만들어질 때는 안 불린다(생성자에서 trip.enabled 를 본다). */
    default void enabled(boolean on) {
    }

    /** 시험 훅(/gave 시험 &lt;행동&gt; &lt;이름|x&gt; ...): 자기 행동이면 결과 글, 아니면 null. a[1] = 행동. */
    default String test(Player p, String[] a) {
        return null;
    }

    /** /gave 상태 의 trip= 뒤에 붙일 짧은 글(키=값 띄어쓰기). 없으면 null. 'state=' 로 시작하는 키는 쓰지 않는다(대본 until 과 헷갈림). */
    default String status() {
        return null;
    }

    /**
     * 그 사람 화면에 쓸 HUD 값(키 → 값). HUD 칸은 Hud 가 {@link Trip#hud(Player)} 로 읽어 그린다. 키는 부품 이름을 앞에 붙인다(예: truck.frac).
     * 매 틱 사람마다 불리니 가볍게.
     */
    default void hud(Player p, Map<String, Object> out) {
    }

    /**
     * 배율 물음({@link Trip#mul(String)} 이 모든 부품의 값을 곱함). 모르는 키는 1. 약속한 키: diff(난이도 손잡이: 감독이 총량, 체력, 피해에 먹임), hp, speed, dmg, reward(감독 배율 고리),
     * truckShare(트럭 노림 몫), truckDmg(트럭이 받는 피해), price(상점 값), repair(수리 값), revive(꺼내는 값), money(돈 벌이).
     */
    default double mul(String key) {
        return 1;
    }

    /**
     * 값 물음({@link Trip#ask(String)} 이 차례로 물어 처음 null 아닌 답을 씀). 모르는 키는 null. 약속한 키: act(막 1..4, Integer),
     * stage(스테이지 1..16, Integer), stageKind(스테이지 종류 키, String), roundKind(라운드 종류 키), goal(목표 키), baseTime(세계 시각, Long).
     */
    default Object ask(String key) {
        return null;
    }

    /**
     * 돕기 동작(좌클릭, 휘두름)을 가로챔: Players.help 맨 앞(죽은 사람, 쓰러진 사람도 거침)에서 {@link Trip#help(Player)} 가 차례로 물어 처음 true 에서 멈춘다.
     * true 면 그 좌클릭은 상점 열기, 일으키기, '살려 달라' 로 가지 않는다(예: 표지판 투표).
     */
    default boolean help(Player p) {
        return false;
    }

    /**
     * 부품 사이 사건({@link Trip#fire(String, Object)} 가 모든 부품에 돌림). 모르는 사건은 흘려보낸다. 약속한 사건은 Trip 의 EV_ 상수
     * (도착 arrive: arg = 스테이지 종류 키, 떠남 depart, 보스 처치 bossDown, 사람 죽음 dead: arg = Player, 꺼냄 revived: arg = Player ...).
     */
    default void on(String event, Object arg) {
    }
}
