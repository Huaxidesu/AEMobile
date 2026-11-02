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
 * 通用代理命令桥客户端：App/后端 mod → 代理上的 AEMobileBridge → 代理命令。
 *
 * <p>AllMusic 的 BC 版命令只存在于代理进程，后端的 Forge 只能通过这个 localhost 小桥转发。
 * 桥接插件用捕获型 CommandSender 执行命令，把返回文本私有地回给 App。
 *
 * <p>目前音乐点歌用它执行 {@code music ...}；以后别的代理功能可以复用 {@link #exec}，
 * 只要在桥接插件的 {@code allow-commands} 里加上命令根。
 */
public final class ProxyBridge {

    public static final class Result {
        public final boolean ok;
        public final boolean found;
        public final String error;
        public final boolean online;
        public final List<String> lines;

        Result(boolean ok, boolean found, String error, boolean online, List<String> lines) {
            this.ok = ok;
            this.found = found;
            this.error = error;
            this.online = online;
            this.lines = lines;
        }
    }

    /**
     * 把子服 ServerUtilities 的 TAB 页眉页脚上报给代理，代理按 {@code server} 缓存后下发。
     *
     * <p>这样 TAB 文案的唯一来源是服务端的 {@code serverutilities.cfg}，代理插件里不用再抄一份；
     * 调用方应当放到后台线程（本方法会同步等代理回一行 JSON）。
     *
     * @param server BC 里的子服名；留空即可，代理会用 {@code port} 自动反查，查不到才落到通配
     * @param port 本服 {@code server.properties} 的 server-port，给代理反查子服名用；0 表示不提供
     * @param header 为 null 表示不动这一侧
     */
    public static boolean pushTabSource(String server, int port, String header, String footer) {
        if (!Config.bridgeEnabled) return false;
        if (header == null && footer == null) return false;

        Map<String, Object> req = new LinkedHashMap<String, Object>();
        req.put("op", "tab_source");
        req.put("secret", Config.bridgeSecret);
        req.put("server", server == null ? "" : server);
        if (port > 0) req.put("port", port);
        if (header != null) req.put("header", header);
        if (footer != null) req.put("footer", footer);

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(Config.bridgeHost, Config.bridgePort), 2000);
            socket.setSoTimeout(4000);
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            out.println(Json.write(req));
            out.flush();

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) return false;
            JsonNode node = Json.read(line);
            return node != null && node.path("ok").asBoolean(false);
        } catch (Throwable t) {
            AEMobile.debug("[AEMobile] tab_source 上报失败：" + t);
            return false;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private ProxyBridge() {
    }

    /**
     * @param player    以谁的身份执行（在线用真实玩家，离线用最后一次登录名）
     * @param command   不带前导 / 的命令，例如 {@code "music search 晴天"}
     * @param captureMs 命令返回后继续收集聊天的时间；异步命令（搜歌）传 3500
     */
    public static Result exec(String player, String command, int captureMs) {
        if (!Config.bridgeEnabled) {
            return new Result(false, false, "BRIDGE_DISABLED", false, new ArrayList<String>());
        }
        if (player == null || player.isEmpty()) {
            return new Result(false, false, "BRIDGE_NO_PLAYER", false, new ArrayList<String>());
        }

        Map<String, Object> req = new LinkedHashMap<String, Object>();
        req.put("op", "exec");
        req.put("secret", Config.bridgeSecret);
        req.put("player", player);
        req.put("command", command);
        req.put("captureMs", captureMs);

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(Config.bridgeHost, Config.bridgePort), 2000);
            socket.setSoTimeout(12000);
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            out.println(Json.write(req));
            out.flush();

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) return new Result(false, false, "BRIDGE_NO_RESPONSE", false, new ArrayList<String>());

            JsonNode node = Json.read(line);
            if (node == null) return new Result(false, false, "BRIDGE_BAD_RESPONSE", false, new ArrayList<String>());

            boolean ok = node.path("ok").asBoolean(false);
            boolean found = node.path("found").asBoolean(ok);
            String error = node.path("error").isNull() ? null : node.path("error").asText(null);
            boolean online = node.path("online").asBoolean(false);
            List<String> lines = new ArrayList<String>();
            JsonNode arr = node.get("lines");
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    if (n != null && !n.isNull()) lines.add(n.asText(""));
                }
            }
            return new Result(ok, found, error, online, lines);
        } catch (Throwable t) {
            AEMobile.debug("[AEMobile] bridge 失败：" + t);
            return new Result(false, false, "BRIDGE_UNAVAILABLE", false, new ArrayList<String>());
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }
}
