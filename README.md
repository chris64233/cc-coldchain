# cc-coldchain

管理冷链货物、运输交接与温控记录。

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

### 温度事件

- 事件包含设备事件号、采样时间与温度；同一运单下事件号唯一（数据库唯一约束）。
- 相同事件号且内容完全相同的重放是幂等的，直接返回已存在事件，不重复计算；
  事件号相同但内容不同返回 409 `EVENT_CONFLICT`。
- 事件允许乱序到达。每次写入新事件后，系统按采样时间对该运单的全部事件重新计算
  连续越界区间：一段连续越界采样从首个越界样本开始，到下一个回温（范围内）样本为止；
  若区间末尾没有回温样本，则以最后一个越界样本为区间终点。任一区间时长达到
  （大于等于）允许的连续越界时长时，货物进入 `QUARANTINED`。
- 温度事件作为审计记录始终被接收，即使货物已隔离；晚到的有效历史事件仍可能触发隔离。

### 交接

- 只有当前持有人可以发起交接，且货物必须处于 `IN_TRANSIT`；同一运单同一时间
  只允许存在一个待确认（`PENDING`）交接。
- 只有待确认交接指定的接收方可以确认；确认后当前持有人与不可变交接记录
  （`CONFIRMED` + 确认时间）在同一事务中同时更新。
- 重复确认是幂等的：不改变持有人，也不生成重复记录。
- 隔离货物不能发起交接，待确认交接在货物隔离后也不能再确认（409 `SHIPMENT_QUARANTINED`）。

### 并发与事务

- 所有涉及状态与持有人的修改都在事务内先对运单行加悲观写锁
  （`SELECT ... FOR UPDATE`），温度事件、交接发起、交接确认在同一运单上串行化：
  导致隔离的温度事件先提交时交接失败；交接先提交时后到的历史温度事件依然生效。

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
`INVALID_BREACH_DURATION`（400）、`NOT_FOUND`（404）、`NOT_DESIGNATED_RECEIVER`（403）、
`DUPLICATE_WAYBILL`（409）、`EVENT_CONFLICT`（409）、`NOT_CURRENT_HOLDER`（409）、
`HANDOVER_PENDING`（409）、`SHIPMENT_QUARANTINED`（409）。
