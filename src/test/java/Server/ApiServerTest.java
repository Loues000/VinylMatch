package Server;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiServerTest {

    @Test
    void createBindAddressUsesIpv4WildcardHost() {
        InetSocketAddress address = ApiServer.createBindAddress(8888);

        assertEquals("0.0.0.0", address.getAddress().getHostAddress());
        assertEquals(8888, address.getPort());
    }
}
