package com.acedicearena.service;

import com.acedicearena.domain.GameControl;
import com.acedicearena.domain.PerformanceRecord;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.GameControlRepository;
import com.acedicearena.repository.PerformanceRecordRepository;
import com.acedicearena.repository.UserAccountRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PerformanceImportService {
    public static final List<String> HEADERS = List.of(
            "排名", "部门", "小组", "负责人", "订单量", "销量", "销售额（RMB）", "上周同比销售额"
    );
    private static final int TEAM_COUNT = 8;
    private static final int TEAM_SIZE = LobbyService.TEAM_SIZE;
    private static final int MIN_BACK_END = 5;

    private final UserAccountRepository users;
    private final PerformanceRecordRepository records;
    private final GameControlRepository controls;
    private final LobbyEventService events;
    private final GroupingTestAccountFilter testAccounts;

    public PerformanceImportService(UserAccountRepository users, PerformanceRecordRepository records,
                                    GameControlRepository controls, LobbyEventService events,
                                    GroupingTestAccountFilter testAccounts) {
        this.users = users;
        this.records = records;
        this.controls = controls;
        this.events = events;
        this.testAccounts = testAccounts;
    }

    public byte[] template() {
        return buildTemplate(List.of());
    }

    public byte[] sampleTemplate() {
        ClassPathResource sample = new ClassPathResource("gmv-sample-data.csv");
        if (!sample.exists()) {
            return buildTemplate(List.of());
        }
        try (var input = sample.getInputStream()) {
            List<String> lines = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            List<String[]> rows = lines.stream().skip(1).filter(line -> !line.isBlank())
                    .map(line -> line.split(",", -1)).toList();
            return buildTemplate(rows);
        } catch (Exception e) {
            throw new IllegalStateException("生成 GMV 测试文件失败", e);
        }
    }

    private byte[] buildTemplate(List<String[]> sampleRows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("GMV业绩导入");
            Row header = sheet.createRow(0);
            CellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setAlignment(HorizontalAlignment.CENTER);
            Font font = workbook.createFont();
            font.setBold(true); font.setColor(IndexedColors.BLACK.getIndex());
            style.setFont(font);
            for (int i = 0; i < HEADERS.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS.get(i)); cell.setCellStyle(style);
                sheet.setColumnWidth(i, i == 3 ? 18 * 256 : 16 * 256);
            }
            sheet.createFreezePane(0, 1);
            for (int rowIndex = 0; rowIndex < sampleRows.size(); rowIndex++) {
                String[] values = sampleRows.get(rowIndex);
                Row row = sheet.createRow(rowIndex + 1);
                for (int column = 0; column < HEADERS.size(); column++) {
                    Cell cell = row.createCell(column);
                    if (column == 0 || column >= 4) cell.setCellValue(new BigDecimal(values[column]).doubleValue());
                    else cell.setCellValue(values[column]);
                }
            }
            if (!sampleRows.isEmpty()) sheet.setAutoFilter(new CellRangeAddress(0, sampleRows.size(), 0, HEADERS.size() - 1));
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成模板失败", e);
        }
    }

    @Transactional
    public ImportResult importFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择需要导入的 Excel 文件");
        List<RowData> rows = readRows(file);
        if (rows.isEmpty()) throw new IllegalArgumentException("表格中没有可导入的数据");
        if ("PLAYING".equals(control().getPhase())) throw new IllegalStateException("比赛进行中不能重新导入业绩");

        List<UserAccount> accounts = users.findAll().stream().filter(this::isPlayer).toList();
        Map<String, List<UserAccount>> byName = accounts.stream()
                .collect(Collectors.groupingBy(u -> normalize(u.getDisplayName())));
        List<PerformanceRecord> imported = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();
        Set<Long> matchedIds = new HashSet<>();
        Map<Long, BigDecimal> gmvByUser = new HashMap<>();

        for (RowData row : rows) {
            if (testAccounts.excludes(row.leader())) continue;
            List<UserAccount> candidates = byName.getOrDefault(normalize(row.leader()), List.of());
            if (candidates.size() > 1 && !normalize(row.department()).isEmpty()) {
                List<UserAccount> sameDepartment = candidates.stream()
                        .filter(u -> normalize(u.getDepartment()).equals(normalize(row.department()))).toList();
                if (sameDepartment.size() == 1) candidates = sameDepartment;
            }
            String status;
            Long userId = null;
            if (candidates.isEmpty()) {
                status = "UNMATCHED"; unmatched.add(row.leader());
            } else if (candidates.size() > 1) {
                status = "AMBIGUOUS"; ambiguous.add(row.leader());
            } else {
                UserAccount user = candidates.getFirst();
                status = "MATCHED"; userId = user.getId(); matchedIds.add(userId);
                gmvByUser.merge(userId, row.salesAmount(), BigDecimal::add);
            }
            imported.add(row.toEntity(status, userId));
        }
        if (imported.isEmpty()) throw new IllegalArgumentException("表格中没有可参与分组的业绩数据");

        accounts.forEach(user -> user.setPerformance(matchedIds.contains(user.getId()),
                gmvByUser.getOrDefault(user.getId(), BigDecimal.ZERO)));
        users.saveAll(accounts);
        records.deleteAllInBatch();
        records.saveAll(imported);
        accounts.forEach(u -> u.assignTeam(null));
        control().changePhase("PREPARING");
        events.stateChanged();
        return result(imported, unmatched, ambiguous);
    }

    @Transactional(readOnly = true)
    public ImportResult status() {
        List<PerformanceRecord> imported = records.findAll();
        List<String> unmatched = imported.stream().filter(r -> "UNMATCHED".equals(r.getMatchStatus()))
                .map(PerformanceRecord::getLeader).toList();
        List<String> ambiguous = imported.stream().filter(r -> "AMBIGUOUS".equals(r.getMatchStatus()))
                .map(PerformanceRecord::getLeader).toList();
        return result(imported, unmatched, ambiguous);
    }

    @Transactional
    public GroupingResult randomGroup() {
        if ("PLAYING".equals(control().getPhase())) throw new IllegalStateException("比赛进行中不能重新分组");
        ImportResult imported = status();
        if (imported.totalRows() == 0) throw new IllegalStateException("请先导入 GMV 业绩表");
        if (!imported.unmatchedNames().isEmpty() || !imported.ambiguousNames().isEmpty())
            throw new IllegalStateException("业绩表中仍有未匹配或重名人员，请修正后重新导入");

        users.deleteAll(users.findAll().stream().filter(LobbyService::isStandIn).toList());
        List<UserAccount> gameUsers = users.findAll().stream()
                .filter(user -> "USER".equals(user.getRole()) && !LobbyService.isStandIn(user)).toList();
        List<UserAccount> players = gameUsers.stream().filter(this::isPlayer).toList();
        List<UserAccount> all = players.stream().filter(user -> !testAccounts.excludes(user)).toList();
        List<UserAccount> frontEnds = all.stream().filter(UserAccount::isFrontEnd).collect(Collectors.toCollection(ArrayList::new));
        List<UserAccount> backEnds = all.stream().filter(u -> !u.isFrontEnd()).collect(Collectors.toCollection(ArrayList::new));
        int maxFrontEnds = TEAM_COUNT * (TEAM_SIZE - MIN_BACK_END);
        if (frontEnds.size() > maxFrontEnds)
            throw new IllegalStateException("前端人员超过 " + maxFrontEnds + " 人，无法保证每队至少 "
                    + MIN_BACK_END + " 名后端");
        int requiredBackEnds = TEAM_COUNT * TEAM_SIZE - frontEnds.size();
        if (backEnds.size() < requiredBackEnds)
            throw new IllegalStateException("后端人员不足：当前 " + backEnds.size() + " 人，至少需要 " + requiredBackEnds + " 人");

        Collections.shuffle(frontEnds);
        frontEnds.sort(Comparator.comparing(UserAccount::getGmv).reversed());
        List<TeamBucket> teams = new ArrayList<>();
        for (String teamId : LobbyService.TEAM_IDS) teams.add(new TeamBucket(teamId));
        Random random = new Random();
        for (UserAccount user : frontEnds) {
            List<TeamBucket> available = teams.stream().filter(t -> t.frontCount() < TEAM_SIZE - MIN_BACK_END)
                    .collect(Collectors.toCollection(ArrayList::new));
            Collections.shuffle(available, random);
            TeamBucket target = available.stream().min(Comparator.comparing(TeamBucket::gmv)
                    .thenComparingInt(TeamBucket::frontCount)).orElseThrow();
            target.addFront(user);
        }

        Collections.shuffle(backEnds, random);
        int backendIndex = 0;
        for (TeamBucket team : teams) {
            while (team.size() < TEAM_SIZE) team.addBack(backEnds.get(backendIndex++));
        }
        gameUsers.forEach(u -> u.assignTeam(null));
        teams.forEach(team -> team.members.forEach(u -> u.assignTeam(team.id)));
        users.saveAll(gameUsers);
        control().changePhase("GROUPED");
        events.stateChanged();
        return new GroupingResult(teams.stream().map(TeamBucket::view).toList(), frontEnds.size(), requiredBackEnds);
    }

    private ImportResult result(List<PerformanceRecord> imported, List<String> unmatched, List<String> ambiguous) {
        long matchedRows = imported.stream().filter(r -> "MATCHED".equals(r.getMatchStatus())).count();
        int matchedUsers = (int) imported.stream().filter(r -> "MATCHED".equals(r.getMatchStatus()))
                .map(PerformanceRecord::getMatchedUserId).filter(Objects::nonNull).distinct().count();
        BigDecimal total = imported.stream().filter(r -> "MATCHED".equals(r.getMatchStatus()))
                .map(PerformanceRecord::getSalesAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        int playerCount = (int) users.findAll().stream()
                .filter(this::isPlayer).filter(user -> !testAccounts.excludes(user)).count();
        int backendCount = playerCount - matchedUsers;
        int requiredBackends = TEAM_COUNT * TEAM_SIZE - matchedUsers;
        String issue = null;
        int maxFrontEnds = TEAM_COUNT * (TEAM_SIZE - MIN_BACK_END);
        int participantCount = TEAM_COUNT * TEAM_SIZE;
        if (matchedUsers > maxFrontEnds) issue = "前端超过 " + maxFrontEnds + " 人，无法保证每队至少 " + MIN_BACK_END + " 名后端";
        else if (backendCount < requiredBackends) issue = "用户总数不足 " + participantCount + " 人，还缺 " + (requiredBackends - backendCount) + " 名后端";
        boolean clean = !imported.isEmpty() && matchedRows == imported.size() && issue == null;
        return new ImportResult(imported.size(), matchedUsers, total, unmatched, ambiguous, issue, clean);
    }

    private List<RowData> readRows(MultipartFile file) {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) throw new IllegalArgumentException("Excel 缺少表头");
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Map<String, Integer> columns = new HashMap<>();
            Row header = sheet.getRow(sheet.getFirstRowNum());
            for (Cell cell : header) columns.put(formatter.formatCellValue(cell, evaluator).trim(), cell.getColumnIndex());
            List<String> missing = HEADERS.stream().filter(h -> !columns.containsKey(h)).toList();
            if (!missing.isEmpty()) throw new IllegalArgumentException("Excel 缺少表头：" + String.join("、", missing));
            List<RowData> rows = new ArrayList<>();
            for (int index = header.getRowNum() + 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) continue;
                String leader = text(row, columns.get("负责人"), formatter, evaluator);
                if (leader.isBlank()) continue;
                rows.add(new RowData(integer(row, columns.get("排名"), formatter, evaluator),
                        text(row, columns.get("部门"), formatter, evaluator),
                        text(row, columns.get("小组"), formatter, evaluator), leader,
                        number(row, columns.get("订单量"), formatter, evaluator),
                        number(row, columns.get("销量"), formatter, evaluator),
                        number(row, columns.get("销售额（RMB）"), formatter, evaluator),
                        number(row, columns.get("上周同比销售额"), formatter, evaluator)));
            }
            return rows;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法读取 Excel，请使用下载的 .xlsx 模板", e);
        }
    }

    private String text(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        Cell cell = row.getCell(column);
        return cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim();
    }
    private BigDecimal number(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        String raw = text(row, column, formatter, evaluator).replace(",", "").replace("¥", "").trim();
        if (raw.isEmpty() || "-".equals(raw)) return BigDecimal.ZERO;
        try { return new BigDecimal(raw); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("第 " + (row.getRowNum() + 1) + " 行存在非数字金额或数量"); }
    }
    private Integer integer(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        BigDecimal value = number(row, column, formatter, evaluator);
        return value.compareTo(BigDecimal.ZERO) == 0 ? null : value.intValue();
    }
    private boolean isPlayer(UserAccount user) {
        return "USER".equals(user.getRole()) && !LobbyService.isStandIn(user)
                && AccountService.hasUsableDepartment(user.getDepartment());
    }
    private GameControl control() { return controls.findById(1L).orElseGet(() -> controls.save(new GameControl(1L))); }
    private String normalize(String value) { return value == null ? "" : value.replaceAll("\\s+", "").trim(); }

    private record RowData(Integer ranking, String department, String group, String leader,
                           BigDecimal orderCount, BigDecimal salesQuantity, BigDecimal salesAmount,
                           BigDecimal lastWeekSalesAmount) {
        PerformanceRecord toEntity(String status, Long userId) {
            return new PerformanceRecord(ranking, department, group, leader, orderCount, salesQuantity,
                    salesAmount, lastWeekSalesAmount, status, userId);
        }
    }

    private static final class TeamBucket {
        private final String id;
        private final List<UserAccount> members = new ArrayList<>();
        private BigDecimal gmv = BigDecimal.ZERO;
        private int frontCount;
        private TeamBucket(String id) { this.id = id; }
        private void addFront(UserAccount user) { members.add(user); gmv = gmv.add(user.getGmv()); frontCount++; }
        private void addBack(UserAccount user) { members.add(user); }
        private int size() { return members.size(); }
        private int frontCount() { return frontCount; }
        private BigDecimal gmv() { return gmv; }
        private TeamResult view() { return new TeamResult(id, gmv, frontCount, size() - frontCount, size()); }
    }

    public record ImportResult(int totalRows, int matchedUsers, BigDecimal totalGmv,
                               List<String> unmatchedNames, List<String> ambiguousNames,
                               String groupingIssue, boolean canGroup) {}
    public record TeamResult(String teamId, BigDecimal gmv, int frontEndCount, int backEndCount, int totalCount) {}
    public record GroupingResult(List<TeamResult> teams, int frontEndCount, int backEndCount) {}
}
