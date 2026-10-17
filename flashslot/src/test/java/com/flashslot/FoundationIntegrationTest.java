package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.common.persistence.UtcInstantTypeHandler;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.sql.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

import javax.sql.DataSource;

/** 仅由私有临时 MySQL 验证脚本启用，普通构建不会连接数据库。 */
@EnabledIfEnvironmentVariable(named = "FLASHSLOT_PRIVATE_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundationIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    @Autowired TestRestTemplate http;

    @Test
    void entityColumnsMatchEveryMigratedTableAndJsonRoundTrips() throws Exception {
        String[] entities = {
            "auth.AppUser",
            "resource.Resource",
            "resource.ResourceDay",
            "booking.BookingOrder",
            "booking.SlotOccupation",
            "booking.UserSlotOccupation",
            "waitlist.WaitlistRecord",
            "waitlist.ReleaseJob",
            "credit.CreditLedger",
            "common.ApiIdempotency",
            "messaging.OutboxEvent",
            "messaging.ConsumerInbox",
            "messaging.Notification",
            "operations.AdminAudit"
        };
        try (Connection connection = dataSource.getConnection()) {
            for (String name : entities) {
                Class<?> entity = Class.forName("com.flashslot." + name.replace(".", ".entity."));
                String table = entity.getAnnotation(TableName.class).value();
                Set<String> fields = new HashSet<>();
                for (var field : entity.getDeclaredFields()) {
                    TableId id = field.getAnnotation(TableId.class);
                    TableField column = field.getAnnotation(TableField.class);
                    fields.add(id != null ? id.value() : column.value());
                    if (field.getName().equals("activeMarker")) {
                        assertThat(column.insertStrategy()).isEqualTo(FieldStrategy.NEVER);
                        assertThat(column.updateStrategy()).isEqualTo(FieldStrategy.NEVER);
                    }
                }
                Set<String> columns = new HashSet<>();
                try (ResultSet metadata =
                        connection
                                .getMetaData()
                                .getColumns(connection.getCatalog(), null, table, null)) {
                    while (metadata.next()) columns.add(metadata.getString("COLUMN_NAME"));
                }
                assertThat(fields).as(table).containsExactlyInAnyOrderElementsOf(columns);
            }
            var handler = new JacksonTypeHandler(JsonNode.class);
            JsonNode expected =
                    new ObjectMapper().readTree("{\"schemaVersion\":1,\"message\":\"预约成功\"}");
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TEMPORARY TABLE json_probe (payload JSON)");
            }
            try (PreparedStatement insert =
                    connection.prepareStatement("INSERT INTO json_probe VALUES (?)")) {
                handler.setNonNullParameter(insert, 1, expected, null);
                insert.executeUpdate();
            }
            try (Statement statement = connection.createStatement();
                    ResultSet row = statement.executeQuery("SELECT payload FROM json_probe")) {
                row.next();
                assertThat(handler.getNullableResult(row, "payload")).isEqualTo(expected);
            }
        }
    }

    @Test
    void migrationsAreRepeatableAndHttpFoundationStarts() throws Exception {
        assertThat(flyway.info().applied()).hasSize(10);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                    + " table_schema=DATABASE() AND table_name <>"
                                    + " 'flyway_schema_history'")) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(16);
            assertThat(connection.getTransactionIsolation())
                    .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        }
        assertThat(
                        http.getForEntity("/actuator/health/readiness", String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(http.getForEntity("/v3/api-docs", String.class).getBody())
                .contains("\"openapi\"");
        assertThat(http.getForEntity("/bookings", String.class).getStatusCode().is4xxClientError())
                .isTrue();
    }

    @Test
    void utcDatetimeRoundTripsUnderDifferentJvmTimezones() throws Exception {
        TimeZone original = TimeZone.getDefault();
        Instant expected = Instant.parse("2026-10-14T16:30:00.123Z");
        UtcInstantTypeHandler handler = new UtcInstantTypeHandler();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE instant_probe (value DATETIME(3))");
            for (String zone : new String[] {"UTC", "Asia/Shanghai", "America/New_York"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                statement.execute("DELETE FROM instant_probe");
                try (PreparedStatement insert =
                        connection.prepareStatement("INSERT INTO instant_probe VALUES (?)")) {
                    handler.setNonNullParameter(insert, 1, expected, null);
                    insert.executeUpdate();
                }
                try (ResultSet row = statement.executeQuery("SELECT value FROM instant_probe")) {
                    row.next();
                    assertThat(row.getString(1)).isEqualTo("2026-10-14 16:30:00.123");
                    assertThat(handler.getNullableResult(row, "value")).isEqualTo(expected);
                    assertThat(handler.getNullableResult(row, 1)).isEqualTo(expected);
                }
            }
            try (ResultSet row = statement.executeQuery("SELECT NULL AS value")) {
                row.next();
                assertThat(handler.getNullableResult(row, 1)).isNull();
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
