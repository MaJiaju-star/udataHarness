package com.udata.harness;

import org.noear.solon.Solon;
import org.noear.solon.annotation.SolonMain;

/**
 * UData Harness 的 Solon Web 启动入口。
 */
@SolonMain
public class App {
    public static void main(String[] args) {
        Solon.start(App.class, args);
    }
}
