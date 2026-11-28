package com.example.carwithin;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;

/**
 * Generic EasyConn / PXC reverse TCP session (ported from CarWithin iOS PxcConnection.swift
 * and adapted for QJMotor Sai600 HYTY75-1025 & compatible Carbit/EasyConn motorcycle dashboards).
 *
 * Topology:
 * 1. Phone listens on TCP 10922 (control), 10921 (media config), 10920 (H.264 pull).
 * 2. Phone optionally resolves mDNS _EasyConn._tcp., then probes dash:10930 (or 10915..10935)
 *    with 0x70000010 {"phoneType":"Android","packageName":...}.
 * 3. Dash connects back to phone 10922/10921/10920, negotiates identity (RSA HUID + SN check),
 *    reports runtime capture area (e.g. 800x362), sends start (112), and pulls H.264 via 114.
 */
final class EasyConnPxcConnection implements AutoCloseable {
    record CaptureConfig(int width, int height, int fps, int encoder, byte extFlag) {}
    private record NsdEndpoint(Inet4Address address, int port, String packageName) {}

    private static final int PORT_VIDEO = 10920;
    private static final int PORT_MEDIA = 10921;
    private static final int PORT_CONTROL = 10922;
    private static final int PORT_PROBE_DEFAULT = 10930;

    // Observed on QJMotor Sai600 (HYTY75-1025) mDNS and standard CFMOTO/EasyConn dashboards.
    private static final String PKG_EASYRIDE = "net.easyconn.carman.easyride";
    private static final String PKG_QJ_OBSERVED = "com.cfmoto.cfmotoint0rnstionsl";
    private static final String PKG_CFMOTO_STD = "com.cfmoto.cfmotointernational";

    private final Context context;
    private final P2pController.Route route;
    private final MirrorSession.Listener listener;
    private final List<Closeable> resources = new CopyOnWriteArrayList<>();
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final CountDownLatch identified = new CountDownLatch(1);
    private final CountDownLatch configured = new CountDownLatch(1);
    private final CountDownLatch mediaStarted = new CountDownLatch(1);
    private final CountDownLatch videoArrived = new CountDownLatch(1);
    private final Object frameLock = new Object();

    private KeyPair keys;
    private String phoneId;
    private volatile String acceptedPackage = PKG_EASYRIDE;
    private volatile String instrumentIdentity;
    private volatile Socket video;
    private volatile Socket reverseControl;
    private volatile boolean frameRequested;
    private volatile boolean closed;
    private volatile IOException failure;

    private volatile int negotiatedWidth = 800;
    private volatile int negotiatedHeight = 362;
    private volatile int negotiatedFps = 25;
    private volatile long framesSent;

    EasyConnPxcConnection(Context context, P2pController.Route route, MirrorSession.Listener listener) {
        this.context = context.getApplicationContext();
        this.route = route;
        this.listener = listener;
    }

    int getNegotiatedWidth() { return negotiatedWidth; }
    int getNegotiatedHeight() { return negotiatedHeight; }
    int getNegotiatedFps() { return negotiatedFps; }

    /** Pure parser shared with unit tests; aligns dimensions to even numbers. */
    static CaptureConfig parseCaptureConfig(byte[] body) throws IOException {
        if (body == null || body.length < 4) {
            throw new IOException("亿连媒体配置长度不足");
        }
        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        int rawW = buf.getShort(0) & 0xFFFF;
        int rawH = buf.getShort(2) & 0xFFFF;
        if (rawW < 240 || rawW > 1920 || rawH < 200 || rawH > 1080) {
            throw new IOException("仪表上报尺寸超出范围：" + rawW + "×" + rawH);
        }
        int alignedW = rawW & ~1;
        int alignedH = (rawH % 2 == 0) ? rawH : (rawH + 1);
        int reportedFps = body.length >= 8 ? buf.getInt(4) : 0;
        int fps = (reportedFps >= 1 && reportedFps <= 60) ? reportedFps : ((alignedW == 800 && alignedH <= 386) ? 25 : 30);
        int rawEncoder = body.length >= 12 ? buf.getInt(8) : 2;
        int encoder = rawEncoder == 0 ? 2 : rawEncoder;
        byte ext = body.length >= 30 ? body[29] : 0;
        return new CaptureConfig(alignedW, alignedH, fps, encoder, ext);
    }

