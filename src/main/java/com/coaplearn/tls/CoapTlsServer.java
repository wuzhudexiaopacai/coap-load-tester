package com.coaplearn.tls;

import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.elements.config.CertificateAuthenticationMode;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.config.TcpConfig;
import org.eclipse.californium.elements.tcp.netty.TlsServerConnector;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CoAP over TLS/TCP 服务端。
 * <p>
 * 使用 server.jks 作为身份与信任锚，开启双向客户端认证（NEEDED）。
 * 监听 coaps+tcp://0.0.0.0:5684/
 */
public class CoapTlsServer {

    public static final int PORT = 5684;
    public static final String JKS_FILE = "server.jks";
    public static final String PASSWORD = "123456";

    public static void main(String[] args) throws Exception {
        Path jksPath = Paths.get(JKS_FILE);
        SSLContext sslContext = SslContextFactory.build(jksPath, PASSWORD);

        Configuration config = Configuration.getStandard();
        // 双向认证：要求客户端也提供证书
        config.set(TcpConfig.TLS_CLIENT_AUTHENTICATION_MODE, CertificateAuthenticationMode.NEEDED);
        config.set(TcpConfig.TLS_HANDSHAKE_TIMEOUT, 10, java.util.concurrent.TimeUnit.SECONDS);

        TlsServerConnector connector = new TlsServerConnector(
                sslContext,
                new InetSocketAddress(PORT),
                config);

        CoapEndpoint endpoint = new CoapEndpoint.Builder()
                .setConnector(connector)
                .setConfiguration(config)
                .build();

        CoapServer server = new CoapServer();
        server.addEndpoint(endpoint);

        server.add(new HelloResource());
        server.add(new EchoResource());

        server.start();
        System.out.println("[Server] CoAP over TLS/TCP started at coaps+tcp://localhost:" + PORT + "/");
        System.out.println("[Server] Resources: /hello , /echo");
        System.out.println("[Server] Press Ctrl+C to stop.");
    }

    /** 简单 GET 资源 */
    static class HelloResource extends CoapResource {
        HelloResource() {
            super("hello");
            getAttributes().setTitle("Hello Resource");
        }

        @Override
        public void handleGET(CoapExchange exchange) {
            System.out.println("收到get请求");
            String peer = exchange.getSourceSocketAddress() == null
                    ? "unknown" : exchange.getSourceSocketAddress().toString();
            exchange.respond(CoAP.ResponseCode.CONTENT,
                    "Hello from CoAP+TLS+TCP! peer=" + peer,
                    MediaTypeRegistry.TEXT_PLAIN);
        }

        @Override
        public void handlePOST(CoapExchange exchange) {
            System.out.println("收到post请求: " + exchange.getRequestText());
            exchange.respond(CoAP.ResponseCode.CREATED,
                    "hello echo: " + exchange.getRequestText(),
                    MediaTypeRegistry.TEXT_PLAIN);
        }
    }

    /** 回显 POST payload 的资源 */
    static class EchoResource extends CoapResource {
        EchoResource() {
            super("echo");
            getAttributes().setTitle("Echo Resource");
        }

        @Override
        public void handlePOST(CoapExchange exchange) {
            String body = exchange.getRequestText();
            System.out.println("接收到post请求");
            exchange.respond(CoAP.ResponseCode.CREATED,
                    "echo: " + body,
                    MediaTypeRegistry.TEXT_PLAIN);
        }
    }
}
