# 服务器部署说明

本项目现由 Spring Boot 统一提供网页、登录、联机掷骰和数据持久化服务，不再需要运行 `server.js`。

浏览器端源码统一位于 `frontend/`，使用 Vite 多页面构建。`mvn package` 会自动安装项目固定版本的
Node/npm、执行 `npm install` 和 `npm run build`，再将 `frontend/dist/` 中带内容哈希的生产资源打包进 JAR。

## 快速启动

环境要求：Java 21、Maven 3.9+、MySQL 8.0+。Maven 打包不要求系统预装 Node；首次打包需要联网下载前端工具链和依赖。

本地前端开发需要 Node.js 22+。先启动 8082 端口的 Spring Boot 后端，再执行：

```bash
npm install
npm run dev
```

浏览器打开 `http://localhost:5173/login`。Vite 会热更新前端，并将 `/api` 和 SSE 请求代理到
`http://localhost:8082`。生产前端可单独执行 `npm run build`，产物位于 `frontend/dist/`。

系统支持两个独立 MySQL 数据源：

- 主数据源 `DB_*`：游戏业务库，读写战报、请求、比赛状态和本地账户映射；
- 组织数据源 `ORG_DB_*`：只读查询 `sys_user`、`sys_dept`，结构与 `ai/用户部门数据.sql` 一致。

这里的“主从”是应用层双数据源，不要求两套 MySQL 建立主从复制关系。建议为组织数据源单独创建只读账号：

```sql
CREATE USER 'arena_reader'@'游戏服务器IP' IDENTIFIED BY '只读账号密码';
GRANT SELECT ON `data-visual-manage`.`sys_user` TO 'arena_reader'@'游戏服务器IP';
GRANT SELECT ON `data-visual-manage`.`sys_dept` TO 'arena_reader'@'游戏服务器IP';
FLUSH PRIVILEGES;
```

先执行项目提供的初始化脚本：

```bash
mysql -u root -p < sql/schema.sql
```

脚本会创建 `ace_dice_arena` 数据库及业务表。生产环境使用 `ddl-auto: validate`，应用只校验表结构，不自动修改数据库。若数据库已经执行过旧版脚本，请按版本顺序执行迁移脚本：

```bash
mysql -u root -p ace_dice_arena < sql/migration_v2_lobby.sql
mysql -u root -p ace_dice_arena < sql/migration_v3_performance.sql
mysql -u root -p ace_dice_arena < sql/migration_v4_afk.sql
```

```bash
mvn clean package

# Linux / macOS
DB_HOST=127.0.0.1 DB_PORT=3306 DB_NAME=ace_dice_arena \
DB_USER=arena DB_PASSWORD='你的数据库密码' \
ORG_DB_ENABLED=true ORG_DB_HOST='组织库地址' ORG_DB_PORT=3306 \
ORG_DB_NAME='data-visual-manage' ORG_DB_USER='arena_reader' ORG_DB_PASSWORD='只读账号密码' \
ADMIN_PASSWORD='首次创建 admin 账户时使用的密码' \
java -jar target/ace-dice-arena-1.0.0.jar
```

Windows PowerShell：

```powershell
$env:DB_HOST = "127.0.0.1"
$env:DB_PORT = "3306"
$env:DB_NAME = "ace_dice_arena"
$env:DB_USER = "arena"
$env:DB_PASSWORD = "你的数据库密码"
$env:ORG_DB_ENABLED = "true"
$env:ORG_DB_HOST = "组织库地址"
$env:ORG_DB_PORT = "3306"
$env:ORG_DB_NAME = "data-visual-manage"
$env:ORG_DB_USER = "arena_reader"
$env:ORG_DB_PASSWORD = "只读账号密码"
$env:ADMIN_PASSWORD = "首次创建 admin 账户时使用的密码"
java -jar target/ace-dice-arena-1.0.0.jar
```

双方投骰后会独立进入本局结果展示阶段，默认停留 6 秒再由服务端自动进入下一局或后续赛程。可用 `RESULT_DISPLAY_MS` 调整展示毫秒数；`RESULT_SCAN_MS` 控制服务端检查到期结果的间隔，默认 500 毫秒。

默认监听 `8094` 端口：

- 登录入口：`http://服务器地址:8094/login`
- 管理员总控台：`http://服务器地址:8094/admin`
- 比赛主持台：`http://服务器地址:8094/`
- 用户准备大厅：`http://服务器地址:8094/lobby`
- 联机掷骰终端：`http://服务器地址:8094/player`

