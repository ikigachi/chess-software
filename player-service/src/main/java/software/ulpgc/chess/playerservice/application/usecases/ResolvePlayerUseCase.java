package software.ulpgc.chess.playerservice.application.usecases;

import software.ulpgc.chess.application.domain.model.User;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class ResolvePlayerUseCase {
    public User execute(String credential) {
        if (credential == null || credential.length() != 36 || !credential.equals(UUID.fromString(credential).toString()))
            throw new IllegalArgumentException("Invalid player credential.");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(credential.getBytes(StandardCharsets.UTF_8));
            return new User(HexFormat.of().formatHex(hash), "Guest");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not resolve the player identity.", exception);
        }
    }
}
