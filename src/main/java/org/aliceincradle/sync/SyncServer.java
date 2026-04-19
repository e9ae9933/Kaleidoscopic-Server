package org.aliceincradle.sync;

import com.formdev.flatlaf.FlatDarkLaf;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

public class SyncServer extends WebSocketServer {
    
    private static final Logger log = LoggerFactory.getLogger(SyncServer.class);
    private final Gson gson = new Gson();
    private final Map<WebSocket, PlayerState> clientStates = new ConcurrentHashMap<>();
    private final Queue<SingleMessage> messageQueue = new ConcurrentLinkedQueue<>();
    // 注入 GUI 实例引用
    private final ServerGUI gui;
    
    public SyncServer(int port, ServerGUI gui) {
        super(new InetSocketAddress(port));
        this.gui = gui;
    }
    
    public static void main(String[] args) {
        // 1. 优先初始化扁平化暗黑主题
        FlatDarkLaf.setup();
        
        // 2. 必须在 EDT 线程中创建和显示 GUI
        SwingUtilities.invokeLater(() -> {
            ServerGUI gui = new ServerGUI();
            gui.setVisible(true);
            
            // 3. 在 GUI 准备完毕后，异步启动 WebSocket 服务器，防止阻塞主窗口线程
            new Thread(() -> {
                try {
                    int port = 25561;
                    SyncServer server = new SyncServer(port, gui);
                    server.start();
                } catch (Exception e) {
                    gui.log("[Fatal] 服务端启动失败: " + e.getMessage());
                }
            }, "Server-Boot-Thread").start();
        });
    }
    
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String address = conn.getRemoteSocketAddress().toString();
        gui.log("[+] 玩家建立连接: " + address);
        
