package org.aliceincradle.sync;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

public class SyncServer extends WebSocketServer {
    
    private static final Logger log = LoggerFactory.getLogger(SyncServer.class);
    private final Gson gson = new Gson();
    
    // 【核心重构】：用单一的 Map 扁平化管理所有玩家的综合状态
    private final Map<WebSocket, PlayerState> clientStates = new ConcurrentHashMap<>();
    
    public SyncServer(int port) {
        super(new InetSocketAddress(port));
    }
    
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[+] New Connection: " + conn.getRemoteSocketAddress());
        // 玩家连接时，初始化一个空的 PlayerState
        clientStates.put(conn, new PlayerState());
    }
    
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        // 清理极其简单，移除一个 key 即可
        clientStates.remove(conn);
        System.out.println("[-] Disconnected: " + conn.getRemoteSocketAddress());
    }
    
    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject jsonObject = JsonParser.parseString(message).getAsJsonObject();
            String clazz = jsonObject.get("clazz").getAsString();
            
            // 获取该玩家的综合状态对象
            PlayerState state = clientStates.get(conn);
            if (state == null) return;
            
            switch (clazz) {
                case "ClientBoundVlanChangedPacket":
                    Packet.ClientBoundVlanChangedPacket vlanPkt = gson.fromJson(message, Packet.ClientBoundVlanChangedPacket.class);
                    state.vlan = vlanPkt.vlan; // 更新 VLAN
                    break;
                
                case "ClientBoundPlayerSyncPacket":
                    Packet.ClientBoundPlayerSyncPacket syncPkt = gson.fromJson(message, Packet.ClientBoundPlayerSyncPacket.class);
                    if (syncPkt.info != null) {
                        state.playerInfo = syncPkt.info; // 更新坐标和动画等信息
                    }
                    break;
            }
        } catch (Exception e) {
            System.err.println("[!] Error parsing message: " + e.getMessage());
        }
    }
    
    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
    }
    
    @Override
    public void onStart() {
        System.out.println("[*] Sync Server started on port 25560");
        this.setTcpNoDelay(true);
        // 启动 60Hz 广播任务 (约 16ms 间隔)
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::broadcastSync, 0, 50, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 核心广播逻辑：按 VLAN 分组并发送其他玩家信息
     */
    private void broadcastSync() {
        // 1. 将所有玩家按 VLAN 快速分组 (房间归类)
        Map<Long, List<WebSocket>> vlanRooms = new HashMap<>();
        
        for (Map.Entry<WebSocket, PlayerState> entry : clientStates.entrySet()) {
            WebSocket ws = entry.getKey();
            PlayerState state = entry.getValue();
            
            // 如果玩家还没发过同步包（playerInfo 为空），暂时不加入广播房间
            if (state.playerInfo == null) continue;
            
            vlanRooms.computeIfAbsent(state.vlan, k -> new ArrayList<>()).add(ws);
        }
        
        // 2. 遍历每个房间进行广播
        for (List<WebSocket> roomClients : vlanRooms.values()) {
            
            // 提取该房间内所有的 PlayerInfo
            List<PlayerInfo> roomInfos = new ArrayList<>();
            for (WebSocket ws : roomClients) {
                roomInfos.add(clientStates.get(ws).playerInfo);
            }
            
            // 给房间内的每个玩家发送排除自己后的其他人信息
            for (WebSocket client : roomClients) {
                if (!client.isOpen()) continue;
                // 【核心修复】：如果这个客户端的接收队列已经堵车了，直接丢弃这一帧的广播！
                // 动作游戏宁可丢帧，也绝不容忍积压延迟！
                if (client.hasBufferedData()) continue;
                PlayerInfo selfInfo = clientStates.get(client).playerInfo;
                
                // 过滤掉玩家自己
                PlayerInfo[] others = roomInfos.stream()
                    .filter(info -> info != selfInfo)
                    .toArray(PlayerInfo[]::new);
                
                if (others.length > 0) {
                    Packet.ServerBoundOtherPlayersSyncPacket outPkt = new Packet.ServerBoundOtherPlayersSyncPacket(others);
                    // log.info("sending packet");
                    client.send(gson.toJson(outPkt));
                }
            }
        }
    }
    
    public static void main(String[] args) {
        int port = 25560;
        SyncServer server = new SyncServer(port);
        server.start();
    }
}