package kr.gave.trip;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.game.Hud;
import kr.gave.world.Plaza;

/**
 * 판 시작 인트로(2026-11-14 사용자: "게임시작시 전체유저에게 보여주고 들려주는 인트로", 분위기는 무서움보다 기괴함).
 * 검은 화면에 글이 한 줄씩 뜨고(사이에 얼굴 그림이 끼어듦), 소리 한 곡이 같이 흐른 뒤, 밝아지면서 큰 제목과 레터박스, 엔진과 경적, 그리고 첫 웨이브.
 *
 * <pre>
 * 틱 0                         판 시작 다음 틱. 검어지기 시작, 소리 시작, 배경 음악 멈춤, 상점 잠김
 * fadeIn                       다 검음: 장면이 차례로(장면표 jar 의 trip/intro.json)
 * fadeIn + 길이                 밝아지기 시작: 엔진 소리, 레터박스, 큰 제목. 지평선 그림자 떼는 이미 서 있다(준비 시간이 그 전부터 흐름)
 * 위 + reveal - 30              경적
 * 위 + reveal                   첫 웨이브(Game 이 연다. 그때 배경 음악이 북 한 방과 함께 돌아옴)
 * </pre>
 *
 * <p>화면은 HUD 값으로만 낸다: screen.black, screen.line(지금 Hud 가 그림), screen.alt(0.5초마다 잠깐 비치는 다른 줄), screen.cover(검은 화면 위
 * 그림: 이 둘은 Hud.compose 에 여덟 줄이 들어가야 보인다. build/intro/patches.md). 사람마다 매 틱 보내는 것은 없다(HUD 는 장면이 바뀔 때만 다시 감).
 * 검어지고 밝아지는 흐림과 검은 덮개는 이동 화면의 장치({@link Travel#black})를 글 없이 빌린다.
 *
 * <p>이 클래스만 올려도 돌게 기존 파일을 고치지 않고 붙는다: Game.start 가 부르는 옛 오프닝(Travel.opening)은 Travel 의 공개 값 OPEN_STAY, START_BLACK 을
 * 0 으로 두어 끄고, 준비 시간은 Game.stateAt 을 밀어 맞춘다. 인트로 글귀가 팩에 아직 안 구워졌거나 설정이 off 면 아무것도 건드리지 않아 옛 오프닝이 그대로 나온다.
 * 소리(gave:trip.intro_&lt;대본&gt;_full|short, tools/trip/intro.py 가 구움)가 팩에 없으면 소리 없이 글만 나오고 배경 음악도 그대로 흐른다.
 *
 * <p>설정: trip.intro = full(매 판 긴 판, 기본: 2026-11-16 사용자 "두번째로 시작하면 짧은인트로만 나옴. 고치고") | first(서버를 켠 뒤 첫 판만 긴 판,
 * 다음부터 짧은 판) | short(매 판 짧은 판) | off(옛 오프닝).
 * 대본은 장면표의 하나(2026-11-14 사용자가 a '게이브 씨' 를 고름). 시험 훅 '인트로 x 틀기 [짧게]'(대기실에서 모두에게 틀어 봄), '인트로 x 끝', '인트로 x 상태'.
 */
public final class Story implements TripPart {

    /** 장면 하나: 글 한 줄(없으면 그림만), 잠깐 비치는 다른 줄, 검은 화면 위 그림(여럿이면 머무는 동안 차례로), 머무는 틱, 그 뒤 글 없는 틱. */
    private record Card(String line, String alt, List<String> covers, int stay, int gap) {
    }

    /** 대본 하나: 긴 판과 짧은 판의 장면, 밝아질 때의 큰 제목과 부제(글귀 키, 없으면 null). */
    private record Script(String key, List<Card> full, List<Card> brief, String title, String sub) {
    }

    private final Game game;
    private final Trip trip;
    /** 장면표의 대본(못 읽었으면 null: 옛 오프닝이 나옴). */
    private Script script;
    /** 장면표의 검어지는 틱, 밝아지는 틱, 밝아지기 시작해 첫 웨이브까지의 틱. */
    private int fadeIn = 20, fadeOut = 20, reveal = 80;
    /** 설정 trip.intro. */
    public String mode;
    /** Travel 의 옛 오프닝 값(인트로가 맡는 판에는 0 으로 두고, 못 트는 판에는 되돌린다). */
    private final int oldOpenStay = Travel.OPEN_STAY, oldStartBlack = Travel.START_BLACK;
    /** 서버를 켠 뒤 인트로를 튼 판 수, 판이 시작돼 다음 틱에 인트로를 틀 차례인가. */
    private int played;
    private boolean armed;
    private String warned = "";

