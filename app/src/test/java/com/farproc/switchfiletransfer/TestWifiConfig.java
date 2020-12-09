package com.farproc.switchfiletransfer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TestWifiConfig {
    @Test
    public void simple() {
        final WifiConfig config = WifiConfig.parse("WIFI:S:switch_E313480100f;T:WPA;P:pchg33ss;;");
        assertNotNull(config);
        assertEquals(config.SSID, "switch_E313480100f");
        assertEquals(config.AuthType, "WPA");
        assertEquals(config.Password, "pchg33ss");
    }

    @Test
    public void escape() {
        final WifiConfig config = WifiConfig.parse("WIFI:S:\\:switch;T:WPA;P:1\\;2;;");
        assertNotNull(config);
        assertEquals(config.SSID, ":switch");
        assertEquals(config.AuthType, "WPA");
        assertEquals(config.Password, "1;2");
    }
}
