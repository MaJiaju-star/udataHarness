# Java Web 接口搜索设计方案

## 1. 目标

在本地工作区中检索 Spring Boot、Solon Maven 项目定义的 HTTP 接口，并支持从搜索结果直接打开 Controller 源文件、定位到对应注解或方法。

接口搜索应支持以下匹配维度：

- URL 路径；
- HTTP 方法；
- Controller 类名；
- Java 方法名；
- 接口描述；
- 参数名称；
- 返回类型。

该功能以源码静态分析为主，不要求目标项目已经启动。

## 2. 总体流程

```text
扫描 Maven 模块
  → 识别 Spring Boot / Solon
  → 解析 Java AST
  → 提取 Controller 与 Mapping
  → 合并类路径和方法路径
  → 生成统一接口索引
  → 前端搜索、打开并定位源码
```

不使用正则表达式解析 Java。注解可能跨行、使用数组、别名或常量，推荐使用 JavaParser 处理语法树。

## 3. 统一接口模型

Spring Boot 和 Solon 的解析结果统一转换成 `EndpointDefinition`：

```json
{
  "framework": "spring",
  "httpMethods": ["GET"],
  "path": "/api/users/{id}",
  "controller": "UserController",
  "method": "getUser",
  "returnType": "Result<User>",
  "file": "src/main/java/demo/UserController.java",
  "line": 42,
  "parameters": [
    {
      "name": "id",
      "source": "path",
      "type": "Long"
    }
  ]
}
```

建议字段：

| 字段 | 说明 |
| --- | --- |
| `framework` | `spring` 或 `solon` |
| `module` | Maven 模块名称 |
| `httpMethods` | GET、POST、PUT、DELETE、PATCH 等 |
| `path` | 合并后的完整接口路径 |
| `controller` | Controller 类名 |
| `method` | Java 方法名 |
| `returnType` | 返回类型源码文本 |
| `parameters` | 参数名称、类型和来源 |
| `consumes` | 请求内容类型 |
| `produces` | 响应内容类型 |
| `description` | 接口描述 |
| `file` | 工作区相对路径 |
| `line` | 注解或方法所在行 |

## 4. Spring Boot 解析

### 4.1 Controller 识别

识别以下类注解：

```java
@Controller
@RestController
```

### 4.2 类级路径

支持：

```java
@RequestMapping("/api/users")
@RequestMapping(path = "/api/users")
@RequestMapping(value = {"/api/users", "/users"})
```

### 4.3 方法级接口

支持：

```java
@GetMapping
@PostMapping
@PutMapping
@DeleteMapping
@PatchMapping
@RequestMapping(method = RequestMethod.GET)
```

同时解析 `value`、`path`、多路径数组、多请求方法、`consumes` 和 `produces`。

### 4.4 参数来源

识别：

- `@PathVariable`
- `@RequestParam`
- `@RequestBody`
- `@RequestHeader`
- 未标注的普通参数

## 5. Solon 解析

### 5.1 Controller 与路径

识别：

```java
@Controller
@Mapping("/api/users")
```

### 5.2 HTTP 方法

识别：

```java
@Get
@Post
@Put
@Delete
@Patch
```

以及：

```java
@Mapping(path = "/{id}", method = MethodType.GET)
```

Solon 的 `@Get`、`@Post` 等是 `@Mapping` 的请求方法修饰，需要与方法级 `@Mapping` 组合解析。

同时提取 `consumes`、`produces`、`version`、`name`、`description` 和 `headers`。

## 6. 路径组合

类级路径和方法级路径执行笛卡尔积组合：

```text
类：@RequestMapping("/api/users")
方法：@GetMapping("/{id}")

结果：GET /api/users/{id}
```

组合后统一处理：

- 路径首部 `/`；
- 重复 `/`；
- 空类路径或空方法路径；
- 多路径数组；
- 多 HTTP 方法。

## 7. 搜索与排序

搜索词同时匹配：

- 完整路径和路径片段；
- HTTP 方法；
- Controller 名称；
- Java 方法名；
- 参数名称；
- 返回类型；
- 描述。

排序优先级：

1. 路径完全匹配；
2. 路径前缀匹配；
3. Java 方法名匹配；
4. Controller 名称匹配；
5. 参数、返回类型或描述匹配。

## 8. 索引策略

第一版可在搜索时扫描源码。后续增加工作区级增量索引：

```text
workspaceId
  → moduleName
  → Java 文件路径
  → modifiedAt
  → EndpointDefinition[]
```

仅在 Java 文件修改时间变化时重新解析。以下操作应使对应索引失效：

- 智能体修改或创建文件；
- 用户保存文件；
- 上传、删除或批量上传；
- 切换工作区。

多模块 Maven 项目需要扫描所有 `pom.xml`，识别各模块 `src/main/java`，并在结果中保留模块名称。

## 9. 后端接口

建议新增：

```http
POST /api/files/endpoints/search
```

请求示例：

```json
{
  "keyword": "GET /users",
  "frameworks": ["spring", "solon"],
  "modules": [],
  "maxResults": 200
}
```

响应返回统一的 `EndpointDefinition` 列表以及总数、截断状态和索引时间。

## 10. 前端设计

扩展现有全局搜索弹窗，增加第三个标签：

```text
[文件名称] [文件内容] [接口]
```

接口结果示例：

```text
GET    /api/users/{id}
       UserController#getUser
       src/main/java/demo/UserController.java:42

POST   /api/users
       UserController#createUser
       src/main/java/demo/UserController.java:68
```

HTTP 方法使用语义颜色：

- GET：绿色；
- POST：蓝色；
- PUT：橙色；
- DELETE：红色；
- PATCH：紫色。

点击结果后：

1. 在右侧编辑器打开 Java 文件；
2. Monaco 跳转到接口注解或方法所在行；
3. 选中 `@GetMapping`、`@RequestMapping` 或 `@Mapping`。

## 11. 推荐依赖

第一阶段：

```xml
<dependency>
    <groupId>com.github.javaparser</groupId>
    <artifactId>javaparser-core</artifactId>
</dependency>
```

需要跨文件解析静态常量、继承关系和组合注解时，再加入：

```xml
<dependency>
    <groupId>com.github.javaparser</groupId>
    <artifactId>javaparser-symbol-solver-core</artifactId>
</dependency>
```

## 12. 边界与后续增强

第一版暂不完整解决：

- 注解路径引用跨文件静态常量；
- Spring 自定义组合注解；
- Controller 接口与实现类分离；
- 父类继承的 `@RequestMapping`；
- Kotlin Controller；
- Spring `RouterFunction`；
- Solon 动态注册路由。

后续可以接入 JavaParser Symbol Solver。若目标应用正在运行，也可以选择性读取 Spring Actuator 或 Solon 运行时路由表，对静态分析结果进行校验。

## 13. 实施顺序

1. 引入 JavaParser；
2. 定义 `EndpointDefinition`；
3. 实现 `SpringEndpointParser`；
4. 实现 `SolonEndpointParser`；
5. 实现路径和 HTTP 方法组合；
6. 支持 Maven 多模块扫描；
7. 增加工作区增量索引；
8. 新增接口搜索 API；
9. 全局搜索弹窗增加“接口”标签；
10. 接入 Monaco 行号定位；
11. 补充 Spring、Solon、多路径、多方法和异常语法测试。
