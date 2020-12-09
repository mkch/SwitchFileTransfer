package com.farproc.switchfiletransfer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WifiConfig {
    public final String SSID;
    public final String AuthType;
    public final String Password;

    public WifiConfig(String ssid, String authType, String password) {
        this.SSID = ssid;
        this.AuthType = authType;
        this.Password = password;
    }

    private static Pattern WIFI_CONFIG_SSID = Pattern.compile("\\bS\\:([^;]+);");
    private static Pattern WIFI_CONFIG_AUTH_TYPE = Pattern.compile("\\bT\\:([^;]+);");
    private static Pattern WIFI_CONFIG_PASSWORD = Pattern.compile("\\bP\\:([^;]+);");

    private static String restoreEscaped(String str) {
        return str
                .replace("\0_1", ";")
                .replace("\0_2", ":")
                .replace("\\,", ",")
                .replace("\\\\", "\\");
    }

    // Parse the configuration string from QR.
    // Returns null if config can't be parsed.
    public static WifiConfig parse(String config) {
        // https://github.com/zxing/zxing/wiki/Barcode-Contents#wifi-network-config-android
        // replace all escaped characters.
        config = config
                .replace("\\;", "\0_1")
                .replace("\\:", "\0_2");

        Matcher m = WIFI_CONFIG_SSID.matcher(config);
        if(!m.find() || m.groupCount() == 0) {
            return null;
        }
        final String ssid = m.group(1);

        m = WIFI_CONFIG_AUTH_TYPE.matcher(config);
        if(!m.find() || m.groupCount() == 0) {
            return null;
        }
        final String authType = m.group(1);

        m = WIFI_CONFIG_PASSWORD.matcher(config);
        if(!m.find() || m.groupCount() == 0) {
            return null;
        }
        final String password = m.group(1);


        if (ssid == null || authType == null || password == null) {
            return null;
        }

        return new WifiConfig(restoreEscaped(ssid), restoreEscaped(authType), restoreEscaped(password));
    }
}
