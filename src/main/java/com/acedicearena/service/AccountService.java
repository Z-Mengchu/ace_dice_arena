package com.acedicearena.service;

import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.UserAccountRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

@Service
public class AccountService {
    private final UserAccountRepository repository;
    private final String initialAdminPassword;
    private final SecureRandom random = new SecureRandom();
    private final Optional<ExternalDirectoryService> externalDirectory;

    public AccountService(UserAccountRepository repository,
                          @Value("${app.admin-password:admin123}") String initialAdminPassword,
                          Optional<ExternalDirectoryService> externalDirectory) {
        this.repository = repository;
        this.initialAdminPassword = initialAdminPassword;
        this.externalDirectory = externalDirectory;
    }

    @PostConstruct
    @Transactional
    void createDefaultAccount() {
        if (!repository.existsByUsername("admin")) create("admin", "主持人", "赛事运营", initialAdminPassword, "ADMIN");
    }

    @Transactional
    public UserAccount register(String username, String displayName, String department, String password) {
        if (externalDirectory.isPresent()) throw new IllegalArgumentException("用户由公司组织数据库统一管理，无需在游戏中注册");
        validate(username, displayName, department, password);
        if (repository.existsByUsername(username)) throw new IllegalArgumentException("用户名已存在");
        return create(username, displayName, department, "123456", "USER");
    }

    private UserAccount create(String username, String displayName, String department, String password, String role) {
        byte[] saltBytes = new byte[16];
        random.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);
        return repository.save(new UserAccount(username, displayName, department, role, hash(password, salt), salt));
    }

    @Transactional
    public UserAccount login(String username, String password) {
        if (!"admin".equals(username) && externalDirectory.isPresent()) {
            ExternalDirectoryService.DirectoryUser directoryUser = externalDirectory.get().authenticate(username, password);
            return syncExternalUser(directoryUser);
        }
        UserAccount user = repository.findByUsername(username).orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        if ("USER".equals(user.getRole())) {
            if (password == null || !MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8),
                    "123456".getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("用户名或密码错误");
            }
            return user;
        }
        if (!MessageDigest.isEqual(user.getPasswordHash().getBytes(StandardCharsets.UTF_8),
                hash(password, user.getSalt()).getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return user;
    }

    public boolean isRegistrationEnabled() { return externalDirectory.isEmpty(); }

    @Transactional
    public UserAccount syncExternalUser(ExternalDirectoryService.DirectoryUser directoryUser) {
        if (!hasUsableDepartment(directoryUser.department()))
            throw new IllegalArgumentException("用户未配置有效部门，不能加入游戏");
        UserAccount user = repository.findByUsername(directoryUser.username()).orElse(null);
        String name = directoryUser.displayName() == null || directoryUser.displayName().isBlank()
                ? directoryUser.username() : directoryUser.displayName().trim();
        String department = directoryUser.department().trim();
        if (user == null) {
            // 外部普通账号使用统一初始密码认证，本地随机密码不会被校验，无需执行高成本 PBKDF2。
            return repository.save(new UserAccount(directoryUser.username(), name, department,
                    "USER", "external-directory", UUID.randomUUID().toString().replace("-", "")));
        }
        if (!"ADMIN".equals(user.getRole())) {
            if (user.syncExternalDirectoryProfile(name, department)) return repository.save(user);
        }
        return user;
    }

    /** 将外部目录中已停用、删除或不再满足筛选条件的历史账号移出当前游戏名单。 */
    @Transactional
    public void deactivateMissingExternalUsers(Set<String> activeUsernames) {
        var inactive = repository.findAll().stream()
                .filter(UserAccount::isExternalDirectoryManaged)
                .filter(user -> !activeUsernames.contains(user.getUsername()))
                .filter(UserAccount::deactivateExternalDirectoryAccount)
                .toList();
        if (!inactive.isEmpty()) repository.saveAll(inactive);
    }

    public static boolean hasUsableDepartment(String department) {
        return department != null && !department.isBlank() && !"未分配部门".equals(department.trim());
    }

    private void validate(String username, String displayName, String department, String password) {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,32}")) throw new IllegalArgumentException("用户名需为 3-32 位字母、数字或下划线");
        if (displayName == null || displayName.isBlank() || displayName.length() > 32) throw new IllegalArgumentException("显示名称需为 1-32 个字符");
        if (department == null || department.isBlank() || department.length() > 64) throw new IllegalArgumentException("部门需为 1-64 个字符");
        if (password == null || password.length() < 6 || password.length() > 64) throw new IllegalArgumentException("密码需为 6-64 个字符");
    }

    private String hash(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), HexFormat.of().parseHex(salt), 120_000, 256);
            return HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
