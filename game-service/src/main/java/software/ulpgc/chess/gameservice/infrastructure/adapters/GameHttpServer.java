package software.ulpgc.chess.gameservice.infrastructure.adapters;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GameHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;

    public GameHttpServer(int port, RoomApiHandler rooms, GameApiHandler games) throws IOException {
        Objects.requireNonNull(rooms);
        Objects.requireNonNull(games);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/api/", rooms);
        server.createContext("/games", games);
    }

    public void start() { server.start(); }
    public URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }

    @Override public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
