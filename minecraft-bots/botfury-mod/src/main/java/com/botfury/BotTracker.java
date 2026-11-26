package com.botfury;

import com.google.gson.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

final class BotTracker {
    record Bot(int port, String name, boolean reachable, boolean connected, boolean alive,
               double x, double y, double z, float health, float maxHealth, String server, String dimension,
               String mode, String state, String inventory, String mining, String build, boolean baritone,boolean defenseCapable, boolean scaffoldCleanupCapable, boolean scaffoldBatchCapable,
               Map<String,Integer> items, List<BlockPos> route, boolean inventoryCoordinates, long contactAt, long positionAt,
               String error, String task, boolean fuelChoicesCapable) {
        boolean fresh() { return reachable && System.currentTimeMillis() - contactAt < 3000; }
        boolean sameWorld() {
            var here = LocalFormation.here();
            return here != null && server.equals(here.server()) && dimension.equals(here.dimension());
        }
        double distance() {
            var here = LocalFormation.here();
            return here == null ? Double.POSITIVE_INFINITY : Math.sqrt(Math.pow(x-here.x(),2)+Math.pow(y-here.y(),2)+Math.pow(z-here.z(),2));
        }
        String summary() {
            if (!fresh()) return "Offline · last seen " + Math.max(0,(System.currentTimeMillis()-contactAt)/1000) + "s ago";
            if (!connected) return "At title screen";
            if (!alive) return "Dead / respawning";
            if (!error.isEmpty())return error;
            if (!mining.isEmpty()) return mining;
            if (!inventory.isEmpty() && !inventory.equals("Resumed formations") && !inventory.equals("Stopped")) return inventory;
            return state.isEmpty() ? mode : state;
        }
    }
    private static final ConcurrentMap<Integer,Bot> bots = new ConcurrentHashMap<>();
    private static final Set<Integer> inflight = ConcurrentHashMap.newKeySet();
    private static final ExecutorService IO = Executors.newFixedThreadPool(4, task -> {var t=new Thread(task,"BotFury-tracker");t.setDaemon(true);return t;});
    private static final HttpClient HTTP = HttpClient.newBuilder().executor(IO).connectTimeout(Duration.ofMillis(300)).build();
    private static final ConcurrentMap<Integer,CompletableFuture<Void>> commands = new ConcurrentHashMap<>();
    private static final AtomicLong sequence = new AtomicLong();
    private static final String SESSION = "tracker-" + UUID.randomUUID();
    private static volatile long nextPoll;
    private static long rosterAt;
    private static int selected = -1;
    private static String squad;
    record Target(int port, Set<String> members) {
        boolean all() { return port < 0 && members == null; }
        boolean includes(int candidate, String name) {
            return members == null ? port < 0 || port == candidate : members.contains(name.toLowerCase(Locale.ROOT));
        }
    }
    static Target target() {
        if (squad == null) return new Target(selected, null);
        var members = BotUiConfig.squads.getOrDefault(squad, List.of()).stream()
                .map(name -> name.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new Target(-1, members);
    }
    static void selectSquad(String name) { selected = -1; squad = name; }
    static String selectedSquad() { return squad; }
    static void cycleSquad() {
        var names = new ArrayList<>(BotUiConfig.squads.keySet());
        int next = names.indexOf(squad) + 1;
        selectSquad(next < names.size() ? names.get(next) : null);
    }
    static String json(){return new Gson().toJson(java.util.Map.of("selectedPort",selected,"bots",all()));}
    static int selected() { return selected; }
    static void select(int port) { selected = port; squad = null; }
    static String targetLabel() { Bot bot=bots.get(selected);return squad != null ? squad : bot==null?"All bots":bot.name; }
    static Bot get(int port) { return bots.get(port); }
    static Bot trackedPlayer(String name) {
        return bots.values().stream().filter(bot -> bot.sameWorld() && bot.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }
    static boolean isTrackedPlayer(String name) { return trackedPlayer(name) != null; }
    static List<Bot> all() { return bots.values().stream().sorted(Comparator.comparingInt(Bot::port)).toList(); }
    static List<Bot> targets() {
        var target = target();
        return all().stream().filter(bot -> target.includes(bot.port, bot.name)).toList();
    }
    static void refresh() { nextPoll=0; }
    static void tick() {
        if (Boolean.getBoolean("botfury.managedHttp")) return;
        if (LocalFormation.here()==null || System.currentTimeMillis()<nextPoll) return;
        nextPoll=System.currentTimeMillis()+500;
        int base=Integer.getInteger("botfury.formationBasePort",8765), count=Math.min(32,Math.max(0,Integer.getInteger("botfury.formationPortCount",10)));
        for(int i=0;i<count;i++){int port=base+i;if(port>=1024&&port<=65535&&inflight.add(port))poll(port);}
        if(System.currentTimeMillis()-rosterAt>3000){
            rosterAt=System.currentTimeMillis();
            sendDefenses();
            var names=all().stream().filter(Bot::sameWorld).map(Bot::name).toList();
            BotCollisionPolicy.replace(names);
            for(Bot bot:all())if(bot.fresh()&&bot.connected&&bot.sameWorld()&&bot.build.startsWith("0.3"))
                command(bot.port,"collision:"+bot.server+"|"+bot.dimension+"|"+String.join(",",names),false);
        }
    }
    static void sendDefenses(){
        var here=LocalFormation.here();if(here==null)return;
        String names=String.join(",",all().stream().filter(Bot::sameWorld).map(Bot::name).toList());
        String command="defense:"+(BotUiConfig.defense?"on":"off")+"|"+here.server()+"|"+here.dimension()+"|"+here.name()+"|"+names;
        for(Bot bot:all())if(bot.fresh()&&bot.connected&&bot.sameWorld()&&bot.defenseCapable)command(bot.port,command,false);
    }
    private static HttpRequest.Builder request(int port,String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofMillis(700));}
    private static void poll(int port){
        var a=HTTP.sendAsync(request(port,"/status").GET().build(),HttpResponse.BodyHandlers.ofString());
        var b=HTTP.sendAsync(request(port,"/local-formation").GET().build(),HttpResponse.BodyHandlers.ofString());
        a.thenCombine(b,(status,peer)->{
            if(status.statusCode()!=200||peer.statusCode()!=200)throw new IllegalArgumentException("Not a managed BotFury client");
            JsonObject s=JsonParser.parseString(status.body()).getAsJsonObject();
            var element=JsonParser.parseString(peer.body());JsonObject p=element.isJsonObject()?element.getAsJsonObject():null;
            if(p!=null&&!bool(p,"managed",false)){offline(port);return null;}
            String name=text(s,"offlineUsername",text(s,"player",""));
            if(!name.matches("[A-Za-z0-9_]{1,16}")||number(s,"protocol",0)<3)return null;
            Bot old=bots.get(port);long now=System.currentTimeMillis();
            boolean connected=bool(s,"connected",false), position=connected&&p!=null;
            double x=position?number(p,"x",0):old==null?0:old.x,y=position?number(p,"y",0):old==null?0:old.y,z=position?number(p,"z",0):old==null?0:old.z;
            if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>30000000||Math.abs(z)>30000000)return null;
            Bot value=new Bot(port,name,true,connected,bool(s,"alive",true),x,y,z,(float)number(s,"health",0),(float)number(s,"maxHealth",20),
                position?text(p,"server",""):old==null?"":old.server,position?text(p,"dimension",""):old==null?"":old.dimension,
                text(s,"formation","idle"),text(s,"movementState",""),text(s,"inventoryTask",""),text(s,"miningTask",""),text(s,"build","0.2"),
                p!=null&&bool(p,"baritone",false),bool(s,"taskDefenseCapable",false),bool(s,"scaffoldCleanupCapable",false),bool(s,"scaffoldBatchCapable",false),s.has("items")?itemCounts(s):connected?null:old==null?null:old.items,
                position ? route(s) : List.of(),bool(s,"inventoryCoordinates",false),now,position?now:old==null?0:old.positionAt,text(s,"lastEventLevel","info").equals("info")?"":text(s,"lastEvent",""), text(s,"taskKind",""),
                bool(s,"fuelChoicesCapable",false));
            bots.put(port,value);return null;
        }).whenComplete((ignored,error)->{if(error!=null)offline(port);inflight.remove(port);});
    }
    private static List<BlockPos> route(JsonObject status) {
        if (!(status.get("route") instanceof JsonArray array) || array.size() > BaritoneClient.PATH_LIMIT) return List.of();
        var points = new ArrayList<BlockPos>(array.size());
        for (var entry : array) {
            try {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isNumber()) return List.of();
                points.add(BlockPos.fromLong(entry.getAsLong()));
            } catch (NumberFormatException invalid) {
                return List.of();
            }
        }
        return List.copyOf(points);
    }

    static CompletableFuture<Vec3d> recipientLocation(BotInventory.Destination destination, String name) {
        return HTTP.sendAsync(request(destination.controller(), "/status").GET().build(), HttpResponse.BodyHandlers.ofString())
                .handle((response, failure) -> {
                    if (failure != null || response.statusCode() != 200) return null;
                    try {
                        var status = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (!name.equalsIgnoreCase(text(status, "player", "")) || bool(status, "managed", true)
                                || !bool(status, "connected", false) || !bool(status, "alive", false)
                                || !destination.server().equals(text(status, "server", ""))
                                || !destination.dimension().equals(text(status, "dimension", ""))) return null;
                        var point = new Vec3d(number(status, "x", Double.NaN), number(status, "y", Double.NaN), number(status, "z", Double.NaN));
                        return BotInventory.validLocation(point) ? point : null;
                    } catch (RuntimeException invalid) {
                        return null;
                    }
                });
    }

    private static void offline(int port){bots.computeIfPresent(port,(p,b)->new Bot(b.port,b.name,false,b.connected,b.alive,b.x,b.y,b.z,b.health,b.maxHealth,b.server,b.dimension,b.mode,b.state,b.inventory,b.mining,b.build,b.baritone,b.defenseCapable,b.scaffoldCleanupCapable,b.scaffoldBatchCapable,b.items,List.of(),b.inventoryCoordinates,b.contactAt,b.positionAt,"Unreachable",b.task,b.fuelChoicesCapable));}
    static void command(int port,String command){command(port,command,true);}
    private static void command(int port,String command,boolean report){
        Bot bot=bots.get(port);if(bot==null||!bot.fresh()){if(report)BotEvents.add("warn","bots","Bot is offline");return;}
        long seq=sequence.incrementAndGet();boolean urgent=command.equals("control:stop");
        commands.compute(port,(key,prior)->{
            var start=prior==null||urgent?CompletableFuture.<Void>completedFuture(null):prior;
            return start.handle((r,e)->null).thenCompose(ignored->HTTP.sendAsync(request(port,"/command?cmd="+URLEncoder.encode(command,StandardCharsets.UTF_8)+"&session="+SESSION+"&sequence="+seq).GET().build(),HttpResponse.BodyHandlers.ofString()))
                .handle((response,error)->{if(report)BotEvents.add(error!=null||response.statusCode()!=200?"warn":"info","bots",bot.name+": "+(error==null?response.body():"Command delivery failed"));refresh();return null;});
        });
    }
    static int sendSelected(String command){return sendSelected(command, "");}
    static int sendSelected(String command,String recipientCoordinates){
        var targets=targets();if(targets.isEmpty()){BotEvents.add("warn","bots","No tracked bots selected; open Bots and wait for discovery");return 0;}
        int sent=0;
        for(Bot bot:targets())if(bot.fresh()&&bot.connected&&bot.alive){
            if(command.startsWith("mining:")&&!bot.build.startsWith("0.3")){BotEvents.add("warn","mining",bot.name+": update this bot for coordinated mining");continue;}
            LocalFormation.manage(bot.port,"task");
            command(bot.port,bot.inventoryCoordinates ? command + recipientCoordinates : command);
            sent++;
        }
        return sent;
    }
    static void manageSelected(String action){for(Bot bot:targets())if(bot.fresh())LocalFormation.manage(bot.port,action);}
    private static Map<String,Integer> itemCounts(JsonObject status){
        try {
            var value=status.getAsJsonObject("items");
            if(value.size()>128)return null;
            var counts=new HashMap<String,Integer>();
            for(var entry:value.entrySet()){
                int count=entry.getValue().getAsInt();
                if(!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||count<0||count>1000000)return null;
                counts.put(entry.getKey(),count);
            }
            return Map.copyOf(counts);
        }catch(RuntimeException error){return null;}
    }
    private static String text(JsonObject o,String key,String fallback){try{return o.get(key).getAsString();}catch(Exception e){return fallback;}}
    private static double number(JsonObject o,String key,double fallback){try{return o.get(key).getAsDouble();}catch(Exception e){return fallback;}}
    private static boolean bool(JsonObject o,String key,boolean fallback){try{return o.get(key).getAsBoolean();}catch(Exception e){return fallback;}}
}
