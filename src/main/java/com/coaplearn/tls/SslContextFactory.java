package com.coaplearn.tls;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * 从同一份 server.jks 构建双向 TLS 所需的 {@link SSLContext}。
 * <p>
 * 该 JKS 内部包含两个条目：
 * <ul>
 *     <li>alias {@code server}：PrivateKeyEntry，私钥 + 自签名证书（同时充当服务端/客户端身份）</li>
 *     <li>alias {@code ca}：TrustedCertEntry，同一张证书作为信任锚</li>
 * </ul>
 * 因为证书是自签名 CA，所以它既是身份证书也是信任锚。
 */
public final class SslContextFactory {

    private SslContextFactory() {
    }

    /**
     * 构建用于双向 TLS 的 SSLContext。
     *
     * @param jksPath      server.jks 路径
     * @param storePassword keystore 密码（也是 keyEntry 密码）
     * @return 初始化好的 SSLContext（TLSv1.3）
     */
    public static SSLContext build(Path jksPath, String storePassword)
            throws GeneralSecurityException, IOException {
        char[] password = storePassword.toCharArray();

        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(jksPath)) {
            keyStore.load(in, password);
        }

        // 身份：取 server 条目的私钥 + 证书链
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(keyStore, password);

        // 信任：取 ca 条目作为信任锚
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(keyStore);

        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return sslContext;
    }
}
