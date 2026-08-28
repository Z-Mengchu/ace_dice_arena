package com.acedicearena.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix = "app.organization-datasource", name = "enabled", havingValue = "true")
public class ExternalDirectoryService {
    private static final String BASE_SQL = """
            SELECT u.user_id, u.user_name, u.nick_name, d.dept_name
            FROM sys_user u
            INNER JOIN sys_dept d ON d.dept_id = u.dept_id
            WHERE u.status = '0' AND u.del_flag = '0'
              AND d.status = '0' AND d.del_flag = '0'
              AND d.dept_name IS NOT NULL AND TRIM(d.dept_name) <> ''
            """;

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private volatile Map<String, DirectoryUser> activeUsers = Map.of();

    public ExternalDirectoryService(@Value("${app.organization-datasource.jdbc-url}") String jdbcUrl,
                                    @Value("${app.organization-datasource.username}") String username,
                                    @Value("${app.organization-datasource.password}") String password) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("OrganizationReadOnlyPool");
        config.setJdbcUrl(jdbcUrl); config.setUsername(username); config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setMaximumPoolSize(3); config.setMinimumIdle(0); config.setReadOnly(true);
        config.setConnectionTimeout(5000); config.setValidationTimeout(3000);
        this.dataSource = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public List<DirectoryUser> findAllActiveUsers() {
        List<DirectoryUser> users = jdbc.query(BASE_SQL + " ORDER BY u.user_id", (rs, row) -> map(rs));
        activeUsers = users.stream().collect(Collectors.toUnmodifiableMap(
                DirectoryUser::username, Function.identity(), (first, ignored) -> first));
        return users;
    }

    public DirectoryUser authenticate(String username, String rawPassword) {
        DirectoryUser found = activeUsers.get(username);
        if (found == null || rawPassword == null || !passwordEquals(rawPassword, defaultPasswordFor(found.displayName()))) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return found;
    }

    public static String defaultPasswordFor(String displayName) {
        return "123456";
    }

    private boolean passwordEquals(String actual, String expected) {
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private DirectoryUser map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DirectoryUser(rs.getLong("user_id"), rs.getString("user_name"),
                rs.getString("nick_name"), rs.getString("dept_name"));
    }

    @PreDestroy
    void close() { dataSource.close(); }

    public record DirectoryUser(long id, String username, String displayName, String department) {}
}
