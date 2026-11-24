package software.ulpgc.chess.gameservice.application.ports;

import software.ulpgc.chess.application.domain.model.User;

public interface AccountPlayerProfiles {
    User loadProfile(String token);

    User updateProfile(String token, String name);
}
