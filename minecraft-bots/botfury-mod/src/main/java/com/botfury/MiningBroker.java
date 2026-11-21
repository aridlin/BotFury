package com.botfury;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
final class MiningBroker {
    private static final Gson JSON=new Gson();
    private static final MiningClaims CLAIMS=new MiningClaims();
    static void handle(HttpExchange exchange)throws IOException{
        String host=exchange.getRequestHeaders().getFirst("Host");
        if(host==null||!host.matches("(127\\.0\\.0\\.1|localhost)(:[0-9]+)?")||exchange.getRequestHeaders().containsKey("Origin")||!exchange.getRequestMethod().equals("POST")){reply(exchange,403,"Local mining clients only");return;}
        byte[] body=exchange.getRequestBody().readNBytes(4097);
        if(body.length>4096){reply(exchange,400,"Claim too large");return;}
        try{
            var request=JSON.fromJson(new String(body,StandardCharsets.UTF_8),MiningClaims.Request.class);
            var here=LocalFormation.here();
            if(request==null||request.action()==null){reply(exchange,400,"Invalid claim");return;}
            if(!request.action().equals("release")&&(here==null||!request.world().startsWith(here.server()+"|"))){reply(exchange,409,"Controller is on a different server");return;}
            reply(exchange,200,JSON.toJson(CLAIMS.handle(request,System.currentTimeMillis())));
        }catch(RuntimeException error){reply(exchange,400,"Invalid claim");}
    }
    private static void reply(HttpExchange e,int code,String body)throws IOException{
        byte[] data=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(code,data.length);try(var out=e.getResponseBody()){out.write(data);}
    }
}
