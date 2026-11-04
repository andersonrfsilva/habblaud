package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.httpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.io.IOException

class TlsTest {
    private val certificate = """MIIDHzCCAgegAwIBAgIUD27SuAthVzn2GBq2f8/wrKalUbMwDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MTEwMjE1NTU0MloXDTM2MTAz
MDE1NTU0MlowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEA2twzTpM/eYfFsDywl6Wwb5bRFXlBwPdZ9c5NJuyB4vDu
bKMAPWsd82XDLvIQgC6a5wEhs6bLeM8yIg0D+8fI0WJ5kmJBen/fP3LlW+s8gABx
HHd5M8FuudYFLwdQMUUq7LnbINrUjni1jYCDnzxt5O5yz94AckbABuwtYW2n8JmO
p9m0JVM5+I+JoAAh4paHTntZktA09Tnf3rU0I1j5HqGmg4fwnh8KMLYr/ZW6TmpV
58UhNPB0OBkHJjc0NTzK31pZho0mfnkPk0s1rosuCUaGKLSX6+WpTTQ6LlaZGEqT
sHattNtzRH56wNR0ymJoBxAvpaD/KEu7Ceprhz4qmQIDAQABo2kwZzAdBgNVHQ4E
FgQURnDtspD+6ygdqdnmo7n+lJ6igOowHwYDVR0jBBgwFoAURnDtspD+6ygdqdnm
o7n+lJ6igOowDwYDVR0TAQH/BAUwAwEB/zAUBgNVHREEDTALgglsb2NhbGhvc3Qw
DQYJKoZIhvcNAQELBQADggEBAJEGnDRB89kYE4xHTjWqSXaWUR89Vl8eheGTUSot
TmydgloHjWYFdLLTeLcuFhl3Pl0vqvJUe8qRde+wpyaKDGFJMH3NfAn1UqkSl5Xp
cF9YZxjPks+/lkbM1qSLupto3OmVRrB4lamwLD6CFDTSU4kNJeZZIRTGOHVRFu2s
UmF8b07glBl7ZFWFrG2Zr70axfJMqksUy1goruhNDzAFjU8AyUs+7Je4DPAruZDy
HUu33scxEDMGDMHia28qZ/oR1uRq6tLWyfzOUEBvHbi7CfPdwN7UDtL+b7W+qA7F
+qf0hC5bp75cIqIRs5BMbUGikMdsMHrQJObpNmmc2cj9nDY="""
    private val privateKey = """MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDa3DNOkz95h8Ww
PLCXpbBvltEVeUHA91n1zk0m7IHi8O5sowA9ax3zZcMu8hCALprnASGzpst4zzIi
DQP7x8jRYnmSYkF6f98/cuVb6zyAAHEcd3kzwW651gUvB1AxRSrsudsg2tSOeLWN
gIOfPG3k7nLP3gByRsAG7C1hbafwmY6n2bQlUzn4j4mgACHilodOe1mS0DT1Od/e
tTQjWPkeoaaDh/CeHwowtiv9lbpOalXnxSE08HQ4GQcmNzQ1PMrfWlmGjSZ+eQ+T
SzWuiy4JRoYotJfr5alNNDouVpkYSpOwdq2023NEfnrA1HTKYmgHEC+loP8oS7sJ
6muHPiqZAgMBAAECggEACT5LWxs0SBa2ce4KsmXP0po97UaUwuiaI0aIC90iYW3g
pcKbSIr/uWLn4DUlrQmR+JPi4Ub4AhgUV9nRGLKdRhYpoWQUvBlnkrL+yRJ9/DGI
lW6k/T7t03gcpJnxsTaTaPkyvF1+Fjz9xDDwwbiUpO/MvVUtvheZTpw09DqPC8Xs
gH7PYs/Twg3inJduPvNTKvRywpzRbjmEsmLD1efOFK5K8ZM7MfHBKNrOd+wkr9DU
xrhPvdQOFjwVqZmmmHC7fIcfUBISple96rN9xXgr7KXCboY8x/tZgYZ6UpMLg9oC
zsAHg2l6+sb5vF5jSsdRKQGHZUklBayKZN9XmfRU/QKBgQDwAWbtbulEn9E7Nxzw
7gfODDwTvdvk4YDEo12xtTZwdaNYCs8CNLQz/LjTY/u9Vdbew9Z00QqVKH3boCcU
XussEbm2IWgfre71AZVGFehf0XYb57aS4AG8r/VObns/C26dAoIfp96yu09G9ZRQ
xTvbgRKuLYyVQ2O7m2AEzvN60wKBgQDpcgzNaCCx/p6yEa0sB0UPeVg4SByVLuDn
keui4iEOJi3UUNVDuNblS187Q86xGk6pCJlIn1tEwxdkgXNeJ/9k1FqOcaA3pC4q
f35gy+P7/HHXtD5yOaPN/cZ2arZRI8S6mqkhl4lsQRvLNUh/AP5vTtqz8bmfVCEJ
bG1wagLJYwKBgQCApL++1IxENEW6L7hbi7qzPuuUyVdsP1L+E3LNJgygCu4a6vA6
hnVwyC1pYVY2DiS2EtKImuPJP4L2jclkqdv0KywX66AcGubSfJ6+ik+bzHI6UuER
qPBFaytZdu6oxD/t+MI2pYxA0RWVO1zcTlvfNDgHFHMeLpjunkHBUgw/8QKBgCgg
W4T/LgeKbbrMAQk9YqiLQhfChW87NJt5YJiJ6Aanl18UWeQfJIkbUI4+VIvqqrSx
bq+l/KkwI5g6DBuWIncxbTNo4pJN3UWezplS0qhgtyzNpaI6uCZTRcbbBuWhkVJF
/WZxxsRd2KowSXrTL+jwE/W0SiTR8C/PfGxDPKFjAoGAIMA7lMAZNCLkuQplWbNO
Qk7WRiAtZhEZlROEWy6/bC6PdmH9FS/+2M76WT2rz/37kO1VsXWY3BhVabzEyiZh
aSzMH65cQNccOVjC1dskZ/C7Ftp5gfNHnUWr2x7UNR+NUozsZP1A19GbU7n/OSyU
DYbmqhiT9tm3Jp/K4SYOko0="""