首次启动会创建本地 `admin` 管理员账户，密码由 `ADMIN_PASSWORD` 指定；未设置时才使用 `admin123`。启用组织数据源后，应用启动时会把有效用户的姓名、部门同步到游戏库，用户每次登录时也会重新读取其最新信息；登录页不再显示注册入口。

普通用户的登录账号取 `sys_user.user_name`，姓名取 `sys_user.nick_name`，部门取关联的 `sys_dept.dept_name`。组织库中的 `sys_user.password` 不会被读取或校验，所有普通用户统一使用密码 `123456`。只有 `status='0'` 且 `del_flag='0'` 的有效用户可以登录。

本轮固定 8 支队伍、每队 30 人、共 240 名参赛用户；每两队组成一个 60 人对战组。管理员可通过业绩表自动均衡分组，也可在分组后手动调整。只有 8 队满员且 240 人全部点击准备后才能开始游戏。

测试或现场统一确认时，管理员可点击“一键全员准备”，再选择“设为准备并标记挂机”或“仅设为准备”。第一种会将点击前尚未准备的真实玩家标记为“挂机”并交由系统托管，玩家返回后可在自己的页面取消挂机；第二种不会新增挂机状态。观众席用户不会被处理，人数或队伍容量不符合规则时仍不能开始游戏。

管理员总控台的赛前数据作业台提供 GMV Excel 模板下载。模板固定包含：`排名`、`部门`、`小组`、`负责人`、`订单量`、`销量`、`销售额（RMB）`、`上周同比销售额`。导入时用“负责人”匹配组织库中的用户姓名，用“销售额（RMB）”作为个人 GMV；匹配到的人员标记为前端，其余普通用户标记为后端。姓名未匹配、姓名重复、人数不足时页面会阻止分组并显示具体名单或原因。

总控台同时提供“下载截图测试数据”，其中已录入 `ai/企业微信截图_17873053229818.png` 的 95 行可见业绩。截图中同一负责人可能出现多行，导入时会按用户累计这些行的销售额，再将累计值作为该用户 GMV。

随机分组会把所有符合参赛条件的前端按 GMV 从高到低分配给当前总 GMV 最低的队伍，再随机补足后端。结果固定为 8 队、每队 30 人，并保证每队至少 5 名后端；超过 240 人的多余后端留在观战席。

测试账号排除名单仅在服务端配置，不通过前端接口返回。默认排除显示姓名为 `测试账号` 的示例账户；多个姓名用英文或中文逗号分隔：

```bash
GROUPING_TEST_ACCOUNT_NAMES='测试账号,自动化测试员,演示账户'
```

名单中的用户始终留在观战席，不计入 240 名参赛者、前端 GMV 或后端人数。

管理员点击开始后，八支队伍先依次投票选出队长、军师、王牌投手；全部角色产生后进入积累期，八队用完全部积累骰才会启动 4 个首轮对战组。之后每场由服务器按“预言 → 阵容展示 → 五人备战准备 → 队长发令与 3 秒倒计时 → 双方同时进攻 → 双方完成后自动判局”的固定流程独立推进；先完成进攻的一方会等待对手，不会提前结算。首轮结束后自动生成两场半决赛，再自动生成决赛。主持区是只读的实时赛事墙，可同时查看当前全部场次、阶段、骰点、攻击值和比分。

正式开赛后，每支队伍先进行一次全员角色投票。每名队员分别选择军师、王牌投手和队长候选人；王牌投手只能选择后端，队长只能选择前端，军师可选择任意队员。全队提交后按各角色最高票产生结果，平票按队伍成员的稳定顺序决出。角色在整届赛事中保持不变：只有军师可以预言，只有队长可以提交阵容并在五人全部准备后发号施令，最终骰子仍由王牌投手完成；服务端会对每次操作校验登录用户身份。

## 管理员双人沙盘

沙盘入口默认关闭，只在服务器显式设置 `GAME_TEST_MODE=true` 后出现在管理员总控台，普通用户页面和接口均不展示该入口。它会建立 8 队、每队 30 人的临时虚拟队员并直接开始比赛；管理员可以反复点击“推进入下一阶段”，让所有活跃赛场同步完成预言、阵容、确认、投骰和晋级，直至产生冠军，无需登录任何普通用户账号。

建立沙盘后可点击“查看玩家视角”打开只读监视器。监视器支持切换八支队伍，并按所选队伍展示普通用户实际看到的队伍成员、对战比分、军师预言、阵容选择、攻方确认和投骰等待状态；推进动作仍在管理员总控台完成。

