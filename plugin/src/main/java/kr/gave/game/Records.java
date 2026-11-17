package kr.gave.game;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;

/**
 * 판 기록: plugins/Gave/records.jsonl 한 줄 = {at, players, bracket, wave, seconds, bundle, mutations, people:[{name, kills, dealt, downs,
 * revives, earned, shots}]}. 접속자 수 구간(1~5, 6~12, 13~20, 21~30, 31~)마다 최고 도달 웨이브를 켤 때 읽어 두고 판마다 고친다.
 * 판 끝에 채팅 한 줄과 [판] 로그(판뒤분석이 읽음).
 */
public final class Records {

    /** 접속자 수 구간(아래 끝 포함). */
    public static final int[][] BRACKETS = {{1, 5}, {6, 12}, {13, 20}, {21, 30}, {31, 999}};

    public final Game game;
    private final Path file;
    private final Map<UUID, Integer> shots = new HashMap<>();
    private final Map<String, Integer> best = new LinkedHashMap<>();
    private long startedAt;
    private String startedIso = "";
    private int peak;
    /** 이 판 보스 기록(쓰러뜨린 차례: b 보스, w 웨이브, c 회차, s 싸운 초, n 사람, d 쓰러짐)과 엔딩(wave, seconds, players, downs). 2026-11-12 boss_infra. */
    private final List<JsonObject> bosses = new ArrayList<>();
    private JsonObject ending;
    /** 원정 발사 결과(탄 사람, 남은 사람). 2026-11-12 hooks3. */
    private JsonObject escape;
    /** 구간마다 엔딩까지 가장 짧은 초(건너뛴 판 빼고). */
    private final Map<String, Long> endingBest = new LinkedHashMap<>();
    /** 마지막 판 결과(HUD 판 끝 표시용). */
    private int lastWave = -1, lastBest, lastPeople;
    private boolean lastNew;
    private String lastBracket = "";

    public Records(Game game) {
        this.game = game;
        this.file = game.plugin.getDataFolder().toPath().resolve("records.jsonl");
        load();
    }

