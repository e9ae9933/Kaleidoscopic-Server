package org.aliceincradle.sync;

public abstract class Packet {
    public String clazz; // 对应 C# 的 clazz 属性
    
    public Packet() {
        this.clazz = this.getClass().getSimpleName();
    }
    
    public static abstract class ClientBoundPacket extends Packet {
        public long token;
    }
    
    public static abstract class ServerBoundPacket extends Packet {
    }
    
    // Client -> Server
    public static class ClientBoundVlanChangedPacket extends ClientBoundPacket {
        public int vlan;
    }
    
    public static class ClientBoundPlayerSyncPacket extends ClientBoundPacket {
        public PlayerInfo info;
    }
    
    public static class ClientBoundEnemySyncPacket extends ClientBoundPacket {
        public EnemyInfo[] enemyInfos;
    }
    
    // Server -> Client
    public static class ServerBoundOtherPlayersSyncPacket extends ServerBoundPacket {
        public PlayerInfo[] otherPlayers;
        
        public ServerBoundOtherPlayersSyncPacket(PlayerInfo[] players) {
            super();
            this.otherPlayers = players;
        }
    }
    
    
    public static class ServerBoundOtherEnemiesSyncPacket extends ServerBoundPacket {
        public EnemyInfo[] otherEnemies;
        public long[] remoteTokens;
        
        public ServerBoundOtherEnemiesSyncPacket(EnemyInfo[] otherEnemies, long[] remoteTokens) {
            super();
            this.otherEnemies = otherEnemies;
            this.remoteTokens = remoteTokens;
        }
    }
    
    public static class ClientBoundDamagePacket extends ClientBoundPacket {
        public long targetToken;
        public String targetKey;
        public int damage;
        public int attrInt;
        public float knockback_len;
        public float knockback_ratio_p;
        public float knockback_ratio_t;
        public boolean _apply_knockback_current;
        public int mgkindInt;
        public boolean casterIsPlayer;
    }
    
    public static class ServerBoundDamagePacket extends ServerBoundPacket {
        public ClientBoundDamagePacket original;
        
        public ServerBoundDamagePacket(ClientBoundDamagePacket original) {
            super();
            this.original = original;
        }
    }
    
    public static class ServerBoundPingPacket extends ServerBoundPacket {
        public long time;
        
        public ServerBoundPingPacket(long time) {
            super();
            this.time = time;
        }
    }
    
    public static class ClientBoundPongPacket extends ClientBoundPacket {
        public long time;
    }
    
    public static class ClientBoundDmgCounterPacket extends ClientBoundPacket {
        public boolean isPlayer;
        public float x;
        public float y;
        public int dcInt;
        public int damage;
        public int mpDamage;
    }
    
    public static class ServerBoundDmgCounterPacket extends ServerBoundPacket {
        public ClientBoundDmgCounterPacket original;
        
        public ServerBoundDmgCounterPacket(ClientBoundDmgCounterPacket original) {
            super();
            this.original = original;
        }
    }
    
    public static class ServerBoundProtectedModePacket extends ServerBoundPacket {
        public String payload;
        
        public ServerBoundProtectedModePacket(String payload) {
            super();
            this.payload = payload;
        }
    }
    
    public static class ClientBoundProtectedModePacket extends ClientBoundPacket {
        public String payload;
    }
    
    public static class ClientBoundBboxPacket extends ClientBoundPacket {
        public BoundingBox[] bboxes;
    }
    
    public static class ServerBoundBboxPacket extends ServerBoundPacket {
        public BoundingBox[] bboxes;
        
        public ServerBoundBboxPacket(BoundingBox[] bboxes) {
            super();
            this.bboxes = bboxes;
        }
    }
}