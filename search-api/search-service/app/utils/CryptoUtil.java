package utils;

import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Map;

public class CryptoUtil {

    private static final String US_ASCII = "US-ASCII";

    public static boolean verifyRSASign(
            String payLoad,
            byte[] signature,
            PublicKey key,
            String algorithm,
            Map<String, Object> requestContext) {
        try {
            Signature sign = Signature.getInstance(algorithm);
            sign.initVerify(key);
            sign.update(toAsciiBytes(payLoad));
            return sign.verify(signature);
        } catch (NoSuchAlgorithmException e) {
            return false;
        } catch (InvalidKeyException e) {
            return false;
        } catch (SignatureException e) {
            return false;
        }
    }

    private static byte[] toAsciiBytes(String s) {
        try {
            return s.getBytes(US_ASCII);
        } catch (UnsupportedEncodingException e) {
            return s.getBytes();
        }
    }
}