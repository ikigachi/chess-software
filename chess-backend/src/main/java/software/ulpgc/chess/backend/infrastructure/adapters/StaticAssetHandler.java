package software.ulpgc.chess.backend.infrastructure.adapters;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import software.ulpgc.chess.backend.infrastructure.http.HttpResponses;

import java.io.IOException;
import java.util.Set;

public final class StaticAssetHandler implements Handler {
    @Override public void handle(Context context) throws IOException {
        if (!context.method().equals(HandlerType.GET)) {
            HttpResponses.text(context, 405, "Method not allowed");
            return;
        }
        String path = context.path();
        if (path.equals("/")) path = "/index.html";
        boolean piece = path.matches("/assets/pieces/(white|black)-(pawn|rook|knight|bishop|queen|king)\\.svg");
        if (!piece && !Set.of("/index.html", "/style.css", "/main.js", "/app.js", "/room-actions.js", "/room-api.js",
                "/assets/crown.svg", "/assets/stone.svg", "/assets/marble-bust.png").contains(path)) {
            HttpResponses.text(context, 404, "Not found");
            return;
        }
        try (var input = getClass().getResourceAsStream("/web" + path)) {
            if (input == null) {
                HttpResponses.text(context, 404, "Not found");
                return;
            }
            String type = path.endsWith(".html") ? "text/html" : path.endsWith(".css") ? "text/css"
                    : path.endsWith(".js") ? "text/javascript" : path.endsWith(".png") ? "image/png" : "image/svg+xml";
            HttpResponses.send(context, 200, type, input.readAllBytes());
        }
    }
}
