package com.hmdp;

import com.hmdp.config.HttpConnectionConfig;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.coyote.Adapter;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HttpConnectionConfigTest {
    @Test void backendMustHonorTheUpstreamReuseWindow() throws Exception {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        new HttpConnectionConfig().upstreamKeepAlive(10000,65000).customize(factory);
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>) connector.getProtocolHandler();
        protocol.setPort(0);
        protocol.setAddress(InetAddress.getLoopbackAddress());
        protocol.setAdapter(mock(Adapter.class));
        try {
            protocol.init(); // An unbound Tomcat endpoint deliberately reports a limit of 1.
            assertEquals(100,protocol.getMaxKeepAliveRequests(),"Document the bundled Tomcat default");
            factory.getTomcatConnectorCustomizers().forEach(c -> c.customize(connector));
            assertEquals(10000,protocol.getMaxKeepAliveRequests());
            assertEquals(65000,protocol.getKeepAliveTimeout());
        } finally { protocol.destroy(); }
        assertThrows(IllegalArgumentException.class,() -> new HttpConnectionConfig().upstreamKeepAlive(0,65000));
    }
}
