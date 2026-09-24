package com.udata.harness.config;

import com.udata.harness.repository.SessionRepository;
import com.udata.harness.service.UserWorkspaceService;
import com.udata.harness.service.impl.UserWorkspaceServiceImpl;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 基础路径与仓储组件装配。
 *
 * <p>在一个位置把可配置相对路径解析成规范化绝对路径，避免各服务使用不同根目录。</p>
 */
@Configuration
public class HarnessConfiguration {
    /**
     * 用户工作区根目录配置。
     */
    @Inject("${agent.workspace:./workspace}")
    private String workspace;

    /**
     * 应用数据目录配置（会话、注册表、密钥）。
     */
    @Inject("${agent.data-dir:./data}")
    private String dataDir;

    /**
     * 创建会话仓储，数据文件位于 {@code <data-dir>/users}。
     *
     * @return 会话仓储实例
     */
    @Bean
    public SessionRepository sessionRepository() {
        return new SessionRepository(resolve(dataDir));
    }

    /**
     * 创建工作区服务，工作区根为 {@code <workspace>}，注册表位于数据目录。
     *
     * @return 用户工作区服务实例
     */
    @Bean
    public UserWorkspaceService userWorkspaceService() {
        return new UserWorkspaceServiceImpl(resolve(workspace), resolve(dataDir), "");
    }

    /**
     * 将配置的相对路径解析为规范化绝对路径。
     *
     * @param path 配置路径
     * @return 绝对且规范化的路径
     */
    private Path resolve(String path) {
        return Paths.get(path).toAbsolutePath().normalize();
    }
}