        PlayerState state = new PlayerState();
        clientStates.put(conn, state);
    }
    
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clientStates.remove(conn);
        gui.log("[-] 玩家断开连接: " + conn.getRemoteSocketAddress());
    }
    
    @Override
    public void onMessage(WebSocket conn, String message) {
        messageQueue.add(new SingleMessage(conn, message));
        // onMessage0(conn, message);
    }
    
    // ensure on main thread
    private void onMessage0(WebSocket conn, String message) {
        try {
            JsonObject jsonObject = JsonParser.parseString(message).getAsJsonObject();
            String clazz = jsonObject.get("clazz").getAsString();
            
            PlayerState state = clientStates.get(conn);
            if (state == null)
                return;
            
            // 【新增】提取并保存该连接的唯一标识 token
            if (jsonObject.has("token")) {
                state.token = jsonObject.get("token").getAsLong();
            }
            
            switch (clazz) {
                case "ClientBoundVlanChangedPacket" -> {
                    Packet.ClientBoundVlanChangedPacket vlanPkt = gson.fromJson(message,
                                                                                Packet.ClientBoundVlanChangedPacket.class);
                    state.vlan = vlanPkt.vlan;
                }
                
                case "ClientBoundPlayerSyncPacket" -> {
                    Packet.ClientBoundPlayerSyncPacket syncPkt = gson.fromJson(message,
                                                                               Packet.ClientBoundPlayerSyncPacket.class);
                    if (syncPkt.info != null) {
                        state.playerInfo = syncPkt.info;
                    }
                }
                
                case "ClientBoundEnemySyncPacket" -> {
                    Packet.ClientBoundEnemySyncPacket esync = gson.fromJson(message,
                                                                            Packet.ClientBoundEnemySyncPacket.class);
                    if (esync != null) {
                        state.enemyInfos = esync.enemyInfos;
                    }
                }
                
                case "ClientBoundDamagePacket" -> {
                    Packet.ClientBoundDamagePacket cp = gson.fromJson(message,
                                                                      Packet.ClientBoundDamagePacket.class);
                    if (cp != null) {
                        log.info(
                            "attack at " + cp.token + " " + cp.targetToken + " " + cp.targetKey);
                        for (Map.Entry<WebSocket, PlayerState> entry : clientStates.entrySet()) {
                            if (entry.getValue().token == cp.targetToken) {
                                WebSocket targetSocket = entry.getKey();
                                send(targetSocket, new Packet.ServerBoundDamagePacket(cp));
                                break;
                            }
                        }
                    }
                }
                
                case "ClientBoundPongPacket" -> {
                    Packet.ClientBoundPongPacket p;
                    p = gson.fromJson(message, Packet.ClientBoundPongPacket.class);
                    if (p != null) {
                        state.ping = System.currentTimeMillis() - p.time;
                    }
                }
                
                case "ClientBoundBboxPacket" -> {
                    Packet.ClientBoundBboxPacket p;
                    p = gson.fromJson(message, Packet.ClientBoundBboxPacket.class);
                    state.bboxes = p.bboxes;
                }
                
                case "ClientBoundDmgCounterPacket" -> {
                    Packet.ClientBoundDmgCounterPacket dp = gson.fromJson(message,
                                                                          Packet.ClientBoundDmgCounterPacket.class);
                    if (dp != null) {
                        long senderVlan = state.vlan;
                        
                        Packet.ServerBoundDmgCounterPacket serverPacket =
                            new Packet.ServerBoundDmgCounterPacket(dp);
                        
                        for (Map.Entry<WebSocket, PlayerState> entry : clientStates.entrySet()) {
                            WebSocket targetSocket = entry.getKey();
                            PlayerState targetState = entry.getValue();
                            
                            if (targetState.vlan == senderVlan && targetSocket != conn &&
                                targetSocket.isOpen()) {
                                send(targetSocket, serverPacket);
                            }
                        }
                    }
                }
                case "ClientBoundProtectedModePacket" -> {
                    // 1. 自动切换开关：只要收到这个包，以后发给这个客户端的数据全走压缩队列！
                    state.isProtectedMode = true;
                    
                    Packet.ClientBoundProtectedModePacket compressedPkt = gson.fromJson(message,
                                                                                        Packet.ClientBoundProtectedModePacket.class);
                    
                    if (compressedPkt != null && compressedPkt.payload != null) {
                        try {
                            // 2. Base64 解码
                            byte[] compressedBytes = Base64.getDecoder()
                                .decode(compressedPkt.payload);
                            
                            // 3. GZIP 解压
                            try (java.io.ByteArrayInputStream bis =
                                     new java.io.ByteArrayInputStream(compressedBytes); java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(bis); java.io.InputStreamReader reader = new java.io.InputStreamReader(gzip, StandardCharsets.UTF_8)) {
                                
                                // 解析出原版的 JSON 数组 (假设客户端发来的是一个 List<Packet> 的 JSON)
                                com.google.gson.JsonArray jsonArray =
                                    com.google.gson.JsonParser.parseReader(reader)
                                    .getAsJsonArray();
                                
                                // 4. 递归处理：把解压出来的每一个小包，重新丢给 onMessage0 处理
                                for (com.google.gson.JsonElement element : jsonArray) {
                                    onMessage0(conn, element.toString());
                                }
                            }
                        } catch (Exception e) {
                            gui.log("[!] 客户端压缩包解压失败: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            gui.log("[!] 异常包解析失败: " + e.getMessage());
        }
    }
    
    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            gui.log("[!] 发生网络错误 (" + conn.getRemoteSocketAddress() + "): " + ex.getMessage());
        } else {
            gui.log("[!] 服务器底层错误: " + ex.getMessage());
        }
    }
    
    @Override
    public void onStart() {
        gui.log("[*] Kaleidoscopic-Server 网络层已启动，端口: " + getPort());
        this.setTcpNoDelay(true);
        
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2); // 扩展为核心线程池
        
        // 任务 1：高频广播逻辑 (60Hz -> 16ms 间隔)
        scheduler.scheduleAtFixedRate(this::tick, 0, 100, TimeUnit.MILLISECONDS);
        
        // 任务 2：低频 UI 刷新逻辑 (2Hz -> 500ms 间隔)
        // 界面不需要 60Hz 刷新，半秒更新一次足够了，减轻 EDT 负担
        scheduler.scheduleAtFixedRate(this::updateGuiPlayerList, 0, 500, TimeUnit.MILLISECONDS);
    }
    
    /**
     * UI 刷新逻辑：投递给 Swing EDT，Ping 暂时写死为 -1
     */
    private void updateGuiPlayerList() {
        // 预先准备好数据结构
        Object[][] rowData = new Object[clientStates.size()][2];
        int index = 0;
        
        // 迭代 ConcurrentHashMap 是弱一致性安全的
        for (PlayerState state : clientStates.values()) {
            String name = (state.playerInfo != null && state.playerInfo.playerName != null) ?
                          state.playerInfo.playerName : "连接中...";
            
            // 【修改点】：直接将 Ping 写死为 -1 ms
            if (index < rowData.length) {
                rowData[index++] = new Object[]{name, "%d ms / %.3fs".formatted(state.ping,
                                                                                (System.currentTimeMillis() -
                                                                                 state.lastPing) /
                                                                                1000.0),
                    state.isProtectedMode};
            }
        }
        
        // 提交给 UI 更新
        gui.updatePlayerList(rowData);
    }
    
    private void tick() {
        while (!messageQueue.isEmpty()) {
            SingleMessage message = messageQueue.poll();
            try {
                onMessage0(message.webSocket, message.message);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }
        calcPing();
        broadcastSync();
        flushSockets();
    }
    
    private void calcPing() {
        clientStates.forEach((ws, state) -> {
            if (ws.isOpen() && state.lastPing + 5000 < System.currentTimeMillis()) {
                state.lastPing = System.currentTimeMillis();
                send(ws, new Packet.ServerBoundPingPacket(state.lastPing));
            }
        });
    }
    
    private void broadcastSync() {
        Map<Long, List<WebSocket>> vlanRooms = new HashMap<>();
        
        for (Map.Entry<WebSocket, PlayerState> entry : clientStates.entrySet()) {
            WebSocket ws = entry.getKey();
            PlayerState state = entry.getValue();
            if (state.playerInfo == null)
                continue;
            vlanRooms.computeIfAbsent(state.vlan, k -> new ArrayList<>()).add(ws);
        }
        // rewrite. what shit AI code
        vlanRooms.forEach((vlan, clients) -> {
            // broadcast playerInfo and enemyInfo
            // how?
            clients.forEach(client -> {
                if (client.hasBufferedData())
                    return;
                boolean loopback = 1 == 0;
                List<PlayerState> others = clients.stream().filter(c -> c.isOpen())
                    .filter(c -> loopback || c != client).map(c -> clientStates.get(c)).toList();
                // generate others
                PlayerInfo[] otherPlayers = others.stream().map(o -> o.playerInfo)
                    .filter(i -> i != null).toArray(PlayerInfo[]::new);
                send(client, new Packet.ServerBoundOtherPlayersSyncPacket(otherPlayers));
                
                // 2. 【核心修改】平铺怪物数组与对应的 Token 数组
                List<EnemyInfo> enemyList = new ArrayList<>();
                List<Long> tokenList = new ArrayList<>();
                
                for (PlayerState otherState : others) {
                    if (otherState.enemyInfos != null) {
                        for (EnemyInfo enemy : otherState.enemyInfos) {
                            enemyList.add(enemy);
                            // 关键：将怪物的拥有者的 token 塞入列表，与怪物形成一对一映射
                            tokenList.add(otherState.token);
                        }
                    }
                }
                
                EnemyInfo[] otherEnemies = enemyList.toArray(new EnemyInfo[0]);
                long[] remoteTokens = tokenList.stream().mapToLong(l -> l).toArray();
                
                // 3. 调用更新后的双参数构造函数发送
                send(client, new Packet.ServerBoundOtherEnemiesSyncPacket(otherEnemies,
                                                                          remoteTokens));
                
                BoundingBox[] otherBboxes = others.stream().map(s -> s.bboxes)
                    .filter(s -> s != null).flatMap(s -> Arrays.stream(s))
                    .toArray(BoundingBox[]::new);
                send(client, new Packet.ServerBoundBboxPacket(otherBboxes));
            });
        });
    }
    
    private void send(WebSocket ws, Packet p) {
        PlayerState state = clientStates.get(ws);
        if (!state.isProtectedMode)
            ws.send(gson.toJson(p));
        else {
            state.pendingPackets.add(p);
        }
    }
    
    private void flushSockets() {
        clientStates.forEach((ws, state) -> {
            if (!ws.isOpen() || state.pendingPackets.isEmpty()) {
                return;
            }
            List<Packet> pending = new ArrayList<>();
            while (!state.pendingPackets.isEmpty())
                pending.add(state.pendingPackets.poll());
            String rawJson = gson.toJson(pending);
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
                    gzip.write(rawJson.getBytes(StandardCharsets.UTF_8));
                    gzip.finish();
                }
                String payload = Base64.getEncoder().encodeToString(baos.toByteArray());
                Packet.ServerBoundProtectedModePacket compressedPacket =
                    new Packet.ServerBoundProtectedModePacket(payload);
                ws.send(gson.toJson(compressedPacket));
                
            } catch (Exception e) {
                log.error("保护模式压缩失败", e);
            }
        });
    }
    
    public record SingleMessage(WebSocket webSocket, String message) {
    }
}