    static byte[] buildCaptureReply(CaptureConfig cfg) {
        return ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(cfg.encoder())
                .putShort((short) cfg.width())
                .putShort((short) cfg.height())
                .put(cfg.extFlag())
                .array();
    }

    void start() throws Exception {
        loadIdentity();
        for (int port : new int[]{PORT_CONTROL, PORT_MEDIA, PORT_VIDEO}) {
            ServerSocket server = track(new ServerSocket());
            server.setReuseAddress(true);
            try {
                server.bind(new InetSocketAddress(route.local(), port));
            } catch (BindException e) {
                throw new IOException("亿连 TCP " + port + " 被占用，请退出官方亿连/MOTO-HUB 后重试", e);
            }
            threads.execute(() -> accept(server, port));
        }
        listener.log("亿连 PXC 已监听 " + route.local().getHostAddress() + ":10920/10921/10922");
        listener.stats("赛600 Wi-Fi 已连接 · 正在探测 EasyConn");

        probe();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!closed && failure == null &&
                (identified.getCount() != 0 || configured.getCount() != 0 ||
                 mediaStarted.getCount() != 0 || videoArrived.getCount() != 0)) {
            if (System.nanoTime() >= deadline) {
                throw new SocketTimeoutException(
                        "亿连协商超时（身份=" + (identified.getCount() == 0) +
                        " 尺寸=" + (configured.getCount() == 0) +
                        " 开流=" + (mediaStarted.getCount() == 0) +
                        " 视频通道=" + (videoArrived.getCount() == 0) + "），请保持仪表停在投屏界面");
            }
            Thread.sleep(50);
        }
        check();
        listener.log("亿连 PXC 协商完成：" + negotiatedWidth + "×" + negotiatedHeight + "@" + negotiatedFps + "fps，等待逐帧拉取");
    }

    private void probe() throws Exception {
        NsdEndpoint nsd = discoverNsd(2200);
        Inet4Address targetIp = route.remote();
        List<Integer> ports = new ArrayList<>();
        List<String> packages = new ArrayList<>();

        if (nsd != null) {
            if (nsd.address() != null) targetIp = nsd.address();
            if (nsd.port() > 0 && nsd.port() <= 65535) ports.add(nsd.port());
            if (nsd.packageName() != null && !nsd.packageName().isBlank()) packages.add(nsd.packageName());
            listener.log("mDNS 发现 _EasyConn._tcp. → " + targetIp.getHostAddress() + ":" + nsd.port() + " (" + nsd.packageName() + ")");
        }
        if (!ports.contains(PORT_PROBE_DEFAULT)) ports.add(PORT_PROBE_DEFAULT);
        for (String fallbackPkg : new String[]{PKG_EASYRIDE, PKG_QJ_OBSERVED, PKG_CFMOTO_STD, context.getPackageName()}) {
            if (!packages.contains(fallbackPkg)) packages.add(fallbackPkg);
        }

        IOException lastError = null;
        for (int attempt = 0; attempt < 2 && !closed; attempt++) {
            for (int port : ports) {
                for (String pkg : packages) {
                    if (closed) throw new IOException("会话已停止");
                    try {
                        if (sendProbeOnce(targetIp, port, pkg)) {
                            acceptedPackage = pkg;
                            listener.log("仪表接受投屏探测：" + targetIp.getHostAddress() + ":" + port + " (" + pkg + ")");
                            return;
                        }
                    } catch (IOException e) {
                        lastError = e;
                    }
                }
            }
            if (attempt == 0) {
                // Fallback sweep across EasyConn range 10915..10935 if 10930 failed
                for (int p = 10915; p <= 10935 && !closed; p++) {
                    if (p == PORT_PROBE_DEFAULT || p == PORT_VIDEO || p == PORT_MEDIA || p == PORT_CONTROL) continue;
                    try {
                        if (sendProbeOnce(targetIp, p, packages.get(0))) {
                            acceptedPackage = packages.get(0);
                            listener.log("端口扫描命中 EasyConn：" + targetIp.getHostAddress() + ":" + p);
                            return;
                        }
                    } catch (IOException e) {
                        lastError = e;
                    }
                }
                Thread.sleep(600);
            }
        }
        throw lastError != null ? lastError : new IOException("未收到仪表 EasyConn 探测应答（" + targetIp.getHostAddress() + ":10930）");
    }

    private boolean sendProbeOnce(Inet4Address targetIp, int port, String pkg) throws IOException {
        try (Socket s = outbound(targetIp, port)) {
            String payload = new JSONObject()
                    .put("phoneType", "Android")
                    .put("packageName", pkg)
                    .toString();
            s.getOutputStream().write(TailingProtocol.message(0x70000010, payload));
            s.getOutputStream().flush();
            TailingProtocol.Message reply = TailingProtocol.read(s.getInputStream(), true);
            String text = new String(reply.payload(), StandardCharsets.UTF_8);
            if (reply.command() == 0x70000011 && new JSONObject(text).optBoolean("status")) {
                return true;
            }
            listener.log("探测 " + port + " 收到拒绝：" + text);
            return false;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private NsdEndpoint discoverNsd(long timeoutMs) {
        try {
            NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return null;
            CompletableFuture<NsdEndpoint> future = new CompletableFuture<>();
            NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String regType) {}
                @Override public void onServiceFound(NsdServiceInfo info) {
                    if (future.isDone()) return;
                    try {
                        nsd.resolveService(info, new NsdManager.ResolveListener() {
                            @Override public void onResolveFailed(NsdServiceInfo si, int code) {}
                            @Override public void onServiceResolved(NsdServiceInfo si) {
                                InetAddress host = si.getHost();
                                Inet4Address v4 = (host instanceof Inet4Address a && !a.isLoopbackAddress()) ? a : null;
                                String pkg = null;
                                Map<String, byte[]> attrs = si.getAttributes();
                                if (attrs != null) {
                                    byte[] raw = attrs.get("packagename");
                                    if (raw == null) raw = attrs.get("packageName");
                                    if (raw != null) pkg = new String(raw, StandardCharsets.UTF_8).trim();
                                }
                                if (v4 != null || (pkg != null && !pkg.isEmpty())) {
                                    future.complete(new NsdEndpoint(v4, si.getPort(), pkg));
                                }
                            }
                        });
                    } catch (Exception ignored) {}
                }
                @Override public void onServiceLost(NsdServiceInfo info) {}
                @Override public void onDiscoveryStopped(String serviceType) {}
                @Override public void onStartDiscoveryFailed(String serviceType, int code) { future.complete(null); }
                @Override public void onStopDiscoveryFailed(String serviceType, int code) {}
            };
            nsd.discoverServices("_EasyConn._tcp.", NsdManager.PROTOCOL_DNS_SD, dl);
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                return null;
            } finally {
                try { nsd.stopServiceDiscovery(dl); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private Socket outbound(Inet4Address targetIp, int port) throws IOException {
        Socket s = track(new Socket());
        if (route.network() != null) route.network().bindSocket(s);
        s.bind(new InetSocketAddress(route.local(), 0));
        s.connect(new InetSocketAddress(targetIp, port), 2500);
        s.setSoTimeout(3000);
        return s;
    }

    private void accept(ServerSocket server, int port) {
        try {
            while (!closed) {
                Socket s = track(server.accept());
                s.setTcpNoDelay(true);
                s.setSoTimeout(0);
                listener.log("仪表回连 TCP " + port + " (" + s.getInetAddress().getHostAddress() + ")");
                if (port == PORT_VIDEO) {
                    if (video != null) { s.close(); continue; }
                    video = s;
                    videoArrived.countDown();
                    threads.execute(() -> readVideoRequests(s));
                } else if (port == PORT_MEDIA) {
                    threads.execute(() -> handleMedia(s));
                } else {
                    threads.execute(() -> handleControl(s));
                }
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    private void handleControl(Socket s) {
        try {
            InputStream in = s.getInputStream();
            while (!closed) {
                TailingProtocol.Message m = TailingProtocol.read(in, true);
                int cmd = m.command();
                switch (cmd) {
                    case 0x10000:
                    case 0x10690:
                    case 0x10740:
                    case 0x10040:
                    case 0x10780:
                    case 0x70000000:
                        sendControl(s, cmd + 1, null);
                        break;
                    case 0x20000:
                        sendControl(s, cmd + 1, null);
                        reverseControl = s;
                        threads.execute(() -> keepaliveLoop(s));
                        break;
                    case 0x10010: {
                        JSONObject info = new JSONObject(new String(m.payload(), StandardCharsets.UTF_8));
                        String huName = info.optString("HUName", info.optString("huName", "EasyConn"));
                        String huid = info.optString("HUID", info.optString("huid", ""));
                        if (huid.isEmpty()) throw new IOException("仪表身份缺少 HUID");
                        instrumentIdentity = huid;
                        sendControl(s, cmd + 1, buildPhoneInfo(huid));
                        identified.countDown();
                        listener.log("仪表身份已验证：" + huName + " (HUID=" + huid.substring(0, Math.min(8, huid.length())) + "…)");
                        break;
                    }
                    case 0x103e0: {
                        String sn = "";
                        try {
                            sn = new JSONObject(new String(m.payload(), StandardCharsets.UTF_8)).optString("sn", "");
                        } catch (Exception ignored) {}
                        sendControl(s, cmd + 1, null);
                        String ok = new JSONObject()
                                .put("isOk", true)
                                .put("errCode", 0)
                                .put("errMsg", "")
                                .put("id", sn)
                                .put("client_set", "easy_conn")
                                .toString();
                        sendControl(s, 0x201c0, ok);
                        listener.log("仪表 SN 校验已回应通过");
                        break;
                    }
                    case 0x10450: {
                        long now = System.currentTimeMillis();
                        String ts = new JSONObject()
                                .put("time", now)
                                .put("currentTime", now + TimeZone.getDefault().getOffset(now))
                                .put("currentTimeZone", TimeZone.getDefault().getID())
                                .toString();
                        sendControl(s, cmd + 1, ts);
                        break;
                    }
                    case 0x0:
                    case 0x70000001:
                    case 0x20591:
                    case 0x20021:
                    case 0x20031:
                    case 0x80000010:
                        break;
                    default:
                        if ((cmd & 1) == 0) {
                            sendControl(s, cmd + 1, null);
                            listener.log("控制通道扩展命令 0x" + Integer.toHexString(cmd) + " 已默认应答");
                        }
                        break;
                }
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    private void handleMedia(Socket s) {
        try {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            while (!closed) {
                TailingProtocol.Auxiliary req = TailingProtocol.readAuxiliary(in);
                int cmd = req.command();
                int token = req.token();
                switch (cmd) {
                    case 16: {
                        CaptureConfig cfg = parseCaptureConfig(req.payload());
                        negotiatedWidth = cfg.width();
                        negotiatedHeight = cfg.height();
                        negotiatedFps = Math.max(15, Math.min(30, cfg.fps()));
                        synchronized (s) {
                            out.write(TailingProtocol.auxiliary(17, token, buildCaptureReply(cfg)));
                            out.flush();
                        }
                        configured.countDown();
                        listener.log("仪表协商画布：" + cfg.width() + "×" + cfg.height() + "@" + negotiatedFps + "fps (enc=" + cfg.encoder() + ")");
                        break;
                    }
                    case 48: {
                        byte[] ver = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(3).putInt(1).array();
                        synchronized (s) { out.write(TailingProtocol.auxiliary(49, token, ver)); out.flush(); }
                        break;
                    }
                    case 64:
                        synchronized (s) { out.write(TailingProtocol.auxiliary(65, token, null)); out.flush(); }
                        break;
                    case 96:
                        synchronized (s) {
                            out.write(TailingProtocol.auxiliary(97, token, "{\"state\":0}".getBytes(StandardCharsets.UTF_8)));
                            out.flush();
                        }
                        break;
                    case 112:
                        synchronized (s) { out.write(TailingProtocol.auxiliary(113, token, null)); out.flush(); }
                        mediaStarted.countDown();
                        listener.log("仪表已请求开流（112 → 113）");
                        break;
                    case 32:
                        break;
                    default:
                        synchronized (s) { out.write(TailingProtocol.auxiliary(32514, token, null)); out.flush(); }
                        break;
                }
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    private void readVideoRequests(Socket s) {
        try {
            InputStream in = s.getInputStream();
            while (!closed) {
                TailingProtocol.Auxiliary req = TailingProtocol.readAuxiliary(in);
                if (req.command() == 114) {
                    synchronized (frameLock) {
                        frameRequested = true;
                        frameLock.notifyAll();
                    }
                }
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    void sendAvc(byte[] annexB) throws IOException {
        check();
        Socket s = video;
        if (s == null) throw new IOException("亿连视频通道未连接");
        if (annexB == null || annexB.length <= 4) return;
        long deadline = System.currentTimeMillis() + 10_000L;
        synchronized (frameLock) {
            while (!closed && failure == null && !frameRequested) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) throw new SocketTimeoutException("等待仪表逐帧拉取超时（10s）");
                try {
                    frameLock.wait(Math.min(left, 200));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("已中断");
                }
            }
            check();
            frameRequested = false;
        }
        OutputStream out = s.getOutputStream();
        byte[] lenPrefix = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(annexB.length).array();
        synchronized (s) {
            out.write(lenPrefix);
            out.write(annexB);
            out.flush();
        }
        framesSent++;
        if (framesSent <= 3 || framesSent % 120 == 0) {
            listener.log("亿连已推送第 " + framesSent + " 帧 (" + annexB.length + " B)");
        }
    }

    private void keepaliveLoop(Socket s) {
        try {
            while (!closed && !s.isClosed()) {
                Thread.sleep(1000);
                if (!closed) sendControl(s, 0x70000000, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            fail(e);
        }
    }

    private void sendControl(Socket s, int command, String json) throws IOException {
        synchronized (s) {
            s.getOutputStream().write(TailingProtocol.message(command, json));
            s.getOutputStream().flush();
        }
    }

    private String buildPhoneInfo(String huid) throws Exception {
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, keys.getPrivate());
        byte[] identity = huid.getBytes(StandardCharsets.UTF_8);
        if (identity.length > 117) throw new IOException("仪表 HUID 长度异常");
        byte[] sig = rsa.doFinal(identity);
        return new JSONObject()
                .put("pxcVersion", "1.0.2")
                .put("phoneUUID", phoneId)
                .put("phoneBrand", Build.BRAND)
                .put("phoneModel", Build.MODEL)
                .put("phoneOsVersion", Integer.toString(Build.VERSION.SDK_INT))
                .put("phoneOs", "Android")
                .put("package", acceptedPackage)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("token", 0)
                .put("pubkey", Base64.encodeToString(keys.getPublic().getEncoded(), Base64.NO_WRAP))
                .put("encryptedHUID", Base64.encodeToString(sig, Base64.NO_WRAP))
                .put("bluetoothName", "")
                .put("supportH264IFrame", true)
                .put("supportFunction", 0)
                .put("appVersionFingerPrint", "carwithin")
                .toString();
    }

    private void loadIdentity() throws Exception {
        synchronized (EasyConnPxcConnection.class) {
            android.content.SharedPreferences prefs = context.getSharedPreferences("easyconn_identity", Context.MODE_PRIVATE);
            String pub = prefs.getString("public", null);
            String priv = prefs.getString("private", null);
            if (pub != null && priv != null) {
                KeyFactory f = KeyFactory.getInstance("RSA");
                keys = new KeyPair(
                        f.generatePublic(new X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))),
                        f.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP))));
            } else {
                KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                g.initialize(1024);
                keys = g.generateKeyPair();
            }
            phoneId = prefs.getString("phone_id", null);
            if (phoneId == null) phoneId = UUID.randomUUID().toString();
            prefs.edit()
                    .putString("public", Base64.encodeToString(keys.getPublic().getEncoded(), Base64.NO_WRAP))
                    .putString("private", Base64.encodeToString(keys.getPrivate().getEncoded(), Base64.NO_WRAP))
                    .putString("phone_id", phoneId)
                    .apply();
        }
    }

    private <T extends Closeable> T track(T c) throws IOException {
        resources.add(c);
        if (closed) {
            c.close();
            throw new IOException("亿连会话已停止");
        }
        return c;
    }

    void cancelVideoRead() {
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        Socket s = video;
        if (s != null) {
            try { s.shutdownInput(); } catch (IOException ignored) {}
        }
    }

    void finishMedia() {
        Socket ctrl = reverseControl;
        if (!closed && ctrl != null) {
            try { sendControl(ctrl, 0x20590, "{\"control\":0}"); } catch (IOException ignored) {}
        }
    }

    private void check() throws IOException {
        if (failure != null) throw failure;
        if (closed) throw new IOException("亿连会话已停止");
    }

    private void fail(Exception e) {
        if (!closed) {
            failure = (e instanceof IOException io) ? io : new IOException(e);
            synchronized (frameLock) { frameLock.notifyAll(); }
            close();
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (frameLock) { frameLock.notifyAll(); }
        for (Closeable c : resources) {
            try { c.close(); } catch (IOException ignored) {}
        }
        threads.shutdownNow();
    }
}
