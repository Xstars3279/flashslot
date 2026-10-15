package com.flashslot.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.flashslot.auth.model.LoginUserDetails;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;

/** 测试身份显式携带账号 ID，业务代码不为测试 principal 提供查库降级分支。 */
public class TestUser implements RequestPostProcessor {
    private final Long id;
    private final String username;
    private String[] roles = {"USER"};

    public TestUser(JdbcTemplate jdbc, String username) {
        this.username = username;
        var ids =
                jdbc.queryForList("SELECT id FROM app_user WHERE username=?", Long.class, username);
        this.id = ids.isEmpty() ? Long.MAX_VALUE : ids.get(0);
    }

    public TestUser roles(String... roles) {
        this.roles = roles;
        return this;
    }

    @Override
    public MockHttpServletRequest postProcessRequest(MockHttpServletRequest request) {
        var authorities =
                Arrays.stream(roles)
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList();
        return user(new LoginUserDetails(id, username, "test-only", true, authorities))
                .postProcessRequest(request);
    }
}
