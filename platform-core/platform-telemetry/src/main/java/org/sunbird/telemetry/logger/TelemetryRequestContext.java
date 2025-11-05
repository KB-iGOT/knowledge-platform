// Java
package org.sunbird.telemetry.logger;

import org.apache.commons.lang3.StringUtils;
import java.util.HashMap;
import java.util.Map;

public final class TelemetryRequestContext {
    private static final ThreadLocal<Map<String, String>> CTX = ThreadLocal.withInitial(HashMap::new);

    private TelemetryRequestContext() { }

    public static void setUserId(String userId) {
        if (StringUtils.isNotBlank(userId)) CTX.get().put("userId", userId);
    }

    public static String getUserId() {
        return CTX.get().get("userId");
    }

    public static void clear() {
        CTX.remove();
    }
}