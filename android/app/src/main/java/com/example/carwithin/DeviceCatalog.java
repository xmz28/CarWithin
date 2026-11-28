package com.example.carwithin;

/** Name families suggest a protocol; the P2P controller still verifies the full selected identity. */
public final class DeviceCatalog {
    private DeviceCatalog() {}
    public static boolean ninebotCandidate(String name) {
        return name!=null && name.matches("DIRECT-9c4h[A-Za-z0-9]{4,16}");
    }
    public static boolean zeehoCandidate(String name) {
        return name!=null && name.matches("ZEEHO-[A-Fa-f0-9]{6}");
    }
    public static boolean tailingCandidate(String name) {
        return name!=null && name.matches("TL-DASH-[A-Fa-f0-9]{6}");
    }
    /** Generic EasyConn / QJMotor P2P peers (e.g. "594200", "DIRECT-uh-594200", "QJ..."). */
    public static boolean easyConnCandidate(String name) {
        return name != null && !name.trim().isEmpty();
    }
}
