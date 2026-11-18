package software.ulpgc.chess.gameservice.application.ports;

import software.ulpgc.chess.application.domain.model.User;

public interface PlayerProfileUpdater {
    User updateDisplayName(String credential, String name);
}
