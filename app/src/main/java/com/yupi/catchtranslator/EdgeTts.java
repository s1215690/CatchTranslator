package com.yupi.catchtranslator;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import javax.net.ssl.SSLSocketFactory;

/**
 * Microsoft Edge 神經語音（免費、無需 API key、自然）。
 * 支援真·廣東話（zh-HK-HiuGaaiNeural 女聲／zh-HK-WanLungNeural 男聲）同普通話（zh-CN-XiaoxiaoNeural）。
 * 實現：Sec-MS-GEC token + WebSocket 手寫握手 + SSML 合成，收 MP3。
 */
public class EdgeTts {

    public static final String VOICE_HK = "zh-HK-HiuGaaiNeural";   // 女·曉佳
    public static final String VOICE_HK_M = "zh-HK-WanLungNeural"; // 男·雲龍
    public static final String VOICE_CN = "zh-CN-XiaoxiaoNeural";

    private static final String HOST = "speech.platform.bing.com";
    private static final String PATH_BASE = "/consumer/speech/synthesize/readaloud/edge/v1";
    private static final String TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String GEC_VERSION = "1-143.0.3650.75";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";

    /**
     * 合成語音到 MP3 檔案。失敗會拋 exception。
     * pitchPct 例如 "+0%" / "+6%" / "-8%"（免費 Edge 接口唔支援 express-as 語氣風格，用音調微調代替）。
     */
    public static void synthesize(String text, String voice, String ratePct, String pitchPct, File out) throws Exception {
        String secGec = secMsGec();
        String connId = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String muid = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String path = PATH_BASE + "?TrustedClientToken=" + TRUSTED_TOKEN
                + "&Sec-MS-GEC=" + secGec
                + "&Sec-MS-GEC-Version=" + GEC_VERSION
                + "&ConnectionId=" + connId;

        try (Socket sock = SSLSocketFactory.getDefault().createSocket()) {
            sock.connect(new InetSocketAddress(HOST, 443), 10000);
            sock.setSoTimeout(25000);
            OutputStream os = sock.getOutputStream();
            DataInputStream dis = new DataInputStream(sock.getInputStream());

            // ---- WebSocket 握手 ----
            byte[] keyBytes = new byte[16];
            new SecureRandom().nextBytes(keyBytes);
            String wsKey = Base64.getEncoder().encodeToString(keyBytes);
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + HOST + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "User-Agent: " + UA + "\r\n"
                    + "Accept-Encoding: gzip, deflate, br, zstd\r\n"
                    + "Accept-Language: en-US,en;q=0.9\r\n"
                    + "Pragma: no-cache\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n"
                    + "Cookie: muid=" + muid + ";\r\n"
                    + "\r\n";
            os.write(req.getBytes(StandardCharsets.US_ASCII));
            os.flush();

            String statusLine = readLine(dis);
            String headerLine;
            while ((headerLine = readLine(dis)) != null && !headerLine.isEmpty()) { /* skip headers */ }
            if (statusLine == null || !statusLine.contains(" 101 ")) {
                throw new Exception("WebSocket 握手失敗: " + statusLine);
            }

            // ---- 合成設定 + SSML（每個 message 都要有 HTTP-like headers）----
            java.text.SimpleDateFormat tsFmt = new java.text.SimpleDateFormat(
                    "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
                    java.util.Locale.US);
            tsFmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
            String timestamp = tsFmt.format(new java.util.Date());
            String configMsg = "X-Timestamp:" + timestamp + "\r\n"
                    + "Content-Type:application/json; charset=utf-8\r\n"
                    + "Path:speech.config\r\n\r\n"
                    + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                    + "\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"},"
                    + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";
            sendFrame(os, 1, configMsg.getBytes(StandardCharsets.UTF_8));
            String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                    + "<voice name='" + voice + "'><prosody pitch='" + normPitch(pitchPct) + "' rate='"
                    + normRate(ratePct) + "' volume='+0%'>"
                    + escapeXml(text) + "</prosody></voice></speak>";
            String ssmlMsg = "X-RequestId:" + connId + "\r\n"
                    + "Content-Type:application/ssml+xml\r\n"
                    + "X-Timestamp:" + timestamp + "Z\r\n" // Microsoft Edge bug：要加 Z
                    + "Path:ssml\r\n\r\n"
                    + ssml;
            sendFrame(os, 1, ssmlMsg.getBytes(StandardCharsets.UTF_8));

            // ---- 收音訊 ----
            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            boolean gotAudio = false;
            while (true) {
                Frame f = readFrame(dis);
                if (f == null) break;
                if (f.opcode == 8) break; // close
                if (f.opcode == 9) {      // ping → pong
                    sendFrame(os, 10, f.payload);
                    continue;
                }
                if (f.opcode == 2) {      // binary：頭2 bytes=header長度，header 內含 Path:audio + 音訊
                    byte[] p = f.payload;
                    if (p.length >= 2) {
                        int headerLen = ((p[0] & 0xFF) << 8) | (p[1] & 0xFF);
                        if (headerLen >= 0 && 2 + headerLen <= p.length) {
                            String head = new String(p, 2, headerLen, StandardCharsets.US_ASCII);
                            if (head.contains("Path:audio")) {
                                int dataStart = 2 + headerLen;
                                if (dataStart < p.length) {
                                    audio.write(p, dataStart, p.length - dataStart);
                                    gotAudio = true;
                                }
                            }
                        }
                    }
                } else if (f.opcode == 1) {
                    String s = new String(f.payload, StandardCharsets.UTF_8);
                    if (s.contains("turn.end")) break;
                }
            }
            if (!gotAudio || audio.size() == 0) throw new Exception("冇收到音訊");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                audio.writeTo(fos);
            }
        }
    }

    // ---------- WebSocket frame ----------

    private static class Frame {
        final int opcode;
        final byte[] payload;
        Frame(int opcode, byte[] payload) { this.opcode = opcode; this.payload = payload; }
    }

    private static void sendFrame(OutputStream os, int opcode, byte[] payload) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(0x80 | opcode); // FIN + opcode
        int len = payload.length;
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        if (len < 126) {
            buf.write(0x80 | len);
        } else if (len < 65536) {
            buf.write(0x80 | 126);
            buf.write((len >> 8) & 0xFF);
            buf.write(len & 0xFF);
        } else {
            buf.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) buf.write((int) ((long) len >> (8 * i)) & 0xFF);
        }
        buf.write(mask);
        for (int i = 0; i < len; i++) buf.write(payload[i] ^ mask[i & 3]);
        os.write(buf.toByteArray());
        os.flush();
    }

    private static Frame readFrame(DataInputStream dis) throws Exception {
        int b0 = dis.read();
        if (b0 < 0) return null;
        int b1 = dis.read();
        if (b1 < 0) return null;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) len = dis.readUnsignedShort();
        else if (len == 127) len = dis.readLong();
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            dis.readFully(mask);
        }
        byte[] payload = new byte[(int) len];
        dis.readFully(payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        }
        return new Frame(opcode, payload);
    }

    // ---------- 工具 ----------

    /**
     * 新版 Sec-MS-GEC（2024 年底起）：Windows file time（1601 epoch、100ns 刻度、round 到 5 分鐘）
     * + TrustedClientToken 一齊 SHA-256，輸出大寫 hex。唔係舊版 base64 格式。
     */
    private static String secMsGec() throws Exception {
        final long WIN_EPOCH = 11644473600L;
        long ticks = System.currentTimeMillis() / 1000 + WIN_EPOCH;
        ticks -= ticks % 300;                       // round down to 5 min
        long ticks100ns = ticks * 10_000_000L;      // 100-nanosecond intervals
        String strToHash = ticks100ns + TRUSTED_TOKEN;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return toHex(md.digest(strToHash.getBytes(StandardCharsets.US_ASCII))).toUpperCase();
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String readLine(DataInputStream dis) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c = -1;
        while ((c = dis.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (b.size() == 0 && c == -1) return null;
        return new String(b.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** 音調百分比正規化，例如 "+6%"→"+6%"、"0"→"+0%"、"-8%"→"-8%"。 */
    private static String normPitch(String pct) {
        String v = (pct == null || pct.trim().isEmpty()) ? "+0" : pct.trim();
        if (!v.endsWith("%")) v = v + "%";
        if (!v.startsWith("+") && !v.startsWith("-")) v = "+" + v;
        return v;
    }

    /** 語速百分比正規化做 SSML prosody rate 格式，例如 "0"→"+0%"、"-10"→"-10%"、"20"→"+20%"。 */
    private static String normRate(String pct) {
        String v = (pct == null || pct.trim().isEmpty()) ? "+0" : pct.trim();
        if (!v.startsWith("+") && !v.startsWith("-")) v = "+" + v;
        return v + "%";
    }
}
