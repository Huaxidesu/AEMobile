package dev.youtiao.aemobile.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * TCP 帧格式（协议 3.0.0）。
 *
 * <pre>
 * +-----------+--------+---------+-------------------+
 * | len (4B)  | type   | flags   | payload (len-2 B) |
 * +-----------+--------+---------+-------------------+
 * </pre>
 *
 * <ul>
 *   <li>{@code len}：大端 int，值为 <b>type + flags + payload 的总字节数</b>（不含自身 4 字节）。</li>
 *   <li>{@code type}：见下面的 T_* 常量。</li>
 *   <li>{@code flags}：位标记，目前只有 {@link #FLAG_GZIP}（预留，暂未使用）。</li>
 *   <li>{@code payload}：UTF-8 的 JSON 对象（心跳帧为空）。</li>
 * </ul>
 *
 * <p>契约见 doc/传输协议与安全.md §2。
 */
public final class Protocol {

    /** 协议版本，握手时比对 */
    public static final int VERSION = 3;

    // ---- 握手 ----
    /** C→S 客户端打招呼：{"v":3,"app":"AEMobileApp/3.0.0"} */
    public static final int T_HELLO = 0x01;
    /** S→C 服务器回应：{"v":3,"name":"AEMobile","ver":"3.0.0","time":1698345600,"nonce":"..."} */
    public static final int T_HELLO_ACK = 0x02;
    /** C→S 鉴权：{"kid":"ABCD1234","ts":1698345600,"sig":"..."} */
    public static final int T_AUTH = 0x03;
    /** S→C 鉴权成功：{"ok":true} */
    public static final int T_AUTH_OK = 0x04;
    /** S→C 鉴权失败：{"code":"CLOCK","time":...} */
    public static final int T_AUTH_FAIL = 0x05;

    // ---- 请求 / 响应 ----
    /** C→S 一次性请求：{"id":1,"op":"networks","p":{...}} */
    public static final int T_REQ = 0x10;
    /** S→C 响应：{"id":1,"ok":true,"b":...,"m":null,"more":false} */
    public static final int T_RES = 0x11;

    // ---- 订阅 ----
    /** C→S 订阅：{"id":2,"op":"items","p":{...},"sid":"..."} */
    public static final int T_SUB = 0x20;
    /** S→C 订阅成功：{"id":2,"s":1,"sid":"a3f9c1d2e4b5"} */
    public static final int T_SUB_OK = 0x21;
    /** S→C 推送：{"s":1,"f":{"t":"f",...}} */
    public static final int T_PUSH = 0x22;
    /** C→S 退订：{"s":1} */
    public static final int T_UNSUB = 0x23;

    // ---- 保活 ----
    public static final int T_PING = 0x30;
    public static final int T_PONG = 0x31;

    /** S→C 致命错误，发完即断：{"m":"..."} */
    public static final int T_ERR = 0x40;

    /** 预留：payload 是 gzip 流。v3 未启用（客户端没有 zlib，全量帧 45KB 一次性可接受） */
    public static final int FLAG_GZIP = 0x01;

    /**
     * 单帧上限，防止恶意/错乱数据把堆撑爆。
     * 2026-10-28 实测：真实基地的 items 全量帧有 13,316 条 / 2.97MB（craftableOnly=false），
     * 原来的 4MiB 太贴边，调到 16MiB。
     */
    public static final int MAX_FRAME = 16 * 1024 * 1024;

    private Protocol() {
    }

    /** 一帧 */
    public static final class Frame {
        public final int type;
        public final int flags;
        public final byte[] payload;

        Frame(int type, int flags, byte[] payload) {
            this.type = type;
            this.flags = flags;
            this.payload = payload;
        }

        public String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    /** 读一帧；流结束返回 null，格式错误抛 IOException（调用方直接断开） */
    public static Frame read(DataInputStream in) throws IOException {
        int len;
        try {
            len = in.readInt();
        } catch (java.io.EOFException e) {
            return null;
        }
        if (len < 2 || len > MAX_FRAME) {
            throw new IOException("bad frame length: " + len);
        }
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new Frame(buf[0] & 0xFF, buf[1] & 0xFF, java.util.Arrays.copyOfRange(buf, 2, len));
    }

    /** 写一帧（调用方负责串行化） */
    public static void write(DataOutputStream out, int type, byte[] payload) throws IOException {
        if (payload == null) payload = new byte[0];
        out.writeInt(payload.length + 2);
        out.writeByte(type);
        out.writeByte(0);
        out.write(payload);
        out.flush();
    }

    public static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }
}
