package org.aliceincradle.sync;

import java.util.LinkedList;
import java.util.Queue;

public class PlayerState {
    public long vlan = 0;
    public long token = 0;
    public PlayerInfo playerInfo = null;
    public EnemyInfo[] enemyInfos = null;
    public long ping = -1;
    public long lastPing = 0;
    public boolean isProtectedMode = false;
    public transient final Queue<Packet> pendingPackets = new LinkedList<>();
    BoundingBox[] bboxes = null;
}
