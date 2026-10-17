package com.aistock.assistant.business

import org.json.JSONArray
import org.json.JSONObject

/** The same JSON shape the server validates (server/src/businessConfig.js). */
object BusinessConfigJson {
    fun write(c: BusinessConfig): String {
        val o = JSONObject()
        o.put("schemaVersion", c.schemaVersion)
        o.put("businessType", c.businessType)
        o.put("modules", JSONArray(c.modules.map { it.key }.sorted()))
        o.put("terminology", JSONObject(c.terminology as Map<*, *>))
        o.put("units", JSONArray(c.units))
        val wf = JSONObject()
        for ((m, w) in c.workflows) wf.put(m.key, JSONObject().put("statuses", JSONArray(w.statuses)).put("done", JSONArray(w.done)).put("stages", JSONArray(w.stages)))
        o.put("workflows", wf)
        o.put("customFields", JSONArray(c.customFields.map { f ->
            JSONObject().put("module", f.module.key).put("key", f.key).put("label", f.label).put("type", f.type.key).apply { if (f.options.isNotEmpty()) put("options", JSONArray(f.options)) }
        }))
        o.put("language", c.language)
        return o.toString()
    }

    /** null when the text is not a usable configuration. */
    fun read(text: String?): BusinessConfig? = try {
        val o = JSONObject(text ?: return null)
        fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optString(it).takeIf { s -> s.isNotBlank() } }
        val modules = strings(o.optJSONArray("modules")).mapNotNull { Module.of(it) }.toSet()
        val terms = LinkedHashMap<String, String>()
        o.optJSONObject("terminology")?.let { t -> for (k in t.keys()) terms[k] = t.optString(k) }
        val flows = LinkedHashMap<Module, Workflow>()
        o.optJSONObject("workflows")?.let { w ->
            for (k in w.keys()) {
                val m = Module.of(k) ?: continue
                val x = w.optJSONObject(k) ?: continue
                flows[m] = Workflow(strings(x.optJSONArray("statuses")), strings(x.optJSONArray("done")), strings(x.optJSONArray("stages")))
            }
        }
        val fields = ArrayList<CustomField>()
        o.optJSONArray("customFields")?.let { a ->
            for (i in 0 until a.length()) {
                val f = a.optJSONObject(i) ?: continue
                val m = Module.of(f.optString("module")) ?: continue
                val type = FieldType.of(f.optString("type")) ?: continue
                fields.add(CustomField(m, f.optString("key"), f.optString("label"), type, strings(f.optJSONArray("options"))))
            }
        }
        BusinessConfig(o.optString("businessType"), modules, terms, strings(o.optJSONArray("units")), flows, fields, o.optString("language", "en"), o.optInt("schemaVersion", 1))
            .takeIf { it.validate().isEmpty() }
    } catch (e: Exception) {
        null
    }
}
