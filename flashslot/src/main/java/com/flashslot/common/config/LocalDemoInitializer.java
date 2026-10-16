package com.flashslot.common.config;

import com.flashslot.resource.service.ResourceCalendarService;
import lombok.RequiredArgsConstructor;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;

/** 仅显式demo配置下初始化独立本机体验库，不在正常local/production配置创建账号。 */
@Component
@Profile("demo")
@RequiredArgsConstructor
public class LocalDemoInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final Environment environment;
    private final ResourceCalendarService calendars;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments arguments) {
        String url = environment.getRequiredProperty("spring.datasource.url");
        String password = environment.getRequiredProperty("FLASHSLOT_DEMO_PASSWORD");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/flashslot_local\\?.*")
                || password.length() < 12)
            throw new IllegalStateException("demo仅允许独立回环地址flashslot_local数据库和至少12位体验密码");
        String hash = passwords.encode(password);
        for (String role : new String[] {"USER", "ADMIN"}) {
            String name = "ADMIN".equals(role) ? "demo-admin" : "demo-user";
            jdbc.update(
                    "INSERT INTO app_user(username,password_hash,role) VALUES (?,?,?) ON DUPLICATE"
                        + " KEY UPDATE"
                        + " password_hash=VALUES(password_hash),role=VALUES(role),enabled=1",
                    name,
                    hash,
                    role);
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM resource", Integer.class) == 0) {
            jdbc.update(
                    "INSERT INTO resource(name,location) VALUES ('3D打印机 01','创客中心 A201'),('数码显微镜"
                        + " 01','实验楼 B302'),('激光切割机 01','创客中心 A205')");
        }
        for (long id : jdbc.queryForList("SELECT id FROM resource", Long.class)) {
            calendars.ensureWindow(id);
        }
    }
}
