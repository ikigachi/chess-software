package software.ulpgc.chess.gameservice;

import software.ulpgc.chess.gameservice.infrastructure.adapters.*;
import software.ulpgc.chess.gameservice.application.ports.RoomRepository;
import software.ulpgc.chess.gameservice.application.usecases.*;
import software.ulpgc.chess.gameservice.infrastructure.http.RoomJsonCodec;

import java.nio.file.Path;
import java.net.URI;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        SqliteDatabase database = new SqliteDatabase(Path.of(args.length > 1 ? args[1] : ".regalia/rooms.sqlite"));
        database.initialize();
        RoomRepository repository = new SqliteRoomRepository(database);
        GetRoomUseCase get = new GetRoomUseCase(repository);
        EnterRoomUseCase enter = new EnterRoomUseCase(get, new JoinRoomUseCase(repository));
        RoomJsonCodec codec = new RoomJsonCodec();
        CreateRoomUseCase create = new CreateRoomUseCase(repository);
        MoveUseCase move = new MoveUseCase(repository);
        HttpPlayerIdentity players = new HttpPlayerIdentity(URI.create(args.length > 2 ? args[2] : "http://127.0.0.1:8082"));
        try {
            BrowserSessions sessions = new BrowserSessions(players);
            RoomApiHandler api = new RoomApiHandler(create, get, enter, move, sessions, codec);
            GameHttpServer server = new GameHttpServer(port, api, new GameApiHandler(create, get, move, sessions, codec));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.close();
                players.close();
            }));
            server.start();
            System.out.println("Game service: " + server.uri());
        } catch (Exception exception) {
            players.close();
            throw exception;
        }
    }
}
