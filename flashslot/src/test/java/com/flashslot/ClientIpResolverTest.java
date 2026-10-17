package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import com.flashslot.auth.service.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {
    @Test void directClientsCannotOverrideTheirAddress() {
        var request=request("198.51.100.8","203.0.113.1");
        request.addHeader("X-Forwarded-For","203.0.113.2");
        assertThat(new ClientIpResolver("192.0.2.254/32").resolve(request)).isEqualTo("198.51.100.8");
        assertThat(new ClientIpResolver("").resolve(request("192.0.2.254","203.0.113.1"))).isEqualTo("192.0.2.254");
    }

    @Test void trustedProxyAcceptsOnlyASingleLiteralAddress() {
        var resolver=new ClientIpResolver("192.0.2.254/32,2001:db8::/64");
        assertThat(resolver.resolve(request("192.0.2.254","203.0.113.1"))).isEqualTo("203.0.113.1");
        for(String header:new String[]{"example.org","127.1","203.0.113.1, 203.0.113.2","999.1.1.1",""})
            assertThat(resolver.resolve(request("192.0.2.254",header))).isEqualTo("192.0.2.254");
        var duplicate=request("192.0.2.254","203.0.113.1");
        duplicate.addHeader("X-Real-IP","203.0.113.2");
        assertThat(resolver.resolve(duplicate)).isEqualTo("192.0.2.254");
        assertThat(resolver.resolve(request("2001:db8::1","2001:db8::2")))
            .isEqualTo("2001:db8:0:0:0:0:0:2");
    }

    private MockHttpServletRequest request(String peer,String header) {
        var request=new MockHttpServletRequest();
        request.setRemoteAddr(peer); request.addHeader("X-Real-IP",header); return request;
    }
}
