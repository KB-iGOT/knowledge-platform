package utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.keycloak.common.util.Time;
import org.sunbird.common.Platform;
import org.sunbird.telemetry.logger.TelemetryManager;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

public class AccessTokenValidator {
  private AccessTokenValidator() { }
  private static final ObjectMapper mapper = new ObjectMapper();
  private static final String sso_url = Platform.config.getString("sso.url");
  private static final String realm = Platform.config.getString("sso.realm");

  private static Map<String, Object> validateToken(String token, Map<String, Object> requestContext)
          throws IOException {
    String[] tokenElements = token.split("\\.");
    String header = tokenElements[0];
    String body = tokenElements[1];
    String signature = tokenElements[2];
    String payLoad = header + "." + body;
    Map<Object, Object> headerData =
        mapper.readValue(new String(decodeFromBase64(header)), Map.class);
    String keyId = headerData.get("kid").toString();
    boolean isValid =
        CryptoUtil.verifyRSASign(
            payLoad,
            decodeFromBase64(signature),
            KeyManager.getPublicKey(keyId).getPublicKey(),
                "SHA256withRSA",
            requestContext);
    if (isValid) {
      Map<String, Object> tokenBody =
          mapper.readValue(new String(decodeFromBase64(body)), Map.class);
      boolean isExp = isExpired((Integer) tokenBody.get("exp"));
      if (isExp) {
          TelemetryManager.warn("Token is expired ");
        return Collections.EMPTY_MAP;
      }
      return tokenBody;
    }
    return Collections.EMPTY_MAP;
  }

  public static String verifyUserToken(String token, Map<String, Object> requestContext) {
    String userId = "UNAUTHORIZED";
    try {
      Map<String, Object> payload = validateToken(token, requestContext);
      if (MapUtils.isNotEmpty(payload) && checkIss((String) payload.get("iss"))) {
        userId = (String) payload.get("sub");
        if (StringUtils.isNotBlank(userId)) {
          int pos = userId.lastIndexOf(":");
          userId = userId.substring(pos + 1);
        }
      }
    } catch (Exception ex) {
        TelemetryManager.error(
          "Exception in verifyUserAccessToken: Token : ", ex);
    }
    if ("UNAUTHORIZED".equalsIgnoreCase(userId)) {
        TelemetryManager.info(
          "verifyUserAccessToken: Invalid User Token");
    }
    return userId;
  }

  private static boolean checkIss(String iss) {
    String realmUrl = sso_url + "realms/" + realm;
    return (realmUrl.equalsIgnoreCase(iss));
  }


  private static boolean isExpired(Integer expiration) {
    return (Time.currentTime() > expiration);
  }

  private static byte[] decodeFromBase64(String data) {
    return Base64Util.decode(data, 11);
  }
}
