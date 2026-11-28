package com.example.carwithin;

import java.util.Locale;

public record Target(String name, String mac) {
    public Target {
        name = name.trim(); mac = mac.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || !mac.matches("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}"))
            throw new IllegalArgumentException("请输入完整设备名称和 MAC 地址");
    }
    public boolean matches(String deviceName, String address) {
        return name.equals(deviceName) && mac.equalsIgnoreCase(address);
    }
    /** Loose peer match for QR-seeded EasyConn P2P lookup when P2P MAC differs from Wi-Fi MAC. */
    public boolean matchesEasyConnPeer(String deviceName, String address) {
        if (matches(deviceName, address)) return true;
        if (deviceName != null && !deviceName.isBlank()) {
            String dn = deviceName.trim().toLowerCase(Locale.ROOT);
            String tn = name.toLowerCase(Locale.ROOT);
            if (dn.equals(tn) || dn.contains(tn)) return true;
        }
        return address != null && !mac.equals("02:00:00:00:00:00") && sameMacTail(mac, address);
    }
    public boolean matchesOwner(String deviceName, String networkName, String address) {
        return mac.equalsIgnoreCase(address) && (name.equals(deviceName) || name.equals(networkName));
    }
    /** EasyConn / PXC Wi-Fi Direct groups often advertise "DIRECT-xy-<name>" and an interface MAC
     *  that differs from the P2P device MAC in the locally-administered bit. */
    public boolean matchesEasyConnOwner(String deviceName, String networkName, String address) {
        if (matchesOwner(deviceName, networkName, address)) return true;
        String lowerName = name.toLowerCase(Locale.ROOT);
        boolean nameHit = (deviceName != null && deviceName.toLowerCase(Locale.ROOT).contains(lowerName))
                || (networkName != null && networkName.toLowerCase(Locale.ROOT).contains(lowerName));
        if (nameHit) return true;
        if (address != null && sameMacTail(mac, address)) return true;
        // Some dashboards expose only a generic "DIRECT-xx" group name with empty owner deviceName.
        return networkName != null && networkName.startsWith("DIRECT-");
    }
    private static boolean sameMacTail(String a, String b) {
        String x = a.trim().toLowerCase(Locale.ROOT), y = b.trim().toLowerCase(Locale.ROOT);
        if (x.length() != 17 || y.length() != 17) return false;
        return x.substring(3).equals(y.substring(3));
    }
}
