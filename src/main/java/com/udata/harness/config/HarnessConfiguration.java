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
    @Inject("${agent.workspace:./workspace}")
    private String workspace;

    @Inject("${agent.data-dir:./data}")
    private String dataDir;

    @Bean
    public SessionRepository sessionRepository() {
        return new SessionRepository(resolve(dataDir));
    }

    @Bean
    public UserWorkspaceService userWorkspaceService() {
        return new UserWorkspaceServiceImpl(resolve(workspace), resolve(dataDir), "");
    }

    private Path resolve(String path) {
        return Paths.get(path).toAbsolutePath().normalize();
    }
}