    /** 접속자 수의 구간 이름("1~5"). */
    public static String bracket(int players) {
        for (int[] b : BRACKETS) if (players >= b[0] && players <= b[1]) return b[1] >= 999 ? b[0] + "~" : b[0] + "~" + b[1];
        return "1~5";
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        int broken = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    String b = o.has("bracket") ? o.get("bracket").getAsString() : bracket(o.get("players").getAsInt());
                    best.merge(b, o.get("wave").getAsInt(), Math::max);
                    if (o.has("ending") && o.get("ending").isJsonObject()) {
                        JsonObject e = o.getAsJsonObject("ending");
                        if (e.has("seconds") && !(e.has("skipped") && e.get("skipped").getAsBoolean())) endingBest.merge(b, e.get("seconds").getAsLong(), Math::min);
                    }
                } catch (RuntimeException ignored) {
                    // 깨진 줄은 건너뜀(몇 줄인지는 아래에서 알림)
                    broken++;
                }
            }
            if (broken > 0) game.plugin.getLogger().warning("[기록] " + file.getFileName() + " 에서 못 읽은 줄 " + broken + "개를 건너뜀(그 판들은 최고 기록 셈에서 빠짐)");
        } catch (IOException e) {
            game.plugin.getLogger().warning("[기록] 읽기 실패: " + e);
        }
    }

    /** 판 시작(Game.start, 감독 묶음이 정해진 뒤). */
    public void reset() {
        skipped = false;
        shots.clear();
        bosses.clear();
        ending = null;
        escape = null;
        startedAt = System.currentTimeMillis();
        startedIso = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).substring(0, 19);
        peak = game.players.inPlay().size();
    }

    /** 사람이 쏜 한 발(GunHost.onShot). */
    public void shot(Player p) {
        shots.merge(p.getUniqueId(), 1, Integer::sum);
    }

    /** 웨이브 시작(감독이 부름): 최대 접속자 수. */
    public void noteWave(int n) {
        peak = Math.max(peak, game.players.inPlay().size());
    }

    public int shots(UUID id) {
        return shots.getOrDefault(id, 0);
    }

    /** 보스를 쓰러뜨림(보스 감독이 부름): 보스 이름(B1), 웨이브, 회차, 싸운 초, 사람 수, 그 싸움의 쓰러짐. 판 끝 기록 줄 bosses 에 들어간다. */
    public void noteBoss(String boss, int wave, int cycle, long seconds, int people, int downs) {
        JsonObject b = new JsonObject();
        b.addProperty("b", boss);
        b.addProperty("w", wave);
        b.addProperty("c", cycle);
        b.addProperty("s", seconds);
        b.addProperty("n", people);
        b.addProperty("d", downs);
        bosses.add(b);
        game.plugin.getLogger().info("[기록] 보스 " + boss + " w=" + wave + " c=" + cycle + " " + seconds + "초 " + people + "명 쓰러짐 " + downs);
    }

    /** 엔딩(그 판 첫 B5): 웨이브, 판 시작부터 초, 사람 수, 판 쓰러짐. 판 끝 기록 줄 ending 에 들어가고 구간 엔딩 최단 시간을 고친다. */
    public void noteEnding(int wave, long seconds, int people, int downs) {
        if (ending != null) return;
        ending = new JsonObject();
        ending.addProperty("wave", wave);
        ending.addProperty("seconds", seconds);
        ending.addProperty("players", people);
        ending.addProperty("downs", downs);
        if (skipped) ending.addProperty("skipped", true);
        String br = bracket(Math.max(1, Math.max(peak, people)));
        if (!skipped) endingBest.merge(br, seconds, Math::min);
        game.plugin.getLogger().info("[기록] 엔딩 w=" + wave + " " + seconds + "초 " + people + "명 쓰러짐 " + downs + (skipped ? " skipped" : "") + " 구간 " + br
                + " 최단 " + endingBest.getOrDefault(br, -1L));
    }

    /** 원정 발사(Launch 의 AFTER 차례): 우주선에 탄 사람, 남은 사람. 판 끝 기록 줄 escape 에 들어간다. */
    public void noteEscape(int boarded, int left) {
        escape = new JsonObject();
        escape.addProperty("boarded", boarded);
        escape.addProperty("left", left);
        game.plugin.getLogger().info("[기록] 탈출 탄 사람 " + boarded + " 남은 사람 " + left);
    }

    /** 구간 엔딩 최단 초(없으면 -1). */
    public long endingBest(String bracket) {
        return endingBest.getOrDefault(bracket, -1L);
    }

    /** 이 구간 최고 도달 웨이브(없으면 0). */
    public int best(String bracket) {
        return best.getOrDefault(bracket, 0);
    }

    /** 구간 → 최고 웨이브(읽기 전용 사본). */
    public Map<String, Integer> board() {
        return Map.copyOf(best);
    }

    /** 마지막 판 도달 웨이브(-1 아직 없음), 그 구간 최고, 새 기록인가, 구간. */
    public int lastWave() {
        return lastWave;
    }

    public int lastBest() {
        return lastBest;
    }

    public boolean lastNew() {
        return lastNew;
    }

    public String lastBracket() {
        return lastBracket;
    }

    /** 판 끝(Game.gameOver): 기록 한 줄, 구간 최고 갱신, 채팅 한 줄, [판] 로그. */
    /** 이 판에 운영자가 /gave 웨이브 로 건너뛰었거나 진 뒤 다시 했나(Game.retry: 처음 시도는 기록하고 그 뒤는 최고 기록에 넣지 않음, 기록 줄에는 skipped 로 남김). */
    public boolean skipped;

    public void finish(int wave) {
        if (skipped) {
            lastWave = wave;
            game.plugin.getLogger().info("[판] 끝 웨이브 " + wave + " (웨이브 건너뛰거나 다시 한 판: 기록 안 함)");
            return;
        }
        int people = Math.max(1, Math.max(peak, game.players.inPlay().size()));
        String br = bracket(people);
        int before = best(br);
        boolean fresh = wave > before;
        if (fresh) best.put(br, wave);
        lastWave = wave;
        lastBest = Math.max(before, wave);
        lastNew = fresh;
        lastPeople = people;
        lastBracket = br;
        JsonObject o = new JsonObject();
        o.addProperty("at", startedIso.isEmpty() ? LocalDateTime.now().toString() : startedIso);
        o.addProperty("players", people);
        o.addProperty("bracket", br);
        o.addProperty("wave", wave);
        o.addProperty("seconds", startedAt == 0 ? 0 : (System.currentTimeMillis() - startedAt) / 1000);
        JsonArray bundle = new JsonArray();
        for (Species sp : game.director.bundle) bundle.add(sp.name());
        o.add("bundle", bundle);
        JsonArray muts = new JsonArray();
        for (Map.Entry<String, Integer> e : game.director.mutations().entrySet()) muts.add(e.getValue() > 1 ? e.getKey() + "x" + e.getValue() : e.getKey());
        o.add("mutations", muts);
        if (!bosses.isEmpty()) {
            JsonArray bs = new JsonArray();
            for (JsonObject b : bosses) bs.add(b);
            o.add("bosses", bs);
        }
        if (ending != null) o.add("ending", ending);
        if (escape != null) o.add("escape", escape);
        JsonArray ppl = new JsonArray();
        List<String> names = new ArrayList<>();
        for (PState s : game.players.all()) {
            if (s.life == Life.LOBBY || Bukkit.getOfflinePlayer(s.id).getName() == null && Bukkit.getPlayer(s.id) == null) continue;
            JsonObject q = new JsonObject();
            q.addProperty("name", s.name);
            q.addProperty("kills", s.kills);
            q.addProperty("dealt", Math.round(s.dealt));
            q.addProperty("downs", s.downs);
            q.addProperty("revives", s.revives);
            q.addProperty("earned", s.earned);
            q.addProperty("shots", shots(s.id));
            ppl.add(q);
            names.add(s.name + " " + s.kills + "처치");
        }
        o.add("people", ppl);
        String line = new Gson().toJson(o);
        try {
            Files.createDirectories(file.getParent());
            // 쓰는 중에 서버가 죽어 마지막 줄이 줄바꿈 없이 끊겼으면 그 뒤에 붙지 않게 줄바꿈부터(붙으면 두 판이 모두 깨진 줄이 된다)
            String lead = "";
            if (Files.isRegularFile(file) && Files.size(file) > 0) {
                try (java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(file)) {
                    java.nio.ByteBuffer last = java.nio.ByteBuffer.allocate(1);
                    ch.position(ch.size() - 1);
                    if (ch.read(last) == 1 && last.get(0) != '\n') lead = "\n";
                }
            }
            Files.writeString(file, lead + line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            game.plugin.getLogger().warning("[기록] 쓰기 실패: " + e);
        }
        game.plugin.getLogger().info("[판] 끝 웨이브 " + wave + " 접속 " + people + " 구간 " + br + " 최고 " + lastBest + (fresh ? " 새기록" : "") + " 묶음 "
                + bundle.size() + "종 변이 " + muts.size() + " 사람 " + String.join(", ", names));
        Component msg = Component.text("웨이브 " + wave, NamedTextColor.GOLD)
                .append(Component.text(fresh ? "  새 기록 " + br + "명" : "  " + br + "명 최고 " + lastBest, fresh ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(msg);
    }

    public String test(Player p, String[] a) {
        if (a.length < 2 || !a[1].equals("기록")) return null;
        return "[기록] " + file + " best=" + best + " last=" + lastWave + "/" + lastBest + (lastNew ? " 새기록" : "") + " people=" + lastPeople + " shots=" + shots.size()
                + " bosses=" + bosses + " ending=" + ending + " endingBest=" + endingBest;
    }
}
