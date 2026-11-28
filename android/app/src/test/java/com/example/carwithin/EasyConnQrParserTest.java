package com.example.carwithin;

import org.junit.Test;
import static org.junit.Assert.*;

public class EasyConnQrParserTest {

    @Test
    public void parsesQjSai600CarbitQrWithEncodedMacAndNewline() {
        String qr = "http://www.carbit.com.cn/down6/642/643/_ylqx?modelid=9975&action=0&ssid=594200&pwd=88888888&auth=WPA2&mac=50%3A23%3A4A%3A00%3A42%3A59%0A&name=594200";
        EasyConnQrParser.ParsedQr p = EasyConnQrParser.parse(qr);

        assertEquals("594200", p.ssid());
        assertEquals("88888888", p.password());
        assertEquals("50:23:4a:00:42:59", p.mac());
        assertEquals("594200", p.name());
        assertEquals("9975", p.modelId());
        assertEquals("WPA2", p.auth());
        assertEquals(0, p.action());
        assertEquals(EasyConnQrParser.LinkMode.WIFI_DIRECT, p.recommendedMode());
    }

    @Test
    public void parsesCfmotoAccessPointQr() {
        String qr = "http://www.carbit.com.cn/downsdk/657/658/_sdk?modelid=37416&sn=test&action=1&ssid=CFMOTO-1234&pwd=87654321&auth=wpa2-psk&mac=AA%3ABB%3ACC%3ADD%3AEE%3AFF&name=CFMOTO-1234";
        EasyConnQrParser.ParsedQr p = EasyConnQrParser.parse(qr);

        assertEquals("CFMOTO-1234", p.ssid());
        assertEquals("87654321", p.password());
        assertEquals("aa:bb:cc:dd:ee:ff", p.mac());
        assertEquals("37416", p.modelId());
        assertEquals(EasyConnQrParser.LinkMode.WIFI_AP, p.recommendedMode());
    }

    @Test
    public void parsesAction8AsWifiDirect() {
        String qr = "http://www.carbit.com.cn/app?ssid=qj5inch-0758&pwd=12345678&action=8&modelid=1234";
        EasyConnQrParser.ParsedQr p = EasyConnQrParser.parse(qr);
        assertEquals("qj5inch-0758", p.ssid());
        assertEquals(EasyConnQrParser.LinkMode.WIFI_DIRECT, p.recommendedMode());
    }

    @Test
    public void parsesStandardWifiQrCode() {
        String qr = "WIFI:T:WPA;S:DIRECT-go-CFMOTO-9999;P:11223344;;";
        EasyConnQrParser.ParsedQr p = EasyConnQrParser.parse(qr);
        assertEquals("DIRECT-go-CFMOTO-9999", p.ssid());
        assertEquals("11223344", p.password());
        assertEquals(EasyConnQrParser.LinkMode.WIFI_DIRECT, p.recommendedMode());
    }

    @Test
    public void parsesMotoFunQrCode() {
        String qr = "https://example.com/motofun?Wifi=MOTO-888#pass9999";
        EasyConnQrParser.ParsedQr p = EasyConnQrParser.parse(qr);
        assertEquals("MOTO-888", p.ssid());
        assertEquals("pass9999", p.password());
        assertEquals(EasyConnQrParser.LinkMode.WIFI_AP, p.recommendedMode());
    }

    @Test
    public void normalizesBareHexMac() {
        assertEquals("50:23:4a:00:42:59", EasyConnQrParser.normalizeMac("50234A004259"));
        assertEquals("50:23:4a:00:42:59", EasyConnQrParser.normalizeMac("50-23-4A-00-42-59\n"));
        assertEquals("", EasyConnQrParser.normalizeMac("invalid"));
    }
}
