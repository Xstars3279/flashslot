package com.flashslot.auth.bootstrap;

import java.io.Console;
import java.io.PrintStream;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Explicit, standalone first-administrator initialization. Never starts a Spring context. */
public final class AdminBootstrap {
    private AdminBootstrap() {}

    public static int run(String[] args, Map<String,String> env, Console console,
            PrintStream out, PrintStream err) {
        char[] password=null;
        try {
            if(args.length!=1 || !"--bootstrap-admin".equals(args[0]))
                throw new BootstrapFailure("Use only --bootstrap-admin; credentials must not be command arguments.");
            String url=required(env,"DB_URL");
            String dbUser=required(env,"DB_USERNAME");
            String dbPassword=env.get("DB_PASSWORD");
            if(dbPassword==null) throw new BootstrapFailure("Missing required environment variable: DB_PASSWORD");
            if(!url.startsWith("jdbc:mysql:")) throw new BootstrapFailure("DB_URL must select a MySQL database.");
            String username=env.get("FLASHSLOT_BOOTSTRAP_USERNAME");
            if(username==null && console!=null) username=console.readLine("Administrator username: ");
            String value=env.get("FLASHSLOT_BOOTSTRAP_PASSWORD");
            if(value!=null) password=value.toCharArray();
            else if(console!=null) password=console.readPassword("Administrator password: ");
            validate(username,password);
            try(Connection connection=DriverManager.getConnection(url,dbUser,dbPassword)) {
                create(connection,username,password);
            }
            out.println("First administrator created. Bootstrap has exited.");
            return 0;
        } catch(BootstrapFailure e) {
            err.println("Administrator bootstrap refused: "+e.getMessage());
            return 1;
        } catch(Exception e) {
            // JDBC exceptions can contain full URLs, passwords or driver properties. Never print them.
            err.println("Administrator bootstrap failed. Verify database connectivity, permissions and completed schema migrations.");
            return 1;
        } finally {
            if(password!=null) Arrays.fill(password,'\0');
        }
    }

    private static String required(Map<String,String> env,String key) throws BootstrapFailure {
        String value=env.get(key);
        if(value==null || value.isBlank()) throw new BootstrapFailure("Missing required environment variable: "+key);
        return value;
    }

    private static void validate(String username,char[] password) throws BootstrapFailure {
        if(username==null || !username.matches("[A-Za-z0-9][A-Za-z0-9_.-]{2,63}"))
            throw new BootstrapFailure("Username must be 3–64 characters: letters, numbers, dot, underscore or hyphen; start with a letter or number.");
        if(password==null || password.length<8 || CharBuffer.wrap(password).toString().isBlank()
                || StandardCharsets.UTF_8.encode(CharBuffer.wrap(password)).remaining()>72)
            throw new BootstrapFailure("Password must contain at least 8 characters and at most 72 UTF-8 bytes.");
    }

    /** Caller must close this dedicated connection, releasing the MySQL advisory lock on every outcome. */
    static void create(Connection connection,String username,char[] password) throws SQLException,BootstrapFailure {
        validate(username,password);
        try(var statement=connection.prepareStatement("SELECT GET_LOCK('flashslot.bootstrap-admin', 10)");
                var result=statement.executeQuery()) {
            if(!result.next() || result.getInt(1)!=1 || result.wasNull())
                throw new BootstrapFailure("Another administrator bootstrap is running; retry after it exits.");
        }
        connection.setAutoCommit(false);
        try {
            checkSchema(connection);
            try(var statement=connection.prepareStatement("SELECT COUNT(*) FROM app_user WHERE role='ADMIN'");
                    var result=statement.executeQuery()) {
                result.next();
                if(result.getLong(1)>0) throw new BootstrapFailure("An ADMIN already exists (including disabled accounts); bootstrap cannot reset or replace it.");
            }
            String hash=new BCryptPasswordEncoder(12).encode(CharBuffer.wrap(password));
            try(var statement=connection.prepareStatement("INSERT INTO app_user "
                    +"(username,password_hash,role,enabled,credential_version,credit_score) VALUES (?,?,'ADMIN',1,0,100)")) {
                statement.setString(1,username);statement.setString(2,hash);
                statement.executeUpdate();
            }
            connection.commit();
        } catch(SQLException | BootstrapFailure | RuntimeException e) {
            try {connection.rollback();} catch(SQLException rollback) {e.addSuppressed(rollback);}
            throw e;
        }
    }

    private static void checkSchema(Connection connection) throws SQLException,BootstrapFailure {
        Set<String> expected=new HashSet<>();
        try {
            for(var resource:new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql")) {
                String filename=resource.getFilename();
                if(filename==null || !filename.matches("V[0-9]+(?:[._][0-9]+)*__.+\\.sql"))
                    throw new BootstrapFailure("Cannot determine the supported schema migrations.");
                expected.add(filename.substring(1,filename.indexOf("__")).replace('_','.'));
            }
        } catch(java.io.IOException e) {
            throw new BootstrapFailure("Cannot determine the supported schema migrations.");
        }
        if(expected.isEmpty()) throw new BootstrapFailure("No schema migrations are available in this application.");
        Set<String> migrated=new HashSet<>();
        try(var statement=connection.prepareStatement("SELECT version,success FROM flyway_schema_history WHERE version IS NOT NULL");
                var result=statement.executeQuery()) {
            while(result.next()) {
                if(!result.getBoolean("success")) throw new BootstrapFailure("Schema contains a failed migration; complete migrations separately first.");
                migrated.add(result.getString("version"));
            }
        }
        if(!migrated.equals(expected)) throw new BootstrapFailure("Schema is not at the supported Flyway version; complete migrations separately first.");
        try(var statement=connection.prepareStatement("SELECT id,username,password_hash,role,enabled,credential_version,"
                +"credit_score,restriction_until,created_at,updated_at,phone FROM app_user LIMIT 0");
                var ignored=statement.executeQuery()) {
            // Resolving the real columns refuses partially applied or manually fabricated old schemas.
        }
    }

    static final class BootstrapFailure extends Exception {
        BootstrapFailure(String message) {super(message);}
    }
}
