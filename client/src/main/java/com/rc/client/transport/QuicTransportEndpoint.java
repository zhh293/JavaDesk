package com.rc.client.transport;

import com.rc.client.ice.UdpSocket;
import com.rc.common.model.Endpoint;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import tech.kwik.core.ClientConfig;
import tech.kwik.core.Kwik;
import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicVersion;
import tech.kwik.core.ServerConnector;

import java.io.StringReader;
import java.math.BigInteger;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 打洞成功后，在同一 UDP socket 上建立 QUIC 连接的编排点（Phase 2「QUIC 替换裸 UDP」）。
 *
 * <p>角色约定：<b>控制端 = QUIC 客户端</b>（发起握手），<b>被控端 = QUIC 服务端</b>
 * （接受握手）——延续「控制端主动发起」的既有语义。双方握手仍走 ICE 打洞产出的同一
 * socket，保证 NAT 映射一致（设计文档 §1.4「同一 UDP Socket」）。</p>
 *
 * <p><b>socket 交接</b>：经 {@link UdpSocket#punchedDatagramSocket()} 把打洞 socket 的底层
 * {@link java.net.DatagramSocket} 交棒给 kwik（客户端经 {@code datagramSocketFactory} 注入，
 * 服务端经 {@code ServerConnector} 的 socket 工厂注入），而非让 kwik 另开 socket——否则
 * 映射端口改变、打洞失效。</p>
 *
 * <p><b>kwik API 未编译核对</b>：本类依赖的 {@code ServerConnector} 构造签名、TLS 传参
 * 方式（{@code withKeyStore} / {@code withCertificate}）与 datagram 工厂注入点在无 Maven/JDK17
 * 环境无法校验，均集中在 {@link #establishServer} / {@link #clientConfigFor} 两处并标注
 * 「待对照 javadoc」，接入时机械校正即可，其余逻辑不随 API 变动。</p>
 */
public final class QuicTransportEndpoint {

    /** QUIC 端点角色（对应会话两端）。 */
    public enum Role {
        /** 控制端：发起 QUIC 握手。 */
        CONTROLLER,
        /** 被控端：接受 QUIC 握手。 */
        AGENT
    }

    /** 服务端 TLS 材料（自签证书链 + 私钥，PEM 文本）。 */
    public record TlsMaterial(String certChainPem, String privateKeyPem) {
    }

    private static final String APPLICATION_PROTOCOL = "rc/quic";
    private static final long HANDSHAKE_TIMEOUT_MS = 4000L;

    /** 服务端 KeyStore 别名 / 密码（进程内一次性，非机密）。 */
    private static final String KEYSTORE_ALIAS = "rc-agent";
    private static final char[] KEYSTORE_PASSWORD = "rc-agent".toCharArray();

    private QuicTransportEndpoint() {
    }

    /**
     * 在打洞 socket 上建立 QUIC 连接。
     *
     * @param role  会话角色（决定客户端 / 服务端）。
     * @param socket ICE 打洞产出的复用 socket（候选发现与数据面同 socket）。
     * @param peer  对端打洞锁定端点（控制端握手指向；被控端侧可忽略）。
     * @param tls   被控端（服务端）TLS 材料；控制端 dev 忽略并 trustAll。
     * @return 已完成 QUIC 握手的连接，交由 {@link QuicTransportChannel} 承载数据面。
     */
    public static QuicConnection establish(Role role, UdpSocket socket, Endpoint peer, TlsMaterial tls)
            throws Exception {
        if (role == Role.CONTROLLER) {
            return establishClient(socket, peer);
        }
        return establishServer(socket, tls);
    }

    private static QuicConnection establishClient(UdpSocket socket, Endpoint peer) throws Exception {
        URI uri = URI.create("quic://" + peer.ip() + ":" + peer.port());
        ClientConfig config = clientConfigFor(socket);
        return Kwik.createClient(uri, QuicVersion.V1, config);
    }

    /**
     * 被控端充当 QUIC 服务端：以自签证书 + 私钥构建 KeyStore，经 {@link ServerConnector}
     * 把打洞 socket 注入（同一 fd），注册应用协议并阻塞等待首个握手成功的连接。
     *
     * <p><b>待对照 javadoc</b>：{@code ServerConnector.builder()} 的 TLS 入参（{@code withKeyStore}
     * 或 {@code withCertificate}）与 socket 工厂方法名（{@code datagramSocketFactory}）需按
     * kwik 0.9.x 实际签名校正；{@code registerApplicationProtocol} 的回调类型若为
     * {@code ServerConnectionRegistry}，其 {@code newConnection(ServerConnection, String)} 为
     * SAM，下方 lambda 可直接匹配。</p>
     */
    private static QuicConnection establishServer(UdpSocket socket, TlsMaterial tls) throws Exception {
        KeyStore keyStore = keyStoreFrom(tls);
        ServerConnector connector = ServerConnector.builder()
                .withKeyStore(keyStore, KEYSTORE_ALIAS, KEYSTORE_PASSWORD)
                .datagramSocketFactory(() -> socket.punchedDatagramSocket())
                .build();

        CompletableFuture<QuicConnection> accepted = new CompletableFuture<>();
        connector.registerApplicationProtocol(APPLICATION_PROTOCOL, connection -> {
            accepted.complete(connection.getQuicConnection());
        });
        connector.start();

        try {
            return accepted.get(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            connector.stop();
            throw new TimeoutException("quic server accept timeout");
        } catch (Exception e) {
            connector.stop();
            throw e;
        }
    }

    /**
     * 构造客户端配置：应用层协议 + 超时 + <b>把打洞 socket 交给 kwik</b>。
     *
     * <p><b>待对照 javadoc</b>：{@code datagramSocketFactory} 方法名与工厂接口形状需按 kwik
     * 校正；语义为「返回已绑定打洞端口的 {@code DatagramSocket}，勿新开 socket」。</p>
     */
    private static ClientConfig clientConfigFor(UdpSocket socket) {
        return ClientConfig.builder()
                .applicationProtocol(APPLICATION_PROTOCOL)
                .connectTimeout(Duration.ofMillis(HANDSHAKE_TIMEOUT_MS))
                .datagramSocketFactory(() -> socket.punchedDatagramSocket())
                .build();
    }

    /**
     * 由设备 RSA 密钥对派生自签 X.509 证书 + PEM 私钥（被控端充当 QUIC 服务端的 TLS 材料）。
     *
     * <p>控制端 dev 阶段 trustAll（忽略证书校验），故无需预置 CA；证书 CN 固定为
     * {@code rc-agent}，有效期 1 年（自签证书生命周期以会话为单位，非持久化）。</p>
     */
    public static TlsMaterial selfSigned(KeyPair keyPair) throws Exception {
        X500Name subject = new X500Name("CN=rc-agent");
        long now = System.currentTimeMillis();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                new BigInteger(64, new SecureRandom()),
                new Date(now - 24L * 3600 * 1000),
                new Date(now + 365L * 24 * 3600 * 1000),
                subject,
                keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(holder);
        return new TlsMaterial(
                pem("CERTIFICATE", cert.getEncoded()),
                pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
    }

    /** 由 PEM 文本构建进程内 PKCS#12 KeyStore（证书 + 私钥），供 kwik 服务端 TLS 使用。 */
    private static KeyStore keyStoreFrom(TlsMaterial tls) throws Exception {
        X509Certificate cert = parseCertificate(tls.certChainPem());
        PrivateKey key = parsePrivateKey(tls.privateKeyPem());
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(KEYSTORE_ALIAS, key, KEYSTORE_PASSWORD, new X509Certificate[]{cert});
        return ks;
    }

    private static X509Certificate parseCertificate(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            if (obj instanceof X509CertificateHolder holder) {
                return new JcaX509CertificateConverter().getCertificate(holder);
            }
            throw new IllegalStateException("unexpected certificate PEM object: " + obj);
        }
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (obj instanceof PEMKeyPair keyPair) {
                return converter.getKeyPair(keyPair).getPrivate();
            }
            if (obj instanceof PrivateKeyInfo keyInfo) {
                return converter.getPrivateKey(keyInfo);
            }
            throw new IllegalStateException("unexpected private key PEM object: " + obj);
        }
    }

    /** DER 字节 → PEM 文本（每 64 字符换行）。 */
    private static String pem(String type, byte[] der) {
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            int end = Math.min(i + 64, b64.length());
            sb.append(b64, i, end).append('\n');
        }
        sb.append("-----END ").append(type).append("-----\n");
        return sb.toString();
    }
}
