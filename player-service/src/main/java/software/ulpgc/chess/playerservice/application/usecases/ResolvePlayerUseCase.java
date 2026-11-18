package software.ulpgc.chess.playerservice.application.usecases;

import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.playerservice.application.ports.PlayerRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Objects;

public final class ResolvePlayerUseCase {
    private final PlayerRepository repository;

    public ResolvePlayerUseCase(PlayerRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public User execute(String credential) {
        if (!isCanonicalUuid(credential))
            throw new IllegalArgumentException("Invalid player credential.");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(credential.getBytes(StandardCharsets.UTF_8));
            String id = HexFormat.of().formatHex(hash);
            return repository.findById(id).orElseGet(() -> new User(id, "Guest"));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not resolve the player identity.", exception);
        }
    }

    private boolean isCanonicalUuid(String value) {
        if (value == null || value.length() != 36) return false;
        try {
            return value.equals(UUID.fromString(value).toString());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
