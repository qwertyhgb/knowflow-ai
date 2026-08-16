package io.github.qwertyhgb.knowflow.infrastructure.database;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway 迁移验证（H2 内存库，MODE=MySQL）。
 *
 * <p>应用启动时 Flyway 会在 H2 上按序执行 {@code db/migration/} 下的全部迁移脚本，
 * 本测试验证迁移确实成功：版本历史记录、各业务表及关键字段都与脚本一致。
 * 真实 MySQL 上的执行由 {@link MySqlIntegrationTest}（需 {@code RUN_DATABASE_TESTS=true}）覆盖。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationTest {

    /** 已发布的迁移版本 → 文件名描述（Flyway 将 {@code V2__create_enterprise.sql} 解析为 "create enterprise"）。 */
    private static final Map<String, String> EXPECTED_MIGRATIONS = Map.of(
            "1", "create sys user",
            "2", "create enterprise",
            "3", "create enterprise member",
            "4", "create enterprise invitation",
            "5", "create enterprise department",
            "6", "create enterprise rbac tables",
            "7", "drop member role from enterprise member");

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldApplyPublishedMigrationsOnH2() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             // Flyway 以带引号的小写名创建历史表与列，H2 中须用双引号精确匹配。
             ResultSet history = statement.executeQuery(
                     "SELECT \"version\", \"description\", \"success\" " +
                             "FROM \"flyway_schema_history\" WHERE \"version\" IS NOT NULL " +
                             "ORDER BY \"installed_rank\"")) {

            Set<String> foundVersions = new HashSet<>();
            while (history.next()) {
                String version = history.getString(1);
                String expectedDescription = EXPECTED_MIGRATIONS.get(version);
                if (expectedDescription == null) {
                    // 新增更高版本迁移后，本测试仍只验证这里声明的已发布基线版本。
                    continue;
                }
                assertTrue(foundVersions.add(version), "迁移版本不应重复: " + version);
                assertEquals(expectedDescription, history.getString(2), "description 列");
                assertTrue(history.getBoolean(3), "version=" + version + " 的迁移应标记为成功");
            }
            assertEquals(EXPECTED_MIGRATIONS.keySet(), foundVersions,
                    "V1-V5 基线迁移都应存在且执行成功");
        }
    }

    @Test
    void shouldCreateSysUserTableWithExpectedColumns() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertTableExists(statement, "sys_user");
            assertEquals(Set.of("id", "email", "password_hash", "nickname", "status", "created_at", "updated_at"),
                    tableColumns(statement, "sys_user"));
        }
    }

    @Test
    void shouldCreateEnterpriseTablesWithExpectedColumns() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertTableExists(statement, "enterprise");
            assertEquals(Set.of("id", "name", "slug", "status", "created_at", "updated_at"),
                    tableColumns(statement, "enterprise"));

            assertTableExists(statement, "enterprise_member");
            // V7 已删除 V3 遗留的 member_role 列，角色统一经 role_id 关联 enterprise_role。
            assertEquals(Set.of("id", "enterprise_id", "user_id", "role_id", "status",
                            "joined_at", "created_at", "updated_at"),
                    tableColumns(statement, "enterprise_member"));

            assertTableExists(statement, "enterprise_invitation");
            assertEquals(Set.of("id", "enterprise_id", "inviter_user_id", "invitee_email",
                            "member_role", "token_hash", "status", "expires_at",
                            "accepted_by_user_id", "accepted_at", "created_at", "updated_at"),
                    tableColumns(statement, "enterprise_invitation"));

            assertTableExists(statement, "enterprise_department");
            assertEquals(Set.of("id", "enterprise_id", "parent_id", "name", "sort_order",
                            "status", "created_at", "updated_at"),
                    tableColumns(statement, "enterprise_department"));
        }
    }

    @Test
    void shouldCreateEnterpriseRbacTablesWithExpectedColumns() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertTableExists(statement, "enterprise_role");
            assertEquals(Set.of("id", "enterprise_id", "code", "name", "description",
                            "status", "created_at", "updated_at"),
                    tableColumns(statement, "enterprise_role"));

            assertTableExists(statement, "permission");
            assertEquals(Set.of("id", "code", "name", "created_at", "updated_at"),
                    tableColumns(statement, "permission"));

            assertTableExists(statement, "enterprise_role_permission");
            assertEquals(Set.of("id", "enterprise_role_id", "permission_id", "created_at"),
                    tableColumns(statement, "enterprise_role_permission"));
        }
    }

    private void assertTableExists(Statement statement, String table) throws Exception {
        // H2 未加引号的标识符以大写存储，用 UPPER() 兼容大小写差异。
        try (ResultSet tables = statement.executeQuery(
                "SELECT COUNT(*) AS c FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = '" + table.toUpperCase() + "'")) {
            tables.next();
            assertEquals(1, tables.getInt("c"), table + " 表应存在");
        }
    }

    private Set<String> tableColumns(Statement statement, String table) throws Exception {
        Set<String> columns = new HashSet<>();
        try (ResultSet cols = statement.executeQuery(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = '" + table.toUpperCase() + "'")) {
            while (cols.next()) {
                columns.add(cols.getString(1).toLowerCase());
            }
        }
        return columns;
    }
}
