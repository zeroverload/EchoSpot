# 启动项目（共享设备租赁版）

## 0. 环境准备

- JDK 8+
- Maven 3.6+
- MySQL 5.7/8.x
- Redis 6+
- RabbitMQ 3.x（需要启用并保证能连接）
- （可选）Nginx：本项目仓库内自带 Windows 版 `frontend-nginx/nginx.exe`

默认端口（可在配置中修改）：

- 后端：`8081`（`src/main/resources/application.yaml:2`）
- 前端 Nginx：`8082`（`frontend-nginx/conf/nginx.conf:12`）
- Redis：`6379`
- RabbitMQ：`5672`

## 1. 初始化数据库

1) 创建数据库（库名默认 `hmdp`）：

```sql
CREATE DATABASE IF NOT EXISTS hmdp DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE hmdp;
```

2) 导入原项目表和数据：

- `src/main/resources/db/hmdp.sql`

3) 导入租赁系统新增表和示例设备数据：

- `src/main/resources/db/rental.sql`

说明：

- 本项目“租赁版前端”沿用后端的站点/店铺数据模型：站点数据来自 `tb_shop`（接口 `/shop/of/name`），因此前端页面里仍用 `shopId` 作为 `stationId`。
- `rental.sql` 示例数据把 `station_id=1` 视作一个站点（对应 `tb_shop.id=1`）。如果你要在其它店铺页面展示设备，把 `tb_device.station_id` 改为对应的 `tb_shop.id` 即可。

## 2. 启动 Redis / RabbitMQ

- Redis：确保 `127.0.0.1:6379` 可访问
- RabbitMQ：确保 `localhost:5672` 可访问（用户名/密码默认 `guest/guest`）

如果你暂时没有 RabbitMQ（或连接不上），可以先关闭 MQ 消费端，避免控制台持续报错：

- `src/main/resources/application.yaml:35` 设置 `rental.mq.enabled: false`

此时会完全关闭 MQ 监听与队列/交换机声明，后端启动不会依赖 RabbitMQ；租借/归还接口会在 MQ 发送失败后自动降级为“同步落库”（仍保持 Redis+Lua 的原子校验入口）。

## 3. 配置后端连接信息

编辑 `src/main/resources/application.yaml:1`：

- `spring.datasource.*`：MySQL 地址、账号、密码
- `spring.redis.*`：Redis 地址、端口
- `spring.rabbitmq.*`：RabbitMQ 地址、端口、账号、密码

## 4. 启动后端

项目根目录执行：

```bash
mvn -DskipTests spring-boot:run
```

或：

```bash
mvn -DskipTests package
java -jar target/EchoSpot-0.0.1-SNAPSHOT.jar
```

如果你更新了代码但访问接口仍提示 404（例如 `/station/1/devices` 不存在），通常是后端没有重新编译/重启。建议用下面命令强制全量重建：

```bash
mvn -DskipTests clean package
java -jar target/EchoSpot-0.0.1-SNAPSHOT.jar
```

如果仍然 404，可打开“映射打印”帮助定位你到底启动了哪一版后端：

- `src/main/resources/application.yaml:43` 设置 `rental.debug-mappings: true` 后重启后端
- 启动日志会打印 `/station/**`、`/rental/**` 等接口映射

关键说明（库存初始化）：

- 设备库存高并发路径以 Redis 为准：`rental:stock:{stationId}:{deviceId}`
- 后端启动时会自动把 `tb_device.stock` 预热到 Redis（仅当 key 不存在时）：
  - 代码：`src/main/java/com/zeroverload/config/RentalStockWarmupRunner.java:1`

## 5. 启动前端（沿用现有页面）

本仓库前端为静态页面 + Nginx 反向代理：

1) 启动 Nginx（Windows 示例，在项目根目录执行）：

```bat
cd frontend-nginx
nginx.exe -p . -c conf/nginx.conf
```

2) 访问：

- `http://localhost:8082/`

前端会通过 `/api` 代理到后端（见 `frontend-nginx/conf/nginx.conf:32`），无需改前端域名。

补充（更省事的本地联调方式）：

- 也可以直接双击打开 `frontend-nginx/html/rental/index.html`（或用任意静态服务器托管该目录）。
- 前端会自动直连后端 `http://127.0.0.1:8081`（无需 Nginx 代理），后端已开启 CORS 允许跨域请求。

如果你更新了前端文件但浏览器仍显示旧页面：

- 确认你启动的是本仓库的 Nginx，并且使用了本仓库的配置文件（推荐按上面的 `-p . -c conf/nginx.conf` 启动）
- 重载 Nginx：`nginx.exe -s reload -p . -c conf/nginx.conf`
- 或停止后重新启动：`nginx.exe -s stop -p . -c conf/nginx.conf` 然后再运行启动命令
- 浏览器强制刷新（Windows 通常是 `Ctrl+F5`）
- 如果仍不对：检查 `frontend-nginx/logs/error.log`，以及确认 8082 端口是否被其它程序占用

## 6. 功能验证（租借/归还）

建议先直接用浏览器或 curl 验证后端接口存在（不经过 Nginx）：

- `http://127.0.0.1:8081/__version`（若 404，说明你跑的不是租赁后端这版代码）
- `http://127.0.0.1:8081/shop/1`
- `http://127.0.0.1:8081/shop/of/name?name=&current=1`
- `http://127.0.0.1:8081/station/1/devices`

1) 先登录（前端页面 `login.html`）
2) 打开站点列表与站点详情页：

- 站点列表：`http://localhost:8082/stations.html`
- 站点详情：`http://localhost:8082/station.html?id=1`

3) 在站点详情页的“共享设备租赁”区域：

- 点击“租借”会调用：`POST /rental/rent/{stationId}/{deviceId}`
- 点击“归还”会调用：`POST /rental/return/{orderId}`

如果设备列表为空：

- 检查 `tb_device` 是否有 `station_id=当前 shopId` 的数据
- 检查后端接口：`GET /station/{stationId}/devices`

验证码说明（本地开发）：

- 默认开启 `auth.sms.mock: true`：`POST /user/code` 会把验证码直接返回给前端，并自动填入登录页输入框。
- 如需更贴近线上行为，可在 `src/main/resources/application.yaml` 里将 `auth.sms.mock` 设为 `false`，此时验证码仅打印在后端日志中。
