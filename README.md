# cc-coldchain

管理冷链货物、运输交接、温控记录，以及温度异常调查、受控放行与影响批次追踪。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper 3.9.9
- H2

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test

## 主要业务规则

### 货物（运单）

- 创建货物时记录唯一运单号、允许温度下限/上限、允许的连续越界时长（秒）、初始持有人与启运时间。
- 温度下限必须小于上限，连续越界时长必须大于 0，否则返回 400。
- 运单号通过数据库唯一约束保证唯一，重复创建返回 409 `DUPLICATE_WAYBILL`。
- 货物状态：`IN_TRANSIT`（正常流转）→ `QUARANTINED`（隔离）→ `RELEASED`（放行）/ `CONDEMNED`（报废）。
  隔离不可逆；放行后的货物恢复交接能力；报废为终态。

### 温度事件

- 事件包含设备事件号、采样时间与温度；同一运单下事件号唯一（数据库唯一约束）。
- 相同事件号且内容完全相同的重放是幂等的，直接返回已存在事件，不重复计算；
  事件号相同但内容不同返回 409 `EVENT_CONFLICT`。
- 事件允许乱序到达。每次写入新事件后，系统按采样时间对该运单的全部事件重新计算
  连续越界区间：一段连续越界采样从首个越界样本开始，到下一个回温（范围内）样本为止；
  若区间末尾没有回温样本，则以最后一个越界样本为区间终点。任一区间时长达到
  （大于等于）允许的连续越界时长时，货物进入 `QUARANTINED`。
- 温度事件作为审计记录始终被接收，即使货物已隔离；晚到的有效历史事件仍可能触发隔离，
  并可能扩大已立案调查的异常区间（见下）。

### 包装单元（影响批次追踪）

- 一个运单可拆分为多个包装单元（编号 + 数量），同一运单内单元编号唯一
  （409 `DUPLICATE_UNIT`），数量必须大于 0。
- 未显式拆分的运单在首次创建调查时自动生成一个数量为 1 的 `DEFAULT` 单元，
  使所有处置都在单元级别完成。
- 存在未完成调查、或货物已进入放行/报废终态后，不能再变更单元拆分
  （409 `INVESTIGATION_IN_PROGRESS` / `SHIPMENT_DISPOSED`）。
- 单元状态机：`HELD`（隔离中）→ `RELEASED`（已放行）/ `SCRAPPED`（已报废），
  单向迁移。已处置单元不能重复纳入结论或再次处置（409 `UNIT_ALREADY_DISPOSED`），
  因此同一单元不可能同时处于已放行与已报废状态，数量严格守恒。

### 温度异常调查

- 只有 `QUARANTINED` 货物可以创建调查（409 `SHIPMENT_NOT_QUARANTINED`）。
- 创建调查时锁定：
  - 触发隔离的温度事件集合与达阈异常区间（起止时刻、事件号清单）；
  - 当时的阈值配置（温度上下限、允许连续越界时长）；
  - 当时的保管方（即使之后发生交接，调查中的保管方快照不变）。
- 调查号全局唯一、支持幂等：相同调查号在同一运单上重放返回已建调查，不重复创建；
  调查号被其他运单占用时返回 409 `DUPLICATE_INVESTIGATION`。
- 同一运单同时只允许存在一个未完成调查（409 `INVESTIGATION_IN_PROGRESS`）。
- 证据版本：立案时为 1。后到的历史温度事件若使达阈异常区间扩大
  （新增独立区间、区间起点提前或终点后延；仅在既有区间内补点不算扩大），
  证据版本递增，并使尚未完成的结论变为 `INVALIDATED`，必须重新评估；
  调查尚无结论（`OPEN`）时只刷新证据版本。已完成（`COMPLETED`）的调查不受影响。
- 调查状态：`OPEN` → `CONTINUED` / `SCRAP_PENDING` / `RELEASE_PENDING`
  → `COMPLETED`；任何未完成状态在证据扩大时进入 `INVALIDATED`，
  重新提交结论后回到对应待执行状态。

