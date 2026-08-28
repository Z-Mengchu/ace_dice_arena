package com.acedicearena;

import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.PerformanceRecordRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.acedicearena.service.LobbyService;
import com.acedicearena.service.PerformanceImportService;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PerformanceImportServiceTest {
    @Autowired PerformanceImportService performance;
    @Autowired UserAccountRepository users;
    @Autowired PerformanceRecordRepository records;
    @Autowired LobbyService lobby;

    @Test
    void screenshotSampleContainsImportableRowsAndLightGrayHeaders() throws Exception {
        byte[] sample = performance.sampleTemplate();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(sample))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(95);
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("林锦云");
            assertThat(sheet.getRow(1).getCell(6).getNumericCellValue()).isEqualTo(94485d);
            assertThat(sheet.getRow(0).getCell(0).getCellStyle().getFillForegroundColor())
                    .isEqualTo(IndexedColors.GREY_25_PERCENT.getIndex());
        }
    }

    @Test
    void templateImportAndGroupingProduceEightBalancedTeams() throws Exception {
        records.deleteAll();
        users.deleteAll(users.findAll().stream().filter(u -> "USER".equals(u.getRole())).toList());
        List<UserAccount> accounts = new ArrayList<>();
        for (int i = 1; i <= LobbyService.PARTICIPANT_COUNT; i++) {
            String name = i <= 80 ? "前端" + i : "后端" + i;
            accounts.add(new UserAccount("group_user_" + i, name, "测试部门", "USER", "hash", "00"));
        }
        accounts.add(new UserAccount("ignored_test_user", "测试账号", "测试部门", "USER", "hash", "00"));
        accounts.add(new UserAccount("missing_department_user", "无部门用户", "未分配部门", "USER", "hash", "00"));
        users.saveAll(accounts);
        assertThat(lobby.view("admin").teams()).flatExtracting(LobbyService.TeamView::members)
                .noneMatch(user -> "missing_department_user".equals(user.username()));

        byte[] template = performance.template();
        byte[] importFile;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(template));
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            for (int i = 0; i < PerformanceImportService.HEADERS.size(); i++) {
                assertThat(sheet.getRow(0).getCell(i).getStringCellValue()).isEqualTo(PerformanceImportService.HEADERS.get(i));
                var headerCell = sheet.getRow(0).getCell(i);
                assertThat(headerCell.getCellStyle().getFillForegroundColor())
                        .isEqualTo(IndexedColors.GREY_25_PERCENT.getIndex());
                assertThat(workbook.getFontAt(headerCell.getCellStyle().getFontIndex()).getColor())
                        .isEqualTo(IndexedColors.BLACK.getIndex());
            }
            for (int i = 1; i <= 80; i++) {
                var row = sheet.createRow(i);
                row.createCell(0).setCellValue(i);
                row.createCell(1).setCellValue("测试部门");
                row.createCell(2).setCellValue("小组" + i);
                row.createCell(3).setCellValue("前端" + i);
                row.createCell(4).setCellValue(10 + i);
                row.createCell(5).setCellValue(20 + i);
                row.createCell(6).setCellValue(100_000 - i * 317);
                row.createCell(7).setCellValue(80_000 - i * 251);
            }
            var repeatedLeader = sheet.createRow(81);
            repeatedLeader.createCell(0).setCellValue(81);
            repeatedLeader.createCell(1).setCellValue("测试部门");
            repeatedLeader.createCell(2).setCellValue("第二渠道");
            repeatedLeader.createCell(3).setCellValue("前端1");
            repeatedLeader.createCell(4).setCellValue(5);
            repeatedLeader.createCell(5).setCellValue(5);
            repeatedLeader.createCell(6).setCellValue(5_000);
            repeatedLeader.createCell(7).setCellValue(4_000);
            workbook.write(output);
            importFile = output.toByteArray();
        }

        var imported = performance.importFile(new MockMultipartFile("file", "gmv.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", importFile));
        assertThat(imported.totalRows()).isEqualTo(81);
        assertThat(imported.matchedUsers()).isEqualTo(80);
        assertThat(imported.canGroup()).isTrue();
        assertThat(users.findByUsername("group_user_1").orElseThrow().getGmv())
                .isEqualByComparingTo("104683");
        assertThat(users.findByUsername("group_user_1").orElseThrow().isFrontEnd()).isTrue();
        assertThat(users.findByUsername("group_user_81").orElseThrow().isFrontEnd()).isFalse();

        var grouped = performance.randomGroup();
        assertThat(grouped.teams()).hasSize(8).allSatisfy(team -> {
            assertThat(team.totalCount()).isEqualTo(LobbyService.TEAM_SIZE);
            assertThat(team.backEndCount()).isGreaterThanOrEqualTo(5);
        });
        BigDecimal minimum = grouped.teams().stream().map(PerformanceImportService.TeamResult::gmv).min(BigDecimal::compareTo).orElseThrow();
        BigDecimal maximum = grouped.teams().stream().map(PerformanceImportService.TeamResult::gmv).max(BigDecimal::compareTo).orElseThrow();
        assertThat(maximum.subtract(minimum)).isLessThan(new BigDecimal("100000"));
        assertThat(users.findByUsername("ignored_test_user").orElseThrow().getTeamId()).isNull();
        assertThat(users.findByUsername("missing_department_user").orElseThrow().getTeamId()).isNull();
    }
}
