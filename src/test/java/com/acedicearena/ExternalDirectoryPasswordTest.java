package com.acedicearena;

import com.acedicearena.service.AccountService;
import com.acedicearena.service.ExternalDirectoryService;
import com.acedicearena.domain.UserAccount;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalDirectoryPasswordTest {
    @Test
    void allDirectoryUsersUseTheSameDefaultPassword() {
        assertThat(ExternalDirectoryService.defaultPasswordFor("钟小飞")).isEqualTo("123456");
        assertThat(ExternalDirectoryService.defaultPasswordFor("Alice Smith")).isEqualTo("123456");
        assertThat(ExternalDirectoryService.defaultPasswordFor(null)).isEqualTo("123456");
    }

    @Test
    void rejectsMissingDepartmentsFromTheGameDirectory() {
        assertThat(AccountService.hasUsableDepartment(null)).isFalse();
        assertThat(AccountService.hasUsableDepartment("")).isFalse();
        assertThat(AccountService.hasUsableDepartment("   ")).isFalse();
        assertThat(AccountService.hasUsableDepartment("未分配部门")).isFalse();
        assertThat(AccountService.hasUsableDepartment("销售一部")).isTrue();
    }

    @Test
    void deactivatesPreviouslySyncedUsersMissingFromTheActiveDirectory() {
        var repository = mock(com.acedicearena.repository.UserAccountRepository.class);
        var account = new UserAccount("disabled_user", "停用用户", "销售部", "USER",
                "external-directory", "00");
        account.assignTeam("t1");
        when(repository.findAll()).thenReturn(List.of(account));
        var service = new AccountService(repository, "admin123", Optional.empty());

        service.deactivateMissingExternalUsers(Set.of());

        assertThat(account.getRole()).isEqualTo("INACTIVE");
        assertThat(account.getTeamId()).isNull();
        verify(repository).saveAll(anyList());
    }

    @Test
    void reactivatesUserWhenDirectoryStatusReturnsToNormal() {
        var repository = mock(com.acedicearena.repository.UserAccountRepository.class);
        var account = new UserAccount("restored_user", "恢复用户", "旧部门", "USER",
                "external-directory", "00");
        account.deactivateExternalDirectoryAccount();
        when(repository.findByUsername("restored_user")).thenReturn(Optional.of(account));
        when(repository.save(account)).thenReturn(account);
        var service = new AccountService(repository, "admin123", Optional.empty());

        service.syncExternalUser(new ExternalDirectoryService.DirectoryUser(
                1L, "restored_user", "恢复用户", "新部门"));

        assertThat(account.getRole()).isEqualTo("USER");
        assertThat(account.getDepartment()).isEqualTo("新部门");
        verify(repository).save(account);
    }
}
