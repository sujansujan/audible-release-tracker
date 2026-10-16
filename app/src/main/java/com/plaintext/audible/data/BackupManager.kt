package com.plaintext.audible.data

import org.json.JSONArray
import org.json.JSONObject

object BackupManager {
    suspend fun exportJson(repo: ReleaseRepository): String {
        val root = JSONObject()
        val prefs = repo.getPrefs()

        root.put("version", 1)
        root.put("prefs", JSONObject().apply {
            put("englishOnly", prefs.englishOnly)
            put("hideAbridged", prefs.hideAbridged)
            put("hideDramatized", prefs.hideDramatized)
            put("isDarkTheme", prefs.isDarkTheme)
            put("notifyHour", prefs.notifyHour)
            put("notifyMinute", prefs.notifyMinute)
        })

        val trackedArr = JSONArray()
        for (t in repo.dao.getAllTrackedItems()) {
            trackedArr.put(JSONObject().apply {
                put("type", t.type.name)
                put("queryValue", t.queryValue)
                put("externalId", t.externalId ?: "")
                put("lastReadSequence", t.lastReadSequence)
            })
        }
        root.put("trackedItems", trackedArr)

        val dndArr = JSONArray()
        for (d in repo.dao.getAllDndRules()) {
            dndArr.put(JSONObject().apply {
                put("targetKey", d.targetKey)
                put("targetType", d.targetType.name)
                put("displayTitle", d.displayTitle)
            })
        }
        root.put("dndRules", dndArr)

        return root.toString(2)
    }

    suspend fun importJson(repo: ReleaseRepository, rawJson: String) {
        val root = JSONObject(rawJson)
        root.optJSONObject("prefs")?.let { p ->
            repo.savePrefs(
                FilterPrefs(
                    englishOnly = p.optBoolean("englishOnly", true),
                    hideAbridged = p.optBoolean("hideAbridged", true),
                    hideDramatized = p.optBoolean("hideDramatized", false),
                    isDarkTheme = p.optBoolean("isDarkTheme", true),
                    notifyHour = p.optInt("notifyHour", 9),
                    notifyMinute = p.optInt("notifyMinute", 0)
                )
            )
        }

        root.optJSONArray("trackedItems")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                repo.dao.insertTrackedItem(
                    TrackedItemEntity(
                        type = TrackType.valueOf(o.getString("type")),
                        queryValue = o.getString("queryValue"),
                        externalId = o.optString("externalId").takeIf { it.isNotBlank() },
                        lastReadSequence = o.optInt("lastReadSequence", 0)
                    )
                )
            }
        }

        root.optJSONArray("dndRules")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                repo.dao.insertDndRule(
                    DndRuleEntity(
                        targetKey = o.getString("targetKey"),
                        targetType = DndTargetType.valueOf(o.getString("targetType")),
                        displayTitle = o.getString("displayTitle")
                    )
                )
            }
        }
    }
}
