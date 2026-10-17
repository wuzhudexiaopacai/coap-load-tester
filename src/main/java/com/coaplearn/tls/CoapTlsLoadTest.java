package com.coaplearn.tls;

import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.tcp.netty.TlsClientConnector;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * CoAP over TLS/TCP 压测客户端。
 * <p>
 * 用法：
 * <pre>
 *   CoapTlsLoadTest --threads 50 --duration 30 --path /hello --method GET
 *   CoapTlsLoadTest --threads 100 --requests 1000 --path /echo --method POST
 * </pre>
 * 参数：
 * <ul>
 *     <li>{@code --threads N}      并发线程数（同时建立的 TLS/TCP 连接数），默认 50</li>
 *     <li>{@code --duration S}     持续压测秒数（与 --requests 二选一，默认 10 秒）</li>
 *     <li>{@code --requests N}     总请求数（达到即停止）</li>
 *     <li>{@code --path /xxx}      请求路径，默认 /hello</li>
 *     <li>{@code --method GET|POST} 请求方法，默认 GET</li>
 *     <li>{@code --warmup S}       预热秒数（不计入统计），默认 2</li>
 * </ul>
 */
public class CoapTlsLoadTest {

    public static final String JKS_FILE = "server.jks";
    public static final String PASSWORD = "123456";
    public static final String BASE_URI = "coaps+tcp://localhost:5684";

    // 统计
    static final LongAdder totalRequests = new LongAdder();
    static final LongAdder successCount = new LongAdder();
    static final LongAdder failureCount = new LongAdder();
    // 用数组存延迟样本，每个线程写自己的槽位避免伪共享
    static long[][] latencies;

