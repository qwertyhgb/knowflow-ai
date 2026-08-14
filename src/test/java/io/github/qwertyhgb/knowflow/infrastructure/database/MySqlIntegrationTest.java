package io.github.qwertyhgb.knowflow.infrastructure.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 仅在显式设置 RUN_DATABASE_TESTS=true 时连接本地 Docker MySQL。
 */
@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class MySqlIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldConnectToMySql() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            resultSet.next();
            assertEquals(1, resultSet.getInt(1));
        }
    }
}