    @Test fun validCertificateWorksAndUntrustedOrWrongHostFails() = runBlocking {
        val decoder = Base64.getMimeDecoder()
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(decoder.decode(certificate)))
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decoder.decode(privateKey)))
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setKeyEntry("test", key, charArrayOf(), arrayOf(cert)) }
        val keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, charArrayOf()) }
        val serverTls = SSLContext.getInstance("TLS").apply { init(keyManager.keyManagers, null, null) }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("test", cert) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
        val trust = managers.trustManagers.single() as X509TrustManager
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val trusted = HttpClient(OkHttp) {
            engine { config { sslSocketFactory(tls.socketFactory, trust); retryOnConnectionFailure(false) } }
        }
        val untrusted = httpClient()
        try { MockWebServer().use { server ->
            server.useHttps(serverTls.socketFactory, false)
            server.enqueue(MockResponse().setBody("ok"))
            server.start()
            val localhost = server.url("/").newBuilder().host("localhost").build()
            assertEquals("ok", trusted.get(localhost.toString()).bodyAsText())
            try { untrusted.get(localhost.toString()); fail("Untrusted certificate accepted") } catch (_: IOException) { }
            val wrongHost = localhost.newBuilder().host("127.0.0.1").build()
            try { trusted.get(wrongHost.toString()); fail("Wrong hostname accepted") } catch (_: IOException) { }
        } } finally { trusted.close(); untrusted.close() }
    }
}
