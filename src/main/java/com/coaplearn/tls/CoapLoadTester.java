package com.coaplearn.tls;

import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.Option;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.tcp.netty.TcpClientConnector;
import org.eclipse.californium.elements.tcp.netty.TlsClientConnector;

import javax.net.ssl.SSLContext;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * CoapLoadTester — CoAP 图形化压测工具
 * <p>
 * 功能：
 * <ul>
 *     <li>传输模式：TLS/TCP（JKS 双向认证）或明文 TCP，可开关</li>
 *     <li>配置 JKS / 密码 / URI / 方法 / Content-Type / 载荷 / 并发 / 模式（按时长或每线程次数）/ 预热开关</li>
 *     <li>认证：自定义 CoAP Option 携带 Token，可开关</li>
 *     <li>每个请求的结果表格（方法、路径、响应码、延迟、状态、响应体），状态着色</li>
 *     <li>聚合统计（samples / avg / min / max / 吞吐 / 错误率 / p90 / p95 / p99）</li>
 *     <li>可变参数：载荷和 URI 路径支持占位符
 *         ${counter} ${timestamp} ${random:N} ${uuid} ${threadId} ${randStr:N} ${uniqStr:N}</li>
 *     <li>导出 CSV（聚合统计 + 请求明细，UTF-8 BOM）</li>
 * </ul>
 *
 */

/**
 * @author ndx
 */
public class CoapLoadTester extends JFrame {

    // ---- 输入组件 ----
    private final JCheckBox tlsCheck = new JCheckBox("启用 TLS 证书认证", true);
    private final JTextField jksField = new JTextField("server.jks", 20);
    private final JPasswordField pwdField = new JPasswordField("123456", 12);
    private final JTextField uriField = new JTextField("coaps+tcp://localhost:5684/hello", 30);
    private final JComboBox<String> methodBox = new JComboBox<>(new String[]{"GET", "POST"});
    private final JComboBox<String> contentTypeBox = new JComboBox<>(new String[]{"text/plain", "application/json"});
    private final JTextArea payloadArea = new JTextArea("{\"msg\":\"hello-${counter}\",\"id\":${random:1000}}", 3, 30);
    private final JSpinner threadsSpin = new JSpinner(new SpinnerNumberModel(10, 1, 1000, 1));
    private final JCheckBox warmupCheck = new JCheckBox("开启预热", true);
    private final ButtonGroup modeGroup = new ButtonGroup();
    private final JRadioButton modeDuration = new JRadioButton("按时长", true);
    private final JRadioButton modeRequests = new JRadioButton("每线程次数");
    private final JSpinner durationSpin = new JSpinner(new SpinnerNumberModel(10, 1, 3600, 1));
    private final JSpinner totalReqSpin = new JSpinner(new SpinnerNumberModel(100, 1, 1000000, 10));
    // ---- 认证（自定义 CoAP Option） ----
    private final JCheckBox authCheck = new JCheckBox("启用认证 (自定义 Option)");
    private final JTextField optionNumField = new JTextField("65000", 6);
    private final JTextField tokenField = new JTextField("your-token-here", 20);

