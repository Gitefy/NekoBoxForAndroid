package io.nekohasekai.sagernet.widget

data class GroupPreferenceItem(
    val id: Long,
    val name: String,
)

object GroupPreferenceCatalog {
    enum class State { Loading, Ready, Empty }

    data class Model(
        val state: State,
        val items: List<GroupPreferenceItem>,
        val interactive: Boolean,
    ) {
        val entries: Array<CharSequence> = items.map { it.name }.toTypedArray()
        val entryValues: Array<CharSequence> = items.map { it.id.toString() }.toTypedArray()
        val namesById: Map<Long, String> = items.associate { it.id to it.name }
    }

    fun loading(currentValue: String?): Model {
        val id = currentValue?.toLongOrNull()?.takeIf { it != 0L } ?: return Model(
            state = State.Loading,
            items = emptyList(),
            interactive = false,
        )
        return Model(
            state = State.Loading,
            items = listOf(GroupPreferenceItem(id, currentValue)),
            interactive = false,
        )
    }

    fun ready(items: List<GroupPreferenceItem>): Model =
        if (items.isEmpty()) Model(State.Empty, emptyList(), interactive = true)
        else Model(State.Ready, items, interactive = true)

    fun summary(value: String?, model: Model, fallback: CharSequence?): CharSequence? {
        if (value.isNullOrBlank() || value == "0") return fallback
        val id = value.toLongOrNull() ?: return fallback
        return model.namesById[id] ?: fallback
    }
}