    /** 도는 중인 인트로: 장면, 시작 틱(-1 = 안 도는 중), 다 검은 동안의 길이, 소리 사건, 끄기 전의 음악 설정(null = 안 건드림), 판 안에서 도는가. */
    private List<Card> cards = List.of();
    private long at = -1;
    private int length;
    private String sound;
    private Boolean musicWas;
    private boolean inGame;

    public Story(Game game, Trip trip) {
        this.game = game;
        this.trip = trip;
        var c = game.plugin.getConfig();
        mode = c.getString("trip.intro", "full").toLowerCase(Locale.ROOT);
        // YAML 은 따옴표 없는 off 를 false 로 읽는다
        if (mode.equals("false")) mode = "off";
        if (!List.of("first", "full", "short", "off").contains(mode)) {
            game.plugin.getLogger().warning("[이야기] 설정 trip.intro 에 모르는 값 " + mode + ": full 로 읽음(full, first, short, off)");
            mode = "full";
        }
        load();
    }

    // ------------------------------------------------------------------ 장면표

    /** jar 의 trip/intro.json(꼴은 그 파일의 '설명', 대본은 scripts 의 하나: 그 키가 소리 이름에 들어감). 못 읽으면 대본 없이(옛 오프닝이 나옴). */
    private void load() {
        try (InputStream in = Story.class.getClassLoader().getResourceAsStream("trip/intro.json")) {
            if (in == null) throw new IllegalStateException("jar 에 trip/intro.json 없음");
            JsonObject o = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            fadeIn = num(o, "fadeIn", fadeIn);
            fadeOut = num(o, "fadeOut", fadeOut);
            reveal = num(o, "reveal", reveal);
            int gap = num(o, "gap", 6);
            Map.Entry<String, JsonElement> e = o.getAsJsonObject("scripts").entrySet().iterator().next();
            JsonObject s = e.getValue().getAsJsonObject();
            script = new Script(e.getKey(), cards(s, "full", gap), cards(s, "short", gap), str(s, "title"), str(s, "sub"));
        } catch (Exception e) {
            script = null;
            game.plugin.getLogger().warning("[이야기] 인트로 장면표 읽기 실패(옛 오프닝으로): " + e);
        }
    }

    private static List<Card> cards(JsonObject s, String name, int gap) {
        List<Card> out = new ArrayList<>();
        if (s.has(name)) for (JsonElement e : s.getAsJsonArray(name)) {
            JsonObject c = e.getAsJsonObject();
            List<String> covers = new ArrayList<>();
            if (c.has("cover")) for (JsonElement k : c.getAsJsonArray("cover")) covers.add(k.getAsString());
            out.add(new Card(str(c, "line"), str(c, "alt"), covers, Math.max(1, num(c, "stay", 40)), Math.max(0, num(c, "gap", gap))));
        }
        return out;
    }

