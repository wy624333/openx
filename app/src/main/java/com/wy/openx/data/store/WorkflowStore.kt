package com.wy.openx.data.store

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class WorkflowStep(
    val id: String,
    val tool: String,
    val args: Map<String, Any> = emptyMap(),
    val dependsOn: List<String> = emptyList(),
    val description: String = ""
)

data class Workflow(
    val name: String,
    val description: String = "",
    val steps: List<WorkflowStep> = emptyList()
)

class WorkflowStore(context: Context) {
    private val sp = context.getSharedPreferences("agent_workflows", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): List<Workflow> {
        val raw = sp.getString("workflows", "[]") ?: "[]"
        return try {
            val result: List<Workflow>? =
                gson.fromJson(raw, object : TypeToken<List<Workflow>>() {}.type)
            result ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun save(workflows: List<Workflow>) {
        sp.edit().putString("workflows", gson.toJson(workflows)).apply()
    }

    fun add(workflow: Workflow) {
        synchronized(this) {
            val list = load().toMutableList()
            list.removeAll { it.name == workflow.name }
            list.add(workflow)
            sp.edit().putString("workflows", gson.toJson(list)).apply()
        }
    }

    fun delete(name: String) {
        synchronized(this) {
            val filtered = load().filterNot { it.name == name }
            sp.edit().putString("workflows", gson.toJson(filtered)).apply()
        }
    }
}
