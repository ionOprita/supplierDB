package ro.sellfluence.test;


import com.bastiaanjansen.otp.TOTPGenerator;
import com.example.otp.OtpMigrationProtos;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.commons.codec.binary.Base32;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Converter that takes a URL as provided by the Google Authenticator app's export function
 * and generates the corresponding otpauth URL.
 */
public final class GoogleAuthenticatorMigration {

    private GoogleAuthenticatorMigration() {
    }

    /**
     * Reads a URL exported by "Google Authenticator", and produces a list of otpauth URLs.
     * If only one entry was exported the size of the list is 1.
     *
     * @param migrationUrl URL exported from Google Authenticator.
     * @return list of otpauth URLs.
     */
    public static List<URI> toOtpAuthUris(String migrationUrl)  {
        URI uri = URI.create(migrationUrl.trim());

        if (!"otpauth-migration".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Not an otpauth-migration URI");
        }

        String rawData = getRawQueryParam(uri.getRawQuery(), "data");
        if (rawData == null || rawData.isBlank()) {
            throw new IllegalArgumentException("Missing data parameter");
        }

        String base64Payload = percentDecode(rawData);
        byte[] protobufBytes = decodeBase64Lenient(base64Payload);

        OtpMigrationProtos.MigrationPayload payload;
        try {
            payload = OtpMigrationProtos.MigrationPayload.parseFrom(protobufBytes);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }

        List<URI> result = new ArrayList<>();

        for (OtpMigrationProtos.OtpParameters p : payload.getOtpParametersList()) {
            URI otpAuthUri = toOtpAuthUri(p);
            result.add(otpAuthUri);
        }

        return result;
    }

    private static URI toOtpAuthUri(OtpMigrationProtos.OtpParameters p) {
        if (!p.hasSecret() || p.getSecret().isEmpty()) {
            throw new IllegalArgumentException("OTP entry has no secret");
        }

        String otpType = switch (p.getType().getNumber()) {
            case 1 -> "hotp";
            case 2 -> "totp";
            default -> throw new IllegalArgumentException("Unsupported OTP type: " + p.getType().getNumber());
        };

        String issuer = p.hasIssuer() ? p.getIssuer() : "";
        String accountName = p.hasName() ? p.getName() : "";

        String label = issuer.isBlank()
                ? accountName
                : issuer + ":" + accountName;

        String secretBase32 = toBase32NoPadding(p.getSecret());

        String algorithm = switch (p.getAlgorithm().getNumber()) {
            case 2 -> "SHA256";
            case 3 -> "SHA512";
            case 4 -> throw new IllegalArgumentException("MD5 OTP is not supported by otp-java");
            default -> "SHA1";
        };

        int digits = switch (p.getDigits().getNumber()) {
            case 2 -> 8;
            default -> 6;
        };

        StringBuilder query = new StringBuilder();
        appendQuery(query, "secret", secretBase32);

        if (!issuer.isBlank()) {
            appendQuery(query, "issuer", issuer);
        }

        appendQuery(query, "algorithm", algorithm);
        appendQuery(query, "digits", String.valueOf(digits));

        if ("totp".equals(otpType)) {
            appendQuery(query, "period", "30");
        } else {
            long counter = p.hasCounter() ? p.getCounter() : 0L;
            appendQuery(query, "counter", String.valueOf(counter));
        }

        String otpAuth = "otpauth://" + otpType + "/" + encode(label) + "?" + query;
        return URI.create(otpAuth);
    }

    private static String toBase32NoPadding(ByteString secret) {
        Base32 base32 = new Base32();
        return base32.encodeToString(secret.toByteArray()).replace("=", "");
    }

    private static void appendQuery(StringBuilder query, String key, String value) {
        if (!query.isEmpty()) {
            query.append('&');
        }
        query.append(encode(key)).append('=').append(encode(value));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String getRawQueryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }

        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String rawName = equals >= 0 ? pair.substring(0, equals) : pair;
            String rawValue = equals >= 0 ? pair.substring(equals + 1) : "";

            if (name.equals(percentDecode(rawName))) {
                return rawValue;
            }
        }

        return null;
    }

    /**
     * Percent-decodes URI data without treating '+' as a space.
     * That matters because Base64 may contain '+'.
     */
    private static String percentDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);

                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) + lo);
                    i += 2;
                    continue;
                }
            }

            out.write((byte) c);
        }

        return out.toString(StandardCharsets.UTF_8);
    }

    private static byte[] decodeBase64Lenient(String value) {
        String normalized = value.trim().replace(" ", "+");
        int remainder = normalized.length() % 4;

        if (remainder != 0) {
            normalized += "=".repeat(4 - remainder);
        }

        try {
            return Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException standardBase64Failed) {
            return Base64.getUrlDecoder().decode(normalized);
        }
    }

    static void main() throws Exception {
        IO.println("Enter the URL exported by Google Authenticator:");
        String migrationUrl = IO.readln();

        List<URI> otpAuthUris = toOtpAuthUris(migrationUrl);

        for (URI otpAuthUri : otpAuthUris) {
            IO.println("OTP to add to Secrets/userpws.txt: " + otpAuthUri);

            if ("totp".equalsIgnoreCase(otpAuthUri.getHost())) {
                TOTPGenerator totp = TOTPGenerator.fromURI(otpAuthUri);
                IO.println("Current OTP: " + totp.now());
            }
        }
    }
}