如需真实测试，可在建立沙盘后指定两名不同的正式用户。两名用户可以加入同一队伍，用两个浏览器账号验证队内频道和共同操作；也可以分别加入同一对战组的两支相对队伍，分别完成双方的预言、阵容、队长确认、投手确认和五骰投掷。每名真实用户都会替换一个虚拟席位，因此队伍仍保持 30 人。

锁定双人后，总控台会显示“复制玩家 A/B 登录链接”。请分别在两个无痕窗口、不同浏览器或不同设备打开链接并登录；真实玩家会直接进入正式 `/lobby` 页面，使用与正式比赛完全相同的操作台、队内频道和本局结果页。“管理员只读监视器”仍是管理员查看其他队伍状态的沙盘页面，不作为真实玩家入口。

真实玩家未覆盖的对手操作由服务器自动补齐，其他赛场跟随双人所在赛场同步推进。若两名玩家位于相对队伍，首轮胜者会继续后续赛程，服务器会自动接管未配置的后续对手；若两人位于同队，任一玩家都可以完成本队当前操作，两人始终共享同一队内频道。

请只在隔离测试环境启用，因为建立沙盘时会把当前真实普通用户移到观战席，并占用当前全局比赛状态。测试完成后点击“清理沙盘”恢复准备阶段：

```powershell
$env:GAME_TEST_MODE = "true"
java -jar target/ace-dice-arena-1.0.0.jar
```

如需修改端口：

```bash
java -jar target/ace-dice-arena-1.0.0.jar --server.port=9000
```

## 500 人同时入场部署

不要让登录页、CSS 和 JavaScript 与游戏接口共同占用 Spring Boot 请求线程。项目已提供
`deploy/nginx-ace-dice-arena.conf`：它会从 `/home/app/game/frontend/dist` 直接返回构建后的页面和静态资源，
仅把 `/api/` 请求转发到 8082 端口，并为两个 SSE 地址关闭代理缓冲。

```bash
sudo cp deploy/nginx-ace-dice-arena.conf /etc/nginx/conf.d/ace-dice-arena.conf
sudo nginx -t
sudo systemctl reload nginx
```

如果实际部署目录或 Java 端口不同，先修改配置中的 `root` 或 `upstream`。应用默认允许 48 个登录请求并发，
其余浏览器会收到排队响应并自动随机重试；可通过 `LOGIN_MAX_CONCURRENT` 调整。主业务数据库连接池默认 50，
可通过 `DB_POOL_SIZE` 调整，但该值不能超过 MySQL `max_connections` 能承受的范围。

同时确认 `/etc/nginx/nginx.conf` 的文件句柄和 `events` 段至少允许 8192 个连接；500 个 SSE 长连接会同时占用客户端和上游连接，
Nginx 默认连接上限可能让登录页本身无法建立连接。

```nginx
worker_rlimit_nofile 65535;

events {
    worker_connections 8192;
}
```

JMeter 应给每个虚拟用户配置 HTTP Cookie Manager，并把连接超时设为 10 秒、响应超时设为 30 秒。
`/api/lobby/events` 和 `/api/events` 是不会主动结束的 SSE 长连接，不能作为普通 HTTP 请求等待完整响应，
否则 JMeter 会把正常保持在线的连接统计成“没有 response”。建议分别压测静态页面、`/api/auth/login`、
`/api/lobby` 和 `/api/game-state`，再用 5-10 秒 Ramp-up 验证真实入场过程。

## 持久化数据

初始化脚本会在 MySQL 中创建以下表：

- `game_state`：主持人端完整比赛进度；
- `battle_report`：逐条游戏战报；
- `request_audit`：HTTP 请求方法、端点、用户、来源 IP、状态码和耗时；
- `user_account`：登录账户及加盐后的密码摘要。
- `game_control`：准备、已分组和比赛中等全局阶段。
- `performance_record`：管理员导入的本轮前端业绩及姓名匹配结果。

备份使用 MySQL 自带的 `mysqldump`，不需要复制应用目录。

## 四小时版本的边界

- 面向单台服务器、单个 Spring Boot 实例；不支持集群部署。
- 使用 MySQL 8 持久化；当前由 `sql/schema.sql` 管理初始结构，后续结构变更可再引入 Flyway。
- 使用 `HttpSession` 登录，不引入 Spring Security；只按管理员/普通用户做页面跳转和必要的管理员操作拦截。
- 队内聊天使用 SSE 保存在服务器内存中，不写入数据库；应用重启后聊天记录清空。
- 比赛状态采用整份 JSON 覆盖保存，主持人应只开启一个控制页面，避免多个主持人同时操作导致后保存者覆盖前者。
- 正式公网部署应在 Nginx/Caddy 后配置 HTTPS，并修改或停用默认账户。
