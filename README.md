# 智惠生活通 · LocalPerks

基于 Spring Boot 与 Redis 的本地生活服务项目，提供商铺发现、探店内容、用户社交和优惠券秒杀。

## 功能

| 模块 | 功能 |
| --- | --- |
| 用户 | 手机验证码登录、Redis 登录态、签到及连续签到统计 |
| 商铺 | 分类、详情缓存、名称搜索、附近商铺 |
| 优惠券 | 发券、优惠券查询、Lua 秒杀资格判断、Stream 异步下单 |
| 内容 | 探店笔记、热门列表、点赞 |
| 社交 | 关注、共同关注、关注动态滚动分页 |

## 技术栈

Java 8 · Spring Boot 2.3.12 · MyBatis-Plus 3.4.3 · Spring Data Redis 2.6.2 · Redisson 3.13.6 · MySQL · Vue · Element UI · Nginx

## 运行

1. 准备 JDK 8、Maven 3、MySQL 和 Redis 6.2+。
2. 创建 hmdp 开发数据库，导入 src/main/resources/db/hmdp.sql。脚本会重建同名表。
3. 参考 .env.example，在终端或 IDE 中配置 DB_PASSWORD、DB_USER、DB_URL 和 REDIS_HOST、REDIS_PORT、REDIS_PASSWORD。应用不会自动读取 .env 文件。
4. 在 Redis 中初始化消费组（已存在时无需重复创建）：

~~~text
XGROUP CREATE stream.orders g1 0 MKSTREAM
~~~

5. 从项目根目录启动后端：

~~~powershell
$env:DB_PASSWORD = '填写本机数据库密码'
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '6379'
# Redis 开启认证时设置 REDIS_PASSWORD。
mvn spring-boot:run
~~~

6. 单独安装 Nginx，使用仓库中的配置和前端资源。Windows 示例：

~~~powershell
New-Item -ItemType Directory -Force nginx-1.18.0/logs, nginx-1.18.0/temp
& 'D:/tools/nginx/nginx.exe' -p "$PWD/nginx-1.18.0/" -c conf/nginx.conf
~~~

前端地址：http://localhost:8080；后端端口：8081。Nginx 将 /api 请求转发到后端。

图片默认保存到 nginx-1.18.0/html/hmdp/imgs，可通过 JVM 属性 hmdp.image-upload-dir 修改。附近商铺的 GEO 数据可通过 HmDianPingApplicationTests.loadShopData 导入开发环境。

## 构建

~~~text
mvn -DskipTests package
~~~

现有集成测试依赖 MySQL 和 Redis，部分测试会写入数据，请使用独立测试环境。

## 待完善

- 评论、退出登录、支付、核销和退款；验证码目前仅输出到开发日志。
- 秒杀消息 ACK 的 Stream 名称、数据库事务、活动时间校验和异常补偿。
- 商铺、优惠券、上传接口的角色权限。
