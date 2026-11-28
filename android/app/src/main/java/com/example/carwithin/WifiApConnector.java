package com.example.carwithin;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Connects Android 10+ (API 29+) phones to a motorcycle dashboard's standard WPA2/open Wi-Fi AP
 * using SSID + password extracted from the dashboard QR code, and resolves it into a P2pController.Route
 * so EasyConnPxcConnection can run seamlessly over either Wi-Fi Direct or Wi-Fi AP.
 */
public final class WifiApConnector {

    public interface Listener {
        void log(String message);
        void route(P2pController.Route route);
        void status(String message, boolean connecting);
    }

    private final Context context;
    private final Listener listener;
    private final ConnectivityManager cm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback activeCallback;
    private P2pController.Route activeRoute;
    private int generation;
    private boolean closed;

    public WifiApConnector(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.cm = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void connect(String ssid, String password, String macHint) {
        if (closed || cm == null) return;
        String cleanSsid = ssid == null ? "" : ssid.trim();
        if (cleanSsid.isEmpty()) {
            listener.status("请先扫码或输入仪表 Wi-Fi 名称", false);
            return;
        }
        disconnectInternal(false);
        int gen = ++generation;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            adoptCurrentWifi(cleanSsid, macHint, gen);
            return;
        }

        listener.status("正在连接车机 Wi-Fi 热点 " + cleanSsid + "，请在系统弹窗点击连接…", true);
        listener.log("发起 Wi-Fi AP 连接：SSID=" + cleanSsid + (password == null || password.isEmpty() ? "（无密码）" : "（WPA2）"));

        WifiNetworkSpecifier.Builder builder = new WifiNetworkSpecifier.Builder().setSsid(cleanSsid);
        if (password != null && !password.isEmpty()) {
            builder.setWpa2Passphrase(password);
        }

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(builder.build())
                .build();

        String safeMac = EasyConnQrParser.normalizeMac(macHint);
        String targetMac = safeMac.isEmpty() ? "02:00:00:00:00:00" : safeMac;
        Target target = new Target(cleanSsid, targetMac);

        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            private boolean settled;

            @Override
            public void onAvailable(Network network) {
                main.post(() -> {
                    if (closed || generation != gen) return;
                    LinkProperties lp = cm.getLinkProperties(network);
                    tryResolve(network, lp);
                });
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                main.post(() -> {
                    if (closed || generation != gen) return;
                    tryResolve(network, linkProperties);
                });
            }

            private void tryResolve(Network network, LinkProperties lp) {
                if (settled || lp == null) return;
                P2pController.Route resolved = buildRoute(target, network, lp);
                if (resolved == null) return;
                settled = true;
                activeRoute = resolved;
                listener.log("车机 Wi-Fi 已连接：" + resolved.iface() + " " +
                        resolved.local().getHostAddress() + " → " + resolved.remote().getHostAddress());
                listener.status("已连接热点 " + cleanSsid, false);
                listener.route(resolved);
            }

            @Override
            public void onUnavailable() {
                main.post(() -> {
                    if (closed || generation != gen) return;
                    disconnectInternal(false);
                    listener.log("未能连接 Wi-Fi 热点 " + cleanSsid + "（可能热点未开启、密码不符，或该车走 WLAN 直连）");
                    listener.status("热点连接失败，若该车为 WLAN 直连请切换连接模式重试", false);
                });
            }

            @Override
            public void onLost(Network network) {
                main.post(() -> {
                    if (closed || generation != gen) return;
                    activeRoute = null;
                    listener.log("车机 Wi-Fi 热点已断开");
                    listener.status("车机 Wi-Fi 已断开", false);
                    listener.route(null);
                });
            }
        };

        activeCallback = cb;
        try {
            cm.requestNetwork(request, cb, 30_000);
        } catch (Exception e) {
            activeCallback = null;
            listener.log("请求车机 Wi-Fi 失败：" + e.getMessage());
            listener.status("请求车机 Wi-Fi 失败：" + e.getMessage(), false);
        }
    }

    /** Adopts whichever Wi-Fi network the phone is currently joined to in system settings. */
    public void adoptCurrentWifi(String labelName, String macHint) {
        if (closed || cm == null) return;
        disconnectInternal(false);
        adoptCurrentWifi(labelName, macHint, ++generation);
    }

    private void adoptCurrentWifi(String labelName, String macHint, int gen) {
        if (closed || generation != gen || cm == null) return;
        String name = (labelName == null || labelName.isBlank()) ? "Wi-Fi 仪表" : labelName.trim();
        String safeMac = EasyConnQrParser.normalizeMac(macHint);
        Target target = new Target(name, safeMac.isEmpty() ? "02:00:00:00:00:00" : safeMac);

        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            LinkProperties lp = cm.getLinkProperties(net);
            P2pController.Route resolved = buildRoute(target, net, lp);
            if (resolved != null) {
                activeRoute = resolved;
                listener.log("已接管当前系统 Wi-Fi：" + resolved.iface() + " " +
                        resolved.local().getHostAddress() + " → " + resolved.remote().getHostAddress());
                listener.status("已接管当前 Wi-Fi (" + name + ")", false);
                listener.route(resolved);
                return;
            }
        }
        listener.status("当前未连接到任何 Wi-Fi，请先连接仪表热点", false);
    }

    private static P2pController.Route buildRoute(Target target, Network network, LinkProperties lp) {
        if (lp == null) return null;
        Inet4Address local = null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress addr = la.getAddress();
            if (addr instanceof Inet4Address v4 && !v4.isLoopbackAddress()) {
                local = v4;
                break;
            }
        }
        if (local == null) return null;

        Inet4Address gateway = null;
        for (RouteInfo ri : lp.getRoutes()) {
            InetAddress gw = ri.getGateway();
            if (gw instanceof Inet4Address v4 && !v4.isAnyLocalAddress() && !v4.isLoopbackAddress()) {
                gateway = v4;
                break;
            }
        }
        if (gateway == null) {
            byte[] bytes = local.getAddress().clone();
            bytes[3] = 1;
            try {
                gateway = (Inet4Address) InetAddress.getByAddress(bytes);
            } catch (Exception ignored) {
                return null;
            }
        }
        String iface = lp.getInterfaceName() != null ? lp.getInterfaceName() : "wlan0";
        return new P2pController.Route(target, iface, local, gateway, network);
    }

    public void disconnect() {
        disconnectInternal(true);
    }

    private void disconnectInternal(boolean notify) {
        generation++;
        ConnectivityManager.NetworkCallback cb = activeCallback;
        activeCallback = null;
        if (cb != null && cm != null) {
            try { cm.unregisterNetworkCallback(cb); } catch (Exception ignored) {}
        }
        boolean hadRoute = activeRoute != null;
        activeRoute = null;
        if (notify && hadRoute) {
            listener.route(null);
            listener.status("已断开车机 Wi-Fi", false);
        }
    }

    public void close() {
        closed = true;
        disconnectInternal(false);
        main.removeCallbacksAndMessages(null);
    }
}
