package com.coaplearn.tls;

import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.tcp.netty.TlsClientConnector;

import javax.net.ssl.SSLContext;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.net.URI;
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
 * CoAP over TLS/TCP 图形化压测工具（增强版）。
 * <p>
 * 功能：
 * <ul>
 *     <li>配置 JKS / 密码 / URI / 方法 / 载荷 / 并发 / 时长</li>
 *     <li>每个请求的结果表格（方法、路径、响应码、延迟、状态、响应体）</li>
 *     <li>聚合统计（samples / avg / min / max / 吞吐 / 错误率 / p90 / p99）</li>
 *     <li>可变参数：载荷和 URI 路径支持占位符
 *         ${counter} ${timestamp} ${random:N} ${uuid} ${threadId}</li>
 * </ul>
 */
public class CoapTlsLoadTestGui extends JFrame {

    // ---- 输入组件 ----
    private final JTextField jksField = new JTextField("server.jks", 20);
    private final JPasswordField pwdField = new JPasswordField("123456", 12);
    private final JTextField uriField = new JTextField("coaps+tcp://localhost:5684/hello", 30);
    private final JComboBox<String> methodBox = new JComboBox<>(new String[]{"GET", "POST"});
    private final JTextArea payloadArea = new JTextArea("msg-${counter}-${random:1000}", 3, 30);
    private final JSpinner threadsSpin = new JSpinner(new SpinnerNumberModel(10, 1, 1000, 1));
    private final JSpinner durationSpin = new JSpinner(new SpinnerNumberModel(10, 1, 3600, 1));
    private final JSpinner warmupSpin = new JSpinner(new SpinnerNumberModel(1, 0, 60, 1));

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

    // ---- 运行状态 ----
    private volatile boolean running = false;
    private ExecutorService pool;

    // 结果收集：无界队列，UI 定时器批量取走
    private final ConcurrentLinkedQueue<RequestResult> resultQueue = new ConcurrentLinkedQueue<>();
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

    public CoapTlsLoadTestGui() {
        super("CoAP TLS/TCP 压测工具");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(6, 6));

        JPanel top = new JPanel(new BorderLayout());
        top.add(buildForm(), BorderLayout.NORTH);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        stopBtn.setEnabled(false);
        startBtn.addActionListener(e -> startTest());
        stopBtn.addActionListener(e -> stopTest());
        clearBtn.addActionListener(e -> clearResults());
        btnPanel.add(startBtn);
        btnPanel.add(stopBtn);
        btnPanel.add(clearBtn);
        top.add(btnPanel, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);

        // 中间：请求结果表格
        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
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

