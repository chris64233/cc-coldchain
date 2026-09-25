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

- 创建货物需记录唯一运单号、允许温度下限/上限、允许的连续越界时长（分钟）、初始持有人与启运时间。
- 温度下限必须小于上限，允许越界时长必须为正数；运单号通过数据库唯一约束保证，重复创建返回 `409`。
- 货物状态为 `ACTIVE` 或 `QUARANTINED`，隔离为终态，一旦进入不可恢复。

### 温度事件

- 事件包含设备事件号、采样时间与温度；同一运单下设备事件号唯一。
- 相同事件号且内容完全相同的重放为幂等操作，不重复计入；事件号相同但内容不同返回 `409` 冲突。
- 事件允许乱序到达。每次写入新事件后，系统按采样时间排序重新计算连续越界区间：
  连续越界样本序列中，首尾采样时间差达到或超过允许时长即判定越界区间成立，货物进入 `QUARANTINED`。
  区间内出现任一正常温度样本会打断该区间。温度等于上下限视为正常。

### 交接

- 仅当前持有人可发起交接，需指定接收方；同一运单同一时间只允许一笔待确认交接。
- 仅待确认交接的指定接收方可确认；确认后当前持有人更新为接收方，交接记录不可变地保留（含双方、发起与确认时间）。
- 重复确认返回 `409`，不会改变持有人或生成重复记录。
- 隔离货物不能发起或确认交接。

### 并发与一致性

- 所有涉及状态与持有人的修改在同一事务内完成，并对运单行加悲观写锁（`PESSIMISTIC_WRITE`），
  使温度事件、交接发起、交接确认在同一运单上串行化。
- 导致隔离的温度事件先提交，则后续交接发起/确认失败；交接先提交，后到的有效历史温度事件仍会触发隔离。

### API 概览

- `POST /api/shipments` 创建货物
- `GET /api/shipments/{waybillNo}` 运单详情
- `POST /api/shipments/{waybillNo}/temperature-events` 上报温度事件
- `GET /api/shipments/{waybillNo}/temperature-events` 按采样时间排序的温度记录
- `POST /api/shipments/{waybillNo}/handovers` 发起交接
- `POST /api/shipments/{waybillNo}/handovers/{id}/confirm` 确认交接
- `GET /api/shipments/{waybillNo}/handovers` 按发起时间排序的交接审计记录

所有错误统一返回 JSON：`{timestamp, status, error, message, path}`。
