package com.voicetodo.domain

/** 四象限分类；由用户最终决定，AI 只能给出建议。 */
enum class Quadrant {
    ImportantUrgent,
    ImportantNotUrgent,
    NotImportantUrgent,
    NotImportantNotUrgent,
}
