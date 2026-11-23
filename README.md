# TicketFlow

TicketFlow 是一套 SaaS 售后工单系统，处理套餐升级后的报表导出权益异常。客服提交订单与问题描述，系统核对支付和权益台账、检索适用规则并给出处置。符合补发条件的工单在审批后写入权益。

## 调查与执行

- **调查**　LangGraph 按已有证据选择只读工具，最多 6 轮、60 秒。中文 BM25 与 BGE 向量检索使用同一组套餐和有效期过滤条件，经 RRF 融合。工单附订单事实和引用条款，证据不足时转人工。
- **审批**　Spring Security 限定审批角色。SQLite 保存工作流检查点，审批后以原工单运行 ID 继续。
- **补发**　Spring Boot 在执行前重新核对订单状态，在同一 MySQL 事务提交权益与操作流水。发生响应超时时按运行 ID 查结果，查无结果才重试。数据库按订单和权益项去重。
- **追踪**　工作台记录调查和执行时间线。配置 Langfuse 后可按 Trace ID 查看工具调用与模型用量。

## 启动

使用 Docker Compose v2。将 `.env.example` 复制到 `.env`，填写数据库口令、内部服务密钥、两类账号的口令以及兼容 OpenAI 工具调用的模型配置。

~~~sh
cp .env.example .env
docker compose up --build -d
docker compose logs -f agent
~~~

首次启动会下载 BGE 模型。Agent 启动完成后访问 [工单工作台](http://127.0.0.1:8080)。客服使用 `support`，审批员使用 `approver`，口令由部署配置指定。Agent 和数据库留在 Compose 内部网络。

订单数据由上游导入 MySQL 的 `orders` 表，已发权益导入 `entitlements` 表。表结构由 Flyway 管理。产品政策使用 `PRO`、`BASIC` 两个套餐代码，已支付状态为 `PAID`，`paid_at` 以 UTC 保存。知识条款位于 `agent/clauses.json`，更新后重新部署 Agent 即可重建索引。

## 接口

| 方法与路径 | 用途 |
|---|---|
| `POST /tickets` | 提交订单号和客户描述，启动调查 |
| `GET /tickets` | 查看近期工单 |
| `GET /tickets/{id}` | 查看处置、引用、审批与操作结果 |
| `POST /tickets/{id}/decision` | 审批员提交批准或拒绝，并继续工作流 |
| `POST /tickets/{id}/run` | 继续未完成的调查或审批后执行 |

工作台使用 Spring Security 会话和 CSRF 令牌。Agent 通过内部认证访问业务读取与补发接口。
