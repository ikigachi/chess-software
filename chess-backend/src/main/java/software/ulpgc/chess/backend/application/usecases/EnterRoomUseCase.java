package software.ulpgc.chess.backend.application.usecases;

import software.ulpgc.chess.application.domain.model.Room;
import software.ulpgc.chess.application.domain.model.User;

import java.util.Objects;

public final class EnterRoomUseCase {
    private final GetRoomUseCase get;
    private final JoinRoomUseCase join;

    public EnterRoomUseCase(GetRoomUseCase get, JoinRoomUseCase join) {
        this.get = Objects.requireNonNull(get);
        this.join = Objects.requireNonNull(join);
    }

    public Room execute(String code, User user) {
        Room room = get.execute(code);
        return room.hasPlayer(user) ? room : join.execute(code, user);
    }
}