    public static void main(String[] args) throws Exception {
        // ---- 解析参数 ----
        int threads = 50;
        int durationSec = 10;
        long totalRequestsLimit = -1; // -1 表示按时长
        String path = "/hello";
        String method = "GET";
        int warmupSec = 2;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--threads": threads = Integer.parseInt(args[++i]); break;
                case "--duration": durationSec = Integer.parseInt(args[++i]); totalRequestsLimit = -1; break;
                case "--requests": totalRequestsLimit = Long.parseLong(args[++i]); break;
                case "--path": path = args[++i]; break;
                case "--method": method = args[++i].toUpperCase(); break;
                case "--warmup": warmupSec = Integer.parseInt(args[++i]); break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    System.exit(1);
            }
        }

        latencies = new long[threads][];

        System.out.println("===== CoAP TLS/TCP Load Test =====");
        System.out.printf("threads=%d, path=%s, method=%s, ", threads, path, method);
        if (totalRequestsLimit > 0) {
            System.out.printf("totalRequests=%d%n", totalRequestsLimit);
        } else {
            System.out.printf("duration=%ds, warmup=%ds%n", durationSec, warmupSec);
        }

        // ---- 构建 SSLContext（所有连接复用同一个） ----
        Path jksPath = Paths.get(JKS_FILE);
        SSLContext sslContext = SslContextFactory.build(jksPath, PASSWORD);
        Configuration config = Configuration.getStandard();

        // ---- 每个线程一个独立的 CoapEndpoint（独立 TLS/TCP 连接） ----
        CoapEndpoint[] endpoints = new CoapEndpoint[threads];
        for (int i = 0; i < threads; i++) {
            TlsClientConnector connector = new TlsClientConnector(sslContext, config);
            endpoints[i] = new CoapEndpoint.Builder()
                    .setConnector(connector)
                    .setConfiguration(config)
                    .build();
            endpoints[i].start();
        }
        System.out.println("[Setup] " + threads + " TLS/TCP connections established.");

        URI targetUri = new URI(BASE_URI + path);

        // ---- 预热 ----
        System.out.println("[Warmup] " + warmupSec + "s ...");
        runWorkers(endpoints, targetUri, method, threads, -1, warmupSec * 1000L, true);

        // 清零统计
        totalRequests.reset();
        successCount.reset();
        failureCount.reset();
        for (int i = 0; i < threads; i++) latencies[i] = null;

        // ---- 正式压测 ----
        long measureMs = (totalRequestsLimit > 0) ? Long.MAX_VALUE : durationSec * 1000L;
        System.out.println("[Running] measuring ...");
        long start = System.nanoTime();
        runWorkers(endpoints, targetUri, method, threads, totalRequestsLimit, measureMs, false);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // ---- 输出结果 ----
        printReport(elapsedMs);

        // ---- 清理 ----
        for (CoapEndpoint ep : endpoints) {
            ep.stop();
            ep.destroy();
        }
        System.exit(0);
    }

    /**
     * 启动 worker 线程执行请求。
     *
     * @param endpoints       每个线程使用的 endpoint
     * @param uri             目标 URI
     * @param method          GET / POST
     * @param threads         线程数
     * @param requestLimit    总请求上限（-1 表示按时间）
     * @param timeLimitMs     时间上限（毫秒）
     * @param warmup          是否预热模式（不记录延迟）
     */
    private static void runWorkers(CoapEndpoint[] endpoints, URI uri, String method,
                                   int threads, long requestLimit, long timeLimitMs,
                                   boolean warmup) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        long startMs = System.currentTimeMillis();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            final long perThreadLimit = (requestLimit > 0)
                    ? (requestLimit / threads) : Long.MAX_VALUE;
            pool.submit(() -> {
                try {
                    if (!warmup) latencies[tid] = new long[(int) Math.min(perThreadLimit, 200_000)];
                    int idx = 0;
                    long count = 0;
                    CoapClient client = new CoapClient(uri);
                    client.setEndpoint(endpoints[tid]);

                    while (count < perThreadLimit) {
                        if (System.currentTimeMillis() - startMs > timeLimitMs) break;

                        long t0 = System.nanoTime();
                        CoapResponse resp = "POST".equals(method)
                                ? client.post("load-test-payload", MediaTypeRegistry.TEXT_PLAIN)
                                : client.get();
                        long costUs = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - t0);

                        if (!warmup) {
                            totalRequests.increment();
                            if (resp != null && resp.isSuccess()) {
                                successCount.increment();
                            } else {
                                failureCount.increment();
                            }
                            if (idx < latencies[tid].length) {
                                latencies[tid][idx++] = costUs;
                            }
                        }
                        count++;
                    }
                    client.shutdown();
                } catch (Exception e) {
                    if (!warmup) failureCount.increment();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdownNow();
    }

    private static void printReport(long elapsedMs) {
        long total = totalRequests.sum();
        long success = successCount.sum();
        long failure = failureCount.sum();
        double seconds = elapsedMs / 1000.0;
        double qps = (seconds > 0) ? total / seconds : 0;

        // 合并延迟样本并排序
        int sampleCount = 0;
        for (long[] arr : latencies) if (arr != null) sampleCount += arr.length;
        long[] all = new long[sampleCount];
        int p = 0;
        for (long[] arr : latencies) {
            if (arr != null) {
                System.arraycopy(arr, 0, all, p, arr.length);
                p += arr.length;
            }
        }
        java.util.Arrays.sort(all);

        System.out.println();
        System.out.println("===== Result =====");
        System.out.printf("Elapsed      : %.2f s%n", seconds);
        System.out.printf("Total        : %d%n", total);
        System.out.printf("Success      : %d%n", success);
        System.out.printf("Failure      : %d%n", failure);
        System.out.printf("Throughput   : %.0f req/s%n", qps);
        if (all.length > 0) {
            System.out.printf("Latency (us) : min=%d, avg=%.0f, max=%d%n",
                    all[0],
                    java.util.Arrays.stream(all).average().orElse(0),
                    all[all.length - 1]);
            System.out.printf("  p50=%d, p90=%d, p99=%d, p999=%d (us)%n",
                    percentile(all, 50), percentile(all, 90),
                    percentile(all, 99), percentile(all, 99.9));
        }
    }

    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }
}
