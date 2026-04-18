package org.aliceincradle.sync;

public abstract class Packet {
    public String clazz; // 对应 C# 的 clazz 属性
    
    public Packet() {
        this.clazz = this.getClass().getSimpleName();
    }
    
    // Client -> Server
    public static class ClientBoundVlanChangedPacket extends Packet {
        public int vlan;
    }
    
    public static class ClientBoundPlayerSyncPacket extends Packet {
        public PlayerInfo info;
    }
    
    public static class ClientBoundEnemySyncPacket extends Packet {
        public EnemyInfo[] enemyInfos;
    }
    
    // Server -> Client
    public static class ServerBoundOtherPlayersSyncPacket extends Packet {
        public PlayerInfo[] otherPlayers;
        public ServerBoundOtherPlayersSyncPacket(PlayerInfo[] players) {
            super();
            this.otherPlayers = players;
        }
    }
    
    
    public static class ServerBoundOtherEnemiesSyncPacket extends Packet {
        public EnemyInfo[] otherEnemies;
        public ServerBoundOtherEnemiesSyncPacket(EnemyInfo[] otherEnemies) {
            super();
            this.otherEnemies = otherEnemies;
        }
    }
}