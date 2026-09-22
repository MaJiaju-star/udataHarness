/**
 * 持久化访问层。
 *
 * <p>当前采用本地文件保存会话元数据和 Solon AI AgentSession，同时实现
 * AgentSessionProvider 以便 HarnessEngine 自动恢复上下文。</p>
 */
package com.udata.harness.repository;
