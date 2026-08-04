# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目性质（重要）

这是黑马程序员 Redis 课程的**练习空壳项目**（仿大众点评 hm-dianping）。大量核心业务**尚未实现**，controller / service 里留有 `TODO` 和 `return Result.fail("功能未完成")` 桩代码，等着按课程章节逐个用 Redis 补全：

- 用户登录（短信验证码 + Redis token，含刷新滑动窗口）
- 店铺缓存：缓存击穿（互斥锁 `lock:shop:` / 逻辑过期 `RedisData`）、穿透（空值缓存 `CACHE_NULL_TTL`）
- 秒杀（Redis 预扣库存 + Lua + Redis Stream 异步下单 + `seckill:stock:`）
- 探店笔记：ZSet 点赞（`blog:liked:`）、关注推送 Feed（`feed:` + `ScrollResult` 滚动分页）
- 附近店铺 GEO（`shop:geo:`）
- 签到 BitMap（`sign:`，`tb_sign` 表已建但无接口）

改代码前先判断某功能是否只是"待填"状态，别当成已完成的 bug 去修。

## 常用命令

环境：本机已装 Maven 3.9.8 + JDK 8（`pom.xml` 指定 `java.version=1.8`），无 mvnw wrapper。

```bash
mvn package            # 编译打包（target/hm-dianping-0.0.1-SNAPSHOT.jar）
mvn spring-boot:run    # 启动，端口 8081
mvn test               # 只有一个空的 @SpringBootTest 冒烟测试
```

启动前需保证 MySQL（`hmdp` 库）和 Redis 可用，见下方配置。

## 技术栈

Spring Boot 2.3.12.RELEASE · Spring Data Redis(Lettuce + commons-pool2) · MyBatis-Plus 3.4.3（自带分页拦截器）· MySQL 5.1.47 · Hutool 5.7.17 · Lombok。项目**没有前端代码**，前端是独立工程，靠 nginx 静态托管，图片也上传到 nginx 目录。

## 配置与依赖服务

`src/main/resources/application.yaml`（课程实验室地址，需按本机环境改）：

- MySQL：`127.0.0.1:3306/hmdp`，root/123456
- Redis：`192.168.220.128:6379`，密码 `159873246`
- `SystemConstants.IMAGE_UPLOAD_DIR` = `D:\lesson\nginx-1.18.0\html\hmdp\imgs\`（图片上传/访问走 nginx，本机路径要对上）
- 日志：`com.hmdp` 级别为 debug

数据库：把 `src/main/resources/db/hmdp.sql`（Navicat 导出的建表+种子数据）导入 `hmdp` 库。共 11 张表：`tb_user`、`tb_user_info`、`tb_shop`、`tb_shop_type`、`tb_voucher`、`tb_seckill_voucher`、`tb_voucher_order`、`tb_blog`、`tb_blog_comments`、`tb_follow`、`tb_sign`。

## 架构与约定

标准三层：`controller` → `service`（继承 MyBatis-Plus `ServiceImpl<Mapper, Entity>`，实现 `I*Service`）→ `mapper`（继承 `BaseMapper`，自定义 SQL 放在 `src/main/resources/mapper/*.xml`，目前只有 `VoucherMapper.xml` 一个联表查询）。

已就绪的基础设施（写业务时直接用，别重复造）：

- `utils/RedisConstants.java`：**所有 Redis key 前缀和 TTL 的唯一出处**（`login:code:`、`login:token:`、`cache:shop:`、`lock:shop:`、`seckill:stock:`、`blog:liked:`、`feed:`、`shop:geo:`、`sign:`）。新增 key 先加在这里。
- `utils/RedisData.java`：逻辑过期缓存的包装类（`expireTime` + `data`）。
- `utils/UserHolder.java`：ThreadLocal 存当前登录用户 `UserDTO`。目前**没有任何拦截器往里面写**——实现登录时必须补一个实现 `HandlerInterceptor` 的 `WebMvcConfigurer` 配置（`config/` 下现在只有 `MybatisConfig` 和 `WebExceptionAdvice`）。
- `dto/Result.java`：统一响应体，`Result.ok(...)` / `Result.fail(...)`。
- `dto/ScrollResult.java`：Feed 滚动分页 DTO（`list` + `minTime` + `offset`），配 ZSet 用。
- `config/MybatisConfig.java`：分页拦截器，所以 service 里能直接用 `.page(...)`。
- `config/WebExceptionAdvice.java`：`@RestControllerAdvice` 统一兜底返回 `Result.fail("服务器异常")`。
- `controller/UploadController.java`：图片上传到 nginx 目录（已实现，含 UUID 两级目录散列）。

JSON 序列化忽略 null 字段（application.yaml 里 `default-property-inclusion: non_null`），涉及缓存 JSON 时注意反序列化容错。

## 接口概览

- `/user/*`：登录相关，**全部未实现**
- `/shop/*`、`/shop-type/list`：店铺与分类（当前直接查库，缓存待实现）
- `/voucher/list/{shopId}`：店铺优惠券（含秒杀券联查，`VoucherMapper.xml`），`/voucher/seckill` 新增秒杀券（`VoucherServiceImpl` 已实现）
- `/voucher-order/seckill/{id}`：秒杀下单，**未实现**
- `/blog/*`：探店笔记（保存、点赞、热门、我的；点赞目前是简单的 `liked+1`，待改成 ZSet 防重复点赞）
- `/upload/blog`：图片上传

## 秒杀订单实现约定

`VoucherOrderServiceImpl` / `ISeckillVoucherService` 都是空壳。`tb_voucher_order` 表结构齐全，秒杀流程（预扣库存、一人一单、Stream 消费建单）是课程重点，实现时保持 Lua 脚本 + Redis Stream 的课程方案一致即可。
