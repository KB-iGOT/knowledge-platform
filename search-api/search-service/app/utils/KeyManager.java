package utils;

import org.sunbird.common.Constants;
import org.sunbird.common.Platform;
import org.sunbird.telemetry.logger.TelemetryManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KeyManager {

    private static final Map<String, KeyData> keyMap = new HashMap<String, KeyData>();

    public static void init() {
        String basePath = Platform.config.getString(Constants.ACCESS_PUBLIC_KEY_PATH);
        try {
            File baseDir = new File(basePath);
            List<File> files = new ArrayList<File>();
            listFilesRecursively(baseDir, files);

            for (int i = 0; i < files.size(); i++) {
                File f = files.get(i);
                try {
                    String content = readFileUtf8(f);
                    KeyData keyData = new KeyData(f.getName(), loadPublicKey(content));
                    keyMap.put(f.getName(), keyData);
                } catch (Exception e) {
                    TelemetryManager.error(Constants.READING_PUBLIC_KEY_EXCEPTION, e);
                }
            }
        } catch (Exception e) {
            TelemetryManager.error(Constants.LOADING_PUBLIC_KEY_EXCEPTION, e);
        }
    }

    private static void listFilesRecursively(File dir, List<File> out) {
        if (dir == null || !dir.exists()) return;
        if (dir.isFile()) {
            out.add(dir);
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) return;
        for (int i = 0; i < children.length; i++) {
            File c = children[i];
            if (c.isDirectory()) {
                listFilesRecursively(c, out);
            } else if (c.isFile()) {
                out.add(c);
            }
        }
    }

    private static String readFileUtf8(File file) throws IOException {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            if (br != null) {
                try { br.close(); } catch (IOException ignore) {}
            }
        }
    }

    public static KeyData getPublicKey(String keyId) {
        if (keyMap.isEmpty()) {
            init();
        }
        return keyMap.get(keyId);
    }

    public static PublicKey loadPublicKey(String key) throws Exception {
        String publicKey = new String(key.getBytes("UTF-8"), "UTF-8");
        publicKey = publicKey.replaceAll("(-+BEGIN PUBLIC KEY-+)", "");
        publicKey = publicKey.replaceAll("(-+END PUBLIC KEY-+)", "");
        publicKey = publicKey.replaceAll("[\\r\\n]+", "");
        byte[] keyBytes = Base64Util.decode(publicKey.getBytes("UTF-8"), Base64Util.DEFAULT);

        X509EncodedKeySpec X509publicKey = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(X509publicKey);
    }
}