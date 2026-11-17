package software.ulpgc.chess.gameservice;

import software.ulpgc.chess.gameservice.infrastructure.adapters.*;
import software.ulpgc.chess.gameservice.application.ports.RoomRepository;
import software.ulpgc.chess.gameservice.application.usecases.*;
import software.ulpgc.chess.gameservice.infrastructure.http.RoomJsonCodec;

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        Path database = Path.of(args.length > 1 ? args[1] : ".regalia/rooms.sqlite");
        RoomRepository repository = new SqliteRoomRepository(database);
        GetRoomUseCase get = new GetRoomUseCase(repository);
        EnterRoomUseCase enter = new EnterRoomUseCase(get, new JoinRoomUseCase(repository));
        RoomJsonCodec codec = new RoomJsonCodec();
        RoomApiHandler api = new RoomApiHandler(new CreateRoomUseCase(repository),
                get, enter,
                new MoveUseCase(repository), new BrowserSessions(), codec);
        GameHttpServer server = new GameHttpServer(port, api, new GameApiHandler(get, codec));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.println("Game service: " + server.uri());
    }
}
