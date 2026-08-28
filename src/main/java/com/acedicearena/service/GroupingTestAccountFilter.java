package com.acedicearena.service;

import com.acedicearena.domain.UserAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class GroupingTestAccountFilter {
    private final Set<String> excludedDisplayNames;

    public GroupingTestAccountFilter(
            @Value("${app.grouping.test-account-display-names:测试账号}") String configuredNames) {
        this.excludedDisplayNames = Arrays.stream(configuredNames.split("[,，]"))
                .map(this::normalize)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean excludes(UserAccount user) {
        return user != null && excludes(user.getDisplayName());
    }

    public boolean excludes(String displayName) {
        return excludedDisplayNames.contains(normalize(displayName));
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }
}
