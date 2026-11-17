package software.ulpgc.chess.backend;

import software.ulpgc.chess.backend.infrastructure.adapters.*;
import software.ulpgc.chess.backend.application.ports.RoomRepository;
import software.ulpgc.chess.backend.application.usecases.*;
import software.ulpgc.chess.backend.infrastructure.http.RoomJsonCodec;

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path database = Path.of(args.length > 1 ? args[1] : ".regalia/rooms.sqlite");
        RoomRepository repository = new SqliteRoomRepository(database);
        GetRoomUseCase get = new GetRoomUseCase(repository);
        EnterRoomUseCase enter = new EnterRoomUseCase(get, new JoinRoomUseCase(repository));
        RoomJsonCodec codec = new RoomJsonCodec();
        RoomApiHandler api = new RoomApiHandler(new CreateRoomUseCase(repository),
                get, enter,
                new MoveUseCase(repository), new BrowserSessions(), codec);
        RoomHttpServer server = new RoomHttpServer(port, api, new GameApiHandler(get, codec), new StaticAssetHandler());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.println("Regalia: " + server.uri());
    }
}
