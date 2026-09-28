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
- 货物状态：`IN_TRANSIT`（正常流转）→ `QUARANTINED`（隔离，不可逆）。
- 运单维护 **异常证据版本（evidenceVersion）** 与已确认异常区间快照：首次隔离时置为 1，
  之后每次因晚到历史事件导致异常区间扩大时递增。

### 温度事件

- 事件包含设备事件号、采样时间与温度；同一运单下事件号唯一（数据库唯一约束）。
- 相同事件号且内容完全相同的重放是幂等的，直接返回已存在事件，不重复计算；
  事件号相同但内容不同返回 409 `EVENT_CONFLICT`。
- 事件允许乱序到达。每次写入新事件后，系统按采样时间对该运单的全部事件重新计算
  连续越界区间：一段连续越界采样从首个越界样本开始，到下一个回温（范围内）样本为止；
  若区间末尾没有回温样本，则以最后一个越界样本为区间终点。任一区间时长达到
  （大于等于）允许的连续越界时长时，货物进入 `QUARANTINED`。
- 温度事件作为审计记录始终被接收，即使货物已隔离；晚到的有效历史事件仍可能触发隔离。

### 包装单元（影响批次追踪）

- 运单可拆分为多个包装单元，同一运单下单元编码唯一；重复登记幂等。
- 隔离前登记的单元为 `IN_TRANSIT`，货物隔离时全部在途单元转为 `QUARANTINED`；
  隔离后登记的单元直接进入 `QUARANTINED`。
- 单元状态机：`IN_TRANSIT` → `QUARANTINED` → `RELEASED` / `DESTROYED`，后两者均为终态。
- **数量守恒**：处置只作用于调查所覆盖的隔离单元；一个单元只能被处置一次，
  不可能同时处于已放行与已报废状态。

### 温度异常调查

- 只有隔离货物才能创建调查。调查号全局唯一，重复提交且覆盖单元一致时幂等，
  覆盖单元不一致返回 409 `INVESTIGATION_NO_CONFLICT`。
- 创建调查时即锁定三项事实：
  - **触发隔离的温度事件集合**（当前已确认异常区间内的越界样本）；
  - **当时的阈值配置**（温度上/下限、允许连续越界时长）；
  - **当时的保管方**。
- 调查同时记录锁定时的异常证据版本与异常区间快照。结论可覆盖全部隔离单元，
  或请求体 `unitCodes` 指定的单元；同一单元不允许同时存在两个未完成调查
  （409 `INVESTIGATION_PENDING_UNIT`）。
- 调查状态：`OPEN` → `AWAITING_APPROVAL`（有条件放行待审批）→ `COMPLETED`；
  或因证据扩大进入 `SUPERSEDED`。

### 结论失效与重新评估

- 调查创建后若补到的历史温度事件**扩大了异常区间**（区间起点提前、终点延后，
  或出现新的独立越界区间），运单证据版本递增，所有尚未完成的调查
  （`OPEN`、`AWAITING_APPROVAL`，含已双签但尚未放行的）立即置为 `SUPERSEDED`，
  已有结论与审批链失效，必须基于新证据重新创建调查。
- 仅在既有区间内部补点、不改变异常区间范围的，不递增版本，不影响结论。

### 处置结论

- `DESTROY`（报废）：覆盖单元转为 `DESTROYED`，调查完成。
- `CONTINUE_QUARANTINE`（继续隔离）：单元保持 `QUARANTINED`，调查完成，
  后续可对这些单元重新创建调查。
- `CONDITIONAL_RELEASE`（有条件放行）：必须提供晚于当前时间的**有效期限**
  （`validUntil`）与**使用限制**（`usageRestrictions`），调查进入 `AWAITING_APPROVAL`。

### 双角色审批与受控放行

- 有条件放行需要**两名不同角色**的审批：审批人不能相同，角色也不能相同
  （409 `APPROVAL_DUPLICATE_APPROVER` / `APPROVAL_DUPLICATE_ROLE`），最多两人。
- 双签完成后需单独调用放行接口，货物才实际解除隔离。
- 放行时再次复核：证据版本必须与调查一致——**隔离解除与新的越界事件并发时，
  若异常区间已扩大，返回 409 `NEW_BREACH_AFTER_APPROVAL`，绝不放行仍不满足条件的货物**；
  超过放行有效期的返回 409 `RELEASE_EXPIRED`。
- 审批事件号、处置事件号（结论与放行执行各一个）在同一调查下均幂等：
  重放相同内容直接返回原记录；同号不同内容返回 409
  （`APPROVAL_CONFLICT` / `DISPOSITION_CONFLICT`）。

### 交接

- 只有当前持有人可以发起交接，且货物必须处于 `IN_TRANSIT`；同一运单同一时间
  只允许存在一个待确认（`PENDING`）交接。
- 只有待确认交接指定的接收方可以确认；确认后当前持有人与不可变交接记录
  （`CONFIRMED` + 确认时间）在同一事务中同时更新。
- 重复确认是幂等的：不改变持有人，也不生成重复记录。
- 隔离货物不能发起交接，待确认交接在货物隔离后也不能再确认（409 `SHIPMENT_QUARANTINED`）。

### 并发与事务

