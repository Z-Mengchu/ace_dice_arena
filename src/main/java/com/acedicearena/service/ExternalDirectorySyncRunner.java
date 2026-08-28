package com.acedicearena.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "app.organization-datasource", name = "enabled", havingValue = "true")
public class ExternalDirectorySyncRunner implements ApplicationRunner {
    private final ExternalDirectoryService directory;
    private final AccountService accounts;

    public ExternalDirectorySyncRunner(ExternalDirectoryService directory, AccountService accounts) {
        this.directory = directory; this.accounts = accounts;
    }

    @Override
    public void run(ApplicationArguments args) {
        var activeUsers = directory.findAllActiveUsers();
        activeUsers.forEach(accounts::syncExternalUser);
        accounts.deactivateMissingExternalUsers(activeUsers.stream()
                .map(ExternalDirectoryService.DirectoryUser::username)
                .collect(Collectors.toUnmodifiableSet()));
    }
}