    private static int num(JsonObject o, String key, int def) {
        return o.has(key) ? o.get(key).getAsInt() : def;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    /** 이번 판에 틀 장면들. 못 틀면 null(설정 off, 장면표 없음, 검은 덮개나 글귀가 팩에 없음): 그 판은 옛 오프닝이 나온다. */
    private List<Card> pick() {
        if (script == null || mode.equals("off") || !Hud.hasCover("black")) return null;
        List<Card> c = mode.equals("full") || mode.equals("first") && played == 0 ? script.full : script.brief;
        return playable(c) ? c : null;
    }

    private boolean playable(List<Card> c) {
        for (Card k : c) {
            if (k.line == null || Hud.hasPhrase(k.line)) continue;
            if (!warned.equals(k.line)) game.plugin.getLogger().warning("[이야기] 인트로 글귀 " + k.line + " 가 팩에 없음(data/phrases/intro.json 을 넣고 make_hud): 옛 오프닝으로");
            warned = k.line;
            return false;
        }
        return !c.isEmpty();
    }

    // ------------------------------------------------------------------ 틀기와 끊기

    private void begin(List<Card> c, boolean inGame) {
        stop(false);
        Script s = script;
        cards = c;
        this.inGame = inGame;
        at = game.now();
        length = 0;
        for (Card k : c) length += k.stay + k.gap;
        Collection<Player> who = inGame ? game.players.inPlay() : new ArrayList<>(Bukkit.getOnlinePlayers());
        // 검어지고 밝아지는 흐림은 이동 화면의 것(제목 글의 검은 글리프). 시험으로 제목 글 방식(useHud 끔)이면 HUD 글을 덮으므로 빌리지 않는다
        Travel tr = trip.part(Travel.class);
        if (tr != null && Travel.useHud) tr.black(who, fadeIn, length, fadeOut, "");
        // 준비 시간을 인트로 뒤로 민다: 밝아지기 시작하고 reveal 틱 뒤에 첫 웨이브. 지평선 그림자 떼는 준비 40틱부터 서므로 밝아질 때 이미 서 있다
        if (inGame) game.stateAt = Math.max(game.stateAt, at + fadeIn + length + reveal - game.prepSeconds * 20L);
        sound = "trip.intro_" + s.key + (c == s.full ? "_full" : "_short");
        if (Props.hasSound(sound)) {
            // ponytail: 인트로 동안 배경 음악을 Music.enabled 로 잠깐 끈다. 인트로 곡이 music.json 의 cue 로 들어가면 game.music.cue 한 줄로 바꿀 것
            musicWas = game.music.enabled;
            game.music.enabled = false;
            for (Player p : who) {
                p.stopSound(game.music.category);
                p.playSound(p, Props.NS + ":" + sound, game.music.category, game.music.volume, 1f);
            }
        } else sound = null;
        trip.log("인트로 " + s.key + (c == s.full ? " 긴 판 " : " 짧은 판 ") + (fadeIn + length) + "틱" + (inGame ? ", 그 뒤 " + reveal + "틱에 첫 웨이브" : "(시험)")
            + (sound == null ? ", 소리 없음(팩에 없음)" : ""));
    }

    /** 밝아지기 시작: 엔진, 레터박스, 큰 제목. */
    private void reveal() {
        Props.soundAround(center(), "trip.engine_rev", Sound.ENTITY_MINECART_RIDING, 1f, 1f, 0.7f, 64, -1);
        if (Hud.hasCover("letterbox")) for (Player p : game.players.inPlay()) if (game.players.get(p).cover == null) game.hud.cover(p, "letterbox", reveal);
        if (script.title != null) game.hud.bossTitle(script.title, script.sub, reveal - 10);
    }

    /** 인트로를 끊는다(첫 웨이브가 열림, 웨이브 건너뛰기, 판 정리). 검은 동안 끊기면 검은 화면과 흐림도 걷는다. lobby = 대기실로 돌아가는 길. */
    private void stop(boolean lobby) {
        if (at < 0) return;
        boolean black = game.now() - at < fadeIn + length + fadeOut;
        at = -1;
        Collection<? extends Player> who = Bukkit.getOnlinePlayers();
        if (sound != null) for (Player p : who) p.stopSound(Props.NS + ":" + sound, game.music.category);
        sound = null;
        if (musicWas != null) {
            game.music.enabled = musicWas;
            musicWas = null;
            // 대기실로 가는 길이면 끊어 둔 대기 곡이 처음부터 다시 나오게 귀를 새로 만든다(웨이브가 열릴 때는 그대로: 북 한 방 뒤 웨이브 곡)
            if (lobby) for (Player p : who) game.music.forget(p.getUniqueId());
        }
        if (black) {
            Travel tr = trip.part(Travel.class);
            if (tr != null) tr.black(List.of(), 0, 0, 0, "");
            for (Player p : who) p.clearTitle();
        }
    }

    private Location center() {
        return new Location(game.world, Plaza.CX + 0.5, Plaza.FLOOR_Y + 1.5, Plaza.CZ + 0.5);
    }

    // ------------------------------------------------------------------ TripPart

    @Override
    public void startGame() {
        stop(false);
        // 인트로는 판 시작 다음 틱(tick)에 시작한다. 준비 시간이 0 이면 그 전에 첫 웨이브가 열리므로 그 설정에서는 옛 오프닝에 맡긴다
        armed = game.prepSeconds > 0 && pick() != null;
        // 인트로가 맡는 판에는 옛 오프닝(헌사 한 줄 뒤 이야기 한 줄)을 끈다: Game.start 가 이 바로 뒤에 Travel.opening 을 부른다
        Travel.OPEN_STAY = armed ? 0 : oldOpenStay;
        Travel.START_BLACK = armed ? 0 : oldStartBlack;
    }

    @Override
    public void beforeRound(int n) {
        // 첫 웨이브가 열림. '/gave 웨이브 n' 으로 건너뛴 판(대기실에서 치면 판 시작 바로 뒤에 불림)은 인트로 없이, 도는 중이었으면 끊음
        armed = false;
        stop(false);
    }

    @Override
    public void tick() {
        if (armed && game.state == Game.State.PREP) {
            armed = false;
            List<Card> c = pick();
            if (c != null) {
                begin(c, true);
                played++;
            }
        }
        if (at < 0) return;
        long t = game.now() - at - fadeIn - length;
        if (!inGame) {
            if (t >= fadeOut) stop(true);
            return;
        }
        if (t == 0) reveal();
        if (t == reveal - 30) Props.soundAround(center(), "trip.truck_horn", Sound.ITEM_GOAT_HORN_SOUND_2, 1f, 1f, 0.7f, 96, -1);
        // 첫 웨이브가 열리면 beforeRound 가 끊는다. 여기는 그것이 안 온 판(준비 시간이 따로 늘어남)의 뒷정리
        if (t >= reveal + 40 || game.state != Game.State.PREP) stop(false);
    }

    @Override
    public void gameOver() {
        armed = false;
        stop(false);
    }

    @Override
    public void reset() {
        armed = false;
        stop(true);
    }

    @Override
    public void enabled(boolean on) {
        if (on) return;
        reset();
        Travel.OPEN_STAY = oldOpenStay;
        Travel.START_BLACK = oldStartBlack;
    }

    @Override
    public void hud(Player p, Map<String, Object> out) {
        if (at < 0) return;
        long t = game.now() - at - fadeIn;
        if (t < -1 || t >= length) return;
        out.put("screen.black", Boolean.TRUE);
        show(cards, (int) t, out);
    }

    /** 다 검어진 뒤 u 틱째에 보일 것을 out 에 적는다(장면 사이 틈과 끝난 뒤에는 아무것도 안 적음). 검사: build/intro/check_story.py */
    private static void show(List<Card> cards, int u, Map<String, Object> out) {
        for (Card k : cards) {
            if (u < 0) return;
            if (u < k.stay) {
                if (k.line != null) {
                    out.put("screen.line", k.line);
                    if (k.alt != null) out.put("screen.alt", k.alt);
                }
                if (!k.covers.isEmpty()) out.put("screen.cover", k.covers.get(Math.min(k.covers.size() - 1, u * k.covers.size() / k.stay)));
                return;
            }
            u -= k.stay + k.gap;
        }
    }

    @Override
    public Object ask(String key) {
        // 검은 동안은 상점을 잠근다(Shop.canStay 가 묻는다): 아무것도 안 보이는 채로 상점이 열리지 않게. 인트로가 시작되기 전 판 시작 틱(armed)부터 잠가야
        // 그 한 틱에 상점 안내('카운터를 보고 우클릭', 판에 한 번)가 검어지는 화면 밑에서 써 버려지지 않고 상점이 열리는 밝아짐 때 뜬다(2026-11-14 게임 안 시험)
        return key.equals("shopLocked") && (armed || at >= 0 && game.now() - at < fadeIn + length) ? Boolean.TRUE : null;
    }

    @Override
    public String status() {
        if (at < 0) return "intro=-";
        return "intro=" + (cards == script.full ? "full:" : "short:") + (game.now() - at) + "/" + (fadeIn + length + (inGame ? reveal : fadeOut));
    }

    /** 시험 훅. '인트로 x 틀기 [짧게]'(대기실에서만: 접속한 모두에게 검은 화면과 글, 소리. 판은 안 바뀜), '인트로 x 끝', '인트로 x 상태'. */
    @Override
    public String test(Player p, String[] a) {
        if (!a[1].equals("인트로")) return null;
        String sub = a.length > 3 ? a[3] : "상태";
        if (sub.equals("끝")) {
            stop(true);
            return "[이야기] 인트로 끊음";
        }
        if (sub.equals("상태")) {
            if (script == null) return "[이야기] intro=- mode=" + mode + " 장면표 없음";
            return "[이야기] " + status() + " mode=" + mode + " played=" + played + " 긴판=" + (playable(script.full) ? "됨" : "-") + " 짧은판="
                + (playable(script.brief) ? "됨" : "-");
        }
        if (!sub.equals("틀기") || script == null) return "인트로 x 틀기 [짧게] | 끝 | 상태";
        if (game.state != Game.State.LOBBY) return "[이야기] 대기실에서만 틀어 본다(판 중에는 모두의 화면이 검어진다)";
        List<Card> c = a.length > 4 && a[4].equals("짧게") ? script.brief : script.full;
        if (!playable(c)) return "[이야기] 글귀가 팩에 없다(" + warned + ")";
        begin(c, false);
        return "[이야기] " + status();
    }
}
