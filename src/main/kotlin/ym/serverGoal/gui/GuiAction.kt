package ym.serverGoal.gui

enum class GuiActionType {
    SUBMIT,
    REWARDS,
    TOP,
    BACK,
    CLOSE,
    REFRESH,
    CLAIM_STAGE,
    CLAIM_PERSONAL,
    PREVIOUS_PAGE,
    NEXT_PAGE
}

data class GuiAction(
    val type: GuiActionType,
    val value: String? = null
)