### 调查结论

- 判定有三种：`SCRAP`（报废）、`CONTINUE_QUARANTINE`（继续隔离）、
  `CONDITIONAL_RELEASE`（有条件放行）。
- 结论可覆盖全部单元（`unitCodes` 为空或 `["*"]`）或指定单元；被覆盖单元必须都处于
  `HELD`。每次提交结论生成不可变审计记录并递增结论序号。
- 有条件放行必须提供晚于当前时间的有效期限（`validUntil`）与使用限制
  （`usageRestrictions`），否则 400 `VALID_UNTIL_REQUIRED` /
  `INVALID_VALID_UNTIL` / `USAGE_RESTRICTIONS_REQUIRED`。
- 结论提交幂等：调查仍处于有效待执行状态时，以完全相同的判定、单元与条件重放，
  返回既有结论而不生成新序号；证据已扩大时先失效原结论（409
  `CONCLUSION_INVALIDATED`），再提交即视为重新评估并产生新序号。
- 重新评估后，旧结论序号上的审批不再计入新结论的审批链。

### 双角色审批与受控放行

- 有条件放行结论需要**两名不同角色、且不同人员**的审批：
  同角色重复审批 → 409 `ROLE_ALREADY_APPROVED`；同一人换角色再次审批 →
  409 `APPROVER_ALREADY_APPROVED`。
- 审批事件号（`approvalNo`）全局唯一、支持幂等：同号同人同角色重放返回原审批；
  同号但内容不一致 → 409 `APPROVAL_CONFLICT`。
- 只有 `RELEASE_PENDING` 结论可以追加审批（409 `APPROVAL_NOT_ALLOWED`）。
- 放行处置时再次复核最新温度证据：若隔离解除与新越界事件并发、异常区间已扩大，
  拒绝放行（409 `RELEASE_REJECTED_NEW_EXCURSION`），货物保持隔离，必须重新评估。
- 审批不足两名 → 409 `APPROVAL_INCOMPLETE`；超过结论有效期限 →
  409 `RELEASE_EXPIRED`。放行时在单元上记录有效期截止时刻与使用限制。
- 处置事件号（`dispositionNo`）全局唯一、支持幂等：同号且调查/类型/单元一致时
  重放返回原处置记录；不一致 → 409 `DISPOSITION_CONFLICT`。
- 报废/放行按结论覆盖的单元逐个执行；结论未覆盖的单元处置返回
  409 `UNIT_NOT_COVERED`，判定与处置类型不匹配返回 409 `CONCLUSION_INVALIDATED`。
- 当运单下不再有 `HELD` 单元时调查进入 `COMPLETED`；全部报废时运单为
  `CONDEMNED`，存在放行单元时运单为 `RELEASED`（部分放行部分报废也算放行完成，
  单元级状态保留各自处置结果）。

### 并发与事务

- 所有涉及状态、持有人、调查结论与单元处置的修改都在事务内先对运单行加悲观写锁
  （`SELECT ... FOR UPDATE`），再锁调查行；温度事件、调查结论/审批/处置在同一运单上
  串行化，锁顺序全局为“运单 → 调查”，避免死锁。
