package com.flashslot.auth.bootstrap;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Creates only disposable schemas on the explicitly opted-in private MySQL instance. */
@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_TEST", matches="true")
class AdminBootstrapIntegrationTest {
    String schema;
    String url;
    static final String PASSWORD = "Bootstrap-test-password!";

    @BeforeEach void prepare() throws Exception {
        schema="bootstrap_test_"+UUID.randomUUID().toString().replace("-", "");
        if(!schema.matches("bootstrap_test_[0-9a-f]{32}")) throw new IllegalStateException("Invalid disposable schema identifier");
        try (var c=connect(System.getenv("DB_URL")); var s=c.createStatement()) {
            s.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        url=System.getenv("DB_URL").replaceFirst("(?<=/)[^/?]+(?=\\?|$)",schema);
        Flyway.configure().dataSource(url, System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))
                .load().migrate();
    }
    @AfterEach void cleanup() throws Exception {
        if(schema!=null) try(var c=connect(System.getenv("DB_URL"));var s=c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS `"+schema+"`");
        }
    }
    Connection connect(String target) throws SQLException {
        return DriverManager.getConnection(target,System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));
    }
    void create(String username) throws Exception {
        try(var c=connect(url)) { AdminBootstrap.create(c,username,PASSWORD.toCharArray()); }
    }
    @Test void standaloneCliCreatesAdminThenExitsWithoutStartingApplication() throws Exception {
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin","java").toString();
        var builder=new ProcessBuilder(javaExecutable,"-cp",System.getProperty("java.class.path"),
                "com.flashslot.FlashSlotApplication","--bootstrap-admin").redirectErrorStream(true);
        builder.environment().put("DB_URL",url);
        builder.environment().put("DB_USERNAME",System.getenv("DB_USERNAME"));
        builder.environment().put("DB_PASSWORD",System.getenv("DB_PASSWORD"));
        builder.environment().put("FLASHSLOT_BOOTSTRAP_USERNAME","process.admin");
        builder.environment().put("FLASHSLOT_BOOTSTRAP_PASSWORD",PASSWORD);
        var process=builder.start();
        try {
            assertThat(process.waitFor(20,TimeUnit.SECONDS)).isTrue();
            String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output).contains("First administrator created").doesNotContain(PASSWORD,url,
                    "jdbc:","Spring Boot","Tomcat","Started FlashSlotApplication");
        } finally {process.destroyForcibly();}
        try(var c=connect(url);var s=c.createStatement();var r=s.executeQuery("SELECT username,role FROM app_user")) {
            assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("process.admin");
            assertThat(r.getString(2)).isEqualTo("ADMIN");assertThat(r.next()).isFalse();
        }
    }
    @Test void createsEnabledAdminWithCompatibleHashAndNeverReinitializesDisabledAdmin() throws Exception {
        create("first.admin");
        try(var c=connect(url);var s=c.createStatement();var r=s.executeQuery("SELECT * FROM app_user")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getString("username")).isEqualTo("first.admin");
            assertThat(r.getString("role")).isEqualTo("ADMIN");
            assertThat(r.getBoolean("enabled")).isTrue();
            assertThat(r.getLong("credential_version")).isZero();
            assertThat(new BCryptPasswordEncoder().matches(PASSWORD,r.getString("password_hash"))).isTrue();
            assertThat(r.next()).isFalse();
        }
        try(var c=connect(url);var s=c.createStatement()) { s.executeUpdate("UPDATE app_user SET enabled=0"); }
        assertThatThrownBy(()->create("another.admin")).hasMessageContaining("already exists");
        try(var c=connect(url);var s=c.createStatement();var r=s.executeQuery("SELECT enabled,username FROM app_user")) {
            assertThat(r.next()).isTrue(); assertThat(r.getBoolean(1)).isFalse();
            assertThat(r.getString(2)).isEqualTo("first.admin"); assertThat(r.next()).isFalse();
        }
    }
    @Test void concurrentConnectionsAllowExactlyOneAdmin() throws Exception {
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            Callable<Boolean> one=()-> {start.await();try {create("first.admin");return true;}
                catch(AdminBootstrap.BootstrapFailure e){return false;}};
            Callable<Boolean> two=()-> {start.await();try {create("second.admin");return true;}
                catch(AdminBootstrap.BootstrapFailure e){return false;}};
            var a=pool.submit(one);var b=pool.submit(two);start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally {pool.shutdownNow();}
    }
    @Test void missingMigrationOrCredentialColumnFailsWithoutWriting() throws Exception {
        try(var c=connect(url);var s=c.createStatement()) {
            s.executeUpdate("DELETE FROM flyway_schema_history WHERE version='9'");
        }
        assertThatThrownBy(()->create("first.admin")).isInstanceOf(AdminBootstrap.BootstrapFailure.class);
        try(var c=connect(url);var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM app_user")) {
            r.next();assertThat(r.getInt(1)).isZero();
        }
    }
    @Test void missingRequiredColumnFailsWithoutWriting() throws Exception {
        try(var c=connect(url);var s=c.createStatement()) {s.execute("ALTER TABLE app_user DROP COLUMN credential_version");}
        assertThatThrownBy(()->create("first.admin")).isInstanceOf(SQLException.class);
        try(var c=connect(url);var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM app_user")) {
            r.next();assertThat(r.getInt(1)).isZero();
        }
    }
    @Test void futureSchemaVersionIsRefused() throws Exception {
        try(var c=connect(url);var s=c.createStatement()) {
            s.executeUpdate("UPDATE flyway_schema_history SET version='999' WHERE version='1'");
        }
        assertThatThrownBy(()->create("first.admin")).isInstanceOf(AdminBootstrap.BootstrapFailure.class);
    }
    @Test void usernameConflictRollsBackAndReleasesLockForRetry() throws Exception {
        try(var c=connect(url);var s=c.createStatement()) {
            s.executeUpdate("INSERT INTO app_user(username,password_hash,role) VALUES('first.admin','unused','USER')");
        }
        assertThatThrownBy(()->create("first.admin")).isInstanceOf(SQLException.class);
        create("second.admin");
        try(var c=connect(url);var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM app_user WHERE role='ADMIN'")) {
            r.next();assertThat(r.getInt(1)).isEqualTo(1);
        }
    }
}