- 所有涉及状态与持有人的修改都在事务内先对运单行加悲观写锁
  （`SELECT ... FOR UPDATE`），温度事件、调查结论、审批、放行执行、交接在同一运单上串行化：
  导致证据扩大的温度事件先提交时放行失败；放行先提交时后到事件只对新调查生效。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/shipments` | 创建货物 |
| GET | `/api/shipments/{waybillNo}` | 运单详情（含 evidenceVersion） |
| POST | `/api/shipments/{waybillNo}/temperature-events` | 上报温度事件 |
| GET | `/api/shipments/{waybillNo}/temperature-events` | 按采样时间排序的温度审计记录 |
| POST | `/api/shipments/{waybillNo}/units` | 登记包装单元（幂等） |
| GET | `/api/shipments/{waybillNo}/units` | 全部包装单元及当前状态 |
| POST | `/api/shipments/{waybillNo}/handovers` | 发起交接 |
| POST | `/api/shipments/{waybillNo}/handovers/{id}/confirm` | 确认交接 |
| GET | `/api/shipments/{waybillNo}/handovers` | 按时间排序的交接审计记录 |
| POST | `/api/shipments/{waybillNo}/investigations` | 创建温度异常调查 |
| GET | `/api/shipments/{waybillNo}/investigations` | 运单下的调查列表 |
| GET | `/api/shipments/{waybillNo}/investigations/{investigationNo}` | 调查详情（见下） |
| POST | `/api/shipments/{waybillNo}/investigations/{investigationNo}/conclusion` | 判定报废/继续隔离/有条件放行 |
| POST | `/api/shipments/{waybillNo}/investigations/{investigationNo}/approvals` | 提交一名角色审批 |
| POST | `/api/shipments/{waybillNo}/investigations/{investigationNo}/release` | 执行受控放行 |

### 调查详情返回内容

`GET .../investigations/{investigationNo}` 聚合返回：

- `investigation`：调查主体，包括状态、锁定的阈值配置（`threshold`）、锁定保管方、
  锁定异常区间（`lockedBreachIntervals`）、证据版本、`evidenceCurrent`
  （锁定版本是否仍为最新）、有效期与使用限制、失效原因等；
- `units`：结论覆盖的各单元**当前状态**；
- `approvalChain`：完整审批链（审批事件号、审批人、角色、时间）；
- `dispositionEvents`：完整处置事件链（结论 `CONCLUSION` 与放行执行 `EXECUTION`）；
- `evidenceEvents`：调查锁定的触发温度事件集合；
- `currentBreachIntervals`：按当前全部事件重算出的异常区间；
- `temperatureTimeline`：该运单完整温度时间线（按采样时间排序）。

### 请求示例

创建调查（覆盖全部隔离单元可省略 `unitCodes`）：

```json
{"investigationNo": "INV-2026-0001", "unitCodes": ["BOX-01", "BOX-02"], "reason": "到货温度异常"}
```

判定有条件放行（`dispositionEventId` 为客户端生成的幂等事件号）：

```json
{
  "dispositionEventId": "disp-0001",
  "dispositionType": "CONDITIONAL_RELEASE",
  "validUntil": "2026-10-05T00:00:00Z",
  "usageRestrictions": "仅限工业用途，不得零售，24 小时内用完",
  "operator": "qa-lead"
}
```

审批（两名不同角色各调用一次，`approvalEventId` 幂等）：

```json
{"approvalEventId": "appr-qa-0001", "approver": "bob", "role": "QA_MANAGER"}
```

执行放行：

```json
{"dispositionEventId": "rel-0001", "operator": "warehouse-lead"}
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

常见错误码：`VALIDATION_FAILED`（400）、`INVALID_TEMPERATURE_RANGE`（400）、
`INVALID_BREACH_DURATION`（400）、`MISSING_USAGE_RESTRICTIONS`（400）、
`INVALID_VALID_UNTIL`（400）、`UNIT_NOT_FOUND`（400）、`NOT_FOUND`（404）、
`NOT_DESIGNATED_RECEIVER`（403）、`DUPLICATE_WAYBILL`（409）、`EVENT_CONFLICT`（409）、
`NOT_CURRENT_HOLDER`（409）、`HANDOVER_PENDING`（409）、`SHIPMENT_QUARANTINED`（409）、
`SHIPMENT_NOT_QUARANTINED`（409）、`NO_QUARANTINED_UNITS`（409）、
`DUPLICATE_INVESTIGATION_NO`（409）、`INVESTIGATION_NO_CONFLICT`（409）、
`INVESTIGATION_PENDING_UNIT`（409）、`UNIT_NOT_QUARANTINED`（409）、
`INVESTIGATION_SUPERSEDED`（409）、`INVESTIGATION_ALREADY_COMPLETED`（409）、
`CONCLUSION_ALREADY_SUBMITTED`（409）、`INVALID_INVESTIGATION_STATE`（409）、
`APPROVAL_DUPLICATE_APPROVER`（409）、`APPROVAL_DUPLICATE_ROLE`（409）、
`APPROVAL_COMPLETE`（409）、`APPROVAL_INCOMPLETE`（409）、`APPROVAL_CONFLICT`（409）、
`NEW_BREACH_AFTER_APPROVAL`（409）、`RELEASE_EXPIRED`（409）、`DISPOSITION_CONFLICT`（409）。
