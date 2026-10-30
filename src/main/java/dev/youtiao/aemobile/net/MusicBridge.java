package dev.youtiao.aemobile.net;

import com.fasterxml.jackson.databind.JsonNode;
import dev.youtiao.aemobile.AEMobile;
import dev.youtiao.aemobile.Config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App 音乐请求 → 代理上的 AEMobileMusicBridge。
 *
 * <p>AllMusic 的 BC 版命令只存在于代理进程，后端的 Forge 只能通过这个 localhost 小桥转发。
 * 桥接插件用捕获型 CommandSender 执行 {@code /music ...}，把返回文本私有地回给 App。
 */
public final class MusicBridge {

    public static final class Result {
        public final boolean ok;
        public final String error;
        public final boolean online;
        public final List<String> lines;

        Result(boolean ok, String error, boolean online, List<String> lines) {
            this.ok = ok;
            this.error = error;
            this.online = online;
            this.lines = lines;
        }
    }

    private MusicBridge() {
    }

    public static Result request(String player, String cmd, List<String> args) {
        if (!Config.musicEnabled) {
            return new Result(false, "MUSIC_DISABLED", false, new ArrayList<String>());
        }
        if (player == null || player.isEmpty()) {
            return new Result(false, "MUSIC_NO_PLAYER", false, new ArrayList<String>());
        }

        Map<String, Object> req = new LinkedHashMap<String, Object>();
        req.put("secret", Config.musicSecret);
        req.put("player", player);
        req.put("cmd", cmd);
        req.put("args", args == null ? new ArrayList<String>() : args);

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(Config.musicHost, Config.musicPort), 2000);
            socket.setSoTimeout(8000);
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            out.println(Json.write(req));
            out.flush();

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) return new Result(false, "MUSIC_NO_RESPONSE", false, new ArrayList<String>());

            JsonNode node = Json.read(line);
            if (node == null) return new Result(false, "MUSIC_BAD_RESPONSE", false, new ArrayList<String>());

            boolean ok = node.path("ok").asBoolean(false);
            String error = node.path("error").isNull() ? null : node.path("error").asText(null);
            boolean online = node.path("online").asBoolean(false);
            List<String> lines = new ArrayList<String>();
            JsonNode arr = node.get("lines");
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    if (n != null && !n.isNull()) lines.add(n.asText(""));
                }
            }
            // AllMusic 的“没有播放的玩家”是服务器没有音乐会话（不是命令错），给 App 一个稳定 code
            if (error == null) {
                for (String l : lines) {
                    if (l != null && l.contains("没有播放的玩家")) {
                        ok = false;
                        error = "NO_MUSIC_SESSION";
                        break;
                    }
                }
            }
            return new Result(ok, error, online, lines);
        } catch (Throwable t) {
            AEMobile.debug("[AEMobile] music bridge 失败：" + t);
            return new Result(false, "MUSIC_UNAVAILABLE", false, new ArrayList<String>());
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }
}