    // ---- 输出组件 ----
    private final JTextArea logArea = new JTextArea(6, 60);
    private final DefaultTableModel resultModel = new DefaultTableModel(
            new Object[]{"#", "方法", "路径", "响应码", "延迟(ms)", "状态", "响应体"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable resultTable = new JTable(resultModel);
    private final JLabel aggLabel = new JLabel(" ");

    private final JButton startBtn = new JButton("开始压测");
    private final JButton stopBtn = new JButton("停止");
    private final JButton clearBtn = new JButton("清空结果");
    private final JButton exportBtn = new JButton("导出CSV");

    // ---- 运行状态 ----
    private volatile boolean running = false;
    private ExecutorService pool;

    // 结果收集：无界队列，UI 定时器批量取走
    private final ConcurrentLinkedQueue<RequestResult> resultQueue = new ConcurrentLinkedQueue<>();
    // 所有结果（用于导出，不受表格行数限制）
    private final java.util.List<RequestResult> allResults = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private static final int MAX_TABLE_ROWS = 500;

    // 聚合统计
    private final LongAdder totalReq = new LongAdder();
    private final LongAdder successReq = new LongAdder();
    private final LongAdder failReq = new LongAdder();
    private final AtomicLong minLatency = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxLatency = new AtomicLong(0);
    private final AtomicLong latencySum = new AtomicLong(0);
    // 用于百分位：保留最近的延迟样本
    private final ConcurrentLinkedQueue<Long> latencySamples = new ConcurrentLinkedQueue<>();
    private static final int MAX_LATENCY_SAMPLES = 100_000;
    private long measureStartMs;

    public CoapLoadTester() {
        super("CoapLoadTester - CoAP 压测工具");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(6, 6));

        JPanel top = new JPanel(new BorderLayout());
        top.add(buildForm(), BorderLayout.NORTH);
        updateModeState();
        updateAuthState();
        updateTlsState();

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        stopBtn.setEnabled(false);
        startBtn.addActionListener(e -> startTest());
        stopBtn.addActionListener(e -> stopTest());
        clearBtn.addActionListener(e -> clearResults());
        exportBtn.addActionListener(e -> exportResults());
        btnPanel.add(startBtn);
        btnPanel.add(stopBtn);
        btnPanel.add(clearBtn);
        btnPanel.add(exportBtn);
        top.add(btnPanel, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);

        // 中间：请求结果表格
        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        // 状态列着色：OK 绿，FAIL 红
        resultTable.getColumnModel().getColumn(5).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                           boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                String status = String.valueOf(t.getValueAt(row, 5));
                if ("FAIL".equals(status)) {
                    c.setForeground(Color.WHITE);
                    c.setBackground(new Color(200, 60, 60));
                } else if ("OK".equals(status)) {
                    c.setForeground(Color.WHITE);
                    c.setBackground(new Color(60, 150, 60));
                } else {
                    c.setForeground(t.getForeground());
                    c.setBackground(t.getBackground());
                }
                return c;
            }
        });
        resultTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        resultTable.getColumnModel().getColumn(3).setPreferredWidth(70);
        resultTable.getColumnModel().getColumn(4).setPreferredWidth(80);
        resultTable.getColumnModel().getColumn(5).setPreferredWidth(60);
        JScrollPane tableScroll = new JScrollPane(resultTable);
        tableScroll.setPreferredSize(new Dimension(800, 300));

        // 底部：日志 + 聚合统计
        JSplitPane bottom = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(logArea), new JScrollPane(aggLabel));
        bottom.setResizeWeight(0.6);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        aggLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        aggLabel.setVerticalAlignment(SwingConstants.TOP);
        aggLabel.setBorder(new EmptyBorder(4, 6, 4, 6));

        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, bottom);
        center.setResizeWeight(0.55);
        add(center, BorderLayout.CENTER);

        // UI 刷新定时器：每 200ms 从队列取结果更新表格和统计
        new Timer(200, e -> refreshUi()).start();

        pack();
        setSize(900, 700);
        setLocationRelativeTo(null);
    }

    private JPanel buildForm() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new EmptyBorder(6, 6, 6, 6));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 4, 2, 4);
        g.anchor = GridBagConstraints.WEST;

        // TLS 开关
        tlsCheck.addActionListener(e -> updateTlsState());
        g.gridx = 0; g.gridy = 0; g.gridwidth = 4; p.add(tlsCheck, g);
        g.gridwidth = 1;

        g.gridx = 0; g.gridy = 1; p.add(new JLabel("JKS:"), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1; p.add(jksField, g);
        JButton browse = new JButton("浏览...");
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(".");
            fc.setFileFilter(new FileNameExtensionFilter("JKS", "jks"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                jksField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        g.gridx = 2; g.fill = GridBagConstraints.NONE; g.weightx = 0; p.add(browse, g);

        g.gridx = 0; g.gridy = 2; p.add(new JLabel("密码:"), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; p.add(pwdField, g);

        g.gridx = 0; g.gridy = 3; p.add(new JLabel("URI:"), g);
        g.gridx = 1; g.gridwidth = 2; g.fill = GridBagConstraints.HORIZONTAL; p.add(uriField, g);
        g.gridwidth = 1;

        g.gridx = 0; g.gridy = 4; p.add(new JLabel("方法:"), g);
        g.gridx = 1; p.add(methodBox, g);
        g.gridx = 2; p.add(new JLabel("Content-Type:"), g);
        g.gridx = 3; p.add(contentTypeBox, g);

        g.gridx = 0; g.gridy = 5; p.add(new JLabel("载荷(POST):"), g);
        g.gridx = 1; g.gridwidth = 2; g.fill = GridBagConstraints.BOTH; g.weighty = 1;
        p.add(new JScrollPane(payloadArea), g);
        g.weighty = 0; g.gridwidth = 1; g.fill = GridBagConstraints.HORIZONTAL;

        g.gridx = 0; g.gridy = 6; p.add(new JLabel("并发:"), g);
        g.gridx = 1; p.add(threadsSpin, g);

        // 压测模式：按时长 / 按请求数
        modeGroup.add(modeDuration);
        modeGroup.add(modeRequests);
        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        modePanel.add(modeDuration);
        modePanel.add(durationSpin);
        modePanel.add(modeRequests);
        modePanel.add(totalReqSpin);
        modeDuration.addActionListener(e -> updateModeState());
        modeRequests.addActionListener(e -> updateModeState());
        g.gridx = 0; g.gridy = 7; p.add(new JLabel("模式:"), g);
        g.gridx = 1; g.gridwidth = 2; p.add(modePanel, g);
        g.gridwidth = 1;

        g.gridx = 0; g.gridy = 8; p.add(new JLabel("预热:"), g);
        g.gridx = 1; p.add(warmupCheck, g);

        // 认证：自定义 CoAP Option
        JPanel authPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        authPanel.add(authCheck);
        authPanel.add(new JLabel("Option号:"));
        authPanel.add(optionNumField);
        authPanel.add(new JLabel("Token:"));
        authPanel.add(tokenField);
        authCheck.addActionListener(e -> updateAuthState());
        g.gridx = 0; g.gridy = 9; g.gridwidth = 4; p.add(authPanel, g);
        g.gridwidth = 1;

        JLabel hint = new JLabel("  变量: ${counter} ${timestamp} ${random:N} ${uuid} ${threadId} ${randStr:N} ${uniqStr:N}");
        hint.setForeground(Color.GRAY);
        g.gridx = 0; g.gridy = 10; g.gridwidth = 4; p.add(hint, g);

        return p;
    }

    private void updateModeState() {
        boolean byDuration = modeDuration.isSelected();
        durationSpin.setEnabled(byDuration);
        totalReqSpin.setEnabled(!byDuration);
    }

    private void updateAuthState() {
        boolean enabled = authCheck.isSelected();
        optionNumField.setEnabled(enabled);
        tokenField.setEnabled(enabled);
    }

    private void updateTlsState() {
        boolean tls = tlsCheck.isSelected();
        jksField.setEnabled(tls);
        pwdField.setEnabled(tls);
    }

    private void append(String s) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(s + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void clearResults() {
        resultModel.setRowCount(0);
        aggLabel.setText(" ");
        allResults.clear();
    }

    private void exportResults() {
        if (allResults.isEmpty()) {
            JOptionPane.showMessageDialog(this, "没有可导出的结果，请先运行压测。",
                    "导出", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser(".");
        fc.setFileFilter(new FileNameExtensionFilter("CSV 文件", "csv"));
        fc.setSelectedFile(new File("coap-loadtest-" + System.currentTimeMillis() + ".csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) { return; }
        File file = fc.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".csv")) {
            file = new File(file.getAbsolutePath() + ".csv");
        }
        try (java.io.PrintWriter pw = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(file),
                        java.nio.charset.StandardCharsets.UTF_8), false)) {
            // UTF-8 BOM，Excel 可正确识别中文
            pw.write('\ufeff');

            // 聚合统计区
            long total = totalReq.sum();
            long success = successReq.sum();
            long fail = failReq.sum();
            long min = minLatency.get();
            long max = maxLatency.get();
            long sum = latencySum.get();
            double avg = total > 0 ? (double) sum / total : 0;
            double elapsed = (System.currentTimeMillis() - measureStartMs) / 1000.0;
            double tps = elapsed > 0 ? total / elapsed : 0;
            double errPct = total > 0 ? (double) fail / total * 100 : 0;

            pw.println("# CoAP TLS/TCP 压测结果");
            pw.println("# 导出时间: " + java.time.LocalDateTime.now());
            pw.println("# URI: " + uriField.getText().trim());
            pw.println("# 方法: " + methodBox.getSelectedItem());
            pw.println("# 并发: " + threadsSpin.getValue());
            pw.println();
            pw.println("指标,数值");
            pw.println("总请求数," + total);
            pw.println("成功数," + success);
            pw.println("失败数," + fail);
            pw.println("错误率(%)," + String.format("%.2f", errPct));
            pw.println("平均延迟(ms)," + String.format("%.1f", avg));
            pw.println("最小延迟(ms)," + (min == Long.MAX_VALUE ? 0 : min));
            pw.println("最大延迟(ms)," + max);
            pw.println("p90(ms)," + percentile(90));
            pw.println("p95(ms)," + percentile(95));
            pw.println("p99(ms)," + percentile(99));
            pw.println("吞吐(req/s)," + String.format("%.0f", tps));
            pw.println("耗时(s)," + String.format("%.1f", elapsed));
            pw.println();

            // 明细区
            pw.println("序号,方法,路径,响应码,延迟(ms),状态,响应体");
            synchronized (allResults) {
                for (RequestResult r : allResults) {
                    pw.printf("%d,%s,%s,%s,%d,%s,%s%n",
                            r.seq(),
                            csv(r.method()),
                            csv(r.path()),
                            csv(r.code()),
                            r.latencyMs(),
                            r.status(),
                            csv(r.body()));
                }
            }
            pw.flush();
            append("[Export] 已导出 " + allResults.size() + " 条结果到: " + file.getAbsolutePath());
            JOptionPane.showMessageDialog(this,
                    "已导出 " + allResults.size() + " 条结果到:\n" + file.getAbsolutePath(),
                    "导出成功", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "导出失败: " + ex.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String csv(String s) {
        if (s == null) { return ""; }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private void startTest() {
        running = true;
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        resultModel.setRowCount(0);
        resultQueue.clear();
        allResults.clear();
        totalReq.reset(); successReq.reset(); failReq.reset();
        minLatency.set(Long.MAX_VALUE); maxLatency.set(0); latencySum.set(0);
        latencySamples.clear();
        aggLabel.setText(" ");

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                runLoadTest();
                return null;
            }

            @Override
            protected void done() {
                startBtn.setEnabled(true);
                stopBtn.setEnabled(false);
                running = false;
                refreshUi();
            }
        }.execute();
    }

    private void stopTest() {
        running = false;
        append("[Stop] 正在停止 ...");
        if (pool != null) { pool.shutdownNow(); }
    }

    private void runLoadTest() {
        try {
            String jksPath = jksField.getText().trim();
            String password = new String(pwdField.getPassword());
            String uriTemplate = uriField.getText().trim();
            String method = (String) methodBox.getSelectedItem();
            String payloadTemplate = payloadArea.getText();
            int threads = (int) threadsSpin.getValue();
            int durationSec = (int) durationSpin.getValue();
            long perThreadCount = ((Number) totalReqSpin.getValue()).longValue();
            // 总请求数 = 线程数 × 每线程次数（JMeter 风格）
            long totalRequests = (long) threads * perThreadCount;
            boolean warmupEnabled = warmupCheck.isSelected();
            boolean byDuration = modeDuration.isSelected();

            append("===== 配置 =====");
            append("TLS 证书认证: " + (tlsCheck.isSelected() ? "启用" : "关闭 (明文 TCP)"));
            append("JKS: " + jksPath);
            append("URI: " + uriTemplate);
            String modeInfo = byDuration
                    ? "按时长: " + durationSec + "s"
                    : "每线程 " + perThreadCount + " 次 (总计 " + totalRequests + ")";
            append("方法: " + method + " | 并发: " + threads + " | " + modeInfo + " | 预热: " + (warmupEnabled ? "开" : "关"));
            append("载荷模板: " + payloadTemplate);
            if (authCheck.isSelected()) {
                append("认证: 启用 | Option号: " + optionNumField.getText().trim() + " | Token: " + tokenField.getText());
            } else {
                append("认证: 关闭");
            }
            append("");

            boolean useTls = tlsCheck.isSelected();
            Configuration config = Configuration.getStandard();

            // TLS 开关决定连接器类型
            append("[Setup] 传输模式: " + (useTls ? "TLS/TCP (证书认证)" : "明文 TCP (无证书)"));
            CoapEndpoint[] endpoints = new CoapEndpoint[threads];
            if (useTls) {
                Path jks = Paths.get(jksPath);
                if (!new File(jksPath).exists()) {
                    append("[错误] JKS 文件不存在: " + jksPath);
                    return;
                }
                SSLContext sslContext = SslContextFactory.build(jks, password);
                append("[Setup] 建立 " + threads + " 条 TLS/TCP 连接 ...");
                for (int i = 0; i < threads; i++) {
                    TlsClientConnector connector = new TlsClientConnector(sslContext, config);
                    endpoints[i] = new CoapEndpoint.Builder()
                            .setConnector(connector)
                            .setConfiguration(config)
                            .build();
                    endpoints[i].start();
                }
            } else {
                append("[Setup] 建立 " + threads + " 条明文 TCP 连接 ...");
                for (int i = 0; i < threads; i++) {
                    TcpClientConnector connector = new TcpClientConnector(config);
                    endpoints[i] = new CoapEndpoint.Builder()
                            .setConnector(connector)
                            .setConfiguration(config)
                            .build();
                    endpoints[i].start();
                }
            }
            append("[Setup] 连接建立完成。");

            if (warmupEnabled) {
                // 预热：每个线程发固定 5 个请求完成 TLS 握手，不按时长跑，避免打满服务端
                long warmupReqs = (long) threads * 5;
                append("[Warmup] 预热 " + threads + " 条连接，每连接 5 请求 (共 " + warmupReqs + ") ...");
                runWorkers(endpoints, uriTemplate, method, payloadTemplate, threads,
                        10_000L, warmupReqs, true);
            }

            append("[Running] 正式压测 (" + modeInfo + ") ...");
            measureStartMs = System.currentTimeMillis();
            runWorkers(endpoints, uriTemplate, method, payloadTemplate, threads,
                    byDuration ? durationSec * 1000L : Long.MAX_VALUE,
                    byDuration ? 0L : totalRequests, false);

            append("[Done] 压测结束。");

            for (CoapEndpoint ep : endpoints) {
                ep.stop();
                ep.destroy();
            }
        } catch (Exception ex) {
            append("[错误] " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void runWorkers(CoapEndpoint[] endpoints, String uriTemplate, String method,
                            String payloadTemplate, int threads, long timeLimitMs,
                            long totalRequests, boolean warmup) throws InterruptedException {
        // 读取认证配置（在测试开始时固定，避免 UI 变化影响）
        final boolean authEnabled = authCheck.isSelected();
        final int authOptionNum = authEnabled ? parseOptionNum(optionNumField.getText().trim()) : 0;
        final String authToken = authEnabled ? tokenField.getText() : null;
        // 读取 Content-Type
        final int contentFormat = MediaTypeRegistry.parse((String) contentTypeBox.getSelectedItem());

        pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        long startMs = System.currentTimeMillis();
        AtomicLong globalCounter = new AtomicLong(0);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    CoapClient client = new CoapClient();
                    client.setEndpoint(endpoints[tid]);
                    while (running) {
                        if (System.currentTimeMillis() - startMs > timeLimitMs) { break; }
                        if (totalRequests > 0 && globalCounter.get() >= totalRequests) { break; }

                        long counter = globalCounter.incrementAndGet();
                        String uriStr = substitute(uriTemplate, counter, tid);

                        // 构建请求（支持自定义 Option 携带 Token）
                        Request request = new Request("POST".equals(method) ? CoAP.Code.POST : CoAP.Code.GET);
                        request.setURI(uriStr);
                        if (authEnabled && authToken != null) {
                            request.getOptions().addOption(new Option(authOptionNum, authToken));
                        }
                        if ("POST".equals(method)) {
                            request.setPayload(substitute(payloadTemplate, counter, tid));
                            request.getOptions().setContentFormat(contentFormat);
                        }

                        long t0 = System.nanoTime();
                        CoapResponse resp = client.advanced(request);
                        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

                        if (!warmup) {
                            totalReq.increment();
                            boolean ok = resp != null && resp.isSuccess();
                            if (ok) {
                                successReq.increment();
                            } else {
                                failReq.increment();
                            }
                            latencySum.addAndGet(costMs);
                            updateMinMax(costMs);
                            if (latencySamples.size() < MAX_LATENCY_SAMPLES){
                                latencySamples.offer(costMs);
                            }

                            String path = uriStr.contains("?") ? uriStr.substring(uriStr.indexOf('/', 10)) : uriStr.substring(uriStr.indexOf('/', 10));
                            RequestResult rr = new RequestResult(
                                    totalReq.sum(),
                                    method, path,
                                    resp == null ? "TIMEOUT" : resp.getCode().toString(),
                                    costMs,
                                    ok ? "OK" : "FAIL",
                                    resp == null ? "" : truncate(resp.getResponseText(), 80));
                            resultQueue.offer(rr);
                            allResults.add(rr);
                        }
                    }
                    client.shutdown();
                } catch (Exception e) {
                    if (!warmup) {
                        failReq.increment();
                        RequestResult rr = new RequestResult(totalReq.sum(), method, "",
                                "EX:" + e.getClass().getSimpleName(), 0, "FAIL", e.getMessage());
                        resultQueue.offer(rr);
                        allResults.add(rr);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
    }

    private void updateMinMax(long v) {
        long cur;
        do {
            cur = minLatency.get();
            if (v >= cur) { break; }
        } while (!minLatency.compareAndSet(cur, v));
        do {
            cur = maxLatency.get();
            if (v <= cur) { break; }
        } while (!maxLatency.compareAndSet(cur, v));
    }

    private static int parseOptionNum(String s) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 65000; }
    }

    /** 替换占位符变量 */
    private static String substitute(String template, long counter, int threadId) {
        if (template == null) { return ""; }
        String s = template;
        s = s.replace("${counter}", String.valueOf(counter));
        s = s.replace("${threadId}", String.valueOf(threadId));
        s = s.replace("${timestamp}", String.valueOf(System.currentTimeMillis()));
        s = s.replace("${uuid}", UUID.randomUUID().toString());
        // ${random:N}
        int idx;
        while ((idx = s.indexOf("${random:")) >= 0) {
            int end = s.indexOf('}', idx);
            if (end < 0) { break; }
            String numStr = s.substring(idx + 9, end);
            try {
                int bound = Integer.parseInt(numStr.trim());
                s = s.substring(0, idx) + (int) (Math.random() * bound) + s.substring(end + 1);
            } catch (NumberFormatException e) {
                s = s.substring(0, idx) + s.substring(end + 1);
            }
        }
        // ${randStr:N} 固定长度随机字符串（可重复）
        while ((idx = s.indexOf("${randStr:")) >= 0) {
            int end = s.indexOf('}', idx);
            if (end < 0) { break; }
            String numStr = s.substring(idx + 10, end);
            try {
                int len = Integer.parseInt(numStr.trim());
                s = s.substring(0, idx) + randomString(len) + s.substring(end + 1);
            } catch (NumberFormatException e) {
                s = s.substring(0, idx) + s.substring(end + 1);
            }
        }
        // ${uniqStr:N} 固定长度随机字符串（不重复，由 counter 保证唯一）
        while ((idx = s.indexOf("${uniqStr:")) >= 0) {
            int end = s.indexOf('}', idx);
            if (end < 0) { break; }
            String numStr = s.substring(idx + 10, end);
            try {
                int len = Integer.parseInt(numStr.trim());
                s = s.substring(0, idx) + uniqueString(len, counter) + s.substring(end + 1);
            } catch (NumberFormatException e) {
                s = s.substring(0, idx) + s.substring(end + 1);
            }
        }
        return s;
    }

    private static final char[] RAND_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final java.util.Random RANDOM = new java.util.Random();

    /** 生成固定长度的随机字符串（可重复） */
    private static String randomString(int len) {
        if (len <= 0) { return ""; }
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(RAND_CHARS[RANDOM.nextInt(RAND_CHARS.length)]);
        }
        return sb.toString();
    }

    /**
     * 生成固定长度的不重复字符串。
     * 前缀为 counter 的 base36 表示（全局唯一），不足部分补随机字符。
     * 只要 len >= counter 的 base36 长度即可保证全局唯一。
     */
    private static String uniqueString(int len, long counter) {
        if (len <= 0) { return ""; }
        String base = Long.toString(counter, 36);
        if (base.length() >= len) { return base.substring(0, len); }
        StringBuilder sb = new StringBuilder(base);
        while (sb.length() < len) {
            sb.append(RAND_CHARS[RANDOM.nextInt(RAND_CHARS.length)]);
        }
        return sb.toString();
    }

    /** 定时器回调：把队列里的结果搬到表格，更新聚合统计 */
    private void refreshUi() {
        // 搬结果到表格（最多追加 MAX_TABLE_ROWS 行，超出删最旧的）
        RequestResult r;
        int added = 0;
        while ((r = resultQueue.poll()) != null) {
            if (resultModel.getRowCount() >= MAX_TABLE_ROWS) {
                resultModel.removeRow(0);
            }
            resultModel.addRow(new Object[]{
                    r.seq, r.method, r.path, r.code, r.latencyMs, r.status, r.body
            });
            // 状态着色
            int row = resultModel.getRowCount() - 1;
            if ("FAIL".equals(r.status)) {
                // 简单处理：失败行用红色（通过渲染器）
            }
            added++;
            if (added > 200) { break; }// 单次刷新最多处理 200 条，避免卡 UI
        }

        // 聚合统计
        long total = totalReq.sum();
        long success = successReq.sum();
        long fail = failReq.sum();
        long min = minLatency.get();
        long max = maxLatency.get();
        long sum = latencySum.get();
        double avg = total > 0 ? (double) sum / total : 0;
        double elapsed = (System.currentTimeMillis() - measureStartMs) / 1000.0;
        double tps = elapsed > 0 ? total / elapsed : 0;
        double errPct = total > 0 ? (double) fail / total * 100 : 0;

        // 百分位
        long p90 = percentile(90);
        long p95 = percentile(95);
        long p99 = percentile(99);

        aggLabel.setText(String.format(
                "Samples: %d   Success: %d   Fail: %d   Error%%: %.2f%%%n" +
                "Latency(ms)  Avg: %.1f   Min: %d   Max: %d%n" +
                "Percentile   p90: %d   p95: %d   p99: %d%n" +
                "Throughput: %.0f req/s   Elapsed: %.1fs",
                total, success, fail, errPct,
                avg, min == Long.MAX_VALUE ? 0 : min, max,
                p90, p95, p99,
                tps, elapsed));
    }

    private long percentile(double p) {
        if (latencySamples.isEmpty()) { return 0; }
        // 快照：避免与 worker 线程并发修改导致数组越界
        Long[] snapshot = latencySamples.toArray(new Long[0]);
        if (snapshot.length == 0) { return 0; }
        long[] arr = new long[snapshot.length];
        for (int i = 0; i < snapshot.length; i++) {
            arr[i] = snapshot[i];
        }
        java.util.Arrays.sort(arr);
        int idx = (int) Math.ceil(p / 100.0 * arr.length) - 1;
        return arr[Math.max(0, Math.min(idx, arr.length - 1))];
    }

    private static String truncate(String s, int max) {
        if (s == null) { return ""; }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 单条请求结果 */
    private record RequestResult(long seq, String method, String path, String code,
                                 long latencyMs, String status, String body) {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new CoapLoadTester().setVisible(true));
    }
}
