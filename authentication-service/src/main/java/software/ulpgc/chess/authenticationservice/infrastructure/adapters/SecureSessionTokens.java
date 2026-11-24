package software.ulpgc.chess.authenticationservice.infrastructure.adapters;

import software.ulpgc.chess.authenticationservice.application.ports.SessionTokens;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class SecureSessionTokens implements SessionTokens {
    private final SecureRandom random = new SecureRandom();

    @Override public String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override public String digest(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")
                || !Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(token)).equals(token))
            throw new SecurityException("Session is invalid or expired.");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Session token verification is unavailable.", exception);
        }
    }
}
