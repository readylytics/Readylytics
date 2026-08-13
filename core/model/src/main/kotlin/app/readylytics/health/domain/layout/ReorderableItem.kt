package app.readylytics.health.domain.layout

/**
 * Shape shared by all reorderable layout configurations (dashboard cards, vitals charts,
 * sleep top cards / charts / metric cards). Exposes the id, visibility, and position that
 * the generic reorderable UI components drive off.
 */
interface ReorderableItem<Id> {
    val id: Id
    val isVisible: Boolean
    val position: Int
}
