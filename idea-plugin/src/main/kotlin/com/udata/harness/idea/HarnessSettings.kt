package com.udata.harness.idea

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.PROJECT)
@State(name = "UDataHarness", storages = [Storage("udata-harness.xml")])
class HarnessSettings : PersistentStateComponent<HarnessSettings.Data> {
    data class Data(var backendUrl: String = "http://127.0.0.1:8080", var userId: String = "")
    private var data = Data()
    override fun getState() = data
    override fun loadState(state: Data) { data = state }
}
