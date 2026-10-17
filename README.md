# README

ECShopX（PHP 电商后端）的 Kotlin / Spring Boot 重写版。多模块 Maven 工程，接口按源系统 wire 契约对齐：
`{"data": …}` 包络、snake_case 字段、金额一律整数分、时间戳 unix 秒、错误体 `{message, errors, code, status_code}`。

## 快启（30 秒）

前置：JDK 17+、Maven 3.9+（只需一次构建，运行期只依赖 JDK）。

Windows:

```bat
run.bat
```

Linux / macOS / WSL:

```bash
./run.sh            # 或 PORT=18080 ./run.sh
```

脚本行为：jar 不存在时先 `mvn -B -DskipTests package`，再 `java -jar` 起服务。
监听 `http://127.0.0.1:18080`（本机 8080 被占用，故默认 18080），健康检查 `GET /actuator/health`。

数据库用 H2 文件库（`MODE=MySQL`），首启自动执行 `sql/schema.sql` + `sql/seed.sql`（全幂等：
`IF NOT EXISTS` / `INSERT IGNORE`），数据落在启动目录下的 `./data/`。
**想重置演示数据：停服后删掉 `data/` 目录再启动即可。**

演示账号（seed 内置，仅本地演示用）：

| 面 | 入口 | 账号 |
| --- | --- | --- |
| 后台 admin | `POST /api/operator/login` | `admin` / `admin123`（超管）、`viewer` / `viewer123`（无角色，用来验 403） |
| 前台 h5app | `POST /api/h5app/wxapp/new_login` | `13800000001` / `13800000002`，验证码固定 `8888`（`ecx.security.sms-mock-code`） |

拿到 token 后 `Authorization: Bearer <token>` 调用其余接口。

```bash
# 后台
curl -s -X POST localhost:18080/api/operator/login \
     -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}'
# 前台
curl -s -X POST localhost:18080/api/h5app/wxapp/new_login \
     -H 'Content-Type: application/json' \
     -d '{"auth_type":"local","username":"13800000001","check_type":"mobile","vcode":"8888"}'
```

## 模块

| 模块 | 职责 |
| --- | --- |
| `ecshopx-common` | 响应包络 / 错误码 / 时间 / 分页协议等无框架依赖的地基 |
| `ecshopx-dao` | MyBatis 注解 Mapper、DO、时间戳拦截器、号段发号器 |
| `ecshopx-service` | 业务编排：商品、购物车算价、订单状态机、支付、会员、支撑 |
| `ecshopx-web-admin` | 后台端点（`/api/**`） |
| `ecshopx-web-front` | 前台端点（`/api/h5app/**`） |
| `ecshopx-boot` | 装配与启动：三条 SecurityFilterChain、静态映射、fat jar |

## 常用命令

```bash
mvn -B verify                 # 全量测试 + JaCoCo 门槛（P0 契约测试全绿才算完成）
mvn -B -DskipTests package    # 只出 fat jar
mvn -B -pl ecshopx-boot -am test   # 单模块测试务必带 -am，否则会用 ~/.m2 里的旧 jar
python tools/m04_smoke.py     # 实跑冒烟（服务需已在 18080）；m05..m08 同
```

上传的的图片落在 `ecx.upload.root`（默认 `data/storage`），通过 `/storage/**` 静态面匿名回读。

## 文档

设计：[01-架构设计](docs/01-架构设计文档.md) · [02-概要设计](docs/02-概要设计文档.md) ·
[03-详细设计](docs/03-详细设计文档.md) · [04-URL 列表（wire 契约）](docs/04-URL列表文档.md) ·
[05-开发步骤 M0/M1/M2](docs/05-开发步骤-M0M1M2-P0P1P2.md)

## 支付渠道 profile（M1-2）

`ecx.pay.active-channel-profile` 决定哪套渠道实现被注册成 bean，缺省 `mock`（M0 假渠道，回调与关单都由自己 curl 出来）。真渠道用环境变量开：

```bash
ECX_PAY_PROFILE=wechatpay,alipay java -jar ecshopx-boot/target/ecshopx-boot-0.1.0-SNAPSHOT.jar
```

密钥只从环境/配置中心下发，不进代码也不进库：微信 `ECX_WX_MCH_ID / ECX_WX_APP_ID / ECX_WX_API_V3_KEY / ECX_WX_PRIVATE_KEY(_PATH) / ECX_WX_CERT_SERIAL / ECX_WX_PLATFORM_CERT(_PATH) / ECX_WX_NOTIFY_URL`，支付宝 `ECX_ALIPAY_SERVER_URL / ECX_ALIPAY_APP_ID / ECX_ALIPAY_PRIVATE_KEY / ECX_ALIPAY_PUBLIC_KEY / ECX_ALIPAY_NOTIFY_URL`。没开真渠道时 `pay_types` 里就看不到它（PAY-1），硬发起是 501；配了 profile 却缺密钥，第一次下单报「配置不全」而不是启动失败。补单作业（掉单结清 + 渠道侧关单）默认 5 分钟一轮、只扫 `wxpay/alipay`，`ECX_PAY_PROFILE=mock` 时它也只做无操作。

两条真渠道的"真签真验全链路"跑在本地仿真网关上：`mvn -B test -pl ecshopx-boot -Dtest=WxPayV3SimulationTest+AliPaySimulationTest -Dsurefire.failIfNoSpecifiedTests=false`（各 8 例，夹具 `ecshopx-boot/src/test/kotlin/Sim*PayServer.kt` + 自签密钥库 `src/test/resources/sim/mockwx.p12`）。微信商户平台与支付宝沙箱的**真单**验证仍待商户资质到位后执行，代码不再改。
