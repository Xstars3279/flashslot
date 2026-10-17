package com.flashslot.auth.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

class AdminBootstrapTest {
    @Test void mainExitsWithoutStartingSpringWhenBootstrapIsExplicit() throws Exception {
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin","java").toString();
        var process=new ProcessBuilder(javaExecutable,"-cp",System.getProperty("java.class.path"),
                "com.flashslot.FlashSlotApplication","--bootstrap-admin","--password=secret")
                .redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isNotZero();
            assertThat(new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8))
                    .contains("bootstrap refused").doesNotContain("secret","Spring Boot","Tomcat","Started FlashSlotApplication");
        } finally {process.destroyForcibly();}
    }
    @Test void rejectsInvalidCredentialsBeforeOpeningDatabaseAndNeverEchoesSecrets() {
        for (String username : new String[] {"ab", "-admin", " admin", "admin/1"}) {
            var env = environment(); env.put("FLASHSLOT_BOOTSTRAP_USERNAME", username);
            assertRejected(env, true);
        }
        for (String password : new String[] {"short", "x".repeat(73), "密".repeat(25), "        "}) {
            var env = environment(); env.put("FLASHSLOT_BOOTSTRAP_PASSWORD", password);
            assertRejected(env, true);
        }
    }

    @Test void missingDatabaseSettingsAndPasswordFailClosed() {
        for (String key : environment().keySet()) {
            var env = environment(); env.remove(key);
            assertRejected(env, true);
        }
    }

    @Test void connectionErrorsNeverExposeJdbcUrlUsernameOrPassword() {
        assertRejected(environment(), false);
    }

    @Test void explicitlyEmptyLocalDatabasePasswordIsPassedToDatabase() {
        var env=environment();env.put("DB_PASSWORD","");
        assertRejected(env,false);
    }

    @Test void acceptsProjectPasswordByteBoundariesBeforeConnecting() {
        for(String password : new String[] {"12345678", "x".repeat(72), "密".repeat(24)}) {
            var env=environment();env.put("FLASHSLOT_BOOTSTRAP_PASSWORD",password);
            var output=new ByteArrayOutputStream();
            int status=AdminBootstrap.run(new String[]{"--bootstrap-admin"},env,null,new PrintStream(output),new PrintStream(output));
            assertThat(status).isNotZero();
            assertThat(output.toString()).contains("bootstrap failed").doesNotContain("bootstrap refused",password);
        }
    }

    @Test void rejectsAdditionalArgumentsIncludingPasswordOptions() {
        var output = new ByteArrayOutputStream();
        int status = AdminBootstrap.run(new String[] {"--bootstrap-admin", "--password=secret"},
                environment(), null, new PrintStream(output), new PrintStream(output));
        assertThat(status).isNotZero();
        assertThat(output.toString()).doesNotContain("secret");
    }

    static Map<String, String> environment() {
        return new HashMap<>(Map.of("DB_URL", "jdbc:mysql://127.0.0.1:1/secret-database?connectTimeout=100&socketTimeout=100",
                "DB_USERNAME", "private-db-user", "DB_PASSWORD", "private-db-password",
                "FLASHSLOT_BOOTSTRAP_USERNAME", "first.admin", "FLASHSLOT_BOOTSTRAP_PASSWORD", "Bootstrap-secret!"));
    }

    static void assertRejected(Map<String, String> env, boolean refused) {
        var output = new ByteArrayOutputStream();
        int status = AdminBootstrap.run(new String[] {"--bootstrap-admin"}, env, null,
                new PrintStream(output), new PrintStream(output));
        assertThat(status).isNotZero();
        assertThat(output.toString()).contains(refused ? "bootstrap refused" : "bootstrap failed");
        assertThat(output.toString()).doesNotContain("private-host", "private-db-user", "private-db-password",
                "secret-database", "Bootstrap-secret!", "Exception", "jdbc:");
    }
}
