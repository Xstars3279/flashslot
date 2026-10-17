package com.flashslot.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import java.net.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/** 仅指定代理可提供单个 X-Real-IP；入口必须覆盖该头，绝不接受客户端地址链。 */
@Component
public class ClientIpResolver {
    private final List<IpAddressMatcher> trusted;

    public ClientIpResolver(@Value("${app.security.trusted-proxies:}") String proxies) {
        trusted=Arrays.stream(proxies.split(",")).map(String::strip).filter(s -> !s.isEmpty())
            .map(IpAddressMatcher::new).toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer=literal(request.getRemoteAddr());
        if(peer==null) return "unknown";
        if(trusted.stream().anyMatch(m -> m.matches(peer))) {
            var headers=request.getHeaders("X-Real-IP");
            if(headers!=null && headers.hasMoreElements()) {
                String client=literal(headers.nextElement());
                if(client!=null && !headers.hasMoreElements()) return client;
            }
        }
        return peer;
    }

    private String literal(String value) {
        if(value==null || value.length()>45 || !value.matches("[0-9a-fA-F:.]+")
            || (!value.contains(":") && !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) return null;
        try { return InetAddress.getByName(value).getHostAddress(); }
        catch(UnknownHostException ex) { return null; }
    }
}
