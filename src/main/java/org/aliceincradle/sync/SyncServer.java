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

import javax.swing.SwingUtilities;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

public class SyncServer extends WebSocketServer {
    
    private static final Logger log = LoggerFactory.getLogger(SyncServer.class);
    private final Gson gson = new Gson();
    
    private final Map<WebSocket, PlayerState> clientStates = new ConcurrentHashMap<>();
    
    // 注入 GUI 实例引用
    private final ServerGUI gui;
    
    public SyncServer(int port, ServerGUI gui) {
        super(new InetSocketAddress(port));
        this.gui = gui;
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
        try {
            JsonObject jsonObject = JsonParser.parseString(message).getAsJsonObject();
            String clazz = jsonObject.get("clazz").getAsString();
            
            PlayerState state = clientStates.get(conn);
            if (state == null) return;
            
            switch (clazz) {
                case "ClientBoundVlanChangedPacket":
                    Packet.ClientBoundVlanChangedPacket vlanPkt = gson.fromJson(message, Packet.ClientBoundVlanChangedPacket.class);
                    state.vlan = vlanPkt.vlan;
                    break;
                
                case "ClientBoundPlayerSyncPacket":
                    Packet.ClientBoundPlayerSyncPacket syncPkt = gson.fromJson(message, Packet.ClientBoundPlayerSyncPacket.class);
                    if (syncPkt.info != null) {
                        state.playerInfo = syncPkt.info;
                    }
                    break;
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
        scheduler.scheduleAtFixedRate(this::broadcastSync, 0, 16, TimeUnit.MILLISECONDS);
        
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
            String name = (state.playerInfo != null && state.playerInfo.playerName != null)
                          ? state.playerInfo.playerName : "连接中...";
            
            // 【修改点】：直接将 Ping 写死为 -1 ms
            if (index < rowData.length) {
                rowData[index++] = new Object[]{name, "-1 ms"};
            }
        }
        
        // 提交给 UI 更新
        gui.updatePlayerList(rowData);
    }
    
    private void broadcastSync() {
        Map<Long, List<WebSocket>> vlanRooms = new HashMap<>();
        
        for (Map.Entry<WebSocket, PlayerState> entry : clientStates.entrySet()) {
            WebSocket ws = entry.getKey();
            PlayerState state = entry.getValue();
            if (state.playerInfo == null) continue;
            vlanRooms.computeIfAbsent(state.vlan, k -> new ArrayList<>()).add(ws);
        }
        
        for (List<WebSocket> roomClients : vlanRooms.values()) {
            // if (roomClients.size() < 2) continue; // 没人或单机无需广播
            
            List<PlayerInfo> roomInfos = new ArrayList<>();
            for (WebSocket ws : roomClients) {
                roomInfos.add(clientStates.get(ws).playerInfo);
            }
            
            for (WebSocket client : roomClients) {
                if (!client.isOpen() || client.hasBufferedData()) continue;
                
                PlayerInfo selfInfo = clientStates.get(client).playerInfo;
                PlayerInfo[] others = roomInfos.stream()
                    .filter(info -> info != selfInfo)
                    .toArray(PlayerInfo[]::new);
                
                if (others.length > 0) {
                    Packet.ServerBoundOtherPlayersSyncPacket outPkt = new Packet.ServerBoundOtherPlayersSyncPacket(others);
                    client.send(gson.toJson(outPkt));
                }
            }
        }
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
                    int port = 25560;
                    SyncServer server = new SyncServer(port, gui);
                    server.start();
                } catch (Exception e) {
                    gui.log("[Fatal] 服务端启动失败: " + e.getMessage());
                }
            }, "Server-Boot-Thread").start();
        });
    }
}