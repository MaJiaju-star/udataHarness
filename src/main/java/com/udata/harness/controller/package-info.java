/**
 * Solon HTTP/SSE 接入层。
 *
 * <p>Controller 是理解后端能力的入口：每个公开方法应说明路由用途、用户隔离边界、
 * 参数默认值、响应语义及重要的异步行为。实现上只负责解析 Header、Query 和 Body，
 * 调用应用服务，并转换为统一响应。</p>
 *
 * <p>禁止在本层直接访问文件系统、SessionRepository 或构建 HarnessEngine；也禁止在
 * SSE 接口中阻塞或聚合 Flux。业务校验和安全规则应位于 service/repository，确保
 * 不同 HTTP 入口共享同一套约束。</p>
 */
package com.udata.harness.controller;