        g.gridx = 0; g.gridy = 0; p.add(new JLabel("JKS:"), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1; p.add(jksField, g);
        JButton browse = new JButton("浏览...");
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(".");
            fc.setFileFilter(new FileNameExtensionFilter("JKS", "jks"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                jksField.setText(fc.getSelectedFile().getAbsolutePath());
        });
        g.gridx = 2; g.fill = GridBagConstraints.NONE; g.weightx = 0; p.add(browse, g);

        g.gridx = 0; g.gridy = 1; p.add(new JLabel("密码:"), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; p.add(pwdField, g);

        g.gridx = 0; g.gridy = 2; p.add(new JLabel("URI:"), g);
        g.gridx = 1; g.gridwidth = 2; g.fill = GridBagConstraints.HORIZONTAL; p.add(uriField, g);
        g.gridwidth = 1;

        g.gridx = 0; g.gridy = 3; p.add(new JLabel("方法:"), g);
        g.gridx = 1; p.add(methodBox, g);

        g.gridx = 0; g.gridy = 4; p.add(new JLabel("载荷(POST):"), g);
        g.gridx = 1; g.gridwidth = 2; g.fill = GridBagConstraints.BOTH; g.weighty = 1;
        p.add(new JScrollPane(payloadArea), g);
        g.weighty = 0; g.gridwidth = 1; g.fill = GridBagConstraints.HORIZONTAL;

        g.gridx = 0; g.gridy = 5; p.add(new JLabel("并发:"), g);
        g.gridx = 1; p.add(threadsSpin, g);
        g.gridx = 0; g.gridy = 6; p.add(new JLabel("时长(秒):"), g);
        g.gridx = 1; p.add(durationSpin, g);
        g.gridx = 0; g.gridy = 7; p.add(new JLabel("预热(秒):"), g);
        g.gridx = 1; p.add(warmupSpin, g);

        JLabel hint = new JLabel("  变量: ${counter} ${timestamp} ${random:N} ${uuid} ${threadId}");
        hint.setForeground(Color.GRAY);
        g.gridx = 0; g.gridy = 8; g.gridwidth = 3; p.add(hint, g);

        return p;
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
    }

    private void startTest() {
        running = true;
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        resultModel.setRowCount(0);
        resultQueue.clear();
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
        if (pool != null) pool.shutdownNow();
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
            int warmupSec = (int) warmupSpin.getValue();

            append("===== 配置 =====");
            append("JKS: " + jksPath);
            append("URI: " + uriTemplate);
            append("方法: " + method + " | 并发: " + threads + " | 时长: " + durationSec + "s | 预热: " + warmupSec + "s");
            append("载荷模板: " + payloadTemplate);
            append("");

            Path jks = Paths.get(jksPath);
            if (!new File(jksPath).exists()) {
                append("[错误] JKS 文件不存在: " + jksPath);
                return;
            }
            SSLContext sslContext = SslContextFactory.build(jks, password);
            Configuration config = Configuration.getStandard();

            append("[Setup] 建立 " + threads + " 条 TLS/TCP 连接 ...");
            CoapEndpoint[] endpoints = new CoapEndpoint[threads];
            for (int i = 0; i < threads; i++) {
                TlsClientConnector connector = new TlsClientConnector(sslContext, config);
                endpoints[i] = new CoapEndpoint.Builder()
                        .setConnector(connector)
                        .setConfiguration(config)
                        .build();
                endpoints[i].start();
            }
            append("[Setup] 连接建立完成。");

            if (warmupSec > 0) {
                append("[Warmup] 预热 " + warmupSec + "s ...");
                runWorkers(endpoints, uriTemplate, method, payloadTemplate, threads,
                        warmupSec * 1000L, true);
            }

            append("[Running] 正式压测 " + durationSec + "s ...");
            measureStartMs = System.currentTimeMillis();
            runWorkers(endpoints, uriTemplate, method, payloadTemplate, threads,
                    durationSec * 1000L, false);

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
                            boolean warmup) throws InterruptedException {
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
                        if (System.currentTimeMillis() - startMs > timeLimitMs) break;

                        long counter = globalCounter.incrementAndGet();
                        String uriStr = substitute(uriTemplate, counter, tid);
                        client.setURI(uriStr);

                        long t0 = System.nanoTime();
                        CoapResponse resp = "POST".equals(method)
                                ? client.post(substitute(payloadTemplate, counter, tid), MediaTypeRegistry.TEXT_PLAIN)
                                : client.get();
                        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

                        if (!warmup) {
                            totalReq.increment();
                            boolean ok = resp != null && resp.isSuccess();
                            if (ok) successReq.increment(); else failReq.increment();
                            latencySum.addAndGet(costMs);
                            updateMinMax(costMs);
                            if (latencySamples.size() < MAX_LATENCY_SAMPLES)
                                latencySamples.offer(costMs);

                            String path = uriStr.contains("?") ? uriStr.substring(uriStr.indexOf('/', 10)) : uriStr.substring(uriStr.indexOf('/', 10));
                            resultQueue.offer(new RequestResult(
                                    totalReq.sum(),
                                    method, path,
                                    resp == null ? "TIMEOUT" : resp.getCode().toString(),
                                    costMs,
                                    ok ? "OK" : "FAIL",
                                    resp == null ? "" : truncate(resp.getResponseText(), 80)));
                        }
                    }
                    client.shutdown();
                } catch (Exception e) {
                    if (!warmup) {
                        failReq.increment();
                        resultQueue.offer(new RequestResult(totalReq.sum(), method, "",
                                "EX:" + e.getClass().getSimpleName(), 0, "FAIL", e.getMessage()));
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
            if (v >= cur) break;
        } while (!minLatency.compareAndSet(cur, v));
        do {
            cur = maxLatency.get();
            if (v <= cur) break;
        } while (!maxLatency.compareAndSet(cur, v));
    }

    /** 替换占位符变量 */
    private static String substitute(String template, long counter, int threadId) {
        if (template == null) return "";
        String s = template;
        s = s.replace("${counter}", String.valueOf(counter));
        s = s.replace("${threadId}", String.valueOf(threadId));
        s = s.replace("${timestamp}", String.valueOf(System.currentTimeMillis()));
        s = s.replace("${uuid}", UUID.randomUUID().toString());
        // ${random:N}
        int idx;
        while ((idx = s.indexOf("${random:")) >= 0) {
            int end = s.indexOf('}', idx);
            if (end < 0) break;
            String numStr = s.substring(idx + 9, end);
            try {
                int bound = Integer.parseInt(numStr.trim());
                s = s.substring(0, idx) + (int) (Math.random() * bound) + s.substring(end + 1);
            } catch (NumberFormatException e) {
                s = s.substring(0, idx) + s.substring(end + 1);
            }
        }
        return s;
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
            if (added > 200) break; // 单次刷新最多处理 200 条，避免卡 UI
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
        if (latencySamples.isEmpty()) return 0;
        long[] arr = new long[latencySamples.size()];
        int i = 0;
        for (Long v : latencySamples) arr[i++] = v;
        java.util.Arrays.sort(arr);
        int idx = (int) Math.ceil(p / 100.0 * arr.length) - 1;
        return arr[Math.max(0, Math.min(idx, arr.length - 1))];
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 单条请求结果 */
    private record RequestResult(long seq, String method, String path, String code,
                                 long latencyMs, String status, String body) {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new CoapTlsLoadTestGui().setVisible(true));
    }
}
