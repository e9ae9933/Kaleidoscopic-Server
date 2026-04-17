package org.aliceincradle.sync;

// package org.aliceincradle.server.ui;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;

public class ServerGUI extends JFrame {
    
    private final JTextArea logArea;
    private final DefaultTableModel playerTableModel;
    private final MemoryChartPanel memoryChart;
    
    public ServerGUI() {
        // 1. 窗口基础设置
        setTitle("Kaleidoscopic-Server 控制台");
        setSize(1000, 650);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        
        // 2. 左侧面板 (监控与玩家列表)
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setPreferredSize(new Dimension(300, 0));
        leftPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 5));
        
        // 2.1 内存折线图 (顶部)
        memoryChart = new MemoryChartPanel();
        memoryChart.setPreferredSize(new Dimension(300, 150));
        leftPanel.add(memoryChart, BorderLayout.NORTH);
        
        // 2.2 玩家列表 (底部)
        // 关于延迟：如果暂不动底层包代码，我们可以在服务端收到任意包时记录时间戳，计算差值作为“伪Ping”
        String[] columnNames = {"玩家名称", "Ping (ms)"};
        playerTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; } // 禁止手动编辑
        };
        JTable playerTable = new JTable(playerTableModel);
        playerTable.setFillsViewportHeight(true);
        JScrollPane tableScroll = new JScrollPane(playerTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("在线玩家"));
        leftPanel.add(tableScroll, BorderLayout.CENTER);
        
        // 3. 右侧面板 (日志区)
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBorder(BorderFactory.createEmptyBorder(10, 5, 10, 10));
        
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14)); // 极客专属等宽字体
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("系统日志"));
        rightPanel.add(logScroll, BorderLayout.CENTER);
        
        // 4. 底部命令输入框
        JTextField commandField = new JTextField();
        commandField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        commandField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(0, 10, 10, 10),
            BorderFactory.createTitledBorder("输入指令 (回车执行)")
        ));
        commandField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    String cmd = commandField.getText().trim();
                    if (!cmd.isEmpty()) {
                        handleCommand(cmd);
                        commandField.setText("");
                    }
                }
            }
        });
        
        // 5. 组装主界面
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        splitPane.setDividerLocation(320);
        splitPane.setContinuousLayout(true);
        
        add(splitPane, BorderLayout.CENTER);
        add(commandField, BorderLayout.SOUTH);
        
        // 模拟启动信息
        log("[System] Kaleidoscopic-Server GUI 初始化完成...");
        log("[System] 正在等待网络层接入...");
    }
    
    // --- 核心交互方法，供你的 SyncServer 调用 ---
    
    public void log(String message) {
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + time + "] " + message + "\n");
            // 自动滚动到底部
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
    
    public void updatePlayerList(Object[][] players) {
        SwingUtilities.invokeLater(() -> {
            playerTableModel.setRowCount(0); // 清空旧数据
            for (Object[] player : players) {
                playerTableModel.addRow(player);
            }
        });
    }
    
    private void handleCommand(String command) {
        log("> " + command);
        // TODO: 这里接入你的服务端命令解析逻辑 (例如: "stop", "vlan list")
        if ("stop".equalsIgnoreCase(command)) {
            log("[Warning] 正在保存数据并关闭服务器...");
            System.exit(0);
        }
    }
    
    // --- 纯手工高刷内存折线图组件 ---
    private static class MemoryChartPanel extends JPanel {
        private final LinkedList<Long> history = new LinkedList<>();
        private final int MAX_HISTORY = 60; // 保存60个采样点
        private long maxMemory = Runtime.getRuntime().maxMemory();
        
        public MemoryChartPanel() {
            setBorder(BorderFactory.createTitledBorder("内存监控 (Heap)"));
            // 启动定时器，每秒采样一次内存
            new Timer(1000, e -> updateMemory()).start();
        }
        
        private void updateMemory() {
            long total = Runtime.getRuntime().totalMemory();
            long free = Runtime.getRuntime().freeMemory();
            long used = total - free;
            
            history.addLast(used);
            if (history.size() > MAX_HISTORY) {
                history.removeFirst();
            }
            repaint(); // 触发重绘
        }
        
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            int width = getWidth();
            int height = getHeight();
            int pad = 20;
            
            // 画一点炫酷的网格背景
            g2.setColor(new Color(60, 63, 65));
            for (int i = 0; i < width; i += 20) g2.drawLine(i, pad, i, height - pad);
            for (int i = pad; i < height; i += 20) g2.drawLine(0, i, width, i);
            
            if (history.size() < 2) return;
            
            // 动态计算当前视图中的最大内存峰值，让折线图自适应高度
            long currentPeak = history.stream().max(Long::compareTo).orElse(maxMemory);
            // 留出 20% 的顶部空间
            currentPeak = (long) (currentPeak * 1.2);
            
            // 画折线
            g2.setColor(new Color(88, 172, 255)); // 赛博蓝
            g2.setStroke(new BasicStroke(2f));
            
            int xStep = (width - 10) / MAX_HISTORY;
            int x = 5 + (MAX_HISTORY - history.size()) * xStep;
            
            for (int i = 0; i < history.size() - 1; i++) {
                int y1 = height - pad - (int) ((history.get(i) * (height - 2 * pad)) / currentPeak);
                int y2 = height - pad - (int) ((history.get(i + 1) * (height - 2 * pad)) / currentPeak);
                g2.drawLine(x, y1, x + xStep, y2);
                x += xStep;
            }
            
            // 显示当前占用文本
            long currentUsed = history.getLast();
            g2.setColor(Color.WHITE);
            g2.drawString(String.format("Used: %d MB / Max: %d MB",
                                        currentUsed / (1024 * 1024),
                                        maxMemory / (1024 * 1024)), 10, height - 5);
        }
    }
    
    // 独立测试运行入口
    public static void main(String[] args) {
        // 必须在创建任何 UI 组件之前初始化暗黑主题
        FlatDarkLaf.setup();
        
        SwingUtilities.invokeLater(() -> {
            ServerGUI gui = new ServerGUI();
            gui.setVisible(true);
            
            // 测试添加几个假玩家
            gui.updatePlayerList(new Object[][]{
                {"Alice", "12ms"},
                {"Bob", "45ms"},
                {"Hacker_01", "999ms"}
            });
        });
    }
}