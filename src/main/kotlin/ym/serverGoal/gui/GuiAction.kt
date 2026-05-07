package ym.serverGoal.gui

enum class GuiActionType {
    SUBMIT,
    REWARDS,
    HISTORY,
    TOP,
    BACK,
    CLOSE,
    REFRESH,
    CLAIM_PERSONAL,
    START_DEFAULT,
    PREVIOUS_PAGE,
    NEXT_PAGE
}

data class GuiAction(
    val type: GuiActionType,
    val value: String? = null
)
