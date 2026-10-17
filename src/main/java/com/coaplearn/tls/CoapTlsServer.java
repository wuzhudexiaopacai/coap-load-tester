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
        server.add(new AuthResource());

        server.start();
        System.out.println("[Server] CoAP over TLS/TCP started at coaps+tcp://localhost:" + PORT + "/");
        System.out.println("[Server] Resources: /hello , /echo , /auth");
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
            int cf = exchange.getRequestOptions().getContentFormat();
            System.out.println("收到post请求: Content-Format=" + cf
                    + " (" + MediaTypeRegistry.toString(cf) + "), payload=" + exchange.getRequestText());
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
            int cf = exchange.getRequestOptions().getContentFormat();
            System.out.println("接收到post请求: Content-Format=" + cf
                    + " (" + MediaTypeRegistry.toString(cf) + "), payload=" + body);
            exchange.respond(CoAP.ResponseCode.CREATED,
                    "echo: " + body,
                    MediaTypeRegistry.TEXT_PLAIN);
        }
    }

    /**
     * 认证演示资源：读取自定义 Option (65000) 中的 Token 并回显。
     * 若未携带 Token，返回 4.01 Unauthorized。
     */
    static class AuthResource extends CoapResource {
        private static final int AUTH_OPTION = 65000;

        AuthResource() {
            super("auth");
            getAttributes().setTitle("Auth Resource");
        }

        @Override
        public void handleGET(CoapExchange exchange) {
            handle(exchange, "GET");
        }

        @Override
        public void handlePOST(CoapExchange exchange) {
            handle(exchange, "POST");
        }

        private void handle(CoapExchange exchange, String method) {
            byte[] tokenOpt = null;
            for (org.eclipse.californium.core.coap.Option opt : exchange.getRequestOptions().getOthers()) {
                if (opt.getNumber() == AUTH_OPTION) {
                    tokenOpt = opt.getValue();
                    break;
                }
            }
            if (tokenOpt == null) {
                System.out.println("[Auth] " + method + " 无 Token，返回 4.01");
                exchange.respond(CoAP.ResponseCode.UNAUTHORIZED,
                        "missing token in option " + AUTH_OPTION,
                        MediaTypeRegistry.TEXT_PLAIN);
                return;
            }
            String token = new String(tokenOpt);
            System.out.println("[Auth] " + method + " Token=" + token);
            exchange.respond(CoAP.ResponseCode.CONTENT,
                    "auth ok, token=" + token + ", payload=" + exchange.getRequestText(),
                    MediaTypeRegistry.TEXT_PLAIN);
        }
    }
}
