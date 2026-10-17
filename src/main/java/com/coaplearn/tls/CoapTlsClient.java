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

/**
 * CoAP over TLS/TCP 客户端。
 * <p>
 * 使用与服务端相同的 server.jks 作为身份与信任锚，
 * 向 coaps+tcp://localhost:5684/hello 发起 GET，并向 /echo 发起 POST。
 */
public class CoapTlsClient {

    public static final String JKS_FILE = "server.jks";
    public static final String PASSWORD = "123456";
    public static final String BASE_URI = "coaps+tcp://localhost:5684";

    public static void main(String[] args) throws Exception {
        Path jksPath = Paths.get(JKS_FILE);
        SSLContext sslContext = SslContextFactory.build(jksPath, PASSWORD);

        Configuration config = Configuration.getStandard();

        TlsClientConnector connector = new TlsClientConnector(sslContext, config);

        CoapEndpoint endpoint = new CoapEndpoint.Builder()
                .setConnector(connector)
                .setConfiguration(config)
                .build();
        endpoint.start();

        try {
            // 1. GET /hello
            CoapClient client = new CoapClient(new URI(BASE_URI + "/hello"));
            client.setEndpoint(endpoint);
            CoapResponse response = client.get();
            printResponse("GET /hello", response);
            client.shutdown();

            // 2. POST /echo
            CoapClient client2 = new CoapClient(new URI(BASE_URI + "/echo"));
            client2.setEndpoint(endpoint);
            CoapResponse response2 = client2.post("Hello CoAP+TLS+TCP!", MediaTypeRegistry.TEXT_PLAIN);
            printResponse("POST /echo", response2);
            client2.shutdown();
        } finally {
            endpoint.stop();
            endpoint.destroy();
        }
    }

    private static void printResponse(String label, CoapResponse response) {
        if (response == null) {
            System.out.println("[Client] " + label + " -> no response (timeout?)");
            return;
        }
        System.out.println("[Client] " + label + " -> " + response.getCode());
        System.out.println("[Client]   " + response.getResponseText());
    }
}
