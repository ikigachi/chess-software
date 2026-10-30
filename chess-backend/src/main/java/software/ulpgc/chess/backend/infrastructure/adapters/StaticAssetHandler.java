package software.ulpgc.chess.backend.infrastructure.adapters;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import software.ulpgc.chess.backend.infrastructure.http.HttpResponses;

import java.io.IOException;
import java.util.Set;

public final class StaticAssetHandler implements HttpHandler {
    @Override public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRequestMethod().equals("GET")) {
                HttpResponses.text(exchange, 405, "Method not allowed");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            boolean piece = path.matches("/assets/pieces/(white|black)-(pawn|rook|knight|bishop|queen|king)\\.svg");
            if (!piece && !Set.of("/index.html", "/style.css", "/app.js", "/room-api.js",
                    "/assets/crown.svg", "/assets/stone.svg", "/assets/marble-bust.png").contains(path)) {
                HttpResponses.text(exchange, 404, "Not found");
                return;
            }
            try (var input = getClass().getResourceAsStream("/web" + path)) {
                if (input == null) {
                    HttpResponses.text(exchange, 404, "Not found");
                    return;
                }
                String type = path.endsWith(".html") ? "text/html" : path.endsWith(".css") ? "text/css"
                        : path.endsWith(".js") ? "text/javascript" : path.endsWith(".png") ? "image/png" : "image/svg+xml";
                HttpResponses.send(exchange, 200, type, input.readAllBytes());
            }
        } finally { exchange.close(); }
    }
}