- 导致扩大的温度事件先提交时，结论/审批/放行被拒绝且失效状态随事务落库
  （结论、审批、处置接口对 `ApiException` 设置 `noRollbackFor`，保证失效可观测）；
  放行先提交时合法生效，后到事件只作为审计记录，不再影响已完成调查。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/shipments` | 创建货物 |
| GET | `/api/shipments/{waybillNo}` | 运单详情 |
| POST | `/api/shipments/{waybillNo}/temperature-events` | 上报温度事件 |
| GET | `/api/shipments/{waybillNo}/temperature-events` | 按采样时间排序的温度审计记录 |
| POST | `/api/shipments/{waybillNo}/handovers` | 发起交接 |
| POST | `/api/shipments/{waybillNo}/handovers/{id}/confirm` | 确认交接 |
| GET | `/api/shipments/{waybillNo}/handovers` | 按时间排序的交接审计记录 |
| POST | `/api/shipments/{waybillNo}/units` | 注册包装单元 |
| GET | `/api/shipments/{waybillNo}/units` | 包装单元及其当前状态 |
| POST | `/api/shipments/{waybillNo}/investigations` | 创建调查（调查号幂等） |
| GET | `/api/shipments/{waybillNo}/investigations` | 运单下的调查列表 |
| GET | `/api/shipments/{waybillNo}/investigations/{investigationNo}` | 调查详情聚合 |
| POST | `/api/shipments/{waybillNo}/investigations/{investigationNo}/conclusions` | 提交判定 |
| POST | `/api/shipments/{waybillNo}/investigations/{investigationNo}/approvals` | 放行审批（审批号幂等） |
| POST | `/api/shipments/{waybillNo}/investigations/{investigationNo}/scrap` | 报废处置（处置号幂等） |
| POST | `/api/shipments/{waybillNo}/investigations/{investigationNo}/release` | 受控放行处置（处置号幂等） |

调查详情聚合返回：调查状态与锁定快照（阈值、保管方、立案异常区间、触发事件集、
证据版本）、`evidenceExpanded`（最新证据是否较立案时扩大）、最新异常区间、
完整结论历史、审批链（含绑定的结论序号与证据版本）、处置事件、各单元当前状态
及完整温度时间线。

请求示例（有条件放行）：

```json
POST /api/shipments/WB-1/investigations/INV-1/conclusions
{
  "decision": "CONDITIONAL_RELEASE",
  "unitCodes": ["U1"],
  "validUntil": "2026-10-01T00:00:00Z",
  "usageRestrictions": "仅限工业用途，72 小时内使用",
  "decidedBy": "qa-lead"
}
```

### 统一错误格式

所有错误响应均为统一 JSON：

```json
{
  "code": "EVENT_CONFLICT",
  "message": "事件号 e1 已存在但内容不一致",
  "status": 409,
  "timestamp": "2026-09-25T08:00:00Z"
}
```

常见错误码：

- 货物与温度：`VALIDATION_FAILED`（400）、`INVALID_TEMPERATURE_RANGE`（400）、
  `INVALID_BREACH_DURATION`（400）、`NOT_FOUND`（404）、`DUPLICATE_WAYBILL`（409）、
  `EVENT_CONFLICT`（409）。
- 交接：`NOT_DESIGNATED_RECEIVER`（403）、`NOT_CURRENT_HOLDER`（409）、
  `HANDOVER_PENDING`（409）、`SHIPMENT_QUARANTINED`（409）。
- 调查：`SHIPMENT_NOT_QUARANTINED`（409）、`DUPLICATE_INVESTIGATION`（409）、
  `INVESTIGATION_IN_PROGRESS`（409）、`INVESTIGATION_COMPLETED`（409）、
  `CONCLUSION_INVALIDATED`（409）、`SHIPMENT_CONDEMNED`（409）。
- 单元与处置：`INVALID_QUANTITY`（400）、`UNIT_NOT_FOUND`（400）、
  `DUPLICATE_UNIT`（409）、`SHIPMENT_DISPOSED`（409）、`UNIT_ALREADY_DISPOSED`（409）、
  `UNIT_NOT_COVERED`（409）。
- 放行：`VALID_UNTIL_REQUIRED`（400）、`INVALID_VALID_UNTIL`（400）、
  `USAGE_RESTRICTIONS_REQUIRED`（400）、`APPROVAL_NOT_ALLOWED`（409）、
  `ROLE_ALREADY_APPROVED`（409）、`APPROVER_ALREADY_APPROVED`（409）、
  `APPROVAL_CONFLICT`（409）、`DUPLICATE_APPROVAL`（409）、
  `APPROVAL_INCOMPLETE`（409）、`RELEASE_EXPIRED`（409）、
  `RELEASE_REJECTED_NEW_EXCURSION`（409）、`DISPOSITION_CONFLICT`（409）。
